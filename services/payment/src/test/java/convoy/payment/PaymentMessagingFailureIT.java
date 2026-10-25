package convoy.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.contracts.events.MemberLeft;
import convoy.platform.Faults;
import convoy.platform.messaging.EventContext;
import convoy.platform.messaging.InboxConsumer;
import convoy.platform.messaging.MessagingMetrics;
import convoy.platform.messaging.Outbox;
import convoy.platform.messaging.OutboxRelay;
import convoy.platform.messaging.Topology;
import convoy.platform.messaging.Uuid7;
import convoy.platform.testing.ServiceTest;
import convoy.testing.StubServer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 * Messaging failure tests MF-1, MF-2, MF-3, MF-5 (docs/testing/messaging-failure-tests.md) on the pool -> payment path:
 * a simulated pool outbox (its own schema, the real OutboxRelay) publishes MemberLeft through LocalStack SNS/SQS to the
 * real payment service, whose handler requests the refund. Every test ends with the global invariants I-1..I-4.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "convoy.messaging.enabled=true", "convoy.messaging.wait-seconds=1", "convoy.messaging.visibility-timeout-seconds=3",
        "payment.razorpay-key-secret=test_key_secret", "payment.razorpay-webhook-secret=test_webhook_secret",
        "convoy.issuers.convoy-gateway=http://unused", "convoy.issuers.convoy-identity=http://unused"})
@Import({ServiceTest.Config.class, PaymentMessagingFailureIT.Stubs.class})
class PaymentMessagingFailureIT extends ServiceTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final LocalStackContainer LS = new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14.0"))
            .withServices(LocalStackContainer.Service.SNS, LocalStackContainer.Service.SQS);
    static final AtomicInteger ORDERS = new AtomicInteger();
    static final StubServer RAZORPAY = new StubServer()
            .on("/v1/orders", r -> StubServer.Reply.json("{\"id\":\"order_mf_" + ORDERS.incrementAndGet() + "\",\"entity\":\"order\",\"amount\":99900,\"currency\":\"INR\",\"status\":\"created\",\"receipt\":\"x\"}"))
            .on("/v1/payments/.*/refunds", r -> StubServer.Reply.json("{\"entity\":\"collection\",\"count\":0,\"items\":[]}"))
            .on("/v1/payments/.*/refund", r -> StubServer.Reply.json("{\"id\":\"rfnd_" + UUID.randomUUID().toString().substring(0, 8) + "\",\"entity\":\"refund\",\"amount\":99900,\"payment_id\":\"p\",\"receipt\":\"x\",\"status\":\"pending\"}"));

    static {
        LS.start();
    }

    @DynamicPropertySource
    static void aws(DynamicPropertyRegistry r) {
        r.add("convoy.aws.endpoint", () -> LS.getEndpoint().toString());
        r.add("convoy.aws.region", LS::getRegion);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Stubs {
        @Bean
        @Primary
        RazorpayClient testRazorpay() { return new HttpRazorpayClient(RAZORPAY.url(), "rzp_test_fake", "test_key_secret", "test_webhook_secret"); }
    }

    @AfterAll
    static void stop() {
        RAZORPAY.close();
        LS.stop();
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired InboxConsumer consumer;
    @Autowired Topology topology;
    @Autowired SnsClient sns;
    @Autowired MessagingMetrics metrics;
    @Autowired PostgreSQLContainer<?> pg;

    static JdbcTemplate poolJdbc;
    static TransactionTemplate poolTx;

    @AfterEach
    void disarm() { Faults.disarmAll(); }

    /** The simulated producer: schema pool_sim with the platform tables, and pool's relay. */
    OutboxRelay poolRelay() {
        if (poolJdbc == null) {
            DriverManagerDataSource ds = new DriverManagerDataSource(pg.getJdbcUrl() + (pg.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=pool_sim",
                    pg.getUsername(), pg.getPassword());
            Flyway.configure().dataSource(ds).schemas("pool_sim").defaultSchema("pool_sim").locations("classpath:db/platform").load().migrate();
            poolJdbc = new JdbcTemplate(ds);
            poolTx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        }
        return new OutboxRelay(poolJdbc, poolTx, sns, topology, JSON, "pool");
    }

    OutboxRelay relay(String producer) {
        poolRelay();
        return new OutboxRelay(poolJdbc, poolTx, sns, topology, JSON, producer);
    }

    UUID emit(UUID aggregate, long version, convoy.contracts.events.EventPayload payload) {
        EventContext.set(Uuid7.next(), null);
        try {
            return poolTx.execute(s -> new Outbox(poolJdbc, JSON).append(aggregate, version, payload));
        } finally {
            EventContext.clear();
        }
    }

    UUID emitMemberLeft(UUID pool, UUID buyer, long version) {
        EventContext.set(Uuid7.next(), null);
        try {
            return poolTx.execute(s -> new Outbox(poolJdbc, JSON).append(pool, version, new MemberLeft(pool.toString(), Uuid7.next().toString(), buyer.toString(),
                    null, "BUYER_LEFT", 0L, 0L)));
        } finally {
            EventContext.clear();
        }
    }

    /** A captured deposit for (buyer, pool), so MemberLeft has a business effect: one refund. */
    void capturedDeposit(UUID buyer, UUID pool) throws Exception {
        String key = "deposit:" + buyer + ":" + pool + ":1";
        Resp d = send("POST", "/internal/deposits", convoy.platform.testing.TestTokens.service("payment", "pool", List.of("deposits:create")),
                "{\"buyer_id\":\"" + buyer + "\",\"pool_id\":\"" + pool + "\",\"round_set\":1,\"amount_paise\":99900}", Map.of("Idempotency-Key", key));
        assertThat(d.status()).as(d.body()).isEqualTo(200);
        String orderId = JSON.readTree(d.body()).path("razorpay_order_id").asText();
        String raw = JSON.writeValueAsString(Map.of("entity", "event", "event", "payment.captured", "contains", List.of("payment"),
                "payload", Map.of("payment", Map.of("entity", Map.of("id", "pay_" + orderId, "order_id", orderId, "amount", 99900, "currency", "INR", "status", "captured")))));
        String sig = HttpRazorpayClient.hmac("test_webhook_secret", raw.getBytes(StandardCharsets.UTF_8));
        assertThat(send("POST", "/webhooks/razorpay", null, raw, Map.of("X-Razorpay-Signature", sig, "x-razorpay-event-id", "evt_" + orderId)).status()).isEqualTo(200);
    }

    int refunds(UUID buyer, UUID pool) {
        return jdbc.queryForObject("select count(*) from refunds r join payments p on p.id = r.payment_id where p.buyer_id = ? and p.pool_id = ?", Integer.class, buyer, pool);
    }

    static void await(String what, BooleanSupplier ok, long ms) throws InterruptedException {
        long until = System.currentTimeMillis() + ms;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > until) throw new AssertionError("timed out: " + what);
            Thread.sleep(100);
        }
    }

    /** I-1..I-4 for one event and its buyer (I-4 skipped when a poison message is expected). */
    void invariants(UUID event, UUID buyer, UUID pool, boolean dlqExpected) {
        assertThat(poolJdbc.queryForObject("select delivered_at is not null from outbox where event_id = ?", Boolean.class, event)).as("I-1 delivered").isTrue();
        assertThat(jdbc.queryForObject("select count(*) from inbox where consumer = 'payment' and event_id = ?", Integer.class, event)).as("I-1 one inbox row").isEqualTo(1);
        assertThat(refunds(buyer, pool)).as("I-2 exactly one refund").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox o join payments p on p.id = o.aggregate_id where o.event_type = 'RefundRequested' and p.buyer_id = ? and p.pool_id = ?",
                Integer.class, buyer, pool)).as("I-2 one RefundRequested").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from (select entry_id from journal_lines group by entry_id having sum(dr) <> sum(cr)) x", Integer.class))
                .as("I-3 every journal entry balances").isZero();
        if (!dlqExpected) assertThat(metrics.checkDlqs()).as("I-4 DLQs empty").isEmpty();
    }

    @Test
    void mf1CommitThenCrashBeforePublishIsPublishedAfterRestart() throws Exception {
        UUID buyer = UUID.randomUUID(), pool = UUID.randomUUID();
        capturedDeposit(buyer, pool);
        OutboxRelay relay = poolRelay();
        UUID event = emitMemberLeft(pool, buyer, 7);
        Faults.arm(OutboxRelay.CRASH_BEFORE_PUBLISH);
        relay.tick(); // the relay "dies" after the business commit, before sns.publish
        assertThat(poolJdbc.queryForObject("select delivered_at is null from outbox where event_id = ?", Boolean.class, event)).isTrue();
        Thread.sleep(2000);
        assertThat(refunds(buyer, pool)).as("nothing was published, so nothing happened").isZero();
        long restart = System.currentTimeMillis();
        relay.tick(); // restart
        await("refund after restart", () -> refunds(buyer, pool) == 1, 20_000);
        assertThat(poolJdbc.queryForObject("select attempts from outbox where event_id = ?", Integer.class, event)).isEqualTo(1);
        assertThat(System.currentTimeMillis() - restart).as("publish + delivery after restart < 5 s").isLessThan(5_000);
        invariants(event, buyer, pool, false);
    }

    @Test
    void mf2BrokerAcceptedThenRelayCrashedBeforeMarkingDeliveredIsAppliedOnce() throws Exception {
        // Standard topic (order -> payment, DepositRefundRequested): the republished event really arrives twice.
        UUID buyer = UUID.randomUUID(), pool = UUID.randomUUID();
        capturedDeposit(buyer, pool);
        OutboxRelay orderRelay = relay("order");
        long duplicates = consumer.duplicates.get();
        UUID event = emit(Uuid7.next(), 1, new convoy.contracts.events.DepositRefundRequested(Uuid7.next().toString(), pool.toString(), buyer.toString(), "BUYER_CANCELLED"));
        Faults.arm(OutboxRelay.CRASH_AFTER_PUBLISH);
        orderRelay.tick(); // SNS has it; the row is not marked delivered
        await("first delivery applied", () -> refunds(buyer, pool) == 1, 20_000);
        orderRelay.tick(); // restart: the same event_id is published again
        await("second delivery rejected by the inbox (duplicate counted)", () -> consumer.duplicates.get() == duplicates + 1, 20_000);
        invariants(event, buyer, pool, false);
    }

    @Test
    void mf2OnTheFifoTopicSnsDeduplicatesTheRepublish() throws Exception {
        // Pool events go through an SNS FIFO topic with MessageDeduplicationId = event_id: within SNS's deduplication
        // window the republish is dropped by the broker, so the consumer sees it once (no duplicate to count).
        UUID buyer = UUID.randomUUID(), pool = UUID.randomUUID();
        capturedDeposit(buyer, pool);
        OutboxRelay relay = poolRelay();
        long duplicates = consumer.duplicates.get();
        UUID event = emitMemberLeft(pool, buyer, 7);
        Faults.arm(OutboxRelay.CRASH_AFTER_PUBLISH);
        relay.tick();
        await("delivered once", () -> refunds(buyer, pool) == 1, 20_000);
        relay.tick();
        Thread.sleep(5_000);
        assertThat(consumer.duplicates.get()).as("the broker absorbed the duplicate").isEqualTo(duplicates);
        invariants(event, buyer, pool, false);
    }

    @Test
    void mf3ConsumerCommittedThenCrashedBeforeAckIsRedeliveredAndRejected() throws Exception {
        UUID buyer = UUID.randomUUID(), pool = UUID.randomUUID();
        capturedDeposit(buyer, pool);
        OutboxRelay relay = poolRelay();
        long duplicates = consumer.duplicates.get();
        UUID event = emitMemberLeft(pool, buyer, 7);
        Faults.arm(InboxConsumer.CRASH_AFTER_COMMIT_BEFORE_ACK);
        relay.tick();
        await("refund committed before the crash", () -> refunds(buyer, pool) == 1, 20_000);
        // Visibility timeout 3 s in this test (10 s in the spec): the message comes back, the inbox key rejects it, it is acked.
        await("redelivered and rejected by the inbox", () -> consumer.duplicates.get() == duplicates + 1, 30_000);
        invariants(event, buyer, pool, false);
    }

    @Test
    void mf5PoisonMessageGoesToTheDlqWithoutBlockingOthersAndRedriveDeliversItOnce() throws Exception {
        UUID poisonBuyer = UUID.randomUUID(), poisonPool = UUID.randomUUID(), okBuyer = UUID.randomUUID(), okPool = UUID.randomUUID();
        capturedDeposit(poisonBuyer, poisonPool);
        capturedDeposit(okBuyer, okPool);
        OutboxRelay relay = poolRelay();
        UUID poison = Uuid7.next();
        // The handler fails deterministically for this event until the "fix" (disarm).
        EventContext.set(Uuid7.next(), null);
        try {
            poolTx.executeWithoutResult(s -> poolJdbc.update("insert into outbox(event_id, event_type, aggregate_id, aggregate_version, payload, occurred_at, correlation_id) "
                    + "values (?, 'MemberLeft', ?, 7, ?::jsonb, clock_timestamp(), ?)", poison, poisonPool, write(Map.of("pool_id", poisonPool.toString(),
                    "membership_id", Uuid7.next().toString(), "buyer_id", poisonBuyer.toString(), "reason", "BUYER_LEFT", "interested_count", 0, "committed_count", 0)),
                    Uuid7.next()));
        } finally {
            EventContext.clear();
        }
        Faults.armUntilDisarmed("consumer.handle:" + poison);
        UUID ok = emitMemberLeft(okPool, okBuyer, 3);
        relay.tick();
        await("another pool's event keeps flowing (FIFO blocks only the poison message's group)", () -> refunds(okBuyer, okPool) == 1, 20_000);
        // maxReceiveCount 5 with a 3 s visibility timeout: in the DLQ after ~15 s.
        await("poison message in the DLQ, alarm DLQ_NOT_EMPTY", () -> metrics.checkDlqs().values().stream().mapToInt(Integer::intValue).sum() == 1, 60_000);
        assertThat(refunds(poisonBuyer, poisonPool)).isZero();
        // Runbook: fix the cause, then redrive. The message is applied exactly once.
        Faults.disarm("consumer.handle:" + poison);
        String dlq = metrics.checkDlqs().keySet().iterator().next();
        assertThat(topology.redrive(dlq)).isEqualTo(1);
        await("redriven message applied", () -> refunds(poisonBuyer, poisonPool) == 1, 20_000);
        assertThat(topology.redrive(dlq)).as("nothing left to redrive").isZero();
        invariants(poison, poisonBuyer, poisonPool, false);
        invariants(ok, okBuyer, okPool, false);
    }

    static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
