package convoy.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.platform.messaging.InboxConsumer;
import convoy.platform.messaging.Uuid7;
import convoy.platform.testing.ServiceTest;
import convoy.platform.testing.TestTokens;
import convoy.testing.StubServer;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * MF-4 (docs/testing/messaging-failure-tests.md): two events for one pool arrive out of order at realtime, a FULL pool
 * subscriber. Injected straight into its FIFO queue (bypassing SNS ordering). It parks v8, applies v7 then v8, ignores a
 * replayed v6 as stale; when a missing version never arrives it resyncs from the owner after the (shortened) timeout.
 * Business-level assertion: what an SSE client sees never regresses and ends at the owner's version.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "convoy.messaging.enabled=true", "convoy.messaging.wait-seconds=1", "convoy.messaging.gap-snapshot-after=1s",
        "realtime.heartbeat=1h", "realtime.revalidate=1h",
        "convoy.issuers.convoy-gateway=http://unused", "convoy.issuers.convoy-identity=http://unused"})
@org.junit.jupiter.api.Tag("nightly") // P2.5.B: runs in the nightly workflow
@Import({ServiceTest.Config.class, RealtimeMessagingFailureIT.Stubs.class})
class RealtimeMessagingFailureIT extends ServiceTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final UUID MEMBER = UUID.randomUUID();
    static final LocalStackContainer LS = new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14.0"))
            .withServices(LocalStackContainer.Service.SNS, LocalStackContainer.Service.SQS);
    static final StubServer POOL = new StubServer()
            .on("/internal/pools/.*/access", r -> StubServer.Reply.json("{\"allowed\":true}"))
            .on("/internal/pools/.*/version", r -> StubServer.Reply.json("{\"version\":8}"));

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
        @ServiceConnection(name = "redis")
        static GenericContainer<?> redis() { return new GenericContainer<>("redis:8.8.3-alpine").withExposedPorts(6379); }

        @Bean
        @Primary
        RestClient testPoolClient(RestClient.Builder b) { return b.clone().baseUrl(POOL.url()).build(); }
    }

    @AfterAll
    static void stop() {
        POOL.close();
        LS.stop();
    }

    @Autowired InboxConsumer consumer;
    @Autowired SqsClient sqs;

    /** Collects the versions an SSE client is shown, in order. */
    List<JsonNode> watch(UUID pool) throws Exception {
        List<JsonNode> seen = new CopyOnWriteArrayList<>();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/realtime/pools/" + pool + "/stream"))
                .header("Authorization", "Bearer " + TestTokens.buyer("realtime", MEMBER)).GET().build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofLines()).thenAccept(r -> {
            String[] ev = {null};
            r.body().forEach(line -> {
                if (line.startsWith("event:")) ev[0] = line.substring(6).trim();
                else if (line.startsWith("data:") && ev[0] != null && !ev[0].equals("heartbeat")) {
                    try {
                        JsonNode n = JSON.readTree(line.substring(5));
                        ((com.fasterxml.jackson.databind.node.ObjectNode) n).put("_event", ev[0]);
                        seen.add(n);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
            });
        });
        await("stream ready", () -> seen.stream().anyMatch(n -> n.path("_event").asText().equals("ready")), 15_000);
        return seen;
    }

    void inject(UUID pool, long version, int committed) throws Exception {
        String url = sqs.getQueueUrl(b -> b.queueName("convoy-realtime--pool-events.fifo")).queueUrl();
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("event_id", Uuid7.next().toString());
        env.put("event_type", "MemberCommitted");
        env.put("schema_version", 1);
        env.put("aggregate_id", pool.toString());
        env.put("aggregate_version", version);
        env.put("occurred_at", Instant.now().toString());
        env.put("correlation_id", Uuid7.next().toString());
        env.put("causation_id", null);
        env.put("producer", "pool");
        env.put("payload", Map.of("pool_id", pool.toString(), "membership_id", Uuid7.next().toString(), "buyer_id", UUID.randomUUID().toString(),
                "payment_id", UUID.randomUUID().toString(), "interested_count", committed, "committed_count", committed, "kept_from_previous_round", false));
        String body = JSON.writeValueAsString(env);
        sqs.sendMessage(b -> b.queueUrl(url).messageBody(body).messageGroupId(pool.toString()).messageDeduplicationId(env.get("event_id").toString()));
    }

    static void await(String what, BooleanSupplier ok, long ms) throws InterruptedException {
        long until = System.currentTimeMillis() + ms;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > until) throw new AssertionError("timed out: " + what);
            Thread.sleep(50);
        }
    }

    static List<Long> versions(List<JsonNode> seen) {
        return seen.stream().filter(n -> n.path("_event").asText().equals("pool.changed")).map(n -> n.path("version").asLong()).toList();
    }

    @Test
    void outOfOrderEventsAreParkedAppliedInOrderAndAStaleReplayIsIgnored() throws Exception {
        UUID pool = Uuid7.next();
        List<JsonNode> seen = watch(pool);
        inject(pool, 6, 1);
        await("baseline v6", () -> versions(seen).contains(6L), 20_000);
        long parked = consumer.parked.get(), stale = consumer.stale.get();
        inject(pool, 8, 3); // arrives first
        await("v8 parked (gap)", () -> consumer.parked.get() == parked + 1, 20_000);
        inject(pool, 7, 2);
        await("v7 then v8 delivered", () -> versions(seen).contains(8L), 20_000);
        assertThat(versions(seen)).as("the client is shown versions in order, never a regression").containsExactly(6L, 7L, 8L);
        assertThat(seen.stream().filter(n -> n.path("version").asLong() == 8).findFirst().orElseThrow().path("delta").path("committed_count").asInt()).isEqualTo(3);
        inject(pool, 6, 1); // a replayed old version
        await("replayed v6 counted as stale", () -> consumer.stale.get() == stale + 1, 20_000);
        Thread.sleep(500);
        assertThat(versions(seen)).containsExactly(6L, 7L, 8L);
    }

    @Test
    void aVersionThatNeverArrivesFallsBackToTheOwnersSnapshot() throws Exception {
        UUID pool = Uuid7.next();
        List<JsonNode> seen = watch(pool);
        inject(pool, 1, 1);
        await("baseline v1", () -> versions(seen).contains(1L), 20_000);
        long snapshots = consumer.snapshots.get();
        inject(pool, 3, 3); // v2 is never published
        // gap-snapshot-after 1 s in this test (5 min in the catalogue): realtime takes the owner's version (stub: 8).
        await("snapshot fallback", () -> consumer.snapshots.get() == snapshots + 1, 30_000);
        await("clients told to refetch the snapshot at v8", () -> seen.stream().anyMatch(n -> n.path("_event").asText().equals("resync") && n.path("version").asLong() == 8), 20_000);
        assertThat(versions(seen)).as("v3 is older than the snapshot: never shown").containsExactly(1L);
    }
}
