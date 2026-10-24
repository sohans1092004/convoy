package convoy.platform.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.contracts.EventSchemas;
import convoy.contracts.events.MemberJoined;
import convoy.contracts.events.PoolReady;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Outbox -> SNS -> SQS -> inbox against real PostgreSQL and LocalStack: happy path, duplicate delivery,
 * stale versions, and AMENDMENT-006 (a FIFO topic delivering to a standard queue).
 */
@Testcontainers
class MessagingIT {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16.15-alpine");
    @Container static final LocalStackContainer LS = new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14.0"))
            .withServices(LocalStackContainer.Service.SNS, LocalStackContainer.Service.SQS);

    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static SnsClient sns;
    static SqsClient sqs;
    static Topology topology;
    static final List<Envelope> ordered = new CopyOnWriteArrayList<>(), unordered = new CopyOnWriteArrayList<>();
    static InboxConsumer orderedConsumer, unorderedConsumer;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/platform").load().migrate();
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var creds = StaticCredentialsProvider.create(AwsBasicCredentials.create(LS.getAccessKey(), LS.getSecretKey()));
        URI endpoint = LS.getEndpoint();
        sns = SnsClient.builder().endpointOverride(endpoint).region(Region.of(LS.getRegion())).credentialsProvider(creds).build();
        sqs = SqsClient.builder().endpointOverride(endpoint).region(Region.of(LS.getRegion())).credentialsProvider(creds).build();
        topology = new Topology(sns, sqs, JSON);
        // "quote" consumes pool events through a FIFO queue; "audit" through a standard queue (AMENDMENT-006).
        orderedConsumer = new InboxConsumer("quote", sqs, topology, jdbc, tx, JSON, new Subscriptions()
                .on(MemberJoined.class, Subscriptions.Ordering.MONOTONIC, (e, p) -> ordered.add(e))
                .on(PoolReady.class, Subscriptions.Ordering.MONOTONIC, (e, p) -> ordered.add(e)), 1);
        unorderedConsumer = new InboxConsumer("audit", sqs, topology, jdbc, tx, JSON, new Subscriptions()
                .onUnordered(MemberJoined.class, (e, p) -> unordered.add(e)), 1);
        orderedConsumer.start();
        unorderedConsumer.start();
    }

    @AfterAll
    static void tearDown() {
        orderedConsumer.stop();
        unorderedConsumer.stop();
    }

    static void await(BooleanSupplier cond, String what) throws InterruptedException {
        long until = System.currentTimeMillis() + 20_000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > until) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(100);
        }
    }

    UUID appendJoined(UUID pool, long version) {
        EventContext.set(Uuid7.next(), null);
        try {
            return tx.execute(s -> new Outbox(jdbc, JSON).append(pool, version,
                    new MemberJoined(pool.toString(), Uuid7.next().toString(), version, 0L)));
        } finally {
            EventContext.clear();
        }
    }

    @Test
    void happyPathFifoAndStandardQueuesBothReceiveOnce() throws Exception {
        UUID pool = Uuid7.next();
        UUID eventId = appendJoined(pool, 1);
        new OutboxRelay(jdbc, tx, sns, topology, JSON, "pool").tick();

        await(() -> ordered.stream().anyMatch(e -> e.eventId().equals(eventId)), "FIFO queue delivery");
        await(() -> unordered.stream().anyMatch(e -> e.eventId().equals(eventId)), "standard queue delivery from FIFO topic");
        assertNotNull(jdbc.queryForObject("select delivered_at from outbox where event_id = ?", Object.class, eventId));
        assertEquals(1, jdbc.queryForObject("select count(*) from inbox where consumer = 'quote' and event_id = ?", Integer.class, eventId));
        assertEquals(1, jdbc.queryForObject("select count(*) from inbox where consumer = 'audit' and event_id = ?", Integer.class, eventId));

        Envelope got = ordered.stream().filter(e -> e.eventId().equals(eventId)).findFirst().orElseThrow();
        EventSchemas.assertValid(JSON.writeValueAsString(got));
    }

    @Test
    void duplicateDeliveryIsAppliedOnce() throws Exception {
        UUID pool = Uuid7.next();
        UUID eventId = appendJoined(pool, 1);
        new OutboxRelay(jdbc, tx, sns, topology, JSON, "pool").tick();
        await(() -> unordered.stream().anyMatch(e -> e.eventId().equals(eventId)), "first delivery");
        long dupBefore = unorderedConsumer.duplicates.get();

        // Redeliver the identical envelope straight into the standard queue (what SQS at-least-once can do).
        Envelope e = unordered.stream().filter(x -> x.eventId().equals(eventId)).findFirst().orElseThrow();
        String url = sqs.getQueueUrl(b -> b.queueName("convoy-audit--pool-events")).queueUrl();
        sqs.sendMessage(b -> b.queueUrl(url).messageBody(write(e)).messageAttributes(Map.of("event_type",
                software.amazon.awssdk.services.sqs.model.MessageAttributeValue.builder().dataType("String").stringValue("MemberJoined").build())));
        await(() -> unorderedConsumer.duplicates.get() == dupBefore + 1, "duplicate detected");
        assertEquals(1, unordered.stream().filter(x -> x.eventId().equals(eventId)).count());
    }

    @Test
    void staleVersionIsIgnoredAndCounted() throws Exception {
        UUID pool = Uuid7.next();
        appendJoined(pool, 2);
        UUID older = appendJoined(pool, 1);
        long staleBefore = orderedConsumer.stale.get();
        new OutboxRelay(jdbc, tx, sns, topology, JSON, "pool").tick();
        await(() -> orderedConsumer.stale.get() == staleBefore + 1, "stale v1 after v2");
        assertTrue(ordered.stream().noneMatch(e -> e.eventId().equals(older)));
    }

    static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
