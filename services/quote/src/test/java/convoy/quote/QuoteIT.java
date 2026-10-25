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
