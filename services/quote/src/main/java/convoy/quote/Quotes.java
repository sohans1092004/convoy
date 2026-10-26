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
    public static final TransitionTable QUOTE = TransitionTable.load("quote", Set.of("QUO-T01", "QUO-T02", "QUO-T03", "QUO-T04", "QUO-T05", "QUO-T06", "QUO-T08", "QUO-T09"));
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
        if (e.roundNo() == 2) reveal(UUID.fromString(e.poolId()), e.roundSet().intValue());
    }

    /** RoundClosed(SEALED): drafts expire (QUO-T09). RoundClosed(FINAL): live quotes become FINAL (QUO-T04, idempotent). */
    public void roundClosed(RoundClosed e) {
        UUID roundId = UUID.fromString(e.roundId());
        jdbc.update("update rounds_replica set state = 'CLOSED' where round_id = ?", roundId);
        Map<String, Object> r = jdbc.queryForMap("select pool_id, round_set from rounds_replica where round_id = ?", roundId);
        if (e.kind().equals("SEALED")) {
            rankHidden((UUID) r.get("pool_id"), (int) r.get("round_set")); // computed at close, revealed when the final round opens
            for (UUID q : jdbc.queryForList("select id from quotes where pool_id = ? and round_set = ? and state = 'DRAFT' for update", UUID.class, r.get("pool_id"), r.get("round_set"))) {
                QUOTE.check("DRAFT", "EXPIRED");
                jdbc.update("update quotes set state = 'EXPIRED', expire_reason = 'NOT_SUBMITTED', version = version + 1 where id = ?", q);
            }
        } else {
            finalise((UUID) r.get("pool_id"), (int) r.get("round_set"));
            rank((UUID) r.get("pool_id"), (int) r.get("round_set"));
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
        // Each reply is a new event of the quote aggregate: a new version, or order discards it as stale (seen on QuoteFinalised).
        long v = jdbc.queryForObject("update quotes set version = version + 1 where id = ? returning version", Long.class, quote);
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
        checkContent(draft, (Timestamp) round.get("db_now"));
        QuotePricing.Priced priced = QuotePricing.price(draft, facts, (String) round.get("state_code"), rules);
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("draft", draft);
        inputs.put("variant_inputs", priced.variantInputs());
        jdbc.update("insert into quote_versions(quote_id, version_no, inputs, snapshots, snapshot_sha256, ruleset_version, committed_by, ranking) values (?, 1, ?::jsonb, ?::jsonb, ?, ?, ?, ?::jsonb)",
                id, CanonicalJson.write(inputs), CanonicalJson.write(priced.snapshots()), priced.sha256(), rulesetVersion(), c.userId(),
                write(QuotePricing.rankingScores(draft, facts, (String) round.get("state_code"), rules)));
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

    // ------------------------------------------------------------------ P3.B: QUO-T03 revise, QUO-T05 withdraw, QUO-T06 cancel

    /** The quote's current round (the latest of its round set), with this DB's clock. */
    Map<String, Object> currentRound(Map<String, Object> q) {
        return jdbc.queryForMap("select *, clock_timestamp() as db_now from rounds_replica where pool_id = ? and round_set = ? order by round_no desc limit 1",
                q.get("pool_id"), q.get("round_set"));
    }

    /** Validity date and stock claims (content rules not covered by the price engine). */
    @SuppressWarnings("unchecked")
    static void checkContent(Map<String, Object> draft, Timestamp dbNow) {
        java.time.LocalDate today = dbNow.toInstant().atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate();
        java.time.LocalDate validUntil;
        try {
            validUntil = java.time.LocalDate.parse(String.valueOf(draft.get("valid_until")));
        } catch (RuntimeException e) {
            throw ApiProblem.of(ProblemType.QUOTE_INVALID, "Give the date until which this quote is valid.").with("errors", List.of("VALIDITY_MISSING"));
        }
        if (validUntil.isBefore(today)) throw ApiProblem.of(ProblemType.QUOTE_INVALID, "The validity date is in the past.").with("errors", List.of("VALIDITY_IN_PAST"));
        for (Map<String, Object> line : readList(draft.get("variants"))) {
            Map<String, Object> stock = line.get("stock") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            Long units = QuotePricing.lng(stock.get("units"));
            boolean hasColour = stock.get("colours") instanceof List<?> l && !l.isEmpty();
            if (units != null && units > 0 && !hasColour) {
                throw ApiProblem.of(ProblemType.QUOTE_INVALID, "Name the colour(s) of the stock you hold.").with("errors", List.of("STOCK_COLOUR_MISSING")).with("variant_id", line.get("variant_id"));
            }
            Object date = stock.get("delivery_date");
            if (date != null) {
                try {
                    java.time.LocalDate.parse(String.valueOf(date));
                } catch (RuntimeException e) {
                    throw ApiProblem.of(ProblemType.QUOTE_INVALID, "The availability date is not a date.").with("errors", List.of("STOCK_DATE_INVALID")).with("variant_id", line.get("variant_id"));
                }
            }
        }
    }

    /** Changed leaves between two drafts, for the audit trail: [{path, from, to}]. */
    static List<Map<String, Object>> diff(Object before, Object after, String path) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (before instanceof Map<?, ?> a && after instanceof Map<?, ?> b) {
            java.util.Set<String> keys = new java.util.TreeSet<>();
            a.keySet().forEach(k -> keys.add(String.valueOf(k)));
            b.keySet().forEach(k -> keys.add(String.valueOf(k)));
            for (String k : keys) out.addAll(diff(a.get(k), b.get(k), path.isEmpty() ? k : path + "." + k));
        } else if (before instanceof List<?> a && after instanceof List<?> b) {
            for (int i = 0; i < Math.max(a.size(), b.size()); i++) out.addAll(diff(i < a.size() ? a.get(i) : null, i < b.size() ? b.get(i) : null, path + "[" + i + "]"));
        } else if (!java.util.Objects.equals(before, after)) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("path", path);
            d.put("from", before);
            d.put("to", after);
            out.add(d);
        }
        return out;
    }

    /** QUO-T03: a new immutable version n+1 with fresh price snapshots; the diff goes to the audit trail. */
    @Transactional
    public Map<String, Object> revise(UUID id, Map<String, Object> draft, long ifMatch) {
        Caller c = Caller.current();
        UUID d = dealership(c);
        Map<String, Object> q = own(id, d, true);
        if ((long) q.get("version") != ifMatch) throw ApiProblem.of(ProblemType.VERSION_CONFLICT, "The quote changed; reload it.").with("current_version", q.get("version"));
        QUOTE.check((String) q.get("state"), "REVISED");
        Map<String, Object> round = currentRound(q);
        if (!"OPEN".equals(round.get("state")) || !((Timestamp) round.get("db_now")).before((Timestamp) round.get("deadline"))) {
            throw deadlinePassed(round, "The round has closed; this quote can no longer be revised.");
        }
        checkContent(draft, (Timestamp) round.get("db_now"));
        Map<String, Map<String, Object>> facts = new HashMap<>();
        for (Map<String, Object> f : readList(read("{\"v\":" + round.get("variants") + "}").get("v"))) facts.put((String) f.get("id"), f);
        QuotePricing.Priced priced = QuotePricing.price(draft, facts, (String) round.get("state_code"), rules);
        int n = ((Number) q.get("current_version")).intValue() + 1;
        Map<String, Object> previous = read(jdbc.queryForObject("select inputs::text from quote_versions where quote_id = ? and version_no = ?", String.class, id, n - 1));
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("draft", draft);
        inputs.put("variant_inputs", priced.variantInputs());
        jdbc.update("insert into quote_versions(quote_id, version_no, inputs, snapshots, snapshot_sha256, ruleset_version, committed_by, ranking) values (?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?::jsonb)",
                id, n, CanonicalJson.write(inputs), CanonicalJson.write(priced.snapshots()), priced.sha256(), rulesetVersion(), c.userId(),
                write(QuotePricing.rankingScores(draft, facts, (String) round.get("state_code"), rules)));
        // Deadline check is the last statement before commit, on this DB's clock (deadline-rule.md section 1).
        List<Map<String, Object>> ok = jdbc.queryForList("update quotes set state = 'REVISED', current_version = ?, draft = ?::jsonb, version = version + 1 "
                + "where id = ? and version = ? and clock_timestamp() < ? returning version", n, write(draft), id, ifMatch, round.get("deadline"));
        if (ok.isEmpty()) {
            round.put("db_now", jdbc.queryForMap("select clock_timestamp() as db_now").get("db_now"));
            throw deadlinePassed(round, "The round closed before your revision reached our database. Nothing was saved.");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("version_no", n);
        detail.put("snapshot_sha256", priced.sha256());
        detail.put("diff", diff(previous.get("draft"), draft, ""));
        jdbc.update("insert into audit_trail(quote_id, actor, action, detail) values (?, ?, 'REVISE', ?::jsonb)", id, c.userId(), write(detail));
        Timestamp at = jdbc.queryForObject("select committed_at from quote_versions where quote_id = ? and version_no = ?", Timestamp.class, id, n);
        outbox.append(id, ((Number) ok.get(0).get("version")).longValue(), new convoy.contracts.events.QuoteRevised(id.toString(), q.get("pool_id").toString(), d.toString(),
                (long) n, priced.sha256(), at.toInstant()));
        if (((Number) round.get("round_no")).intValue() == 2) rank((UUID) q.get("pool_id"), (int) q.get("round_set")); // ranks change in the final round only
        return dealerView(id, d);
    }

    /** QUO-T05: withdraw (DRAFT, SUBMITTED, REVISED) until the current round's deadline. After FINAL only QUO-T06 applies. */
    @Transactional
    public Map<String, Object> withdraw(UUID id, String reason, long ifMatch) {
        Caller c = Caller.current();
        UUID d = dealership(c);
        Map<String, Object> q = own(id, d, true);
        if ((long) q.get("version") != ifMatch) throw ApiProblem.of(ProblemType.VERSION_CONFLICT, "The quote changed; reload it.").with("current_version", q.get("version"));
        String state = (String) q.get("state");
        if ("FINAL".equals(state)) throw ApiProblem.of(ProblemType.INVALID_TRANSITION, "This quote is final: an admin can cancel it, which counts as a dealer cancellation.");
        QUOTE.check(state, "WITHDRAWN");
        Map<String, Object> round = currentRound(q);
        List<Map<String, Object>> ok = jdbc.queryForList("update quotes set state = 'WITHDRAWN', withdraw_reason = ?, version = version + 1 "
                + "where id = ? and version = ? and clock_timestamp() < ? returning version", reason, id, ifMatch, round.get("deadline"));
        if (ok.isEmpty()) {
            round.put("db_now", jdbc.queryForMap("select clock_timestamp() as db_now").get("db_now"));
            throw deadlinePassed(round, "The round has closed; the quote will become final and can then only be cancelled by an admin.");
        }
        jdbc.update("insert into audit_trail(quote_id, actor, action, detail) values (?, ?, 'WITHDRAW', ?::jsonb)", id, c.userId(), write(Map.of("reason", reason)));
        outbox.append(id, ((Number) ok.get(0).get("version")).longValue(), new convoy.contracts.events.QuoteWithdrawn(id.toString(), q.get("pool_id").toString(), d.toString(), false, reason));
        return dealerView(id, d);
    }

    /** QUO-T06: dealer_admin cancels a FINAL quote at any time; a dealer cancellation per cancellation-rules.md (order acts on QuoteWithdrawn{after_final}). */
    @Transactional
    public Map<String, Object> cancel(UUID id, String reason, long ifMatch) {
        Caller c = Caller.current();
        UUID d = dealership(c);
        Map<String, Object> q = own(id, d, true);
        if ((long) q.get("version") != ifMatch) throw ApiProblem.of(ProblemType.VERSION_CONFLICT, "The quote changed; reload it.").with("current_version", q.get("version"));
        if (!"FINAL".equals(q.get("state"))) throw ApiProblem.of(ProblemType.INVALID_TRANSITION, "Only a final quote is cancelled; before that, withdraw it.");
        QUOTE.check("FINAL", "WITHDRAWN");
        long v = jdbc.queryForObject("update quotes set state = 'WITHDRAWN', withdraw_reason = ?, after_final = true, version = version + 1 where id = ? returning version",
                Long.class, reason, id);
        jdbc.update("insert into audit_trail(quote_id, actor, action, detail) values (?, ?, 'CANCEL', ?::jsonb)", id, c.userId(), write(Map.of("reason", reason, "after_final", true)));
        outbox.append(id, v, new convoy.contracts.events.QuoteWithdrawn(id.toString(), q.get("pool_id").toString(), d.toString(), true, reason));
        return dealerView(id, d);
    }

    // ------------------------------------------------------------------ P3.C ranking (ranking-rule.md)

    /**
     * Ranks every live quote of the pool's round set per variant from the scores stored with each current version, appends a
     * snapshot row per (quote, variant), and emits RankChanged when a dealer's rank or of_n moved. Called when the final round
     * opens, at each version commit in it, and at finalisation. The sealed round shows no rank.
     */
    void rank(UUID poolId, int roundSet) {
        for (Ranking.Ranked k : ranked(poolId, roundSet)) record(poolId, roundSet, k, true);
    }

    /** At the sealed close: snapshots stored hidden, no events (nothing is visible during or right after the sealed round). */
    void rankHidden(UUID poolId, int roundSet) {
        for (Ranking.Ranked k : ranked(poolId, roundSet)) record(poolId, roundSet, k, false);
    }

    /**
     * When the final round opens (inside the same inbox transaction that records the open, so a dealer never sees an open
     * final round without its rank): reveal the ranks computed at the sealed close, or compute them now if there were none.
     */
    void reveal(UUID poolId, int roundSet) {
        List<Map<String, Object>> hidden = jdbc.queryForList("update rank_snapshots set revealed = true where pool_id = ? and round_set = ? and not revealed "
                + "returning id, quote_id, dealership_id, variant_id, rank, of_n", poolId, roundSet);
        if (hidden.isEmpty()) {
            rank(poolId, roundSet);
            return;
        }
        for (Map<String, Object> h : hidden) {
            outbox.append((UUID) h.get("quote_id"), (long) h.get("id"), new convoy.contracts.events.RankChanged(h.get("quote_id").toString(), poolId.toString(),
                    h.get("dealership_id").toString(), h.get("variant_id").toString(), ((Number) h.get("rank")).longValue(), ((Number) h.get("of_n")).longValue(), Ranking.RULE_VERSION));
        }
    }

    private List<Ranking.Ranked> ranked(UUID poolId, int roundSet) {
        List<Ranking.Entry> entries = new java.util.ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("select q.id, q.dealership_id, v.ranking::text as ranking, v.committed_at from quotes q "
                + "join quote_versions v on v.quote_id = q.id and v.version_no = q.current_version "
                + "where q.pool_id = ? and q.round_set = ? and q.state in ('SUBMITTED', 'REVISED', 'FINAL')", poolId, roundSet)) {
            for (Map.Entry<String, Object> v : read((String) r.get("ranking")).entrySet()) {
                Map<String, Object> sc = QuotePricing.m(v.getValue());
                Object date = sc.get("delivery_date");
                entries.add(new Ranking.Entry(r.get("id").toString(), r.get("dealership_id").toString(), v.getKey(), QuotePricing.lng(sc.get("score_paise")),
                        date == null ? null : java.time.LocalDate.parse(date.toString()), ((Timestamp) r.get("committed_at")).toInstant()));
            }
        }
        return Ranking.rank(entries);
    }

    /** Appends a snapshot; a revealed one emits RankChanged when the dealer's rank or of_n moved since its last revealed one. */
    private void record(UUID poolId, int roundSet, Ranking.Ranked k, boolean revealed) {
        List<Map<String, Object>> last = jdbc.queryForList("select rank, of_n from rank_snapshots where quote_id = ?::uuid and variant_id = ?::uuid and revealed "
                + "order by id desc limit 1", k.quoteId(), k.variantId());
        Long id = jdbc.queryForObject("insert into rank_snapshots(pool_id, round_set, variant_id, quote_id, dealership_id, rank, of_n, rule_version, revealed) "
                + "values (?, ?, ?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?) returning id", Long.class, poolId, roundSet, k.variantId(), k.quoteId(), k.dealershipId(), k.rank(), k.ofN(),
                Ranking.RULE_VERSION, revealed);
        boolean changed = last.isEmpty() || (int) last.get(0).get("rank") != k.rank() || (int) last.get(0).get("of_n") != k.ofN();
        if (revealed && changed) {
            outbox.append(UUID.fromString(k.quoteId()), id, new convoy.contracts.events.RankChanged(k.quoteId(), poolId.toString(), k.dealershipId(), k.variantId(),
                    (long) k.rank(), (long) k.ofN(), Ranking.RULE_VERSION));
        }
    }

    /** E068: the caller's own rank per variant, nothing else (AZ-RNK-01..03); empty while only the sealed round has run. */
    public Map<String, Object> ownRank(UUID id) {
        UUID d = dealership(Caller.current());
        own(id, d, false);
        List<Map<String, Object>> ranks = new java.util.ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("select distinct on (variant_id) variant_id, rank, of_n, rule_version, computed_at from rank_snapshots "
                + "where quote_id = ? and revealed order by variant_id, id desc", id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("variant_id", r.get("variant_id").toString());
            m.put("rank", r.get("rank"));
            m.put("of_n", r.get("of_n"));
            m.put("rule_version", r.get("rule_version"));
            m.put("computed_at", ((Timestamp) r.get("computed_at")).toInstant().toString());
            ranks.add(m);
        }
        return Map.of("quote_id", id.toString(), "ranks", ranks);
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
