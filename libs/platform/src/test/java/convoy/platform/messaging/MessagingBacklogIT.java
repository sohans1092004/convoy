package convoy.platform.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.contracts.events.MemberCommitted;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
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
 * MF-6 (docs/testing/messaging-failure-tests.md): a consumer is offline while a backlog builds, then replays it.
 * notification consumes MemberCommitted through a standard queue (no global order). Every event must be processed exactly
 * once (applied, or ignored as stale: the per-aggregate version rule prevents regressions), and the drain time and oldest
 * message age are recorded. Size: -Dmf6.events (default 5,000 in CI; 50,000 in the P2 report run).
 */
@Testcontainers
class MessagingBacklogIT {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16.15-alpine");
    @Container static final LocalStackContainer LS = new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14.0"))
            .withServices(LocalStackContainer.Service.SNS, LocalStackContainer.Service.SQS);
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Test
    void anOfflineConsumerReplaysItsWholeBacklogExactlyOnce() throws Exception {
        int n = Integer.getInteger("mf6.events", 5_000);
        int perPool = 10;
        // A pooled DataSource, as in the services: a new connection per query exhausts ephemeral ports at 50,000 events.
        com.zaxxer.hikari.HikariDataSource ds = new com.zaxxer.hikari.HikariDataSource();
        ds.setJdbcUrl(PG.getJdbcUrl());
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
        ds.setMaximumPoolSize(10);
        Flyway.configure().dataSource(ds).locations("classpath:db/platform").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var creds = StaticCredentialsProvider.create(AwsBasicCredentials.create(LS.getAccessKey(), LS.getSecretKey()));
        URI endpoint = LS.getEndpoint();
        SnsClient sns = SnsClient.builder().endpointOverride(endpoint).region(Region.of(LS.getRegion())).credentialsProvider(creds).build();
        SqsClient sqs = SqsClient.builder().endpointOverride(endpoint).region(Region.of(LS.getRegion())).credentialsProvider(creds).build();
        Topology topology = new Topology(sns, sqs, JSON);
        AtomicLong effects = new AtomicLong();
        InboxConsumer consumer = new InboxConsumer("notification", sqs, topology, jdbc, tx, JSON, new Subscriptions()
                .onUnordered(MemberCommitted.class, (e, p) -> effects.incrementAndGet()), 1, java.time.Duration.ofMinutes(5));
        consumer.start(); // creates the queue and subscription
        consumer.stop();  // ... then the consumer goes offline
        Thread.sleep(1500);

        // The backlog: n events over n / perPool pools, versions 1..perPool each, published through the real relay.
        Outbox outbox = new Outbox(jdbc, JSON);
        List<UUID> pools = new ArrayList<>();
        for (int i = 0; i < n / perPool; i++) pools.add(Uuid7.next());
        EventContext.set(Uuid7.next(), null);
        try {
            for (int v = 1; v <= perPool; v++) {
                final long version = v;
                tx.executeWithoutResult(s -> {
                    for (UUID pool : pools) outbox.append(pool, version, new MemberCommitted(pool.toString(), Uuid7.next().toString(),
                            UUID.randomUUID().toString(), UUID.randomUUID().toString(), version, version, false));
                });
            }
        } finally {
            EventContext.clear();
        }
        long publishStart = System.currentTimeMillis();
        new OutboxRelay(jdbc, tx, sns, topology, JSON, "pool").tick();
        long publishMs = System.currentTimeMillis() - publishStart;
        assertEquals(0, jdbc.queryForObject("select count(*) from outbox where delivered_at is null", Integer.class), "I-1 every outbox row published");
        String url = sqs.getQueueUrl(b -> b.queueName("convoy-notification--pool-events")).queueUrl();

        // Back online: drain and sample the oldest message age.
        List<Long> ages = new ArrayList<>();
        InboxConsumer restarted = new InboxConsumer("notification", sqs, topology, jdbc, tx, JSON, new Subscriptions()
                .onUnordered(MemberCommitted.class, (e, p) -> effects.incrementAndGet()), 1, java.time.Duration.ofMinutes(5));
        long start = System.currentTimeMillis();
        restarted.start();
        long until = start + Math.max(300_000, n * 60L);
        int done = 0;
        while (System.currentTimeMillis() < until) {
            done = jdbc.queryForObject("select count(*) from inbox where consumer = 'notification'", Integer.class);
            // Oldest-message age is a CloudWatch metric, not a queue attribute: sample the backlog depth instead.
            ages.add((long) topology.depth(url));
            if (done >= n) break;
            Thread.sleep(1000);
        }
        long drainMs = System.currentTimeMillis() - start;
        restarted.stop();

        assertEquals(n, done, "I-1 every event reached the inbox exactly once");
        assertEquals(n, jdbc.queryForObject("select count(distinct event_id) from inbox where consumer = 'notification'", Integer.class));
        assertEquals(n, effects.get() + restarted.stale.get(), "each event applied or ignored as stale, once (I-2)");
        // No regression: for every pool, the last applied version is the highest.
        assertEquals(pools.size(), jdbc.queryForObject("select count(*) from aggregate_versions where consumer = 'notification' and last_version = ?", Integer.class, perPool));
        assertEquals(0, topology.depth(topology.dlqs().keySet().iterator().next()), "I-4 DLQ empty");
        long nonIncreasingAfterPeak = 0;
        for (int i = ages.indexOf(ages.stream().max(Long::compare).orElse(0L)) + 1; i < ages.size(); i++) {
            if (ages.get(i) > ages.get(i - 1)) nonIncreasingAfterPeak++;
        }
        System.out.printf("MF-6 RESULT events=%d pools=%d publish_ms=%d drain_ms=%d drain_per_s=%.0f peak_backlog_depth=%s applied=%d stale=%d depth_rises_after_peak=%d%n",
                n, pools.size(), publishMs, drainMs, n * 1000.0 / drainMs, ages.isEmpty() ? "n/a" : String.valueOf(ages.stream().max(Long::compare).get()),
                effects.get(), restarted.stale.get(), nonIncreasingAfterPeak);
        assertTrue(drainMs > 0);
    }
}
