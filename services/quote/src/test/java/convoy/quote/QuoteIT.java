package convoy.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.contracts.events.AcceptanceChanged;
import convoy.contracts.events.MemberCommitted;
import convoy.contracts.events.QuoteRoundOpened;
import convoy.contracts.events.RoundClosed;
import convoy.platform.messaging.EventContext;
import convoy.platform.messaging.Uuid7;
import convoy.platform.problem.ApiProblem;
import convoy.platform.testing.AuthzDenyChecks;
import convoy.platform.testing.Outboxes;
import convoy.platform.testing.ServiceTest;
import convoy.platform.testing.TestTokens;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Draft and submit validated by the Java engine, DB-clock round deadline, sealed visibility, buyer comparison, summary. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "convoy.messaging.enabled=false", "convoy.issuers.convoy-gateway=http://unused", "convoy.issuers.convoy-identity=http://unused"})
@Import(ServiceTest.Config.class)
class QuoteIT extends ServiceTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String SWIFT = "11111111-1111-7111-8111-111111111111", WINDSOR = "33333333-3333-7333-8333-333333333333";

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired Quotes quotes;

    void inTx(Runnable r) {
        try {
            EventContext.set(Uuid7.next(), null);
            tx.executeWithoutResult(s -> r.run());
        } finally {
            EventContext.clear();
        }
    }

    record Round(UUID pool, UUID round, UUID dealership) {}

    Round openRound(int seconds) {
        UUID pool = Uuid7.next(), round = Uuid7.next(), d = UUID.randomUUID();
        List<Map<String, Object>> variants = List.of(
                Map.of("id", SWIFT, "code", "swift-lxi-mt", "name", "LXi MT", "fuel", "PETROL", "engine_cc", 1197),
                Map.of("id", WINDSOR, "code", "windsor-excite", "name", "Excite", "fuel", "EV"));
        inTx(() -> quotes.roundOpened(new QuoteRoundOpened(pool.toString(), round.toString(), 1L, 1L, "SEALED", Instant.now().plusSeconds(seconds),
                List.of(d.toString()), List.of(SWIFT, WINDSOR), 1L, "MH", variants)));
        return new Round(pool, round, d);
    }

    static String draft(String variant, long discount1, long discount2, String tiersOverride) {
        String tiers = tiersOverride != null ? tiersOverride
                : "[{\"min\":1,\"max\":4,\"discount_paise\":" + discount1 + "},{\"min\":5,\"max\":null,\"discount_paise\":" + discount2 + "}]";
        return "{\"variants\":[{\"variant_id\":\"" + variant + "\",\"ex_showroom_paise\":58390000,\"registration_paise\":null,\"insurance\":null,\"accessories\":[],\"fees\":[],"
                + "\"other_charges\":[],\"stock\":{\"units\":3,\"colours\":[\"White\"],\"delivery_date\":\"2026-11-20\"}}],\"tiers\":" + tiers + ",\"min_fulfilment\":1,\"max_units\":3,"
                + "\"valid_until\":\"" + java.time.LocalDate.now().plusDays(30) + "\"}";
    }

    String dealer(UUID d) { return TestTokens.dealer("quote", UUID.randomUUID(), d); }

    JsonNode create(Round r) throws Exception {
        Resp c = send("POST", "/dealer/pools/" + r.pool() + "/quotes", dealer(r.dealership()), null, Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(c.status()).as(c.body()).isEqualTo(201);
        return JSON.readTree(c.body());
    }

    Resp saveAndSubmit(Round r, String id, String body) throws Exception {
        long v = JSON.readTree(get("/dealer/quotes/" + id, dealer(r.dealership())).body()).path("version").asLong();
        Resp s = send("PUT", "/dealer/quotes/" + id + "/draft", dealer(r.dealership()), body, Map.of("If-Match", "\"" + v + "\""));
        assertThat(s.status()).as(s.body()).isEqualTo(200);
        return send("POST", "/dealer/quotes/" + id + "/submit", dealer(r.dealership()), null, Map.of("Idempotency-Key", UUID.randomUUID().toString(),
                "If-Match", "\"" + JSON.readTree(s.body()).path("version").asLong() + "\""));
    }

    static String code(Resp r) throws Exception { return JSON.readTree(r.body()).path("code").asText(); }

    @Test
    void submitValidatesWithTheEngineAndRejectsBadQuotesWithSpecificProblems() throws Exception {
        Round r = openRound(600);
        String id = create(r).path("id").asText();
        assertThat(code(saveAndSubmit(r, id, draft(SWIFT, 20000000, 25000000, null)))).isEqualTo("DISCOUNT_EXCEEDS_LIMIT"); // Rs 2 lakh > 25% of 5,83,900
        assertThat(code(saveAndSubmit(r, id, draft(SWIFT, 0, 0, "[{\"min\":1,\"max\":4,\"discount_paise\":0},{\"min\":6,\"max\":null,\"discount_paise\":0}]")))).isEqualTo("INVALID_TIERS"); // gap
        assertThat(code(saveAndSubmit(r, id, draft(SWIFT, 2000000, 1000000, null)))).isEqualTo("INVALID_TIERS"); // price would rise
        assertThat(code(saveAndSubmit(r, id, draft(WINDSOR, 0, 0, null)))).isEqualTo("QUOTE_INVALID"); // EV without insurance figure: engine refuses
        Resp ok = saveAndSubmit(r, id, draft(SWIFT, 1000000, 2500000, null));
        assertThat(ok.status()).as(ok.body()).isEqualTo(200);
        JsonNode q = JSON.readTree(ok.body());
        assertThat(q.path("state").asText()).isEqualTo("SUBMITTED");
        assertThat(q.at("/snapshots/" + SWIFT + "/0/cash_payable_paise").asLong()).isEqualTo(66347500L); // same as the order ceiling and the API journey
        assertThat(jdbc.queryForObject("select count(*) from quote_versions where quote_id = ?::uuid", Integer.class, id)).isEqualTo(1);
        // Second quote for the same pool from the same dealership is refused; other dealerships cannot read it (AZ-E067-X).
        assertThat(code(send("POST", "/dealer/pools/" + r.pool() + "/quotes", dealer(r.dealership()), null, Map.of("Idempotency-Key", UUID.randomUUID().toString())))).isEqualTo("INVALID_TRANSITION");
        assertThat(get("/dealer/quotes/" + id, dealer(UUID.randomUUID())).status()).isEqualTo(404);
        // AZ-T5: another dealership cannot submit on this quote either.
        assertThat(send("POST", "/dealer/quotes/" + id + "/submit", dealer(UUID.randomUUID()), null, Map.of("Idempotency-Key", UUID.randomUUID().toString(), "If-Match", "\"1\"")).status()).isEqualTo(404);
        // Revisions are built (P3.B) and, like every command, need an Idempotency-Key.
        assertThat(code(send("POST", "/dealer/quotes/" + id + "/revisions", dealer(r.dealership()), "{}", Map.of()))).isEqualTo("IDEMPOTENCY_KEY_MISSING");
    }

    @Test
    void roundNotOpenAndDeadlinePassedUseTheDatabaseClock() throws Exception {
        assertThat(code(send("POST", "/dealer/pools/" + UUID.randomUUID() + "/quotes", dealer(UUID.randomUUID()), null, Map.of("Idempotency-Key", UUID.randomUUID().toString()))))
                .isEqualTo("ROUND_NOT_OPEN");
        Round r = openRound(3);
        String id = create(r).path("id").asText();
        long v = JSON.readTree(get("/dealer/quotes/" + id, dealer(r.dealership())).body()).path("version").asLong();
        Resp s = send("PUT", "/dealer/quotes/" + id + "/draft", dealer(r.dealership()), draft(SWIFT, 1000000, 2500000, null), Map.of("If-Match", "\"" + v + "\""));
        Thread.sleep(3500);
        Resp late = send("POST", "/dealer/quotes/" + id + "/submit", dealer(r.dealership()), null, Map.of("Idempotency-Key", UUID.randomUUID().toString(),
                "If-Match", "\"" + JSON.readTree(s.body()).path("version").asLong() + "\""));
        JsonNode p = JSON.readTree(late.body());
        assertThat(p.path("code").asText()).isEqualTo("DEADLINE_PASSED");
        assertThat(Instant.parse(p.path("server_time").asText())).isAfterOrEqualTo(Instant.parse(p.path("deadline").asText()));
        assertThat(jdbc.queryForObject("select state from quotes where id = ?::uuid", String.class, id)).isEqualTo("DRAFT"); // nothing saved
        assertThat(jdbc.queryForObject("select count(*) from quote_versions where quote_id = ?::uuid", Integer.class, id)).isZero();
    }

    @Test
    void buyersOfThePoolSeeTheCeilingAndALabelledProjectionAndFinalRoundSummaryFinalises() throws Exception {
        Round r = openRound(600);
        String id = create(r).path("id").asText();
        assertThat(saveAndSubmit(r, id, draft(SWIFT, 1000000, 2500000, null)).status()).isEqualTo(200);
        UUID buyer = UUID.randomUUID();
        String token = TestTokens.buyer("quote", buyer);
        assertThat(get("/pools/" + r.pool() + "/quotes", token).status()).isEqualTo(404); // AZ-E069-X: not a committed member
        inTx(() -> quotes.memberCommitted(new MemberCommitted(r.pool().toString(), Uuid7.next().toString(), buyer.toString(), Uuid7.next().toString(), 1L, 1L, false)));
        // Sealed (P3.B): while the round is open even a committed member sees no quote and cannot read one.
        assertThat(JSON.readTree(get("/pools/" + r.pool() + "/quotes", token).body()).path("quotes")).isEmpty();
        assertThat(get("/pools/" + r.pool() + "/quotes/" + id, token).status()).isEqualTo(404);
        // Sealed round closes; final round (zero length in demo) opens and its deadline passes: the summary finalises the quote.
        inTx(() -> quotes.roundClosed(new RoundClosed(r.pool().toString(), r.round().toString(), "SEALED", Instant.now(), Instant.now(), 1L)));
        UUID r2 = Uuid7.next();
        inTx(() -> quotes.roundOpened(new QuoteRoundOpened(r.pool().toString(), r2.toString(), 1L, 2L, "FINAL", Instant.now().minusSeconds(1),
                List.of(r.dealership().toString()), List.of(SWIFT), 1L, "MH", List.of(Map.of("id", SWIFT, "code", "swift-lxi-mt", "name", "LXi MT", "fuel", "PETROL", "engine_cc", 1197)))));
        JsonNode sum = JSON.readTree(get("/internal/rounds/" + r2 + "/summary", TestTokens.service("quote", "pool", List.of("rounds:summary"))).body());
        assertThat(sum.path("final_count").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select state from quotes where id = ?::uuid", String.class, id)).isEqualTo("FINAL");
        JsonNode offers = JSON.readTree(get("/pools/" + r.pool() + "/quotes", token).body());
        JsonNode v = offers.at("/quotes/0/variants/0");
        assertThat(v.at("/ceiling/cash_payable_paise").asLong()).as(offers.toString()).isEqualTo(66347500L);
        assertThat(v.at("/projection/guaranteed").asBoolean()).isFalse();
        assertThat(v.at("/projection/label").asText()).startsWith("If the count stays at 0");
        // 6 buyers accepting: projection falls to the 5+ tier, ceiling unchanged (AMENDMENT-005).
        String dealerBefore = get("/dealer/inbox", dealer(r.dealership())).body().replaceAll("\"server_time\":\"[^\"]*\"", "");
        inTx(() -> quotes.acceptanceChanged(new AcceptanceChanged(r.pool().toString(), Uuid7.next().toString(), id, SWIFT, Map.of(id, 6L))));
        // AZ-T3: acceptances are invisible to dealers: the dealer inbox is unchanged by them.
        assertThat(get("/dealer/inbox", dealer(r.dealership())).body().replaceAll("\"server_time\":\"[^\"]*\"", "")).isEqualTo(dealerBefore);
        JsonNode after = JSON.readTree(get("/pools/" + r.pool() + "/quotes/" + id, token).body()).at("/variants/0");
        assertThat(after.at("/projection/cash_payable_paise").asLong()).isLessThan(after.at("/ceiling/cash_payable_paise").asLong());
        assertThat(after.at("/ceiling/cash_payable_paise").asLong()).isEqualTo(66347500L);
        assertThat(jdbc.queryForObject("select payload->>'min_fulfilment' from outbox where event_type = 'QuoteFinalised' and aggregate_id = ?::uuid", String.class, id)).isEqualTo("1");
        // AZ-SVC-02: the summary needs the rounds:summary scope.
        assertThat(get("/internal/rounds/" + r2 + "/summary", TestTokens.service("quote", "pool", List.of("deposits:create"))).status()).isEqualTo(403);
        assertThat(Outboxes.assertAllValid(jdbc, "quote")).isPositive();
    }

    Resp act(Round r, String id, String path, String body, String token) throws Exception {
        long v = JSON.readTree(get("/dealer/quotes/" + id, dealer(r.dealership())).body()).path("version").asLong();
        return send("POST", "/dealer/quotes/" + id + path, token, body, Map.of("Idempotency-Key", UUID.randomUUID().toString(), "If-Match", "\"" + v + "\""));
    }

    @Test
    void aRevisionIsANewImmutableVersionWithSnapshotsAndAnAuditedDiff() throws Exception {
        Round r = openRound(600);
        String id = create(r).path("id").asText();
        assertThat(saveAndSubmit(r, id, draft(SWIFT, 1000000, 2500000, null)).status()).isEqualTo(200);
        Resp rev = act(r, id, "/revisions", draft(SWIFT, 1500000, 2500000, null), dealer(r.dealership()));
        assertThat(rev.status()).as(rev.body()).isEqualTo(200);
        assertThat(JSON.readTree(rev.body()).path("state").asText()).isEqualTo("REVISED");
        assertThat(JSON.readTree(rev.body()).path("current_version").asInt()).isEqualTo(2);
        // Every submitted version keeps its own price snapshots and hash.
        for (int n = 1; n <= 2; n++) {
            Map<String, Object> v = jdbc.queryForMap("select snapshots::text as s, snapshot_sha256 from quote_versions where quote_id = ?::uuid and version_no = ?", id, n);
            assertThat((String) v.get("s")).contains("cash_payable_paise");
            assertThat((String) v.get("snapshot_sha256")).hasSize(64);
        }
        assertThat(jdbc.queryForObject("select count(distinct snapshot_sha256) from quote_versions where quote_id = ?::uuid", Integer.class, id)).isEqualTo(2);
        // The audit trail records what changed.
        String detail = jdbc.queryForObject("select detail::text from audit_trail where quote_id = ?::uuid and action = 'REVISE'", String.class, id);
        assertThat(detail).contains("tiers[0].discount_paise").contains("1000000").contains("1500000");
        // Versions are immutable in the database itself.
        assertThatThrownBy(() -> jdbc.update("update quote_versions set ruleset_version = 'x' where quote_id = ?::uuid", id)).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("delete from quote_versions where quote_id = ?::uuid", id)).hasMessageContaining("immutable");
        // Content rules: a validity date in the past is refused.
        String past = draft(SWIFT, 1500000, 2500000, null).replaceAll("\"valid_until\":\"[^\"]*\"", "\"valid_until\":\"2020-01-01\"");
        Resp bad = act(r, id, "/revisions", past, dealer(r.dealership()));
        assertThat(bad.status()).isEqualTo(422);
        assertThat(bad.body()).contains("VALIDITY_IN_PAST");
    }

    @Test
    void withdrawUntilTheDeadlineThenOnlyAnAdminCancelAfterFinal() throws Exception {
        Round r = openRound(600);
        String id = create(r).path("id").asText();
        assertThat(saveAndSubmit(r, id, draft(SWIFT, 1000000, 2500000, null)).status()).isEqualTo(200);
        // Cancel before FINAL: invalid-transition (withdraw instead).
        String admin = TestTokens.user("quote", UUID.randomUUID(), List.of("dealer_admin"), r.dealership());
        assertThat(code(act(r, id, "/cancel", "{\"reason\":\"x\"}", admin))).isEqualTo("INVALID_TRANSITION");
        Resp w = act(r, id, "/withdraw", "{\"reason\":\"stock sold elsewhere\"}", dealer(r.dealership()));
        assertThat(w.status()).as(w.body()).isEqualTo(200);
        assertThat(JSON.readTree(w.body()).path("state").asText()).isEqualTo("WITHDRAWN");
        assertThat(jdbc.queryForObject("select payload->>'after_final' from outbox where event_type = 'QuoteWithdrawn' and aggregate_id = ?::uuid", String.class, id)).isEqualTo("false");
        assertThat(code(act(r, id, "/revisions", draft(SWIFT, 1, 2, null), dealer(r.dealership())))).isEqualTo("INVALID_TRANSITION");

        // After the deadline: withdraw is deadline-passed (DB clock).
        Round late = openRound(3);
        String lid = create(late).path("id").asText();
        assertThat(saveAndSubmit(late, lid, draft(SWIFT, 1000000, 2500000, null)).status()).isEqualTo(200);
        Thread.sleep(3500);
        assertThat(code(act(late, lid, "/withdraw", "{\"reason\":\"late\"}", dealer(late.dealership())))).isEqualTo("DEADLINE_PASSED");
        assertThat(code(act(late, lid, "/revisions", draft(SWIFT, 1, 2, null), dealer(late.dealership())))).isEqualTo("DEADLINE_PASSED");

        // FINAL: withdraw is invalid; staff cannot cancel (403); the admin's cancel is a dealer cancellation (after_final).
        jdbc.update("update quotes set state = 'FINAL', final_version = current_version where id = ?::uuid", lid);
        assertThat(code(act(late, lid, "/withdraw", "{\"reason\":\"x\"}", dealer(late.dealership())))).isEqualTo("INVALID_TRANSITION");
        assertThat(act(late, lid, "/cancel", "{\"reason\":\"x\"}", dealer(late.dealership())).status()).isEqualTo(403);
        String lateAdmin = TestTokens.user("quote", UUID.randomUUID(), List.of("dealer_admin"), late.dealership());
        Resp cancelled = act(late, lid, "/cancel", "{\"reason\":\"vehicle recalled\"}", lateAdmin);
        assertThat(cancelled.status()).as(cancelled.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select after_final from quotes where id = ?::uuid", Boolean.class, lid)).isTrue();
        assertThat(jdbc.queryForObject("select payload->>'after_final' from outbox where event_type = 'QuoteWithdrawn' and aggregate_id = ?::uuid", String.class, lid)).isEqualTo("true");
    }

    @Test
    void sealedAnotherDealerCannotReadOrActOnAQuote() throws Exception {
        Round r = openRound(600);
        String id = create(r).path("id").asText();
        assertThat(saveAndSubmit(r, id, draft(SWIFT, 1000000, 2500000, null)).status()).isEqualTo(200);
        String rival = TestTokens.dealer("quote", UUID.randomUUID(), UUID.randomUUID());
        assertThat(get("/dealer/quotes/" + id, rival).status()).isEqualTo(404);
        assertThat(send("POST", "/dealer/quotes/" + id + "/withdraw", rival, "{\"reason\":\"x\"}", Map.of("Idempotency-Key", UUID.randomUUID().toString(), "If-Match", "\"2\"")).status()).isEqualTo(404);
        assertThat(send("POST", "/dealer/quotes/" + id + "/revisions", rival, draft(SWIFT, 1, 2, null), Map.of("Idempotency-Key", UUID.randomUUID().toString(), "If-Match", "\"2\"")).status()).isEqualTo(404);
    }

    /** A sealed round inviting several dealerships. */
    Round openRoundFor(List<UUID> dealers, java.time.Instant deadline, UUID pool, UUID round) {
        List<Map<String, Object>> variants = List.of(Map.of("id", SWIFT, "code", "swift-lxi-mt", "name", "LXi MT", "fuel", "PETROL", "engine_cc", 1197));
        inTx(() -> quotes.roundOpened(new QuoteRoundOpened(pool.toString(), round.toString(), 1L, 1L, "SEALED", deadline,
                dealers.stream().map(UUID::toString).toList(), List.of(SWIFT), 1L, "MH", variants)));
        return new Round(pool, round, dealers.get(0));
    }

    @Test
    void dealersSeeOnlyTheirOwnRankAndOfNAndOnlyFromTheFinalRound() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), pool = Uuid7.next(), r1 = Uuid7.next();
        openRoundFor(List.of(a, b), Instant.now().plusSeconds(600), pool, r1);
        Round ra = new Round(pool, r1, a), rb = new Round(pool, r1, b);
        String qa = create(ra).path("id").asText(), qb = create(rb).path("id").asText();
        assertThat(saveAndSubmit(ra, qa, draft(SWIFT, 2000000, 2500000, null)).status()).isEqualTo(200); // bigger discount: cheaper
        assertThat(saveAndSubmit(rb, qb, draft(SWIFT, 1000000, 2500000, null)).status()).isEqualTo(200);
        // Sealed round: no rank for anyone.
        assertThat(JSON.readTree(get("/dealer/quotes/" + qa + "/rank", dealer(a)).body()).path("ranks")).isEmpty();
        // Final round opens: ranks are computed; each dealer sees exactly {variant_id, rank, of_n, rule_version, computed_at}.
        inTx(() -> quotes.roundClosed(new RoundClosed(pool.toString(), r1.toString(), "SEALED", Instant.now(), Instant.now(), 1L)));
        inTx(() -> quotes.roundOpened(new QuoteRoundOpened(pool.toString(), Uuid7.next().toString(), 1L, 2L, "FINAL", Instant.now().plusSeconds(600),
                List.of(a.toString(), b.toString()), List.of(SWIFT), 1L, "MH", List.of(Map.of("id", SWIFT, "code", "swift-lxi-mt", "name", "LXi MT", "fuel", "PETROL", "engine_cc", 1197)))));
        JsonNode mine = JSON.readTree(get("/dealer/quotes/" + qa + "/rank", dealer(a)).body()).path("ranks").get(0);
        assertThat(mine.path("rank").asInt()).isEqualTo(1);
        assertThat(mine.path("of_n").asInt()).isEqualTo(2);
        java.util.List<String> fields = new java.util.ArrayList<>();
        mine.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("variant_id", "rank", "of_n", "rule_version", "computed_at"); // AZ-RNK-01: nothing else
        assertThat(JSON.readTree(get("/dealer/quotes/" + qb + "/rank", dealer(b)).body()).at("/ranks/0/rank").asInt()).isEqualTo(2);
        assertThat(get("/dealer/quotes/" + qa + "/rank", dealer(b)).status()).isEqualTo(404); // AZ-RNK-02: not another dealer's quote
        assertThat(jdbc.queryForObject("select count(*) from outbox where event_type = 'RankChanged' and payload->>'pool_id' = ?", Integer.class, pool.toString())).isEqualTo(2);
        // A revision in the final round that undercuts the leader re-ranks both.
        assertThat(act(rb, qb, "/revisions", draft(SWIFT, 3000000, 3500000, null), dealer(b)).status()).isEqualTo(200);
        assertThat(JSON.readTree(get("/dealer/quotes/" + qb + "/rank", dealer(b)).body()).at("/ranks/0/rank").asInt()).isEqualTo(1);
        assertThat(JSON.readTree(get("/dealer/quotes/" + qa + "/rank", dealer(a)).body()).at("/ranks/0/rank").asInt()).isEqualTo(2);
    }

    @Test
    void aDealerWhoNeverRevisesSeesItsRankTheMomentTheFinalRoundOpensAndNotBefore() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), pool = Uuid7.next(), r1 = Uuid7.next();
        openRoundFor(List.of(a, b), Instant.now().plusSeconds(600), pool, r1);
        Round ra = new Round(pool, r1, a), rb = new Round(pool, r1, b);
        String qa = create(ra).path("id").asText(), qb = create(rb).path("id").asText();
        assertThat(saveAndSubmit(ra, qa, draft(SWIFT, 1000000, 2500000, null)).status()).isEqualTo(200);
        assertThat(saveAndSubmit(rb, qb, draft(SWIFT, 2000000, 2500000, null)).status()).isEqualTo(200);
        // Sealed round closes; a gap before the final round opens: ranks are computed but hidden, no event yet.
        inTx(() -> quotes.roundClosed(new RoundClosed(pool.toString(), r1.toString(), "SEALED", Instant.now(), Instant.now(), 1L)));
        assertThat(jdbc.queryForObject("select count(*) from rank_snapshots where pool_id = ? and not revealed", Integer.class, pool)).isEqualTo(2);
        assertThat(JSON.readTree(get("/dealer/quotes/" + qa + "/rank", dealer(a)).body()).path("ranks")).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from outbox where event_type = 'RankChanged' and payload->>'pool_id' = ?", Integer.class, pool.toString())).isZero();
        // The final round opens: dealer A (never revised) sees its rank at once; the events go out now.
        inTx(() -> quotes.roundOpened(new QuoteRoundOpened(pool.toString(), Uuid7.next().toString(), 1L, 2L, "FINAL", Instant.now().plusSeconds(600),
                List.of(a.toString(), b.toString()), List.of(SWIFT), 1L, "MH", List.of(Map.of("id", SWIFT, "code", "swift-lxi-mt", "name", "LXi MT", "fuel", "PETROL", "engine_cc", 1197)))));
        JsonNode ranks = JSON.readTree(get("/dealer/quotes/" + qa + "/rank", dealer(a)).body()).path("ranks");
        assertThat(ranks.get(0).path("rank").asInt()).isEqualTo(2);
        assertThat(ranks.get(0).path("of_n").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from outbox where event_type = 'RankChanged' and payload->>'pool_id' = ?", Integer.class, pool.toString())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from quote_versions where quote_id = ?::uuid", Integer.class, qa)).as("no revision needed").isEqualTo(1);
    }

    @Test
    void fiftyParallelSubmissionsAroundTheDeadlineKeepEveryEarlyOneAndRejectEveryLateOne() throws Exception {
        // deadline-rule.md: the DB clock at commit decides. 50 dealers submit spread from 0.4 s before to 0.4 s after the deadline.
        List<UUID> dealers = java.util.stream.IntStream.range(0, 50).mapToObj(i -> UUID.randomUUID()).toList();
        Instant deadline = Instant.now().plusSeconds(8);
        UUID pool = Uuid7.next(), r = Uuid7.next();
        openRoundFor(dealers, deadline, pool, r);
        Map<UUID, String> ids = new java.util.LinkedHashMap<>();
        Map<UUID, Long> versions = new java.util.HashMap<>();
        for (UUID d : dealers) {
            Round rd = new Round(pool, r, d);
            String id = create(rd).path("id").asText();
            Resp saved = send("PUT", "/dealer/quotes/" + id + "/draft", dealer(d), draft(SWIFT, 1000000, 2500000, null), Map.of("If-Match", "\"1\""));
            ids.put(d, id);
            versions.put(d, JSON.readTree(saved.body()).path("version").asLong());
        }
        assertThat(Instant.now()).as("setup must finish before the deadline").isBefore(deadline.minusMillis(500));
        java.util.concurrent.ExecutorService pool50 = java.util.concurrent.Executors.newFixedThreadPool(50);
        List<java.util.concurrent.Future<Resp>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            UUID d = dealers.get(i);
            Instant at = deadline.minusMillis(400).plusMillis(i * 16L); // -400 ms .. +384 ms
            futures.add(pool50.submit(() -> {
                long wait = java.time.Duration.between(Instant.now(), at).toMillis();
                if (wait > 0) Thread.sleep(wait);
                return send("POST", "/dealer/quotes/" + ids.get(d) + "/submit", dealer(d), null,
                        Map.of("Idempotency-Key", UUID.randomUUID().toString(), "If-Match", "\"" + versions.get(d) + "\""));
            }));
        }
        int kept = 0, rejected = 0;
        for (int i = 0; i < 50; i++) {
            Resp res = futures.get(i).get(30, java.util.concurrent.TimeUnit.SECONDS);
            String id = ids.get(dealers.get(i));
            if (res.status() == 200) {
                kept++;
                java.sql.Timestamp committed = jdbc.queryForObject("select committed_at from quote_versions where quote_id = ?::uuid and version_no = 1", java.sql.Timestamp.class, id);
                assertThat(committed.toInstant()).as("kept submissions committed before the deadline").isBefore(deadline);
                assertThat(jdbc.queryForObject("select state from quotes where id = ?::uuid", String.class, id)).isEqualTo("SUBMITTED");
            } else {
                rejected++;
                assertThat(code(res)).as(res.body()).isEqualTo("DEADLINE_PASSED");
                assertThat(jdbc.queryForObject("select state from quotes where id = ?::uuid", String.class, id)).isEqualTo("DRAFT");
                assertThat(jdbc.queryForObject("select count(*) from quote_versions where quote_id = ?::uuid", Integer.class, id)).as("nothing of a late submission is kept").isZero();
            }
        }
        pool50.shutdown();
        assertThat(kept + rejected).isEqualTo(50);
        assertThat(kept).as("some submissions landed before the deadline").isPositive();
        assertThat(rejected).as("some landed after it").isPositive();
    }

    Resp post(String path, String token, String body) {
        return send("POST", path, token, body, Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    }

    static String ask(String type, String text) throws Exception { return JSON.writeValueAsString(Map.of("question_type", type, "text", text)); }

    @Test
    void clarificationsAboutAFinalQuoteAreAnsweredOnceShownToMembersAndRefuseContactDetails() throws Exception {
        Round r = openRound(600);
        String id = create(r).path("id").asText();
        assertThat(saveAndSubmit(r, id, draft(SWIFT, 1000000, 2500000, null)).status()).isEqualTo(200);
        UUID b1 = UUID.randomUUID(), b2 = UUID.randomUUID();
        for (UUID b : List.of(b1, b2)) inTx(() -> quotes.memberCommitted(new MemberCommitted(r.pool().toString(), Uuid7.next().toString(), b.toString(), Uuid7.next().toString(), 1L, 1L, false)));
        String t1 = TestTokens.buyer("quote", b1), t2 = TestTokens.buyer("quote", b2), outsider = TestTokens.buyer("quote", UUID.randomUUID());
        String asks = "/pools/" + r.pool() + "/quotes/" + id + "/clarifications";
        assertThat(code(post(asks, t1, ask("DELIVERY_DATE", "When can you deliver?")))).isEqualTo("INVALID_TRANSITION"); // sealed: not FINAL yet
        inTx(() -> quotes.roundClosed(new RoundClosed(r.pool().toString(), r.round().toString(), "SEALED", Instant.now(), Instant.now(), 1L)));
        UUID r2 = Uuid7.next();
        inTx(() -> quotes.roundOpened(new QuoteRoundOpened(r.pool().toString(), r2.toString(), 1L, 2L, "FINAL", Instant.now().minusSeconds(1), List.of(r.dealership().toString()),
                List.of(SWIFT), 1L, "MH", List.of(Map.of("id", SWIFT, "code", "swift-lxi-mt", "name", "LXi MT", "fuel", "PETROL", "engine_cc", 1197,
                        "catalogue_ex_showroom_paise", 58390000L, "catalogue_evidence", "CATALOGUE_ESTIMATE")))));
        get("/internal/rounds/" + r2 + "/summary", TestTokens.service("quote", "pool", List.of("rounds:summary")));
        assertThat(jdbc.queryForObject("select state from quotes where id = ?::uuid", String.class, id)).isEqualTo("FINAL");

        // AMENDMENT-013: the savings figures ride on QuoteFinalised, each with its snapshot hash.
        JsonNode fig = JSON.readTree(jdbc.queryForObject("select payload::text from outbox where event_type = 'QuoteFinalised' and aggregate_id = ?::uuid", String.class, id)).at("/figures/0");
        assertThat(fig.path("ceiling_cash_payable_paise").asLong()).as(fig.toString()).isEqualTo(66347500L);
        assertThat(fig.path("ceiling_discount_paise").asLong()).isEqualTo(1000000L);
        assertThat(fig.path("ceiling_snapshot_sha256").asText()).hasSize(64);
        assertThat(fig.path("catalogue_estimate_cash_payable_paise").asLong()).isGreaterThan(66347500L); // same configuration, no discount
        assertThat(fig.path("catalogue_snapshot_sha256").asText()).hasSize(64);

        // Contact details are refused before the consent point, in the question ...
        for (String bad : List.of("Call me on 98765 43210", "WhatsApp +91-98765-43210", "mail a.b@example.com", "rahul (at) gmail.com", "see www.dealer-site.in",
                "https://bit.ly/x", "my site dealer.com", "९८७६५४३२१० पर कॉल करें")) {
            Resp x = post(asks, t1, ask("OTHER", bad));
            assertThat(x.status()).as(bad).isEqualTo(400);
            assertThat(JSON.readTree(x.body()).path("field").asText()).as(bad).isEqualTo("text");
        }
        assertThat(code(post(asks, t1, ask("NOT_A_TYPE", "Is it in stock?")))).isEqualTo("VALIDATION_FAILED");
        assertThat(post(asks, outsider, ask("OTHER", "Is it in stock?")).status()).isEqualTo(404); // AZ-E071-X
        // ... while prices and dates pass
        Resp asked = post(asks, t1, ask("PRICE_ITEM", "Is the Rs 5,83,900 ex-showroom final, and delivery by 2026-11-09?"));
        assertThat(asked.status()).as(asked.body()).isEqualTo(201);
        String cid = JSON.readTree(asked.body()).path("id").asText();
        // Open: the asker sees it, another member does not, the dealer does.
        assertThat(JSON.readTree(get("/pools/" + r.pool() + "/quotes/" + id, t1).body()).path("clarifications")).hasSize(1);
        assertThat(JSON.readTree(get("/pools/" + r.pool() + "/quotes/" + id, t2).body()).path("clarifications")).isEmpty();
        assertThat(JSON.readTree(get("/dealer/quotes/" + id, dealer(r.dealership())).body()).path("clarifications")).hasSize(1);

        // ... and in the answer, from the quote's own dealership only, once.
        String answer = "/dealer/clarifications/" + cid + "/answer";
        assertThat(post(answer, dealer(UUID.randomUUID()), "{\"text\":\"Yes\"}").status()).isEqualTo(404); // AZ-E072-X
        assertThat(post(answer, dealer(r.dealership()), "{\"text\":\"Call 9876543210 for details\"}").status()).isEqualTo(400);
        Resp ok = post(answer, dealer(r.dealership()), "{\"text\":\"Yes, final; delivery by 9 Nov.\"}");
        assertThat(ok.status()).as(ok.body()).isEqualTo(200);
        assertThat(code(post(answer, dealer(r.dealership()), "{\"text\":\"Again\"}"))).isEqualTo("INVALID_TRANSITION");
        JsonNode seen = JSON.readTree(get("/pools/" + r.pool() + "/quotes", t2).body()).at("/quotes/0/clarifications/0");
        assertThat(seen.path("answer").asText()).isEqualTo("Yes, final; delivery by 9 Nov.");
        assertThat(seen.path("question_type").asText()).isEqualTo("PRICE_ITEM");
        assertThat(jdbc.queryForObject("select count(*) from outbox where event_type = 'ClarificationAnswered' and aggregate_id = ?", Integer.class, Clarifications.stream(id))).isEqualTo(1);
        assertThat(Outboxes.assertAllValid(jdbc, "quote")).isPositive();
    }

    @Test
    void authorisationMatrixForEveryP1Endpoint() {
        assertThat(AuthzDenyChecks.run("quote", this::send, (m, p) -> p.endsWith("/draft") || p.endsWith("/revisions") ? "{}" : p.endsWith("/withdraw") || p.endsWith("/cancel") ? "{\"reason\":\"x\"}" : null)).isEmpty();
    }

    @Test
    void transitionTableRejectsEveryInvalidPair() throws Exception {
        for (var p : JSON.readTree(QuoteIT.class.getResourceAsStream("/convoy/transitions.json")).get("quote").get("invalid_pairs")) {
            assertThatThrownBy(() -> Quotes.QUOTE.check(p.get("from").asText(), p.get("to").asText()))
                    .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.type().slug).isEqualTo("invalid-transition"));
        }
    }

    @Test
    void openApiMatchesTheCommittedWebClient() { assertOpenApiMatchesWebClient("quote"); }
}
