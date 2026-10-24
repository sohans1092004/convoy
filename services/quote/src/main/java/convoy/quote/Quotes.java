package convoy.quote;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.contracts.events.AcceptanceChanged;
import convoy.contracts.events.AcceptancesFrozen;
import convoy.contracts.events.ConfirmationRecorded;
import convoy.contracts.events.MemberCommitted;
import convoy.contracts.events.MemberLeft;
import convoy.contracts.events.QuoteExpired;
import convoy.contracts.events.QuoteFinalised;
import convoy.contracts.events.QuoteRoundOpened;
import convoy.contracts.events.QuoteSubmitted;
import convoy.contracts.events.RoundClosed;
import convoy.contracts.events.StockReservationRequested;
import convoy.contracts.events.StockReserved;
import convoy.contracts.events.StockUnavailable;
import convoy.platform.messaging.Outbox;
import convoy.platform.messaging.Uuid7;
import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.security.Caller;
import convoy.platform.statemachine.TransitionTable;
import convoy.price.CanonicalJson;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Quote machine (quote.yaml) and its replicas. Round deadlines are enforced with this service's DB clock (Q-006). */
@Service
public class Quotes {
    private static final Logger log = LoggerFactory.getLogger(Quotes.class);
    public static final TransitionTable QUOTE = TransitionTable.load("quote", Set.of("QUO-T01", "QUO-T02", "QUO-T04", "QUO-T08", "QUO-T09"));
    private final JdbcTemplate jdbc;
    private final Outbox outbox;
    private final ObjectMapper json;
    private final Map<String, Object> rules;

    public Quotes(JdbcTemplate jdbc, Outbox outbox, ObjectMapper json, QuoteApplication.Rules rules) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.json = json;
        this.rules = rules.rules();
    }

    String rulesetVersion() { return (String) QuotePricing.m(rules.get("ruleset")).get("ruleset_version"); }

    // ------------------------------------------------------------------ replicas from pool / order events

    public void roundOpened(QuoteRoundOpened e) {
        jdbc.update("insert into rounds_replica(round_id, pool_id, round_set, round_no, kind, deadline, invited_dealership_ids, state_code, variants, committed_count) "
                        + "values (?, ?, ?, ?, ?, ?, ?::uuid[], ?, ?::jsonb, ?) on conflict (round_id) do nothing",
                UUID.fromString(e.roundId()), UUID.fromString(e.poolId()), e.roundSet(), e.roundNo(), e.kind(), Timestamp.from(e.deadline()),
                "{" + String.join(",", e.invitedDealershipIds()) + "}", e.stateCode(), write(e.variants()), e.committedCount());
    }

    /** RoundClosed(SEALED): drafts expire (QUO-T09). RoundClosed(FINAL): live quotes become FINAL (QUO-T04, idempotent). */
    public void roundClosed(RoundClosed e) {
        UUID roundId = UUID.fromString(e.roundId());
        jdbc.update("update rounds_replica set state = 'CLOSED' where round_id = ?", roundId);
        Map<String, Object> r = jdbc.queryForMap("select pool_id, round_set from rounds_replica where round_id = ?", roundId);
        if (e.kind().equals("SEALED")) {
            for (UUID q : jdbc.queryForList("select id from quotes where pool_id = ? and round_set = ? and state = 'DRAFT' for update", UUID.class, r.get("pool_id"), r.get("round_set"))) {
                QUOTE.check("DRAFT", "EXPIRED");
                jdbc.update("update quotes set state = 'EXPIRED', expire_reason = 'NOT_SUBMITTED', version = version + 1 where id = ?", q);
            }
        } else {
            finalise((UUID) r.get("pool_id"), (int) r.get("round_set"));
        }
    }

    /** QUO-T04 for every SUBMITTED/REVISED quote of the pool's round set. */
    void finalise(UUID poolId, int roundSet) {
        for (Map<String, Object> q : jdbc.queryForList("select id, dealership_id, current_version, version from quotes where pool_id = ? and round_set = ? "
                + "and state in ('SUBMITTED', 'REVISED') for update", poolId, roundSet)) {
            QUOTE.check("SUBMITTED", "FINAL");
            UUID id = (UUID) q.get("id");
            int n = (int) q.get("current_version");
            Map<String, Object> ver = jdbc.queryForMap("select inputs::text as inputs, snapshots::text as snapshots, snapshot_sha256, ruleset_version from quote_versions "
                    + "where quote_id = ? and version_no = ?", id, n);
            Map<String, Object> inputs = read((String) ver.get("inputs"));
            jdbc.update("update quotes set state = 'FINAL', final_version = ?, version = version + 1 where id = ?", n, id);
            long v = ((Number) q.get("version")).longValue() + 1;
            List<Map<String, Object>> variantInputs = readList(inputs.get("variant_inputs"));
            List<String> variantIds = variantInputs.stream().map(x -> (String) x.get("variant_id")).toList();
            Map<String, Object> draft = QuotePricing.m(inputs.get("draft"));
            outbox.append(id, v, new QuoteFinalised(id.toString(), poolId.toString(), q.get("dealership_id").toString(), (long) n, variantIds,
                    QuotePricing.lng(draft.get("min_fulfilment")), QuotePricing.lng(draft.get("max_units")), (String) ver.get("snapshot_sha256"),
                    (String) ver.get("ruleset_version"), readList(draft.get("tiers")), variantInputs));
        }
    }

    public void memberCommitted(MemberCommitted e) {
        jdbc.update("insert into member_replica(pool_id, buyer_id, state) values (?, ?, 'COMMITTED') on conflict (pool_id, buyer_id) do update set state = 'COMMITTED'",
                UUID.fromString(e.poolId()), UUID.fromString(e.buyerId()));
    }

    public void memberLeft(MemberLeft e) {
        jdbc.update("delete from member_replica where pool_id = ? and buyer_id = ?", UUID.fromString(e.poolId()), UUID.fromString(e.buyerId()));
    }

    public void acceptanceChanged(AcceptanceChanged e) {
        UUID pool = UUID.fromString(e.poolId());
        e.acceptanceCounts().forEach((quote, n) -> jdbc.update("insert into count_replica(pool_id, quote_id, accepted) values (?, ?, ?) "
                + "on conflict (pool_id, quote_id) do update set accepted = excluded.accepted", pool, UUID.fromString(quote), ((Number) n).intValue()));
    }

    /** QUO-T08: FINAL quotes with no acceptances expire when acceptances freeze. */
    public void acceptancesFrozen(AcceptancesFrozen e) {
        UUID pool = UUID.fromString(e.poolId());
        acceptanceChanged(new AcceptanceChanged(e.poolId(), null, null, null, e.acceptanceCounts()));
        for (Map<String, Object> q : jdbc.queryForList("select id, version from quotes where pool_id = ? and round_set = ? and state = 'FINAL' for update", pool, e.roundSet())) {
            Object n = e.acceptanceCounts().get(q.get("id").toString());
            if (n == null || ((Number) n).longValue() == 0) {
                QUOTE.check("FINAL", "EXPIRED");
                jdbc.update("update quotes set state = 'EXPIRED', expire_reason = 'NO_ACCEPTANCES', version = version + 1 where id = ?", q.get("id"));
                outbox.append((UUID) q.get("id"), ((Number) q.get("version")).longValue() + 1, new QuoteExpired(q.get("id").toString(), e.poolId(), "NO_ACCEPTANCES"));
            }
        }
    }

    public void confirmationRecorded(ConfirmationRecorded e) {
        jdbc.update("insert into count_replica(pool_id, quote_id, confirmed) values (?, ?, ?) on conflict (pool_id, quote_id) do update set confirmed = greatest(count_replica.confirmed, excluded.confirmed)",
                UUID.fromString(e.poolId()), UUID.fromString(e.quoteId()), e.quoteConfirmedCount().intValue());
    }

    /** RESERVE_STOCK saga step: reserved while bookings for the variant stay within the declared units. */
    public void reserveStock(StockReservationRequested e) {
        UUID quote = UUID.fromString(e.quoteId()), booking = UUID.fromString(e.bookingId());
        if (jdbc.queryForObject("select count(*) from stock_reservations where booking_id = ?", Integer.class, booking) > 0) return;
        Map<String, Object> q = jdbc.queryForMap("select final_version, version from quotes where id = ? for update", quote);
        Map<String, Object> inputs = read(jdbc.queryForObject("select inputs::text from quote_versions where quote_id = ? and version_no = ?", String.class, quote, q.get("final_version")));
        long units = readList(inputs.get("variant_inputs")).stream().filter(v -> e.variantId().equals(v.get("variant_id")))
                .mapToLong(v -> QuotePricing.lng(QuotePricing.m(v.get("stock")).get("units"))).findFirst().orElse(0);
        int reserved = jdbc.queryForObject("select count(*) from stock_reservations where quote_id = ? and variant_id = ?", Integer.class, quote, UUID.fromString(e.variantId()));
        long v = ((Number) q.get("version")).longValue();
        if (reserved < units) {
            jdbc.update("insert into stock_reservations(booking_id, quote_id, variant_id, colour) values (?, ?, ?, ?)", booking, quote, UUID.fromString(e.variantId()), e.colour());
            outbox.append(quote, v, new StockReserved(e.quoteId(), e.bookingId(), e.variantId(), e.colour()));
        } else {
            outbox.append(quote, v, new StockUnavailable(e.quoteId(), e.bookingId(), e.variantId(), e.colour()));
        }
    }

    // ------------------------------------------------------------------ dealer commands

    static UUID dealership(Caller c) {
        c.require("dealer_staff", "dealer_admin");
        if (c.dealershipId() == null) throw ApiProblem.of(ProblemType.FORBIDDEN, "Your account is not linked to a dealership.");
        return UUID.fromString(c.dealershipId());
    }

    Map<String, Object> openRound(UUID poolId, UUID dealershipId) {
        List<Map<String, Object>> rs = jdbc.queryForList("select *, clock_timestamp() as db_now from rounds_replica where pool_id = ? and ? = any(invited_dealership_ids) "
                + "order by round_set desc, round_no desc limit 1", poolId, dealershipId);
        if (rs.isEmpty()) throw ApiProblem.of(ProblemType.ROUND_NOT_OPEN, "No quote round is open for your dealership in this pool yet.");
        return rs.get(0);
    }

    static ApiProblem deadlinePassed(Map<String, Object> round, String detail) {
        return ApiProblem.of(ProblemType.DEADLINE_PASSED, detail)
                .with("deadline", ((Timestamp) round.get("deadline")).toInstant().toString())
                .with("server_time", ((Timestamp) round.get("db_now")).toInstant().toString())
                .with("aggregate", Map.of("type", "round", "id", round.get("round_id").toString()));
    }

    /** QUO-T01 (E061): create the dealership's DRAFT for the pool's open round. */
    @Transactional
    public Map<String, Object> createDraft(UUID poolId) {
        Caller c = Caller.current();
        UUID d = dealership(c);
        Map<String, Object> round = openRound(poolId, d);
        if (!((int) round.get("round_no") == 1)) throw ApiProblem.of(ProblemType.INVALID_TRANSITION, "New quotes can only be started in the sealed round.");
        if (!((Timestamp) round.get("db_now")).before((Timestamp) round.get("deadline"))) throw deadlinePassed(round, "The sealed round has closed.");
        QUOTE.check("(none)", "DRAFT");
        List<String> existing = jdbc.queryForList("select id::text from quotes where pool_id = ? and round_set = ? and dealership_id = ? "
                + "and state not in ('EXPIRED','WITHDRAWN','VOID')", String.class, poolId, round.get("round_set"), d);
        if (!existing.isEmpty()) throw ApiProblem.of(ProblemType.INVALID_TRANSITION, "Your dealership already has a quote for this pool.").with("quote_id", existing.get(0));
        UUID id = Uuid7.next();
        try {
            jdbc.update("insert into quotes(id, pool_id, round_set, dealership_id, state, created_by) values (?, ?, ?, ?, 'DRAFT', ?)",
                    id, poolId, round.get("round_set"), d, c.userId());
        } catch (DuplicateKeyException e) {
            // A concurrent create won the unique index; the transaction is aborted, so no further queries here.
            throw ApiProblem.of(ProblemType.INVALID_TRANSITION, "Your dealership already has a quote for this pool.");
        }
        jdbc.update("insert into audit_trail(quote_id, actor, action) values (?, ?, 'CREATE_DRAFT')", id, c.userId());
        return dealerView(id, d);
    }

    /** E062: replace the draft body (no state change). */
    @Transactional
    public Map<String, Object> saveDraft(UUID id, Map<String, Object> draft, long ifMatch) {
        Caller c = Caller.current();
        UUID d = dealership(c);
        Map<String, Object> q = own(id, d, true);
        if (!"DRAFT".equals(q.get("state"))) throw ApiProblem.of(ProblemType.INVALID_TRANSITION, "Only a draft can be edited; submitted quotes change through new versions.");
        if ((long) q.get("version") != ifMatch) throw ApiProblem.of(ProblemType.VERSION_CONFLICT, "The draft changed; reload it.").with("current_version", q.get("version"));
        jdbc.update("update quotes set draft = ?::jsonb, version = version + 1 where id = ?", write(draft), id);
        return dealerView(id, d);
    }

    /** QUO-T02 (E063): validate with the engine, store an immutable version, SUBMITTED. Deadline read in this transaction. */
    @Transactional
    public Map<String, Object> submit(UUID id, long ifMatch) {
        Caller c = Caller.current();
        UUID d = dealership(c);
        Map<String, Object> q = own(id, d, true);
        if ((long) q.get("version") != ifMatch) throw ApiProblem.of(ProblemType.VERSION_CONFLICT, "The quote changed; reload it.").with("current_version", q.get("version"));
        QUOTE.check((String) q.get("state"), "SUBMITTED");
        Map<String, Object> round = jdbc.queryForMap("select *, clock_timestamp() as db_now from rounds_replica where pool_id = ? and round_set = ? and round_no = 1",
                q.get("pool_id"), q.get("round_set"));
        Map<String, Object> draft = read(q.get("draft").toString());
        Map<String, Map<String, Object>> facts = new HashMap<>();
        for (Map<String, Object> f : readList(read("{\"v\":" + round.get("variants") + "}").get("v"))) facts.put((String) f.get("id"), f);
        QuotePricing.Priced priced = QuotePricing.price(draft, facts, (String) round.get("state_code"), rules);
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("draft", draft);
        inputs.put("variant_inputs", priced.variantInputs());
        jdbc.update("insert into quote_versions(quote_id, version_no, inputs, snapshots, snapshot_sha256, ruleset_version, committed_by) values (?, 1, ?::jsonb, ?::jsonb, ?, ?, ?)",
                id, CanonicalJson.write(inputs), CanonicalJson.write(priced.snapshots()), priced.sha256(), rulesetVersion(), c.userId());
        // Deadline check is the last statement before commit, on this DB's clock (deadline-rule.md §1).
        List<Map<String, Object>> ok = jdbc.queryForList("update quotes set state = 'SUBMITTED', current_version = 1, submitted_at = clock_timestamp(), version = version + 1 "
                + "where id = ? and version = ? and clock_timestamp() < ? returning submitted_at, version, clock_timestamp() as db_now", id, ifMatch, round.get("deadline"));
        if (ok.isEmpty()) {
            Map<String, Object> now = jdbc.queryForMap("select clock_timestamp() as db_now");
            round.put("db_now", now.get("db_now"));
            throw deadlinePassed(round, "The sealed round closed before your submission reached our database. Nothing was saved.");
        }
        jdbc.update("insert into audit_trail(quote_id, actor, action, detail) values (?, ?, 'SUBMIT', ?::jsonb)", id, c.userId(), write(Map.of("snapshot_sha256", priced.sha256())));
        outbox.append(id, ((Number) ok.get(0).get("version")).longValue(), new QuoteSubmitted(id.toString(), q.get("pool_id").toString(), d.toString(), 1L,
                priced.sha256(), ((Timestamp) ok.get(0).get("submitted_at")).toInstant()));
        return dealerView(id, d);
    }

    Map<String, Object> own(UUID id, UUID dealershipId, boolean lock) {
        List<Map<String, Object>> rs = jdbc.queryForList("select * from quotes where id = ? and dealership_id = ?" + (lock ? " for update" : ""), id, dealershipId);
        if (rs.isEmpty()) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such quote.");
        return rs.get(0);
    }

    public Map<String, Object> dealerView(UUID id, UUID dealershipId) {
        Map<String, Object> q = own(id, dealershipId, false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id.toString());
        out.put("pool_id", q.get("pool_id").toString());
        out.put("state", q.get("state"));
        out.put("version", q.get("version"));
        out.put("draft", read(q.get("draft").toString()));
        out.put("current_version", q.get("current_version"));
        out.put("submitted_at", q.get("submitted_at") == null ? null : ((Timestamp) q.get("submitted_at")).toInstant().toString());
        if ((int) q.get("current_version") > 0) {
            out.put("snapshots", read(jdbc.queryForObject("select snapshots::text from quote_versions where quote_id = ? and version_no = ?", String.class, id, q.get("current_version"))));
        }
        return out;
    }

    /** E060: rounds this dealership is invited to, with its own quote (if any). */
    public Map<String, Object> inbox() {
        UUID d = dealership(Caller.current());
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select r.round_id, r.pool_id, r.round_set, r.round_no, r.kind, r.deadline, r.state, r.state_code, r.variants::text as variants, r.committed_count,
                       q.id as quote_id, q.state as quote_state, q.version as quote_version, clock_timestamp() as server_time
                from rounds_replica r left join quotes q on q.pool_id = r.pool_id and q.round_set = r.round_set and q.dealership_id = ?
                     and q.state not in ('EXPIRED', 'WITHDRAWN', 'VOID')
                where ? = any(r.invited_dealership_ids) order by r.deadline desc""", d, d);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> x = new LinkedHashMap<>();
            r.forEach((k, v) -> x.put(k, v instanceof Timestamp t ? t.toInstant().toString() : v instanceof UUID u ? u.toString() : v));
            x.put("variants", readList(read("{\"v\":" + r.get("variants") + "}").get("v")));
            x.put("ruleset_version", rulesetVersion());
            out.add(x);
        }
        return Map.of("requests", out);
    }

    // ------------------------------------------------------------------ internal summary for pool (E073)

    /** Counts per round; for a FINAL round past its deadline, finalises quotes first (idempotent, QUO-T04). */
    @Transactional
    public Map<String, Object> summary(UUID roundId) {
        List<Map<String, Object>> rs = jdbc.queryForList("select *, clock_timestamp() >= deadline as passed from rounds_replica where round_id = ?", roundId);
        if (rs.isEmpty()) throw ApiProblem.of(ProblemType.NOT_FOUND, "Round not replicated yet.");
        Map<String, Object> r = rs.get(0);
        UUID pool = (UUID) r.get("pool_id");
        int set = (int) r.get("round_set");
        if ("FINAL".equals(r.get("kind")) && (boolean) r.get("passed")) finalise(pool, set);
        List<Map<String, Object>> quotes = new ArrayList<>();
        for (Map<String, Object> q : jdbc.queryForList("select id, dealership_id, state, current_version from quotes where pool_id = ? and round_set = ? "
                + "and state in ('SUBMITTED', 'REVISED', 'FINAL')", pool, set)) {
            Map<String, Object> inputs = read(jdbc.queryForObject("select inputs::text from quote_versions where quote_id = ? and version_no = ?", String.class, q.get("id"), q.get("current_version")));
            quotes.add(Map.of("quote_id", q.get("id").toString(), "dealership_id", q.get("dealership_id").toString(), "state", q.get("state"),
                    "variant_ids", readList(inputs.get("variant_inputs")).stream().map(x -> x.get("variant_id")).toList()));
        }
        long valid = quotes.stream().filter(q -> Set.of("SUBMITTED", "REVISED", "FINAL").contains(q.get("state"))).count();
        long finals = quotes.stream().filter(q -> "FINAL".equals(q.get("state"))).count();
        return Map.of("round_id", roundId.toString(), "kind", r.get("kind"), "valid_count", valid, "final_count", finals, "quotes", quotes);
    }

    // ------------------------------------------------------------------ helpers

    Optional<Map<String, Object>> member(UUID pool, UUID buyer) {
        return jdbc.queryForList("select * from member_replica where pool_id = ? and buyer_id = ?", pool, buyer).stream().findFirst();
    }

    Map<String, Object> read(String s) {
        try {
            return json.readValue(s, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> readList(Object o) { return o == null ? List.of() : (List<Map<String, Object>>) o; }

    String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    JdbcTemplate jdbc() { return jdbc; }

    Map<String, Object> rules() { return rules; }
}
