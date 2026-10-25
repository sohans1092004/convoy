package convoy.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.platform.security.ServiceTokens;
import convoy.platform.testing.ServiceTest;
import convoy.platform.testing.TestTokens;
import convoy.testing.StubServer;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "convoy.messaging.enabled=false", "realtime.heartbeat=1s", "realtime.revalidate=1s",
        "convoy.issuers.convoy-gateway=http://unused", "convoy.issuers.convoy-identity=http://unused"})
@Import({ServiceTest.Config.class, RealtimeIT.Stubs.class})
class RealtimeIT extends ServiceTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final UUID MEMBER = UUID.randomUUID(), STRANGER = UUID.randomUUID(), INVITED = UUID.randomUUID(), OTHER_DEALER = UUID.randomUUID();
    static final List<UUID> REVOKED = new CopyOnWriteArrayList<>();
    static final StubServer POOL = new StubServer()
            .on("/internal/pools/.*/access", r -> {
                String q = r.query() == null ? "" : r.query();
                boolean ok = (q.contains(MEMBER.toString()) && !REVOKED.contains(MEMBER)) || q.contains(INVITED.toString());
                return StubServer.Reply.json("{\"allowed\":" + ok + "}");
            })
            .on("/internal/pools/.*/version", r -> StubServer.Reply.json("{\"version\":42}"));

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
    static void stop() { POOL.close(); }

    @Autowired Hub hub;
    @Autowired StringRedisTemplate redis;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("redis") GenericContainer<?> redisContainer;
    @Autowired ServiceTokens unused;

    /** A live SSE client: collects (event, data) pairs until closed. */
    final class Client {
        final List<String[]> events = new CopyOnWriteArrayList<>();
        final java.util.concurrent.CompletableFuture<HttpResponse<java.util.stream.Stream<String>>> response;
        volatile int status;

        Client(UUID pool, String token) { this("/realtime/pools/" + pool + "/stream", token); }

        Client(String path, String token) {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Authorization", "Bearer " + token).header("Accept", "text/event-stream").GET().build();
            response = http.sendAsync(req, HttpResponse.BodyHandlers.ofLines());
            response.thenAccept(r -> {
                status = r.statusCode();
                String[] ev = {null, null};
                r.body().forEach(line -> {
                    if (line.startsWith("event:")) ev[0] = line.substring(6).trim();
                    else if (line.startsWith("data:")) ev[1] = line.substring(5).trim();
                    else if (line.isEmpty() && ev[0] != null) {
                        events.add(new String[] {ev[0], ev[1]});
                        ev[0] = null;
                        ev[1] = null;
                    }
                });
            });
        }

        JsonNode await(String event, Predicate<JsonNode> where) throws Exception {
            long until = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < until) {
                for (String[] e : events) {
                    if (e[0].equals(event) && where.test(JSON.readTree(e[1]))) return JSON.readTree(e[1]);
                }
                Thread.sleep(50);
            }
            throw new AssertionError("no " + event + " event; got " + events.stream().map(e -> e[0]).toList());
        }
    }

    @Autowired RealtimeApplication.RealtimeSettings settings;

    @Test
    void atTheConnectionCapANewStreamIsRefusedWith503AndRetryAfter() throws Exception {
        // P2.5.B fail fast: fill the instance to realtime.max-connections with idle connections, then ask for one more.
        java.util.List<Connection> fillers = new java.util.ArrayList<>();
        try {
            for (int i = hub.connections(); i < settings.maxConnections(); i++) {
                Connection f = new Connection("pool:filler", UUID.randomUUID(), UUID.randomUUID(), null, "filler", new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(0L), 4, hub::remove);
                hub.add(f);
                fillers.add(f);
            }
            Resp refused = get("/realtime/pools/" + UUID.randomUUID() + "/stream", TestTokens.dealer("realtime", UUID.randomUUID(), INVITED));
            assertThat(refused.status()).isEqualTo(503);
            assertThat(refused.headers().firstValue("Retry-After")).hasValueSatisfying(v -> assertThat(Integer.parseInt(v)).isBetween(1, 5));
            assertThat(refused.body()).contains("DEPENDENCY_UNAVAILABLE");
        } finally {
            fillers.forEach(hub::remove);
        }
        assertThat(hub.connections()).isLessThan(settings.maxConnections()); // capacity back: streams are accepted again
    }

    @Test
    void theBuyerStreamCarriesOnlyTheQuoteCountAndTheComparisonSignalToMembers() throws Exception {
        // E122 (P3.E): members of the pool only; never any quote content before (or after) the round closes.
        UUID pool = UUID.randomUUID();
        Client member = new Client("/realtime/me/stream?pool_id=" + pool, TestTokens.buyer("realtime", MEMBER));
        member.await("ready", j -> j.path("quote_count").asLong() == 0);
        hub.quoteCount(pool.toString(), 1, java.time.Instant.now());
        JsonNode count = member.await("quotes.changed", j -> j.at("/delta/quote_count").asLong() == 1);
        java.util.List<String> keys = new java.util.ArrayList<>();
        count.path("delta").fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("pool_id", "quote_count"); // no price, dealer, tier or line item
        assertThat(count.toString()).doesNotContain("paise").doesNotContain("dealership").doesNotContain("cash");
        hub.comparisonReady(pool.toString(), 1L, java.time.Instant.now());
        member.await("quotes.changed", j -> j.at("/delta/comparison_ready").asBoolean());
        // Not a member: 404 (no existence leak); dealers cannot open a buyer stream; no token: 401.
        assertThat(get("/realtime/me/stream?pool_id=" + pool, TestTokens.buyer("realtime", STRANGER)).status()).isEqualTo(404);
        assertThat(get("/realtime/me/stream?pool_id=" + pool, TestTokens.dealer("realtime", UUID.randomUUID(), INVITED)).status()).isEqualTo(403);
        assertThat(get("/realtime/me/stream?pool_id=" + pool, null).status()).isEqualTo(401);
    }

    @Test
    void theDealerStreamCarriesOnlyTheCallersOwnDealershipsRankAndRounds() throws Exception {
        // E121 (P3.C): per-resource authorisation is the token's own dealership; nothing of another dealership leaks.
        UUID mine = UUID.randomUUID(), rival = UUID.randomUUID();
        Client me = new Client("/realtime/dealer/stream", TestTokens.dealer("realtime", UUID.randomUUID(), mine));
        Client them = new Client("/realtime/dealer/stream", TestTokens.dealer("realtime", UUID.randomUUID(), rival));
        me.await("ready", j -> j.path("id").asText().equals(mine.toString()));
        them.await("ready", j -> j.path("id").asText().equals(rival.toString()));
        hub.publish("dealer", mine.toString(), System.currentTimeMillis(), "RankChanged", java.time.Instant.now(),
                Map.of("quote_id", UUID.randomUUID().toString(), "variant_id", UUID.randomUUID().toString(), "rank", 2, "of_n", 4));
        me.await("dealer.changed", j -> j.at("/delta/rank").asInt() == 2 && j.at("/delta/of_n").asInt() == 4);
        Thread.sleep(500);
        assertThat(them.events.stream().map(e -> e[0]).toList()).doesNotContain("dealer.changed");
        // Buyers and admins cannot open it; no token: 401.
        assertThat(get("/realtime/dealer/stream", TestTokens.buyer("realtime", UUID.randomUUID())).status()).isEqualTo(403);
        assertThat(get("/realtime/dealer/stream", null).status()).isEqualTo(401);
    }

    @Test
    void onlyMembersAndInvitedDealersMayStreamAPoolAndOthersSeeNothing() throws Exception {
        UUID pool = UUID.randomUUID();
        assertThat(get("/realtime/pools/" + pool + "/stream", TestTokens.buyer("realtime", STRANGER)).status()).isEqualTo(404);
        assertThat(get("/realtime/pools/" + pool + "/stream", TestTokens.dealer("realtime", UUID.randomUUID(), OTHER_DEALER)).status()).isEqualTo(404);
        assertThat(get("/realtime/pools/" + pool + "/stream", TestTokens.admin("realtime", UUID.randomUUID())).status()).isEqualTo(403);
        assertThat(get("/realtime/pools/" + pool + "/stream", null).status()).isEqualTo(401);
        Client dealer = new Client(pool, TestTokens.dealer("realtime", UUID.randomUUID(), INVITED));
        dealer.await("ready", j -> j.path("id").asText().equals(pool.toString()));
    }

    @Test
    void authorisationMatrixForEveryBuiltEndpoint() {
        assertThat(convoy.platform.testing.AuthzDenyChecks.run("realtime", this::send, (m, p) -> null)).isEmpty();
    }

    @Test
    void deltasCarryAggregateAndVersionButNeverABuyerOrDealerIdentity() throws Exception {
        UUID pool = UUID.randomUUID();
        Client buyer = new Client(pool, TestTokens.buyer("realtime", MEMBER));
        buyer.await("ready", j -> true);
        Map<String, Object> payload = Map.of("pool_id", pool.toString(), "membership_id", UUID.randomUUID().toString(), "buyer_id", UUID.randomUUID().toString(),
                "payment_id", UUID.randomUUID().toString(), "interested_count", 7, "committed_count", 3, "kept_from_previous_round", false);
        hub.publish("pool", pool.toString(), 5, "MemberCommitted", Instant.now(), RealtimeApplication.delta(payload));
        JsonNode m = buyer.await("pool.changed", j -> j.path("version").asLong() == 5);
        assertThat(m.path("aggregate").asText()).isEqualTo("pool");
        assertThat(m.path("id").asText()).isEqualTo(pool.toString());
        assertThat(m.path("delta").path("committed_count").asInt()).isEqualTo(3);
        assertThat(m.toString()).doesNotContain("buyer_id").doesNotContain("membership_id").doesNotContain("payment_id");
    }

    @Test
    void aMessagePublishedByAnotherInstanceReachesThisInstancesClientsThroughRedis() throws Exception {
        UUID pool = UUID.randomUUID();
        Client buyer = new Client(pool, TestTokens.buyer("realtime", MEMBER));
        buyer.await("ready", j -> true);
        // What realtime-2 sends when it consumed the event from the shared SQS queue.
        redis.convertAndSend("agg:pool:" + pool, "{\"aggregate\":\"pool\",\"id\":\"" + pool + "\",\"version\":9,\"event_type\":\"MemberJoined\","
                + "\"occurred_at\":\"" + Instant.now() + "\",\"delta\":{\"interested_count\":4}}");
        assertThat(buyer.await("pool.changed", j -> j.path("version").asLong() == 9).path("delta").path("interested_count").asInt()).isEqualTo(4);
        // Heartbeats carry the latest version this instance knows (clients compare it with theirs).
        assertThat(buyer.await("heartbeat", j -> j.path("version").asLong() == 9).path("server_time").asText()).isNotBlank();
    }

    @Test
    void withRedisDownTheConsumingInstanceStillDeliversToItsOwnClients() throws Exception {
        UUID pool = UUID.randomUUID();
        Client buyer = new Client(pool, TestTokens.buyer("realtime", MEMBER));
        buyer.await("ready", j -> true);
        redisContainer.getDockerClient().pauseContainerCmd(redisContainer.getContainerId()).exec();
        try {
            hub.publish("pool", pool.toString(), 11, "MemberLeft", Instant.now(), Map.of("committed_count", 2));
            buyer.await("pool.changed", j -> j.path("version").asLong() == 11);
        } finally {
            redisContainer.getDockerClient().unpauseContainerCmd(redisContainer.getContainerId()).exec();
        }
    }

    @Test
    void aBuyerWhoLosesAccessIsDisconnectedAtTheNextRecheck() throws Exception {
        UUID pool = UUID.randomUUID();
        Client buyer = new Client(pool, TestTokens.buyer("realtime", MEMBER));
        buyer.await("ready", j -> true);
        REVOKED.add(MEMBER);
        try {
            assertThat(buyer.await("closed", j -> true).path("reason").asText()).isEqualTo("access revoked");
        } finally {
            REVOKED.clear();
        }
    }

}
