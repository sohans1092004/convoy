package convoy.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.platform.messaging.EventContext;
import convoy.platform.messaging.Uuid7;
import convoy.platform.problem.ApiProblem;
import convoy.platform.testing.AuthzDenyChecks;
import convoy.platform.testing.Outboxes;
import convoy.platform.testing.ServiceTest;
import convoy.testing.StubServer;
import convoy.platform.testing.TestTokens;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Deposits, webhook-only capture, duplicate webhooks, hold, refund and ledger invariants on a real PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "convoy.messaging.enabled=false", "payment.razorpay-key-secret=test_key_secret", "payment.razorpay-webhook-secret=test_webhook_secret",
        "convoy.issuers.convoy-gateway=http://unused", "convoy.issuers.convoy-identity=http://unused"})
@Import({ServiceTest.Config.class, PaymentIT.Stubs.class})
class PaymentIT extends ServiceTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final AtomicInteger ORDERS = new AtomicInteger();
    static final Map<String, String> REFUNDS = new ConcurrentHashMap<>();
    static final StubServer RAZORPAY = new StubServer()
            .on("/v1/orders", r -> StubServer.Reply.json("{\"id\":\"order_" + ORDERS.incrementAndGet() + "\",\"entity\":\"order\",\"amount\":99900,\"currency\":\"INR\",\"status\":\"created\",\"receipt\":\"x\"}"))
            .on("/v1/payments/.*/refunds", r -> StubServer.Reply.json("{\"entity\":\"collection\",\"count\":0,\"items\":[]}"))
            .on("/v1/payments/.*/refund", r -> {
                String id = "rfnd_" + UUID.randomUUID().toString().substring(0, 8);
                REFUNDS.put(id, r.body());
                return StubServer.Reply.json("{\"id\":\"" + id + "\",\"entity\":\"refund\",\"amount\":99900,\"payment_id\":\"pay_x\",\"receipt\":\"x\",\"status\":\"pending\"}");
            });

    @TestConfiguration(proxyBeanMethods = false)
    static class Stubs {
        @Bean
        @Primary
        RazorpayClient testRazorpay() { return new HttpRazorpayClient(RAZORPAY.url(), "rzp_test_fake", "test_key_secret", "test_webhook_secret"); }
    }

    @AfterAll
    static void stop() { RAZORPAY.close(); }

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired Payments payments;

    String serviceToken() { return TestTokens.service("payment", "pool", List.of("deposits:create")); }

    Map<String, Object> deposit(UUID buyer, UUID pool) throws Exception {
        String key = "deposit:" + buyer + ":" + pool + ":1";
        Resp r = send("POST", "/internal/deposits", serviceToken(), "{\"buyer_id\":\"" + buyer + "\",\"pool_id\":\"" + pool + "\",\"round_set\":1,\"amount_paise\":99900}",
                Map.of("Idempotency-Key", key));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return JSON.readValue(r.body(), Map.class);
    }

    Resp webhook(String event, String entityName, Map<String, Object> entity, String eventId, String secret) throws Exception {
        String raw = JSON.writeValueAsString(Map.of("entity", "event", "event", event, "contains", List.of(entityName), "payload", Map.of(entityName, Map.of("entity", entity))));
        String sig = HttpRazorpayClient.hmac(secret, raw.getBytes(StandardCharsets.UTF_8));
        return send("POST", "/webhooks/razorpay", null, raw, Map.of("X-Razorpay-Signature", sig, "x-razorpay-event-id", eventId));
    }

    Resp captured(String orderId, String eventId) throws Exception {
        return webhook("payment.captured", "payment", Map.of("id", "pay_" + orderId, "order_id", orderId, "amount", 99900, "currency", "INR", "status", "captured"), eventId, "test_webhook_secret");
    }

    String state(Object paymentId) { return jdbc.queryForObject("select state from payments where id = ?::uuid", String.class, paymentId); }

    @Test
    void sameDepositKeyGivesOneRowAndOneRazorpayOrder() throws Exception {
        UUID buyer = UUID.randomUUID(), pool = UUID.randomUUID();
        int before = ORDERS.get();
        var results = new java.util.ArrayList<java.util.concurrent.Future<Map<String, Object>>>();
        try (var ex = Executors.newFixedThreadPool(10)) {
            for (int i = 0; i < 10; i++) results.add(ex.submit(() -> deposit(buyer, pool)));
        }
        Set<Object> ids = new java.util.HashSet<>();
        for (var f : results) ids.add(f.get().get("payment_id"));
        assertThat(ids).hasSize(1);
        assertThat(ORDERS.get() - before).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from payments where buyer_id = ? and pool_id = ?", Integer.class, buyer, pool)).isEqualTo(1);
    }

    @Test
    void onlyTheSignedWebhookCapturesAndDuplicatesHaveNoEffect() throws Exception {
        UUID buyer = UUID.randomUUID(), pool = UUID.randomUUID();
        Map<String, Object> d = deposit(buyer, pool);
        String orderId = (String) d.get("razorpay_order_id");
        String pid = (String) d.get("payment_id");
        // Browser callback with a bad signature: 400; with a good one: PENDING, never CAPTURED.
        String token = TestTokens.buyer("payment", buyer);
        assertThat(send("POST", "/payments/" + pid + "/checkout-callback", token, "{\"razorpay_payment_id\":\"pay_1\",\"razorpay_order_id\":\"" + orderId + "\",\"razorpay_signature\":\"bad\"}", Map.of()).status()).isEqualTo(400);
        String sig = HttpRazorpayClient.hmac("test_key_secret", (orderId + "|pay_1").getBytes(StandardCharsets.UTF_8));
        Resp cb = send("POST", "/payments/" + pid + "/checkout-callback", token, "{\"razorpay_payment_id\":\"pay_1\",\"razorpay_order_id\":\"" + orderId + "\",\"razorpay_signature\":\"" + sig + "\"}", Map.of());
        assertThat(cb.status()).isEqualTo(200);
        assertThat(state(pid)).isEqualTo("PENDING");
        // AZ-E111-X: another buyer cannot use this payment.
        assertThat(send("POST", "/payments/" + pid + "/checkout-callback", TestTokens.buyer("payment", UUID.randomUUID()), "{\"razorpay_payment_id\":\"pay_1\",\"razorpay_order_id\":\"" + orderId + "\",\"razorpay_signature\":\"" + sig + "\"}", Map.of()).status()).isEqualTo(404);
        // AZ-INT-06: a webhook with a wrong signature is rejected and changes nothing.
        assertThat(webhook("payment.captured", "payment", Map.of("id", "pay_1", "order_id", orderId, "amount", 99900, "currency", "INR"), "evt_forged", "wrong-secret").status()).isEqualTo(400);
        assertThat(state(pid)).isEqualTo("PENDING");
        // Signed capture: CAPTURED + J-CAPTURE; the same event id again: 200, no second effect.
        assertThat(captured(orderId, "evt_cap_" + pid).body()).contains("PROCESSED");
        assertThat(captured(orderId, "evt_cap_" + pid).body()).contains("DUPLICATE");
        assertThat(state(pid)).isEqualTo("CAPTURED");
        assertThat(jdbc.queryForObject("select count(*) from journal_entries where payment_id = ?::uuid and kind = 'J-CAPTURE'", Integer.class, pid)).isEqualTo(1);
        // A failure webhook after capture is stale.
        webhook("payment.failed", "payment", Map.of("id", "pay_0", "order_id", orderId, "error_code", "BAD"), "evt_fail_" + pid, "test_webhook_secret");
        assertThat(state(pid)).isEqualTo("CAPTURED");
        // AZ-E112-X: only the owner reads it.
        assertThat(get("/me/payments/" + pid, token).status()).isEqualTo(200);
        assertThat(get("/me/payments/" + pid, TestTokens.buyer("payment", UUID.randomUUID())).status()).isEqualTo(404);
    }

    @Test
    void holdThenRefundKeepsTheLedgerBalancedAndEndsInATerminalState() throws Exception {
        UUID buyer = UUID.randomUUID(), pool = UUID.randomUUID(), booking = Uuid7.next();
        Map<String, Object> d = deposit(buyer, pool);
        String pid = (String) d.get("payment_id");
        captured((String) d.get("razorpay_order_id"), "evt_c_" + pid);
        inTx(() -> payments.hold(buyer, pool, booking));
        assertThat(state(pid)).isEqualTo("HELD_FOR_BOOKING");
        inTx(() -> payments.hold(buyer, pool, booking)); // repeat: no second J-HOLD
        assertThat(jdbc.queryForObject("select count(*) from journal_entries where payment_id = ?::uuid and kind = 'J-HOLD'", Integer.class, pid)).isEqualTo(1);
        // Booking cancelled -> refund requested from 2200; a second request appends a reason, never a second refund.
        inTx(() -> payments.requestRefund(buyer, pool, "BUYER_CANCELLED"));
        inTx(() -> payments.requestRefund(buyer, pool, "DEALER_CANCELLED"));
        assertThat(state(pid)).isEqualTo("REFUND_PENDING");
        assertThat(jdbc.queryForObject("select count(*) from refunds where payment_id = ?::uuid", Integer.class, pid)).isEqualTo(1);
        jdbc.update("update refunds set next_attempt_at = clock_timestamp() - interval '1 second' where payment_id = ?::uuid", pid);
        payments.refundWorker();
        Map<String, Object> rf = jdbc.queryForMap("select id::text as id, razorpay_refund_id, state, source_account from refunds where payment_id = ?::uuid", pid);
        assertThat(rf.get("state")).isEqualTo("REFUND_INITIATED");
        assertThat(rf.get("source_account")).isEqualTo("2200");
        webhook("refund.processed", "refund", Map.of("id", rf.get("razorpay_refund_id"), "receipt", rf.get("id"), "amount", 99900, "status", "processed"), "evt_r_" + pid, "test_webhook_secret");
        assertThat(state(pid)).isEqualTo("REFUNDED");
        // Per deposit: 2100 and 2200 net zero; overall every entry balances.
        assertThat(jdbc.queryForObject("select coalesce(sum(dr - cr), 0) from journal_lines l join journal_entries e on e.id = l.entry_id where e.payment_id = ?::uuid and l.account in ('2100','2200')",
                Long.class, pid)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from (select entry_id from journal_lines group by entry_id having sum(dr) <> sum(cr)) x", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select coalesce(sum(dr - cr), 0) from journal_lines l join journal_entries e on e.id = l.entry_id where e.payment_id = ?::uuid and l.account = '1100'", Long.class, pid)).isZero();
    }

    @Test
    void ledgerRowsAreImmutableAndEntriesMustBalance() {
        UUID payment = UUID.fromString((String) deposit0().get("payment_id"));
        assertThatThrownBy(() -> jdbc.update("update journal_lines set dr = dr + 1")).hasMessageContaining("immutable");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            UUID e = Uuid7.next();
            jdbc.update("insert into journal_entries(id, kind, payment_id) values (?, 'TEST', ?)", e, payment);
            jdbc.update("insert into journal_lines(entry_id, line_no, account, dr, cr) values (?, 1, '1100', 100, 0)", e);
        })).rootCause().hasMessageContaining("does not balance"); // deferred constraint trigger fires at commit
    }

    Map<String, Object> deposit0() {
        try {
            return deposit(UUID.randomUUID(), UUID.randomUUID());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void depositAmountMustMatchTheConfiguredDeposit() {
        Resp r = send("POST", "/internal/deposits", serviceToken(), "{\"buyer_id\":\"" + UUID.randomUUID() + "\",\"pool_id\":\"" + UUID.randomUUID() + "\",\"round_set\":1,\"amount_paise\":1}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(r.status()).isEqualTo(400);
    }

    @Test
    void authorisationMatrixForEveryP1Endpoint() {
        List<String> failures = AuthzDenyChecks.run("payment", this::send, (method, path) -> switch (path) {
            case "/internal/deposits" -> "{\"buyer_id\":\"" + UUID.randomUUID() + "\",\"pool_id\":\"" + UUID.randomUUID() + "\",\"round_set\":1,\"amount_paise\":99900}";
            case "/payments/{id}/checkout-callback" -> "{\"razorpay_payment_id\":\"p\",\"razorpay_order_id\":\"o\",\"razorpay_signature\":\"s\"}";
            default -> null;
        });
        assertThat(failures).isEmpty();
        // AZ-SVC-03: only a token with deposits:create may create deposits.
        Resp wrongScope = send("POST", "/internal/deposits", TestTokens.service("payment", "quote", List.of("rounds:summary")),
                "{\"buyer_id\":\"" + UUID.randomUUID() + "\",\"pool_id\":\"" + UUID.randomUUID() + "\",\"round_set\":1,\"amount_paise\":99900}", Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(wrongScope.status()).isEqualTo(403);
    }

    @Test
    void transitionTablesRejectEveryInvalidPair() throws Exception {
        var all = JSON.readTree(PaymentIT.class.getResourceAsStream("/convoy/transitions.json"));
        for (var t : List.of(Payments.PAYMENT, Payments.REFUND)) {
            for (var p : all.get(t.machine()).get("invalid_pairs")) {
                assertThatThrownBy(() -> t.check(p.get("from").asText(), p.get("to").asText()))
                        .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.type().slug).isEqualTo("invalid-transition"));
            }
        }
    }

    @Test
    void everyPublishedEventMatchesItsSchema() throws Exception {
        UUID buyer = UUID.randomUUID(), pool = UUID.randomUUID();
        Map<String, Object> d = deposit(buyer, pool);
        captured((String) d.get("razorpay_order_id"), "evt_s_" + d.get("payment_id"));
        inTx(() -> payments.hold(buyer, pool, Uuid7.next()));
        assertThat(Outboxes.assertAllValid(jdbc, "payment")).isPositive();
    }

    void inTx(Runnable r) {
        try {
            EventContext.set(Uuid7.next(), null);
            tx.executeWithoutResult(s -> r.run());
        } finally {
            EventContext.clear();
        }
    }
}
