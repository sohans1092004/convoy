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
import convoy.testing.StubServer;
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
    static final List<String> ELIGIBILITY_QUERIES = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final StubServer STUB = new StubServer()
            .on("/internal/models/.*", r -> StubServer.Reply.json("{\"id\":\"22222222-2222-7222-8222-222222222222\",\"slug\":\"maruti-swift\",\"brand\":\"Maruti Suzuki\","
                    + "\"variant_ids\":[\"" + VARIANT + "\"],\"variants\":[{\"id\":\"" + VARIANT + "\",\"code\":\"swift-lxi-mt\",\"name\":\"LXi MT\",\"fuel\":\"PETROL\",\"engine_cc\":1197}]}"))
            .on("/internal/deposits", r -> {
                String key = r.headers().get("Idempotency-key").get(0);
                long delay = r.body().contains(SLOW_BUYER.toString()) ? 2500 : 0;
                return new StubServer.Reply(200, "{\"payment_id\":\"" + UUID.nameUUIDFromBytes(key.getBytes()) + "\",\"state\":\"INITIATED\",\"amount_paise\":99900,"
                        + "\"razorpay_order_id\":\"order_test\",\"razorpay_key_id\":\"rzp_test_fake\",\"test_mode\":true}", delay);
            })
            .on("/internal/eligibility", r -> { ELIGIBILITY_QUERIES.add(String.valueOf(r.query())); return StubServer.Reply.json("{\"dealership_ids\":[\"" + DEALERSHIP + "\"],\"count\":1}"); })
            .on("/internal/rounds/.*/summary", r -> StubServer.Reply.json("{\"round_id\":\"x\",\"kind\":\"SEALED\",\"valid_count\":0,\"final_count\":0,\"quotes\":[]}"));

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

    /** P6.B: a demo visitor's pool is their own: never shared, never suggested, only their demo dealership is asked for. */
    @Test
    void demoVisitorsGetTheirOwnPoolAndRealBuyersNeverSeeIt() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), real = UUID.randomUUID();
        String month = java.time.YearMonth.now(Pools.IST).toString();
        String body = "{\"model_slug\":\"maruti-swift\",\"city\":\"Pune\",\"purchase_month\":\"" + month + "\"}";
        java.util.function.Function<String, UUID> create = token -> {
            try {
                Resp r = send("POST", "/pools", token, body, Map.of("Idempotency-Key", UUID.randomUUID().toString()));
                assertThat(r.status()).as(r.body()).isIn(200, 201);
                return UUID.fromString(JSON.readTree(r.body()).at("/pool/id").asText());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        String demoA = TestTokens.user("pool", a, List.of("buyer", "demo"), null), demoB = TestTokens.user("pool", b, List.of("buyer", "demo"), null);
        UUID poolA = create.apply(demoA), poolB = create.apply(demoB), shared = create.apply(buyer(real));
        assertThat(poolA).isNotEqualTo(poolB).isNotEqualTo(shared);
        assertThat(create.apply(demoA)).isEqualTo(poolA); // the same visitor finds their own pool again
        assertThat(create.apply(buyer(UUID.randomUUID()))).isEqualTo(shared); // real buyers keep sharing theirs
        jdbc.update("insert into preferences(buyer_id, city, purchase_month) values (?, 'Pune', ?) on conflict (buyer_id) do update set city = 'Pune', purchase_month = excluded.purchase_month", real, month);
        assertThat(get("/me/pool-suggestions?model=maruti-swift", buyer(real)).body()).doesNotContain(poolA.toString()).doesNotContain(poolB.toString());
        jdbc.update("update pools set state = 'READY_FOR_QUOTES' where id = ?", poolA);
        sweeper.openSealedRound(poolA);
        assertThat(ELIGIBILITY_QUERIES).anyMatch(q -> q.contains("owner=" + a));
        for (UUID p : List.of(poolA, poolB)) jdbc.update("update pools set purchase_month = 'test-' || id::text where id = ?", p);
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

    /** Commits a buyer through the API and delivers the PaymentCaptured event as the inbox consumer would. */
    UUID commitAndCapture(UUID pool, UUID buyer) throws Exception {
        assertThat(send("POST", "/pools/" + pool + "/commitment", buyer(buyer), "{\"terms_version\":\"2026-10-24-p0\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString())).status()).isEqualTo(200);
        UUID payment = jdbc.queryForObject("select current_payment_id from memberships where pool_id = ? and buyer_id = ?", UUID.class, pool, buyer);
        capture(pool, buyer, payment, false);
        return payment;
    }

    void capture(UUID pool, UUID buyer, UUID payment, boolean late) {
        Envelope env = new Envelope(Uuid7.next(), "PaymentCaptured", 1, payment, 2, "2026-10-24T00:00:00Z", Uuid7.next(), null, "payment", null);
        EventContext.set(env.correlationId(), env.eventId());
        try {
            tx.executeWithoutResult(s -> pools.paymentCaptured(env, new PaymentCaptured(payment.toString(), buyer.toString(), pool.toString(), 99900L, late, true)));
        } finally {
            EventContext.clear();
        }
    }

    /** Every pool version bump carries exactly one event, so a FULL subscriber (realtime) never sees a false gap. */
    void assertVersionsContiguous(UUID pool) {
        long version = jdbc.queryForObject("select version from pools where id = ?", Long.class, pool);
        assertThat(jdbc.queryForList("select aggregate_version from outbox where aggregate_id = ? order by aggregate_version", Long.class, pool))
                .isEqualTo(java.util.stream.LongStream.rangeClosed(1, version).boxed().toList());
    }

    @Test
    void formingDeadlineWithoutTheMinimumExpiresThePoolAndReleasesEveryCommittedMember() throws Exception {
        UUID pool = freshPool();
        jdbc.update("update pools set min_members = 5 where id = ?", pool);
        commitAndCapture(pool, UUID.randomUUID());
        commitAndCapture(pool, UUID.randomUUID());
        sweeper.expireForming(pool);
        assertThat(jdbc.queryForObject("select state from pools where id = ?", String.class, pool)).as("before the deadline nothing happens").isEqualTo("FORMING");
        jdbc.update("update pools set forming_deadline = clock_timestamp() - interval '1 second' where id = ?", pool);
        sweeper.expireForming(pool);
        sweeper.expireForming(pool); // repeat: VERSION_NOOP
        Map<String, Object> p = jdbc.queryForMap("select state, end_reason from pools where id = ?", pool);
        assertThat(p).containsEntry("state", "EXPIRED").containsEntry("end_reason", "MINIMUM_NOT_REACHED");
        assertThat(jdbc.queryForList("select distinct release_reason from memberships where pool_id = ?", String.class, pool)).containsExactly("POOL_MINIMUM_NOT_REACHED");
        assertThat(jdbc.queryForObject("select count(*) from outbox where aggregate_id = ? and event_type = 'PoolClosed' and payload->>'end_state' = 'EXPIRED'",
                Integer.class, pool)).isEqualTo(1);
        // A commit after the pool closed is refused.
        assertThat(send("POST", "/pools/" + pool + "/commitment", buyer(UUID.randomUUID()), "{\"terms_version\":\"2026-10-24-p0\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString())).status()).isEqualTo(409);
        assertVersionsContiguous(pool);
    }

    void bookingsIssued(UUID pool, long voidQuotes, List<UUID> voidBuyers) {
        Envelope env = new Envelope(Uuid7.next(), "BookingsIssued", 1, pool, 1000, "2026-10-24T00:00:00Z", Uuid7.next(), null, "order", null);
        EventContext.set(env.correlationId(), env.eventId());
        try {
            tx.executeWithoutResult(s -> pools.bookingsIssued(env, new convoy.contracts.events.BookingsIssued(pool.toString(), 1L, 0L, voidQuotes,
                    voidBuyers.stream().map(UUID::toString).toList())));
        } finally {
            EventContext.clear();
        }
    }

    Resp choose(UUID pool, UUID buyer, String choice) {
        long v = jdbc.queryForObject("select version from memberships where pool_id = ? and buyer_id = ?", Long.class, pool, buyer);
        return send("PUT", "/pools/" + pool + "/members/me/reround-choice", buyer(buyer), "{\"choice\":\"" + choice + "\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString(), "If-Match", "\"" + v + "\""));
    }

    /** AMENDMENT-002: every quote VOID and a full cycle fits -> one re-round; VOID buyers keep, refund, or (no answer) are refunded. */
    @Test
    void allQuotesVoidReopensThePoolOnceAndEachVoidBuyerChoosesWithin48Hours() throws Exception {
        UUID pool = freshPool();
        UUID keep = UUID.randomUUID(), refund = UUID.randomUUID(), silent = UUID.randomUUID(), late = UUID.randomUUID(), other = UUID.randomUUID();
        Map<UUID, UUID> payments = new java.util.HashMap<>();
        for (UUID b : List.of(keep, refund, silent, late, other)) payments.put(b, commitAndCapture(pool, b));
        jdbc.update("update memberships set state = 'HANDED_TO_ORDER' where pool_id = ?", pool);
        jdbc.update("update pools set state = 'CONFIRMATION', purchase_window_end = clock_timestamp() + interval '90 days' where id = ?", pool);
        bookingsIssued(pool, 1, List.of(keep, refund, silent, late));
        bookingsIssued(pool, 1, List.of(keep, refund, silent, late)); // redelivery: no second re-round
        assertThat(jdbc.queryForMap("select state, round_set, committed_count from pools where id = ?", pool))
                .containsEntry("state", "FORMING").containsEntry("round_set", 2).containsEntry("committed_count", 0);
        assertThat(jdbc.queryForObject("select state from memberships where pool_id = ? and buyer_id = ?", String.class, pool, other)).isEqualTo("INTERESTED"); // MEM-T16
        assertThat(jdbc.queryForObject("select count(*) from memberships where pool_id = ? and state = 'REROUND_CHOICE' and choice_deadline > clock_timestamp() + interval '47 hours'",
                Integer.class, pool)).isEqualTo(4); // MEM-T12, 48 h on the DB clock
        assertThat(jdbc.queryForObject("select count(*) from outbox where aggregate_id = ? and event_type = 'PoolReopened'", Integer.class, pool)).isEqualTo(1);

        assertThat(choose(pool, keep, "KEEP").status()).isEqualTo(200); // MEM-T13: COMMITTED with the same deposit, no new payment
        assertThat(jdbc.queryForMap("select state, current_payment_id from memberships where pool_id = ? and buyer_id = ?", pool, keep))
                .containsEntry("state", "COMMITTED").containsEntry("current_payment_id", payments.get(keep));
        assertThat(jdbc.queryForObject("select committed_count from pools where id = ?", Integer.class, pool)).isEqualTo(1);
        assertThat(choose(pool, refund, "REFUND").status()).isEqualTo(200); // MEM-T14
        jdbc.update("update memberships set choice_deadline = clock_timestamp() - interval '1 second' where pool_id = ? and buyer_id in (?, ?)", pool, silent, late);
        assertThat(code(choose(pool, late, "KEEP"))).isEqualTo("DEADLINE_PASSED"); // the DB clock decides
        sweeper.sweep(); // MEM-T15 for both
        Map<String, String> reasons = new java.util.HashMap<>();
        jdbc.queryForList("select buyer_id, release_reason from memberships where pool_id = ? and state = 'RELEASED'", pool)
                .forEach(r -> reasons.put(r.get("buyer_id").toString(), (String) r.get("release_reason")));
        assertThat(reasons).containsEntry(refund.toString(), "REROUND_REFUND_REQUESTED").containsEntry(silent.toString(), "REROUND_NO_RESPONSE")
                .containsEntry(late.toString(), "REROUND_NO_RESPONSE").hasSize(3);
        // each release is one MemberReleased carrying the deposit (payment refunds it in full)
        assertThat(jdbc.queryForList("select payload->>'payment_id' from outbox where aggregate_id = ? and event_type = 'MemberReleased'", String.class, pool))
                .containsExactlyInAnyOrder(payments.get(refund).toString(), payments.get(silent).toString(), payments.get(late).toString());
        assertThat(code(choose(pool, silent, "KEEP"))).isEqualTo("INVALID_TRANSITION");
        assertVersionsContiguous(pool);
    }

    @Test
    void allQuotesVoidWithoutTimeForAReRoundExpiresThePool() throws Exception {
        UUID pool = freshPool();
        UUID b = UUID.randomUUID();
        commitAndCapture(pool, b);
        jdbc.update("update memberships set state = 'HANDED_TO_ORDER' where pool_id = ?", pool);
        jdbc.update("update pools set state = 'CONFIRMATION', purchase_window_end = clock_timestamp() + interval '1 hour' where id = ?", pool);
        bookingsIssued(pool, 1, List.of(b));
        assertThat(jdbc.queryForMap("select state, end_reason from pools where id = ?", pool)).containsEntry("state", "EXPIRED").containsEntry("end_reason", "ALL_QUOTES_VOID");
        assertThat(jdbc.queryForObject("select count(*) from outbox where aggregate_id = ? and event_type = 'PoolClosed'", Integer.class, pool)).isEqualTo(1); // POOL-T10 -> D27
        assertVersionsContiguous(pool);
    }

    @Test
    void lateCaptureAfterThePoolClosedReleasesTheMemberForAnAutomaticRefund() throws Exception {
        UUID pool = freshPool();
        jdbc.update("update pools set min_members = 5 where id = ?", pool);
        UUID slow = UUID.randomUUID();
        assertThat(send("POST", "/pools/" + pool + "/commitment", buyer(slow), "{\"terms_version\":\"2026-10-24-p0\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString())).status()).isEqualTo(200);
        UUID payment = jdbc.queryForObject("select current_payment_id from memberships where pool_id = ? and buyer_id = ?", UUID.class, pool, slow);
        jdbc.update("update pools set forming_deadline = clock_timestamp() - interval '1 second' where id = ?", pool);
        sweeper.expireForming(pool);
        capture(pool, slow, payment, true); // the delayed webhook's PaymentCaptured arrives after EXPIRED
        Map<String, Object> m = jdbc.queryForMap("select state, release_reason from memberships where pool_id = ? and buyer_id = ?", pool, slow);
        assertThat(m).containsEntry("state", "RELEASED").containsEntry("release_reason", "LATE_CAPTURE");
        String payload = jdbc.queryForObject("select payload::text from outbox where aggregate_id = ? and event_type = 'MemberReleased'", String.class, pool);
        assertThat(JSON.readTree(payload).path("reason").asText()).isEqualTo("LATE_CAPTURE");
        assertThat(JSON.readTree(payload).has("committed_count")).isTrue();
        assertThat(jdbc.queryForObject("select committed_count from pools where id = ?", Integer.class, pool)).isZero();
        assertVersionsContiguous(pool);
    }

    @Test
    void noEligibleDealerByTheWaitDeadlineEndsThePoolWithNoQuotes() throws Exception {
        UUID pool = freshPool();
        commitAndCapture(pool, UUID.randomUUID()); // min 1 -> READY_FOR_QUOTES
        assertThat(jdbc.queryForObject("select state from pools where id = ?", String.class, pool)).isEqualTo("READY_FOR_QUOTES");
        jdbc.update("update pools set dealer_wait_deadline = clock_timestamp() - interval '1 second' where id = ?", pool);
        sweeper.noEligibleDealer(pool);
        assertThat(jdbc.queryForMap("select state, end_reason from pools where id = ?", pool)).containsEntry("state", "NO_QUOTES").containsEntry("end_reason", "NO_ELIGIBLE_DEALER");
        assertThat(jdbc.queryForList("select release_reason from memberships where pool_id = ?", String.class, pool)).containsExactly("POOL_NO_ELIGIBLE_DEALER");
        assertVersionsContiguous(pool);
    }

    @Test
    void sealedRoundWithNoValidQuoteClosesTheRoundAndThePool() throws Exception {
        UUID pool = freshPool();
        commitAndCapture(pool, UUID.randomUUID());
        sweeper.openSealedRound(pool);
        assertThat(jdbc.queryForObject("select state from pools where id = ?", String.class, pool)).isEqualTo("QUOTE_OPEN");
        jdbc.update("update rounds set deadline = clock_timestamp() - interval '1 second' where pool_id = ?", pool);
        sweeper.closeSealedRound(pool); // quote summary stub: valid_count 0 -> POOL-T04
        assertThat(jdbc.queryForMap("select state, end_reason from pools where id = ?", pool)).containsEntry("state", "NO_QUOTES").containsEntry("end_reason", "NO_VALID_QUOTES");
        assertThat(jdbc.queryForObject("select state from rounds where pool_id = ?", String.class, pool)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForList("select event_type from outbox where aggregate_id = ? order by aggregate_version desc limit 2", String.class, pool))
                .containsExactly("PoolClosed", "RoundClosed");
        assertVersionsContiguous(pool);
    }

    @Test
    void fiftyConcurrentCreatesForOneKeyMakeExactlyOnePool() throws Exception {
        String month = java.time.YearMonth.now(Pools.IST).plusMonths(6).toString(); // no other test uses this key
        String body = "{\"model_slug\":\"maruti-swift\",\"city\":\"Mumbai\",\"purchase_month\":\"" + month + "\"}";
        List<Resp> rs = parallel(50, () -> send("POST", "/pools", buyer(UUID.randomUUID()), body, Map.of("Idempotency-Key", UUID.randomUUID().toString())));
        assertThat(rs).extracting(Resp::status).containsOnly(200, 201);
        assertThat(rs.stream().filter(r -> r.status() == 201).count()).isEqualTo(1);
        assertThat(rs.stream().map(r -> {
            try {
                return JSON.readTree(r.body()).at("/pool/id").asText();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).distinct().count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from pools where model_slug = 'maruti-swift' and city = 'Mumbai' and purchase_month = ?",
                Integer.class, month)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox o join pools p on p.id = o.aggregate_id where o.event_type = 'PoolCreated' "
                + "and p.city = 'Mumbai' and p.purchase_month = ?", Integer.class, month)).isEqualTo(1);
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
    void aBuyerMayRejoinAfterLeavingButNotAfterTheSecondLeave() throws Exception {
        // AMENDMENT-008 (Q-045): join, leave, rejoin, leave, then the third join is refused; counts stay exact throughout.
        UUID pool = freshPool();
        UUID b = UUID.randomUUID();
        java.util.function.Supplier<Resp> join = () -> send("POST", "/pools/" + pool + "/members", buyer(b), null, Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        java.util.function.Function<Long, Resp> leave = (v) -> send("DELETE", "/pools/" + pool + "/members/me", buyer(b), null,
                Map.of("Idempotency-Key", UUID.randomUUID().toString(), "If-Match", "\"" + v + "\""));
        JsonNode m = JSON.readTree(join.get().body());
        for (int round = 1; round <= 2; round++) {
            assertThat(leave.apply(m.path("version").asLong()).status()).isEqualTo(200);
            assertThat(jdbc.queryForObject("select interested_count from pools where id = ?", Integer.class, pool)).isZero();
            Resp again = join.get();
            if (round == 1) {
                assertThat(again.status()).isEqualTo(201);
                m = JSON.readTree(again.body());
                assertThat(m.path("state").asText()).isEqualTo("INTERESTED");
                assertThat(jdbc.queryForObject("select interested_count from pools where id = ?", Integer.class, pool)).isEqualTo(1);
            } else {
                assertThat(again.status()).isEqualTo(409);
                assertThat(JSON.readTree(again.body()).path("code").asText()).isEqualTo("REJOIN_LIMIT_REACHED");
            }
        }
        assertThat(jdbc.queryForObject("select count(*) from memberships where pool_id = ?", Integer.class, pool)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select leave_count from memberships where pool_id = ?", Integer.class, pool)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from outbox where aggregate_id = ? and event_type = 'MemberJoined'", Integer.class, pool)).isEqualTo(2);
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
        assertThat(JSON.readTree(reused.body()).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void crashAfterCommitBeforeTheResponseReplaysOnRetry() throws Exception {
        UUID pool = freshPool();
        UUID b = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        convoy.platform.Faults.arm(convoy.platform.idempotency.IdempotentHandlerAdapter.CRASH_AFTER_COMMIT);
        Resp crashed = send("POST", "/pools/" + pool + "/members", buyer(b), null, Map.of("Idempotency-Key", key));
        assertThat(crashed.status()).as("the client never saw a success").isEqualTo(500);
        // The business change and its idempotency record committed together.
        assertThat(jdbc.queryForObject("select count(*) from memberships where pool_id = ? and buyer_id = ?", Integer.class, pool, b)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from idempotency_keys where key = ?", Integer.class, key)).isEqualTo(201);
        Resp retry = send("POST", "/pools/" + pool + "/members", buyer(b), null, Map.of("Idempotency-Key", key));
        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(JSON.readTree(retry.body()).path("pool_id").asText()).isEqualTo(pool.toString());
        assertThat(jdbc.queryForObject("select interested_count from pools where id = ?", Integer.class, pool)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox where aggregate_id = ? and event_type = 'MemberJoined'", Integer.class, pool)).isEqualTo(1);
    }

    @Test
    void failedRequestStoresNothingSoTheRetryRunsAgain() throws Exception {
        UUID pool = freshPool();
        UUID b = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        Resp wrongTerms = send("POST", "/pools/" + pool + "/commitment", buyer(b), "{\"terms_version\":\"old\"}", Map.of("Idempotency-Key", key));
        assertThat(wrongTerms.status()).isBetween(400, 499);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_keys where key = ?", Integer.class, key)).isZero();
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
        assertThat(code(send("PUT", "/pools/" + pool + "/members/me/reround-choice", buyer(b), "{\"choice\":\"KEEP\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString(), "If-Match", "\"1\"")))).isEqualTo("INVALID_TRANSITION"); // no re-round in progress

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
        Resp seen = get("/pools/" + pool, TestTokens.dealer("pool", UUID.randomUUID(), DEALERSHIP));
        assertThat(seen.status()).isEqualTo(200);
        // AZ-T1: no buyer identity reaches an invited dealer (no member list, no membership, no buyer ids).
        assertThat(seen.body()).doesNotContain("my_membership");
        for (UUID b : jdbc.queryForList("select buyer_id from memberships", UUID.class)) assertThat(seen.body()).doesNotContain(b.toString());
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

    @Test
    void openApiMatchesTheCommittedWebClient() { assertOpenApiMatchesWebClient("pool"); }
}
