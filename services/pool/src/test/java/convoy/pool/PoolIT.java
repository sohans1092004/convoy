package convoy.pool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.contracts.events.PaymentCaptured;
import convoy.platform.messaging.Envelope;
import convoy.platform.messaging.EventContext;
import convoy.platform.messaging.Uuid7;
import convoy.platform.problem.ApiProblem;
import convoy.platform.security.ServiceTokens;
import convoy.platform.testing.AuthzDenyChecks;
import convoy.platform.testing.Outboxes;
import convoy.platform.testing.ServiceTest;
import convoy.platform.testing.StubServer;
import convoy.platform.testing.TestTokens;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.web.client.RestClient;

/** Pool through HTTP on a real PostgreSQL: exact counts under concurrency, DB-clock deadlines, invalid commands, authz, contracts. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "convoy.messaging.enabled=false", "pool.sweeper-interval=1h", "pool.min-members=1",
        "convoy.issuers.convoy-gateway=http://unused", "convoy.issuers.convoy-identity=http://unused"})
@Import({ServiceTest.Config.class, PoolIT.Stubs.class})
class PoolIT extends ServiceTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final UUID DEALERSHIP = UUID.fromString("0190f3a0-0000-7000-8000-00000000d001");
    static final UUID VARIANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    static final UUID SLOW_BUYER = UUID.randomUUID();
    static final StubServer STUB = new StubServer()
            .on("/internal/models/.*", r -> StubServer.Reply.json("{\"id\":\"22222222-2222-7222-8222-222222222222\",\"slug\":\"maruti-swift\",\"brand\":\"Maruti Suzuki\","
                    + "\"variant_ids\":[\"" + VARIANT + "\"],\"variants\":[{\"id\":\"" + VARIANT + "\",\"code\":\"swift-lxi-mt\",\"name\":\"LXi MT\",\"fuel\":\"PETROL\",\"engine_cc\":1197}]}"))
            .on("/internal/deposits", r -> {
                String key = r.headers().get("Idempotency-key").get(0);
                long delay = r.body().contains(SLOW_BUYER.toString()) ? 2500 : 0;
                return new StubServer.Reply(200, "{\"payment_id\":\"" + UUID.nameUUIDFromBytes(key.getBytes()) + "\",\"state\":\"INITIATED\",\"amount_paise\":99900,"
                        + "\"razorpay_order_id\":\"order_test\",\"razorpay_key_id\":\"rzp_test_fake\",\"test_mode\":true}", delay);
            })
            .on("/internal/eligibility", r -> StubServer.Reply.json("{\"dealership_ids\":[\"" + DEALERSHIP + "\"],\"count\":1}"));

    @TestConfiguration(proxyBeanMethods = false)
    static class Stubs {
        @Bean
        @Primary
        PoolApplication.Clients testClients(RestClient.Builder b) {
            RestClient c = b.clone().baseUrl(STUB.url()).build();
            return new PoolApplication.Clients(c, c, c, c);
        }
    }

    @AfterAll
    static void stop() { STUB.close(); }

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired Pools pools;
    @Autowired PoolSweeper sweeper;
    @Autowired ServiceTokens unusedTokens;

    String buyer(UUID id) { return TestTokens.buyer("pool", id); }

    UUID createPool(UUID buyer) throws Exception {
        Resp r = send("POST", "/pools", buyer(buyer), "{\"model_slug\":\"maruti-swift\",\"city\":\"Pune\",\"purchase_month\":\"" + java.time.YearMonth.now(Pools.IST) + "\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(r.status()).as(r.body()).isIn(200, 201);
        UUID id = UUID.fromString(JSON.readTree(r.body()).at("/pool/id").asText());
        // Each test gets its own pool: close the shared open one so the next create makes a new pool.
        return id;
    }

    UUID freshPool() throws Exception {
        UUID id = createPool(UUID.randomUUID());
        jdbc.update("update pools set purchase_month = 'test-' || id::text where id = ?", id);
        return id;
    }

    static <T> List<T> parallel(int n, Callable<T> task) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            List<Future<T>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) fs.add(pool.submit(task));
            List<T> out = new ArrayList<>();
            for (Future<T> f : fs) out.add(f.get());
            return out;
        }
    }

    @Test
    void hundredParallelJoinsGiveAnExactCount() throws Exception {
        UUID pool = freshPool();
        List<Integer> codes = parallel(100, () -> send("POST", "/pools/" + pool + "/members", buyer(UUID.randomUUID()), null,
                Map.of("Idempotency-Key", UUID.randomUUID().toString())).status());
        assertThat(codes).containsOnly(201);
        assertThat(jdbc.queryForObject("select interested_count from pools where id = ?", Integer.class, pool)).isEqualTo(100);
        assertThat(jdbc.queryForObject("select count(*) from memberships where pool_id = ?", Integer.class, pool)).isEqualTo(100);
        // One MemberJoined per join, each with its own aggregate version.
        assertThat(jdbc.queryForObject("select count(distinct aggregate_version) from outbox where aggregate_id = ? and event_type = 'MemberJoined'", Integer.class, pool)).isEqualTo(100);
    }

    @Test
    void doubleClickJoinWithOneKeyMakesOneMembership() throws Exception {
        UUID pool = freshPool();
        UUID b = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        List<Resp> rs = parallel(20, () -> send("POST", "/pools/" + pool + "/members", buyer(b), null, Map.of("Idempotency-Key", key)));
        assertThat(rs).extracting(Resp::status).containsOnly(201);
        assertThat(rs.stream().filter(r -> r.headers().firstValue("Idempotent-Replayed").isPresent()).count()).isEqualTo(19);
        assertThat(jdbc.queryForObject("select interested_count from pools where id = ?", Integer.class, pool)).isEqualTo(1);
        // A second click with a new key is a second intent: 409 already-member, count unchanged.
        Resp again = send("POST", "/pools/" + pool + "/members", buyer(b), null, Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(again.status()).isEqualTo(409);
        assertThat(JSON.readTree(again.body()).path("code").asText()).isEqualTo("ALREADY_MEMBER");
        // Same key, different body: 422.
        Resp reused = send("POST", "/pools/" + pool + "/members", buyer(b), "{\"x\":1}", Map.of("Idempotency-Key", key));
        assertThat(reused.status()).isEqualTo(422);
    }

    @Test
    void hundredParallelCommitsAndCapturesGiveExactCounts() throws Exception {
        UUID pool = freshPool();
        jdbc.update("update pools set min_members = 100 where id = ?", pool);
        String terms = "2026-10-24-p0";
        List<UUID> buyers = new ArrayList<>();
        for (int i = 0; i < 100; i++) buyers.add(UUID.randomUUID());
        java.util.concurrent.atomic.AtomicInteger i = new java.util.concurrent.atomic.AtomicInteger();
        List<Resp> rs = parallel(100, () -> send("POST", "/pools/" + pool + "/commitment", buyer(buyers.get(i.getAndIncrement())), "{\"terms_version\":\"" + terms + "\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString())));
        assertThat(rs).extracting(Resp::status).containsOnly(200);
        assertThat(jdbc.queryForObject("select interested_count from pools where id = ?", Integer.class, pool)).isEqualTo(100);
        // Deliver the 100 PaymentCaptured events concurrently, each in its own transaction (as the inbox consumer does).
        List<Map<String, Object>> ms = jdbc.queryForList("select buyer_id, current_payment_id from memberships where pool_id = ?", pool);
        java.util.concurrent.atomic.AtomicInteger j = new java.util.concurrent.atomic.AtomicInteger();
        parallel(100, () -> {
            Map<String, Object> m = ms.get(j.getAndIncrement());
            Envelope env = new Envelope(Uuid7.next(), "PaymentCaptured", 1, (UUID) m.get("current_payment_id"), 2, "2026-10-24T00:00:00Z", Uuid7.next(), null, "payment", null);
            for (int attempt = 0; ; attempt++) {
                try {
                    EventContext.set(env.correlationId(), env.eventId());
                    tx.executeWithoutResult(s -> pools.paymentCaptured(env, new PaymentCaptured(m.get("current_payment_id").toString(), m.get("buyer_id").toString(),
                            pool.toString(), 99900L, false, true)));
                    return null;
                } catch (org.springframework.dao.PessimisticLockingFailureException e) {
                    if (attempt > 5) throw e;
                } finally {
                    EventContext.clear();
                }
            }
        });
        assertThat(jdbc.queryForObject("select committed_count from pools where id = ?", Integer.class, pool)).isEqualTo(100);
        assertThat(jdbc.queryForObject("select count(*) from memberships where pool_id = ? and state = 'COMMITTED'", Integer.class, pool)).isEqualTo(100);
        assertThat(jdbc.queryForObject("select state from pools where id = ?", String.class, pool)).isEqualTo("READY_FOR_QUOTES");
        assertThat(jdbc.queryForObject("select count(*) from outbox where aggregate_id = ? and event_type = 'PoolReady'", Integer.class, pool)).isEqualTo(1);
    }

    @Test
    void commitThatArrivesBeforeTheDeadlineButCommitsAfterItIsRejected() throws Exception {
        UUID pool = freshPool();
        // Pool in QUOTE_OPEN with the sealed round closing 1.5 s from now (DB clock).
        jdbc.update("update pools set state = 'QUOTE_OPEN' where id = ?", pool);
        jdbc.update("insert into rounds(id, pool_id, round_set, round_no, kind, state, deadline) values (?, ?, 1, 1, 'SEALED', 'OPEN', clock_timestamp() + interval '1500 milliseconds')",
                Uuid7.next(), pool);
        long sent = System.currentTimeMillis();
        // The payment call for this buyer takes 2.5 s, so the request reaches pool before the deadline and commits after it.
        Resp r = send("POST", "/pools/" + pool + "/commitment", buyer(SLOW_BUYER), "{\"terms_version\":\"2026-10-24-p0\"}", Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        JsonNode p = JSON.readTree(r.body());
        assertThat(r.status()).as(r.body()).isEqualTo(409);
        assertThat(p.path("code").asText()).isEqualTo("DEADLINE_PASSED");
        assertThat(java.time.Instant.parse(p.path("server_time").asText())).isAfter(java.time.Instant.parse(p.path("deadline").asText()));
        assertThat(java.time.Instant.parse(p.path("deadline").asText()).toEpochMilli()).isGreaterThan(sent); // the request was sent before the deadline
        assertThat(jdbc.queryForObject("select count(*) from memberships where pool_id = ?", Integer.class, pool)).isZero();
    }

    @Test
    void invalidCommandsReturnTheirSpecifiedErrors() throws Exception {
        UUID pool = freshPool();
        UUID b = UUID.randomUUID();
        Map<String, String> idem = Map.of("Idempotency-Key", UUID.randomUUID().toString());
        assertThat(send("POST", "/pools/" + pool + "/members", buyer(b), null, Map.of()).status()).isEqualTo(428); // idempotency-key-missing
        assertThat(send("POST", "/pools/" + pool + "/members", buyer(b), null, idem).status()).isEqualTo(201);
        Resp notCommitted = send("PUT", "/pools/" + pool + "/members/me/acceptance", buyer(b), "{\"quote_id\":\"" + UUID.randomUUID() + "\",\"variant_id\":\"" + VARIANT + "\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString(), "If-Match", "\"1\""));
        assertThat(code(notCommitted)).isEqualTo("NOT_A_MEMBER");
        Resp noIfMatch = send("DELETE", "/pools/" + pool + "/members/me", buyer(b), null, Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(code(noIfMatch)).isEqualTo("PRECONDITION_REQUIRED");
        Resp stale = send("DELETE", "/pools/" + pool + "/members/me", buyer(b), null, Map.of("Idempotency-Key", UUID.randomUUID().toString(), "If-Match", "\"99\""));
        assertThat(code(stale)).isEqualTo("VERSION_CONFLICT");
        Resp wrongTerms = send("POST", "/pools/" + pool + "/commitment", buyer(b), "{\"terms_version\":\"old\"}", Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(code(wrongTerms)).isEqualTo("VALIDATION_FAILED");
        assertThat(code(send("PUT", "/pools/" + pool + "/members/me/reround-choice", buyer(b), "{}", Map.of()))).isEqualTo("NOT_IMPLEMENTED_IN_P1");

        // Committed member, pool moved to DECISION_OPEN: joining and committing are invalid; a non-final quote cannot be accepted.
        UUID c = UUID.randomUUID();
        assertThat(send("POST", "/pools/" + pool + "/commitment", buyer(c), "{\"terms_version\":\"2026-10-24-p0\"}", Map.of("Idempotency-Key", UUID.randomUUID().toString())).status()).isEqualTo(200);
        jdbc.update("update memberships set state = 'COMMITTED' where pool_id = ? and buyer_id = ?", pool, c);
        jdbc.update("update pools set state = 'DECISION_OPEN', decision_deadline = clock_timestamp() + interval '1 hour' where id = ?", pool);
        assertThat(code(send("POST", "/pools/" + pool + "/members", buyer(UUID.randomUUID()), null, Map.of("Idempotency-Key", UUID.randomUUID().toString())))).isEqualTo("INVALID_TRANSITION");
        long v = jdbc.queryForObject("select version from memberships where pool_id = ? and buyer_id = ?", Long.class, pool, c);
        Resp notFinal = send("PUT", "/pools/" + pool + "/members/me/acceptance", buyer(c), "{\"quote_id\":\"" + UUID.randomUUID() + "\",\"variant_id\":\"" + VARIANT + "\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString(), "If-Match", "\"" + v + "\""));
        assertThat(code(notFinal)).isEqualTo("QUOTE_NOT_FINAL");
        // Decision deadline passed (DB clock): acceptance is deadline-passed.
        jdbc.update("update pools set decision_deadline = clock_timestamp() - interval '1 second' where id = ?", pool);
        Resp late = send("PUT", "/pools/" + pool + "/members/me/acceptance", buyer(c), "{\"quote_id\":\"" + UUID.randomUUID() + "\",\"variant_id\":\"" + VARIANT + "\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString(), "If-Match", "\"" + v + "\""));
        assertThat(code(late)).isEqualTo("DEADLINE_PASSED");
        // CONFIRMATION: leaving is invalid (decline through the order instead).
        jdbc.update("update pools set state = 'CONFIRMATION' where id = ?", pool);
        assertThat(code(send("DELETE", "/pools/" + pool + "/members/me", buyer(c), null, Map.of("Idempotency-Key", UUID.randomUUID().toString(), "If-Match", "\"" + v + "\""))))
                .isEqualTo("INVALID_TRANSITION");
        // One active commitment per model: committing in a second pool for the same model is refused.
        UUID other = freshPool();
        jdbc.update("update pools set state = 'FORMING' where id = ?", pool);
        UUID d = UUID.randomUUID();
        assertThat(send("POST", "/pools/" + pool + "/commitment", buyer(d), "{\"terms_version\":\"2026-10-24-p0\"}", Map.of("Idempotency-Key", UUID.randomUUID().toString())).status()).isEqualTo(200);
        assertThat(code(send("POST", "/pools/" + other + "/commitment", buyer(d), "{\"terms_version\":\"2026-10-24-p0\"}", Map.of("Idempotency-Key", UUID.randomUUID().toString()))))
                .isEqualTo("ACTIVE_COMMITMENT_EXISTS");
    }

    @Test
    void authorisationMatrixForEveryP1Endpoint() throws Exception {
        UUID pool = freshPool();
        List<String> failures = AuthzDenyChecks.run("pool", this::send, (method, path) -> switch (path) {
            case "/pools" -> "{\"model_slug\":\"maruti-swift\",\"city\":\"Pune\",\"purchase_month\":\"" + java.time.YearMonth.now(Pools.IST) + "\"}";
            case "/pools/{id}/commitment" -> "{\"terms_version\":\"2026-10-24-p0\"}";
            case "/pools/{id}/members/me/acceptance" -> "{\"quote_id\":\"" + UUID.randomUUID() + "\",\"variant_id\":\"" + VARIANT + "\"}";
            default -> null;
        });
        assertThat(failures).isEmpty();
        // AZ-E033-X / AZ-T1: a dealership not invited to any round of the pool gets 404, an invited one 200.
        assertThat(get("/pools/" + pool, TestTokens.dealer("pool", UUID.randomUUID(), UUID.randomUUID())).status()).isEqualTo(404);
        jdbc.update("update pools set state = 'READY_FOR_QUOTES' where id = ?", pool);
        sweeper.openSealedRound(pool);
        assertThat(get("/pools/" + pool, TestTokens.dealer("pool", UUID.randomUUID(), DEALERSHIP)).status()).isEqualTo(200);
        // AZ-T4 / AZ-E039: a buyer sees only their own membership.
        UUID a = UUID.randomUUID();
        UUID pool2 = freshPool();
        send("POST", "/pools/" + pool2 + "/members", buyer(a), null, Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(get("/pools/" + pool2 + "/members/me", buyer(a)).status()).isEqualTo(200);
        assertThat(get("/pools/" + pool2 + "/members/me", buyer(UUID.randomUUID())).status()).isEqualTo(404);
    }

    @Test
    void transitionTablesRejectEveryInvalidPairAndMark501ForUnbuiltOnes() throws Exception {
        JsonNode all = JSON.readTree(PoolIT.class.getResourceAsStream("/convoy/transitions.json"));
        for (var t : List.of(Pools.POOL, Pools.MEMBERSHIP, Pools.ROUND)) {
            JsonNode m = all.get(t.machine());
            for (JsonNode p : m.get("invalid_pairs")) {
                try {
                    t.check(p.get("from").asText(), p.get("to").asText());
                    throw new AssertionError(t.machine() + " accepted invalid pair " + p);
                } catch (ApiProblem e) {
                    assertThat(e.type().slug).isEqualTo("invalid-transition");
                }
            }
            for (JsonNode v : m.get("valid")) {
                String from = v.get("from").asText(), to = v.get("to").asText();
                Set<String> ids = t.idsFor(from, to);
                if (ids.stream().anyMatch(t::isImplemented)) {
                    assertThat(t.check(from, to)).isIn(ids);
                } else {
                    try {
                        t.check(from, to);
                        throw new AssertionError(t.machine() + " " + v + " should be 501");
                    } catch (ApiProblem e) {
                        assertThat(e.type().slug).isEqualTo("not-implemented-in-p1");
                    }
                }
            }
        }
    }

    @Test
    void everyPublishedEventMatchesItsSchema() throws Exception {
        UUID pool = freshPool();
        send("POST", "/pools/" + pool + "/members", buyer(UUID.randomUUID()), null, Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        jdbc.update("update pools set state = 'READY_FOR_QUOTES' where id = ?", pool);
        sweeper.openSealedRound(pool);
        assertThat(Outboxes.assertAllValid(jdbc, "pool")).isPositive();
    }

    static String code(Resp r) {
        try {
            return JSON.readTree(r.body()).path("code").asText();
        } catch (Exception e) {
            return "?" + r.status();
        }
    }
}
