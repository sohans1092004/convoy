package convoy.pool;

import convoy.contracts.events.AcceptanceChanged;
import convoy.contracts.events.BookingsIssued;
import convoy.contracts.events.MemberCommitted;
import convoy.contracts.events.MemberJoined;
import convoy.contracts.events.MemberLeft;
import convoy.contracts.events.MemberReleased;
import convoy.contracts.events.PaymentCaptured;
import convoy.contracts.events.PaymentFailed;
import convoy.contracts.events.PoolBookingStarted;
import convoy.contracts.events.PoolCreated;
import convoy.contracts.events.PoolClosed;
import convoy.contracts.events.PoolReady;
import convoy.platform.messaging.Envelope;
import convoy.platform.messaging.Outbox;
import convoy.platform.messaging.Uuid7;
import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.security.Caller;
import convoy.platform.statemachine.TransitionTable;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * Pool and membership commands (pool.yaml, membership.yaml). Every guarded transition locks the pool
 * row, checks the version and reads the DB clock (clock_timestamp()) in the committing transaction.
 */
@Service
public class Pools {
    private static final Logger log = LoggerFactory.getLogger(Pools.class);
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    public static final TransitionTable POOL = TransitionTable.load("pool", Set.of("POOL-T01", "POOL-T02", "POOL-T03", "POOL-T04", "POOL-T05", "POOL-T06", "POOL-T07", "POOL-T08", "POOL-T09", "POOL-T10", "POOL-T11", "POOL-T12"));
    public static final TransitionTable MEMBERSHIP = TransitionTable.load("membership",
            Set.of("MEM-T01", "MEM-T02", "MEM-T03", "MEM-T04", "MEM-T05", "MEM-T06", "MEM-T07", "MEM-T08", "MEM-T09", "MEM-T10", "MEM-T11", "MEM-T12", "MEM-T13", "MEM-T14", "MEM-T15", "MEM-T16"));
    public static final TransitionTable ROUND = TransitionTable.load("round", Set.of("RND-T01", "RND-T02"));
    static final Map<String, String> CITY_STATE = Map.of("Mumbai", "MH", "Pune", "MH", "Delhi", "DL", "Bengaluru", "KA");
    static final Set<String> JOINABLE = Set.of("FORMING", "READY_FOR_QUOTES", "QUOTE_OPEN");

    private final JdbcTemplate jdbc;
    private final Outbox outbox;
    private final PoolApplication.PoolSettings settings;
    private final PoolApplication.Clients clients;
    private final long depositAmountPaise;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public Pools(JdbcTemplate jdbc, Outbox outbox, PoolApplication.PoolSettings settings, PoolApplication.Clients clients, Long depositAmountPaise,
                 org.springframework.transaction.support.TransactionTemplate tx) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.settings = settings;
        this.clients = clients;
        this.depositAmountPaise = depositAmountPaise;
    }

    // ------------------------------------------------------------------ reads

    Map<String, Object> lockPool(UUID poolId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select *, clock_timestamp() as db_now from pools where id = ? for update", poolId);
        if (rows.isEmpty()) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such pool.");
        return rows.get(0);
    }

    Optional<Map<String, Object>> membership(UUID poolId, UUID buyer, boolean lock) {
        return jdbc.queryForList("select * from memberships where pool_id = ? and buyer_id = ?" + (lock ? " for update" : ""), poolId, buyer).stream().findFirst();
    }

    Timestamp round1Deadline(UUID poolId, int roundSet) {
        List<Timestamp> d = jdbc.queryForList("select deadline from rounds where pool_id = ? and round_set = ? and round_no = 1", Timestamp.class, poolId, roundSet);
        return d.isEmpty() ? null : d.get(0);
    }

    static boolean before(Map<String, Object> pool, Timestamp deadline) {
        return ((Timestamp) pool.get("db_now")).before(deadline);
    }

    /** MEM-T01 pool guard: joinable state and, in QUOTE_OPEN, before the sealed-round deadline. */
    void requireJoinable(Map<String, Object> pool, String command) {
        String state = (String) pool.get("state");
        if (!JOINABLE.contains(state)) {
            throw ApiProblem.of(ProblemType.INVALID_TRANSITION, "This pool no longer accepts " + command + " (" + state + ").").with("pool_state", state);
        }
        if (state.equals("QUOTE_OPEN")) {
            Timestamp d = round1Deadline((UUID) pool.get("id"), (int) pool.get("round_set"));
            if (!before(pool, d)) throw deadlinePassed(d, pool, "Joining closed at the sealed-round deadline.");
        }
    }

    static ApiProblem deadlinePassed(Timestamp deadline, Map<String, Object> pool, String detail) {
        return ApiProblem.of(ProblemType.DEADLINE_PASSED, detail)
                .with("deadline", deadline.toInstant().toString())
                .with("server_time", ((Timestamp) pool.get("db_now")).toInstant().toString())
                .with("aggregate", Map.of("type", "pool", "id", pool.get("id").toString()));
    }

    static Caller buyer() {
        Caller c = Caller.current().require("buyer");
        if (c.has("dealer_staff") || c.has("dealer_admin")) throw ApiProblem.of(ProblemType.FORBIDDEN, "Dealer staff cannot join pools.");
        return c;
    }

    // ------------------------------------------------------------------ E032 create (or find the open pool)

    @SuppressWarnings("unchecked")
    @Transactional
    public Map<String, Object> createOrFind(String modelSlug, String city, String purchaseMonth) {
        Caller c = buyer();
        String state = CITY_STATE.get(city);
        if (state == null) throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "City must be one of " + CITY_STATE.keySet() + ".");
        YearMonth month;
        try {
            month = YearMonth.parse(purchaseMonth);
        } catch (RuntimeException e) {
            throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "purchase_month must be YYYY-MM.");
        }
        YearMonth now = YearMonth.now(IST);
        if (month.isBefore(now) || month.isAfter(now.plusMonths(6))) throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "Choose this month or one of the next six.");
        List<Map<String, Object>> open = jdbc.queryForList("select id from pools where model_slug = ? and city = ? and purchase_month = ? "
                + "and state in ('FORMING', 'READY_FOR_QUOTES') order by created_at limit 1", modelSlug, city, purchaseMonth);
        if (!open.isEmpty()) return Map.of("created", false, "pool", snapshot((UUID) open.get(0).get("id"), c));

        Map<String, Object> model;
        try {
            model = clients.catalog().get().uri(u -> u.path("/internal/models/{slug}").queryParam("city", city).build(modelSlug)).retrieve().body(Map.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "Unknown model " + modelSlug + ".");
        } catch (RestClientException e) {
            throw ApiProblem.of(ProblemType.DEPENDENCY_UNAVAILABLE, "Catalogue unavailable; retry with the same Idempotency-Key.");
        }
        List<String> variants = (List<String>) model.get("variant_ids");
        // Purchase window ends on the last day of the month, 23:59:59 IST (Q-025).
        ZonedDateTime windowEnd = ZonedDateTime.of(month.atEndOfMonth(), LocalTime.of(23, 59, 59), IST);
        UUID id = Uuid7.next();
        // A concurrent create for the same key waits on one_forming_pool, then finds the winner's pool.
        List<Map<String, Object>> inserted = jdbc.queryForList("""
                insert into pools(id, model_id, model_slug, brand, city, state_code, accepted_variant_ids, variants, min_members, state, purchase_month,
                                  forming_deadline, dealer_wait_deadline, purchase_window_end, created_by)
                values (?, ?, ?, ?, ?, ?, ?::uuid[], ?::jsonb, ?, 'FORMING', ?, clock_timestamp() + ?::interval, clock_timestamp() + ?::interval + ?::interval, ?, ?)
                on conflict (model_slug, city, purchase_month) where state = 'FORMING' do nothing
                returning forming_deadline""", id, UUID.fromString((String) model.get("id")), modelSlug, model.get("brand"), city, state,
                "{" + String.join(",", variants) + "}", json(model.get("variants")), settings.minMembers(), purchaseMonth, interval(settings.durations().forming()),
                interval(settings.durations().forming()), interval(settings.durations().dealerWait()), Timestamp.from(windowEnd.toInstant()), c.userId());
        if (inserted.isEmpty()) {
            UUID winner = jdbc.queryForObject("select id from pools where model_slug = ? and city = ? and purchase_month = ? and state = 'FORMING'",
                    UUID.class, modelSlug, city, purchaseMonth);
            return Map.of("created", false, "pool", snapshot(winner, c));
        }
        Map<String, Object> row = inserted.get(0);
        outbox.append(id, 1, new PoolCreated(id.toString(), (String) model.get("id"), city, "FORMING", (long) settings.minMembers(),
                ((Timestamp) row.get("forming_deadline")).toInstant(), windowEnd.toInstant(), variants));
        return Map.of("created", true, "pool", snapshot(id, c));
    }

    static String interval(java.time.Duration d) { return d.toMillis() + " milliseconds"; }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> variantFacts(Map<String, Object> pool) {
        try {
            return JSON.readValue(pool.get("variants").toString(), List.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ MEM-T01 join

    @Transactional
    public Map<String, Object> join(UUID poolId) {
        Caller c = buyer();
        Map<String, Object> pool = lockPool(poolId);
        Optional<Map<String, Object>> existing = membership(poolId, c.userId(), false);
        if (existing.isPresent() && "LEFT".equals(existing.get().get("state"))) return rejoin(pool, existing.get());
        if (existing.isPresent()) throw ApiProblem.of(ProblemType.ALREADY_MEMBER, "You are already in this pool.").with("membership", view(existing.get()));
        MEMBERSHIP.check("(none)", "INTERESTED");
        requireJoinable(pool, "new members");
        UUID mid = Uuid7.next();
        jdbc.update("insert into memberships(id, pool_id, buyer_id, model_id, state) values (?, ?, ?, ?, 'INTERESTED')", mid, poolId, c.userId(), pool.get("model_id"));
        Map<String, Object> p = jdbc.queryForMap("update pools set interested_count = interested_count + 1, version = version + 1 where id = ? "
                + "returning interested_count, committed_count, version", poolId);
        outbox.append(poolId, (long) p.get("version"), new MemberJoined(poolId.toString(), mid.toString(), num(p, "interested_count"), num(p, "committed_count")));
        return view(membership(poolId, c.userId(), false).orElseThrow());
    }

    /** MEM-T01 on a LEFT row (AMENDMENT-008): a new membership episode as INTERESTED, refused after 2 leaves; a new commitment needs a new deposit. */
    private Map<String, Object> rejoin(Map<String, Object> pool, Map<String, Object> m) {
        UUID poolId = (UUID) pool.get("id");
        MEMBERSHIP.check("(none)", "INTERESTED");
        if ((int) m.get("leave_count") >= 2) {
            throw ApiProblem.of(ProblemType.REJOIN_LIMIT_REACHED, "You have left this pool twice, so you cannot rejoin it. You can join another pool for this car.");
        }
        requireJoinable(pool, "members rejoining");
        jdbc.update("update memberships set state = 'INTERESTED', release_reason = null, current_payment_id = null, accepted_quote_id = null, "
                + "accepted_variant_id = null, version = version + 1 where id = ?", m.get("id"));
        Map<String, Object> p = jdbc.queryForMap("update pools set interested_count = interested_count + 1, version = version + 1 where id = ? "
                + "returning interested_count, committed_count, version", poolId);
        outbox.append(poolId, (long) p.get("version"), new MemberJoined(poolId.toString(), m.get("id").toString(), num(p, "interested_count"), num(p, "committed_count")));
        return view(membership(poolId, (UUID) m.get("buyer_id"), false).orElseThrow());
    }

    // ------------------------------------------------------------------ MEM-T02 commit

    /** EXTERNAL_CALL_THEN_COMMIT: the deposit is created (or found) at payment first, then the membership commits. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> commit(UUID poolId, String termsVersion) {
        Caller c = buyer();
        if (!settings.termsVersion().equals(termsVersion)) {
            throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "Please read the current commitment terms (" + settings.termsVersion() + ") first.")
                    .with("terms_version", settings.termsVersion());
        }
        Map<String, Object> pre = jdbc.queryForMap("select *, clock_timestamp() as db_now from pools where id = ?", poolId);
        Optional<Map<String, Object>> m = membership(poolId, c.userId(), false);
        if (m.isPresent() && !Set.of("INTERESTED", "COMMIT_PENDING").contains(m.get().get("state"))) {
            MEMBERSHIP.check((String) m.get().get("state"), "COMMIT_PENDING");
        }
        requireJoinable(pre, "commitments");
        int roundSet = (int) pre.get("round_set");
        Map<String, Object> deposit = null;
        // The deposit key makes the call idempotent at payment, so transient failures are retried (3 attempts).
        for (int attempt = 1; deposit == null; attempt++) {
            try {
                deposit = clients.payment().post().uri("/internal/deposits")
                        .header("Idempotency-Key", "deposit:" + c.userId() + ":" + poolId + ":" + roundSet)
                        .body(Map.of("buyer_id", c.userId(), "pool_id", poolId, "round_set", roundSet, "amount_paise", depositAmountPaise))
                        .retrieve().body(Map.class);
            } catch (HttpClientErrorException e) {
                log.warn("deposit refused: {}", e.toString());
                throw ApiProblem.of(ProblemType.DEPENDENCY_UNAVAILABLE, "Payments refused the deposit; nothing was charged.");
            } catch (RestClientException e) {
                log.warn("deposit creation attempt {} failed: {}", attempt, e.toString());
                if (attempt == 3) {
                    throw ApiProblem.of(ProblemType.DEPENDENCY_UNAVAILABLE, "Payments are unavailable right now; nothing was charged. Retry with the same Idempotency-Key.");
                }
                try {
                    Thread.sleep(300L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        UUID paymentId = UUID.fromString((String) deposit.get("payment_id"));
        Map<String, Object> membership = tx.execute(s -> commitLocal(poolId, c.userId(), paymentId, termsVersion));
        return Map.of("membership", membership, "deposit", deposit);
    }

    /** Runs in the transaction opened by {@link #commit}. */
    Map<String, Object> commitLocal(UUID poolId, UUID buyer, UUID paymentId, String termsVersion) {
        Map<String, Object> pool = lockPool(poolId);
        requireJoinable(pool, "commitments");
        Optional<Map<String, Object>> m = membership(poolId, buyer, true);
        try {
            if (m.isEmpty()) {
                MEMBERSHIP.check("(none)", "COMMIT_PENDING");
                jdbc.update("insert into memberships(id, pool_id, buyer_id, model_id, state, current_payment_id, terms_version) values (?, ?, ?, ?, 'COMMIT_PENDING', ?, ?)",
                        Uuid7.next(), poolId, buyer, pool.get("model_id"), paymentId, termsVersion);
                Map<String, Object> p = jdbc.queryForMap("update pools set interested_count = interested_count + 1, version = version + 1 where id = ? "
                        + "returning interested_count, committed_count, version", poolId);
                UUID mid = (UUID) membership(poolId, buyer, false).orElseThrow().get("id");
                outbox.append(poolId, (long) p.get("version"), new MemberJoined(poolId.toString(), mid.toString(), num(p, "interested_count"), num(p, "committed_count")));
            } else if (m.get().get("state").equals("INTERESTED")) {
                MEMBERSHIP.check("INTERESTED", "COMMIT_PENDING");
                jdbc.update("update memberships set state = 'COMMIT_PENDING', current_payment_id = ?, terms_version = ?, version = version + 1 where id = ?",
                        paymentId, termsVersion, m.get().get("id"));
            } else if (!m.get().get("state").equals("COMMIT_PENDING")) {
                MEMBERSHIP.check((String) m.get().get("state"), "COMMIT_PENDING");
            }
        } catch (DuplicateKeyException e) {
            throw ApiProblem.of(ProblemType.ACTIVE_COMMITMENT_EXISTS, "You already hold a commitment for this model in another pool.");
        }
        return view(membership(poolId, buyer, false).orElseThrow());
    }

    // ------------------------------------------------------------------ MEM-T03 / MEM-T04 (payment events), POOL-T01

    public void paymentCaptured(Envelope env, PaymentCaptured e) {
        List<Map<String, Object>> ms = jdbc.queryForList("select * from memberships where current_payment_id = ? for update", UUID.fromString(e.paymentId()));
        if (ms.isEmpty()) {
            log.info("PaymentCaptured {} matches no membership", e.paymentId());
            return;
        }
        Map<String, Object> m = ms.get(0);
        UUID poolId = (UUID) m.get("pool_id");
        Map<String, Object> pool = lockPool(poolId);
        String ms0 = (String) m.get("state");
        if (Set.of("LEFT", "RELEASED").contains(ms0) || ms0.equals("COMMITTED")) return; // payment refunds a late capture itself
        String state = (String) pool.get("state");
        Timestamp limit = switch (state) {
            case "FORMING" -> (Timestamp) pool.get("forming_deadline");
            case "QUOTE_OPEN" -> round1Deadline(poolId, (int) pool.get("round_set"));
            default -> null;
        };
        boolean guard = JOINABLE.contains(state) && (limit == null || before(pool, limit));
        if (!guard) {
            // MEM-T06: the capture arrived after the pool closed or the commit deadline passed. Payment refunds it (LATE_CAPTURE).
            MEMBERSHIP.check(ms0, "RELEASED");
            jdbc.update("update memberships set state = 'RELEASED', release_reason = 'LATE_CAPTURE', version = version + 1 where id = ?", m.get("id"));
            Map<String, Object> p = jdbc.queryForMap("update pools set version = version + 1 where id = ? returning interested_count, committed_count, version", poolId);
            outbox.append(poolId, (long) p.get("version"), new MemberReleased(poolId.toString(), m.get("id").toString(), m.get("buyer_id").toString(),
                    e.paymentId(), "LATE_CAPTURE", num(p, "interested_count"), num(p, "committed_count")));
            return;
        }
        MEMBERSHIP.check(ms0, "COMMITTED");
        jdbc.update("update memberships set state = 'COMMITTED', version = version + 1 where id = ?", m.get("id"));
        Map<String, Object> p = jdbc.queryForMap("update pools set committed_count = committed_count + 1, version = version + 1 where id = ? "
                + "returning interested_count, committed_count, version, min_members, state, forming_deadline > clock_timestamp() as before_deadline", poolId);
        outbox.append(poolId, (long) p.get("version"), new MemberCommitted(poolId.toString(), m.get("id").toString(), m.get("buyer_id").toString(),
                e.paymentId(), num(p, "interested_count"), num(p, "committed_count"), false));
        if ("FORMING".equals(p.get("state")) && (int) p.get("committed_count") >= (int) p.get("min_members") && (boolean) p.get("before_deadline")) {
            POOL.check("FORMING", "READY_FOR_QUOTES");
            long v = jdbc.queryForObject("update pools set state = 'READY_FOR_QUOTES', version = version + 1 where id = ? and state = 'FORMING' returning version", Long.class, poolId);
            outbox.append(poolId, v, new PoolReady(poolId.toString(), num(p, "committed_count")));
        }
    }

    /** MEM-T04: a failed or voided deposit returns the member to INTERESTED (they may try to commit again). */
    public void paymentFailed(String paymentId) {
        int n = jdbc.update("update memberships set state = 'INTERESTED', version = version + 1 where current_payment_id = ? and state = 'COMMIT_PENDING'",
                UUID.fromString(paymentId));
        if (n == 1) MEMBERSHIP.check("COMMIT_PENDING", "INTERESTED");
    }

    // ------------------------------------------------------------------ MEM-T05 leave

    @Transactional
    public Map<String, Object> leave(UUID poolId, long ifMatch) {
        Caller c = buyer();
        Map<String, Object> pool = lockPool(poolId);
        Map<String, Object> m = membership(poolId, c.userId(), true).orElseThrow(() -> ApiProblem.of(ProblemType.NOT_FOUND, "You are not in this pool."));
        String from = (String) m.get("state");
        MEMBERSHIP.check(from, "LEFT");
        if ((long) m.get("version") != ifMatch) throw ApiProblem.of(ProblemType.VERSION_CONFLICT, "Your membership changed; reload and try again.").with("current_version", m.get("version"));
        String state = (String) pool.get("state");
        if (!Set.of("FORMING", "READY_FOR_QUOTES", "QUOTE_OPEN", "FINAL_ROUND", "DECISION_OPEN").contains(state)) {
            throw ApiProblem.of(ProblemType.INVALID_TRANSITION, "After confirmation opens, decline or cancel through your order instead.");
        }
        if (state.equals("DECISION_OPEN") && !before(pool, (Timestamp) pool.get("decision_deadline"))) {
            throw deadlinePassed((Timestamp) pool.get("decision_deadline"), pool, "The decision deadline has passed.");
        }
        jdbc.update("update memberships set state = 'LEFT', release_reason = 'BUYER_LEFT', leave_count = leave_count + 1, version = version + 1 where id = ?", m.get("id"));
        boolean committed = Set.of("COMMITTED", "ACCEPTED").contains(from);
        if (from.equals("ACCEPTED")) jdbc.update("update quote_acceptance_counts set count = count - 1 where pool_id = ? and quote_id = ?", poolId, m.get("accepted_quote_id"));
        Map<String, Object> p = jdbc.queryForMap("update pools set interested_count = interested_count - 1, committed_count = committed_count - ?, version = version + 1 "
                + "where id = ? returning interested_count, committed_count, version", committed ? 1 : 0, poolId);
        UUID payment = (UUID) m.get("current_payment_id");
        outbox.append(poolId, (long) p.get("version"), new MemberLeft(poolId.toString(), m.get("id").toString(), c.subject(),
                payment == null ? null : payment.toString(), "BUYER_LEFT", num(p, "interested_count"), num(p, "committed_count")));
        return view(membership(poolId, c.userId(), false).orElseThrow());
    }

    // ------------------------------------------------------------------ MEM-T07 / T08 / T09 acceptance

    @Transactional
    public Map<String, Object> accept(UUID poolId, UUID quoteId, UUID variantId, long ifMatch) {
        Caller c = buyer();
        Map<String, Object> pool = lockPool(poolId);
        Map<String, Object> m = membership(poolId, c.userId(), true).orElseThrow(() -> ApiProblem.of(ProblemType.NOT_FOUND, "You are not in this pool."));
        String from = (String) m.get("state");
        if (Set.of("INTERESTED", "COMMIT_PENDING").contains(from)) throw ApiProblem.of(ProblemType.NOT_A_MEMBER, "Only committed members can accept an offer.");
        MEMBERSHIP.check(from, "ACCEPTED");
        if ((long) m.get("version") != ifMatch) throw ApiProblem.of(ProblemType.VERSION_CONFLICT, "Your membership changed; reload and try again.").with("current_version", m.get("version"));
        if (!"DECISION_OPEN".equals(pool.get("state"))) throw ApiProblem.of(ProblemType.INVALID_TRANSITION, "Offers can be accepted only while the decision is open.");
        if (!before(pool, (Timestamp) pool.get("decision_deadline"))) throw deadlinePassed((Timestamp) pool.get("decision_deadline"), pool, "The decision deadline has passed.");
        Integer ok = jdbc.queryForObject("select count(*) from quote_replica where quote_id = ? and pool_id = ? and state = 'FINAL' and ? = any(variant_ids)",
                Integer.class, quoteId, poolId, variantId);
        if (ok == 0) throw ApiProblem.of(ProblemType.QUOTE_NOT_FINAL, "That offer is not a final offer for this variant in this pool.");
        UUID previous = (UUID) m.get("accepted_quote_id");
        if (previous != null) jdbc.update("update quote_acceptance_counts set count = count - 1 where pool_id = ? and quote_id = ?", poolId, previous);
        jdbc.update("insert into quote_acceptance_counts(pool_id, quote_id, count) values (?, ?, 1) on conflict (pool_id, quote_id) do update set count = quote_acceptance_counts.count + 1",
                poolId, quoteId);
        jdbc.update("update memberships set state = 'ACCEPTED', accepted_quote_id = ?, accepted_variant_id = ?, version = version + 1 where id = ?", quoteId, variantId, m.get("id"));
        return acceptanceChanged(poolId, m, quoteId, variantId, c.userId());
    }

    @Transactional
    public Map<String, Object> withdrawAcceptance(UUID poolId, long ifMatch) {
        Caller c = buyer();
        Map<String, Object> pool = lockPool(poolId);
        Map<String, Object> m = membership(poolId, c.userId(), true).orElseThrow(() -> ApiProblem.of(ProblemType.NOT_FOUND, "You are not in this pool."));
        MEMBERSHIP.check((String) m.get("state"), "COMMITTED");
        if ((long) m.get("version") != ifMatch) throw ApiProblem.of(ProblemType.VERSION_CONFLICT, "Your membership changed; reload and try again.").with("current_version", m.get("version"));
        if (!"DECISION_OPEN".equals(pool.get("state"))) throw ApiProblem.of(ProblemType.INVALID_TRANSITION, "The decision is not open.");
        if (!before(pool, (Timestamp) pool.get("decision_deadline"))) throw deadlinePassed((Timestamp) pool.get("decision_deadline"), pool, "The decision deadline has passed.");
        jdbc.update("update quote_acceptance_counts set count = count - 1 where pool_id = ? and quote_id = ?", poolId, m.get("accepted_quote_id"));
        jdbc.update("update memberships set state = 'COMMITTED', accepted_quote_id = null, accepted_variant_id = null, version = version + 1 where id = ?", m.get("id"));
        return acceptanceChanged(poolId, m, null, null, c.userId());
    }

    private Map<String, Object> acceptanceChanged(UUID poolId, Map<String, Object> m, UUID quoteId, UUID variantId, UUID buyer) {
        long v = jdbc.queryForObject("update pools set version = version + 1 where id = ? returning version", Long.class, poolId);
        outbox.append(poolId, v, new AcceptanceChanged(poolId.toString(), m.get("id").toString(), quoteId == null ? null : quoteId.toString(),
                variantId == null ? null : variantId.toString(), acceptanceCounts(poolId)));
        return view(membership(poolId, buyer, false).orElseThrow());
    }

    Map<String, Object> acceptanceCounts(UUID poolId) {
        Map<String, Object> counts = new HashMap<>();
        jdbc.queryForList("select quote_id, count from quote_acceptance_counts where pool_id = ?", poolId)
                .forEach(r -> counts.put(r.get("quote_id").toString(), ((Number) r.get("count")).longValue()));
        return counts;
    }

    // ------------------------------------------------------------------ POOL-T08 (order resolution)

    public void bookingsIssued(Envelope env, BookingsIssued e) {
        UUID poolId = UUID.fromString(e.poolId());
        Map<String, Object> pool = lockPool(poolId);
        if (!"CONFIRMATION".equals(pool.get("state")) || env.aggregateVersion() <= (long) pool.get("last_order_version_seen")) return;
        if (e.bookingsCount() == 0) {
            jdbc.update("update pools set last_order_version_seen = ? where id = ?", env.aggregateVersion(), poolId);
            var d = settings.durations();
            boolean fits = e.voidQuoteCount() > 0 && (int) pool.get("round_set") == 1 && jdbc.queryForObject(
                    "select clock_timestamp() + ?::interval + ?::interval + ?::interval + ?::interval + ?::interval <= purchase_window_end from pools where id = ?", Boolean.class,
                    interval(d.forming()), interval(d.sealedRound()), interval(d.finalRound()), interval(d.decision()), interval(d.confirmation()), poolId);
            if (fits) reopen(poolId, e.voidBuyerIds() == null ? List.of() : e.voidBuyerIds()); // POOL-T09
            else closePool(poolId, "CONFIRMATION", "EXPIRED", e.voidQuoteCount() > 0 ? "ALL_QUOTES_VOID" : "NO_CONFIRMATIONS"); // POOL-T10
            return;
        }
        POOL.check("CONFIRMATION", "BOOKING");
        long v = jdbc.queryForObject("update pools set state = 'BOOKING', last_order_version_seen = ?, version = version + 1 where id = ? returning version",
                Long.class, env.aggregateVersion(), poolId);
        outbox.append(poolId, v, new PoolBookingStarted(poolId.toString(), e.bookingsCount()));
    }

    /**
     * Ends a pool (POOL-T04/T06/T10/T11/T12) with MEM-T10 for committed members, in the caller's transaction. Refunds are driven
     * by PoolClosed, which payment consumes: captured deposits are refunded, unpaid ones voided (one event per pool, not per member).
     */
    void closePool(UUID poolId, String from, String endState, String endReason) {
        POOL.check(from, endState);
        for (String st : jdbc.queryForList("select distinct state from memberships where pool_id = ? and state in ('COMMITTED', 'REROUND_CHOICE')", String.class, poolId)) {
            MEMBERSHIP.check(st, "RELEASED");
        }
        jdbc.update("update memberships set state = 'RELEASED', release_reason = ?, version = version + 1 where pool_id = ? and state in ('COMMITTED', 'REROUND_CHOICE')",
                "POOL_" + endReason, poolId);
        long v = jdbc.queryForObject("update pools set state = ?, end_reason = ?, version = version + 1 where id = ? returning version", Long.class, endState, endReason, poolId);
        outbox.append(poolId, v, new PoolClosed(poolId.toString(), endState, endReason));
        log.info("pool {} closed: {} ({})", poolId, endState, endReason);
    }

    /** POOL-T09 (single re-round, AMENDMENT-002): VOID buyers keep their deposit and choose within 48 h (MEM-T12); others go back to INTERESTED (MEM-T16). */
    void reopen(UUID poolId, List<String> voidBuyers) {
        POOL.check("CONFIRMATION", "FORMING");
        Timestamp choiceBy = jdbc.queryForObject("select clock_timestamp() + ?::interval", Timestamp.class, interval(settings.durations().reroundChoice()));
        for (Map<String, Object> m : jdbc.queryForList("select id, buyer_id, state from memberships where pool_id = ? and state in ('HANDED_TO_ORDER', 'RELEASED') for update", poolId)) {
            boolean choice = voidBuyers.contains(m.get("buyer_id").toString()) && "HANDED_TO_ORDER".equals(m.get("state"));
            MEMBERSHIP.check((String) m.get("state"), choice ? "REROUND_CHOICE" : "INTERESTED");
            if (choice) jdbc.update("update memberships set state = 'REROUND_CHOICE', choice_deadline = ?, version = version + 1 where id = ?", choiceBy, m.get("id"));
            else jdbc.update("update memberships set state = 'INTERESTED', accepted_quote_id = null, accepted_variant_id = null, current_payment_id = null, version = version + 1 where id = ?", m.get("id"));
        }
        jdbc.update("delete from quote_acceptance_counts where pool_id = ?", poolId);
        Map<String, Object> p = jdbc.queryForMap("update pools set state = 'FORMING', round_set = 2, committed_count = 0, forming_deadline = clock_timestamp() + ?::interval, "
                + "version = version + 1 where id = ? returning version, forming_deadline", interval(settings.durations().forming()), poolId);
        outbox.append(poolId, (long) p.get("version"), new convoy.contracts.events.PoolReopened(poolId.toString(), 2L, ((Timestamp) p.get("forming_deadline")).toInstant(), voidBuyers,
                choiceBy.toInstant()));
        log.info("pool {} re-opened for its single re-round; {} buyers choose by {}", poolId, voidBuyers.size(), choiceBy.toInstant());
    }

    /** E042 re-round choice: KEEP (MEM-T13, no new payment) or REFUND (MEM-T14), before choice_deadline on the DB clock. */
    @Transactional
    public Map<String, Object> reroundChoice(UUID poolId, String choice, long ifMatch) {
        Caller c = buyer();
        Map<String, Object> pool = lockPool(poolId);
        Map<String, Object> m = membership(poolId, c.userId(), true).orElseThrow(() -> ApiProblem.of(ProblemType.NOT_FOUND, "You are not in this pool."));
        boolean keep = "KEEP".equals(choice);
        if (!"REROUND_CHOICE".equals(m.get("state"))) throw ApiProblem.of(ProblemType.INVALID_TRANSITION, "There is no re-round choice to make for your membership.");
        MEMBERSHIP.check("REROUND_CHOICE", keep ? "COMMITTED" : "RELEASED");
        if ((long) m.get("version") != ifMatch) throw ApiProblem.of(ProblemType.VERSION_CONFLICT, "Your membership changed; reload and try again.").with("current_version", m.get("version"));
        if (!before(pool, (Timestamp) m.get("choice_deadline"))) {
            throw deadlinePassed((Timestamp) m.get("choice_deadline"), pool, "The 48-hour choice window has passed; your deposit is being refunded.");
        }
        UUID payment = (UUID) m.get("current_payment_id");
        if (!keep) {
            release(poolId, m, "REROUND_REFUND_REQUESTED");
            return view(membership(poolId, c.userId(), false).orElseThrow());
        }
        if (!Set.of("FORMING", "READY_FOR_QUOTES").contains(pool.get("state")) || (int) pool.get("round_set") != 2) {
            throw ApiProblem.of(ProblemType.INVALID_TRANSITION, "The re-round has already started quoting; you can still take the refund.");
        }
        jdbc.update("update memberships set state = 'COMMITTED', accepted_quote_id = null, accepted_variant_id = null, choice_deadline = null, version = version + 1 where id = ?", m.get("id"));
        Map<String, Object> p = jdbc.queryForMap("update pools set committed_count = committed_count + 1, version = version + 1 where id = ? "
                + "returning interested_count, committed_count, version, min_members, state, forming_deadline > clock_timestamp() as before_deadline", poolId);
        outbox.append(poolId, (long) p.get("version"), new MemberCommitted(poolId.toString(), m.get("id").toString(), c.subject(), payment == null ? null : payment.toString(),
                num(p, "interested_count"), num(p, "committed_count"), true)); // kept from round 1 (MEM-T13)
        if ("FORMING".equals(p.get("state")) && (int) p.get("committed_count") >= (int) p.get("min_members") && (boolean) p.get("before_deadline")) {
            POOL.check("FORMING", "READY_FOR_QUOTES");
            long v = jdbc.queryForObject("update pools set state = 'READY_FOR_QUOTES', version = version + 1 where id = ? and state = 'FORMING' returning version", Long.class, poolId);
            outbox.append(poolId, v, new PoolReady(poolId.toString(), num(p, "committed_count")));
        }
        return view(membership(poolId, c.userId(), false).orElseThrow());
    }

    /** MEM-T14 / MEM-T15: released with a full refund (payment consumes MemberReleased). */
    void release(UUID poolId, Map<String, Object> m, String reason) {
        jdbc.update("update memberships set state = 'RELEASED', release_reason = ?, choice_deadline = null, version = version + 1 where id = ?", reason, m.get("id"));
        Map<String, Object> p = jdbc.queryForMap("update pools set version = version + 1 where id = ? returning interested_count, committed_count, version", poolId);
        UUID payment = (UUID) m.get("current_payment_id");
        outbox.append(poolId, (long) p.get("version"), new MemberReleased(poolId.toString(), m.get("id").toString(), m.get("buyer_id").toString(),
                payment == null ? null : payment.toString(), reason, num(p, "interested_count"), num(p, "committed_count")));
    }

    // ------------------------------------------------------------------ realtime authorisation (internal)

    boolean mayWatch(UUID poolId, UUID buyer, UUID dealership) {
        if (buyer != null && jdbc.queryForObject("select count(*) from memberships where pool_id = ? and buyer_id = ? and state <> 'LEFT'",
                Integer.class, poolId, buyer) > 0) return true;
        return dealership != null && jdbc.queryForObject("select count(*) from round_invitations i join rounds r on r.id = i.round_id where r.pool_id = ? "
                + "and i.dealership_id = ?", Integer.class, poolId, dealership) > 0;
    }

    long version(UUID poolId) {
        return jdbc.queryForList("select version from pools where id = ?", Long.class, poolId).stream().findFirst()
                .orElseThrow(() -> ApiProblem.of(ProblemType.NOT_FOUND, "No such pool."));
    }

    // ------------------------------------------------------------------ preferences and matching (E030, E031)

    private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]{0,19}");

    public Api.Preferences preferences(UUID buyer) {
        return jdbc.query("select city, purchase_month, variant_ids::text[], colours, fuels, transmissions from preferences where buyer_id = ?", (rs, i) ->
                new Api.Preferences(rs.getString(1), rs.getString(2), strings(rs.getArray(3)), strings(rs.getArray(4)), strings(rs.getArray(5)), strings(rs.getArray(6))),
                buyer).stream().findFirst().orElse(new Api.Preferences(null, null, List.of(), List.of(), List.of(), List.of()));
    }

    public Api.Preferences savePreferences(UUID buyer, Api.Preferences b) {
        if (b.city() != null && !CITY_STATE.containsKey(b.city())) throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "City must be one of " + CITY_STATE.keySet() + ".");
        if (b.purchaseMonth() != null) {
            YearMonth m;
            try {
                m = YearMonth.parse(b.purchaseMonth());
            } catch (RuntimeException e) {
                throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "purchase_month must be YYYY-MM.");
            }
            YearMonth now = YearMonth.now(IST);
            if (m.isBefore(now) || m.isAfter(now.plusMonths(6))) throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "Choose this month or one of the next six.");
        }
        for (String v : b.variantIds()) {
            try {
                UUID.fromString(v);
            } catch (IllegalArgumentException e) {
                throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "variant_ids must be variant ids.");
            }
        }
        for (List<String> codes : List.of(b.fuels(), b.transmissions())) {
            if (codes.stream().anyMatch(c -> !CODE.matcher(c).matches())) throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "Fuel and transmission are codes such as PETROL or AMT.");
        }
        if (b.colours().size() > 20 || b.colours().stream().anyMatch(c -> c.isBlank() || c.length() > 40)) {
            throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "Up to 20 colours, each at most 40 characters.");
        }
        jdbc.update("""
                insert into preferences(buyer_id, city, purchase_month, variant_ids, colours, fuels, transmissions) values (?, ?, ?, ?::uuid[], ?::text[], ?::text[], ?::text[])
                on conflict (buyer_id) do update set city = excluded.city, purchase_month = excluded.purchase_month, variant_ids = excluded.variant_ids,
                    colours = excluded.colours, fuels = excluded.fuels, transmissions = excluded.transmissions, updated_at = clock_timestamp()""",
                buyer, b.city(), b.purchaseMonth(), pgArray(b.variantIds()), pgArray(b.colours()), pgArray(b.fuels()), pgArray(b.transmissions()));
        return preferences(buyer);
    }

    @SuppressWarnings("unchecked")
    public Api.Suggestions suggestions(UUID buyer, List<String> models) {
        Api.Preferences p = preferences(buyer);
        Matching.Preferences prefs = new Matching.Preferences(p.city(), p.purchaseMonth(), Set.copyOf(p.variantIds()), Set.copyOf(p.fuels()), Set.copyOf(p.transmissions()));
        List<String> wanted = models.stream().distinct().limit(20).toList();
        java.time.Instant now = jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
        List<Matching.Pool> candidates = wanted.isEmpty() || p.city() == null || p.purchaseMonth() == null ? List.of() : jdbc.query("""
                select p.id::text as id, p.model_slug, p.city, p.purchase_month, p.state, p.variants::text as variants, p.accepted_variant_ids::text[] as accepted,
                       p.min_members, p.committed_count, p.interested_count,
                       case p.state when 'FORMING' then p.forming_deadline when 'QUOTE_OPEN' then r.deadline end as join_deadline
                from pools p left join rounds r on r.pool_id = p.id and r.round_set = p.round_set and r.round_no = 1
                where p.model_slug = any(?::text[]) and p.city = ? and p.purchase_month = ? and p.state in ('FORMING', 'READY_FOR_QUOTES', 'QUOTE_OPEN')""",
                (rs, i) -> {
                    Set<String> accepted = Set.of((String[]) rs.getArray("accepted").getArray());
                    List<Matching.Variant> variants = new ArrayList<>();
                    try {
                        for (Map<String, Object> v : (List<Map<String, Object>>) JSON.readValue(rs.getString("variants"), List.class)) {
                            if (accepted.contains(String.valueOf(v.get("id")))) {
                                variants.add(new Matching.Variant(String.valueOf(v.get("id")), (String) v.get("fuel"), (String) v.get("transmission")));
                            }
                        }
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        throw new IllegalStateException(e);
                    }
                    Timestamp d = rs.getTimestamp("join_deadline");
                    return new Matching.Pool(rs.getString("id"), rs.getString("model_slug"), rs.getString("city"), rs.getString("purchase_month"), rs.getString("state"),
                            variants, rs.getInt("min_members"), rs.getInt("committed_count"), rs.getInt("interested_count"), d == null ? null : d.toInstant());
                }, pgArray(wanted), p.city(), p.purchaseMonth());
        return new Api.Suggestions(wanted.stream().map(m -> Matching.suggest(m, prefs, candidates, now)).toList(), now.toString());
    }

    private static List<String> strings(java.sql.Array a) throws java.sql.SQLException {
        return a == null ? List.of() : List.of((String[]) a.getArray());
    }

    /** A Postgres array literal with every element quoted. */
    private static String pgArray(List<String> xs) {
        return "{" + String.join(",", xs.stream().map(x -> "\"" + x.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").toList()) + "}";
    }

    // ------------------------------------------------------------------ views

    public Map<String, Object> snapshot(UUID poolId, Caller c) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, model_slug, brand, city, state_code, accepted_variant_ids::text[] as accepted_variant_ids, min_members, state, end_reason,
                       round_set, purchase_month, forming_deadline, decision_deadline, confirmation_deadline, purchase_window_end,
                       interested_count, committed_count, version, clock_timestamp() as server_time from pools where id = ?""", poolId);
        if (rows.isEmpty()) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such pool.");
        if (c.has("dealer_staff") || c.has("dealer_admin")) {
            // AZ-E033-X: a dealership sees a pool only once it is invited to one of its rounds.
            Integer invited = jdbc.queryForObject("select count(*) from round_invitations i join rounds r on r.id = i.round_id where r.pool_id = ? and i.dealership_id = ?",
                    Integer.class, poolId, c.dealershipId() == null ? null : UUID.fromString(c.dealershipId()));
            if (invited == 0) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such pool.");
        }
        Map<String, Object> out = new HashMap<>(iso(rows.get(0)));
        out.put("accepted_variant_ids", List.of((String[]) toArray(rows.get(0).get("accepted_variant_ids"))));
        out.put("rounds", jdbc.queryForList("select id, round_set, round_no, kind, state, deadline, closed_at from rounds where pool_id = ? order by round_set, round_no", poolId)
                .stream().map(Pools::iso).toList());
        out.put("deposit_amount_paise", depositAmountPaise);
        out.put("terms_version", settings.termsVersion());
        if (c.has("buyer")) out.put("my_membership", membership(poolId, c.userId(), false).map(Pools::view).orElse(null));
        if ("DECISION_OPEN".equals(out.get("state")) || "CONFIRMATION".equals(out.get("state"))) out.put("acceptance_counts", acceptanceCounts(poolId));
        return out;
    }

    static Object[] toArray(Object sqlArray) {
        try {
            return (Object[]) ((java.sql.Array) sqlArray).getArray();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Object> iso(Map<String, Object> row) {
        Map<String, Object> out = new HashMap<>();
        row.forEach((k, v) -> out.put(k, v instanceof Timestamp t ? t.toInstant().toString() : v instanceof UUID u ? u.toString() : v));
        return out;
    }

    static Map<String, Object> view(Map<String, Object> m) {
        Map<String, Object> out = new HashMap<>();
        for (String k : List.of("id", "pool_id", "state", "current_payment_id", "terms_version", "accepted_quote_id", "accepted_variant_id", "release_reason", "choice_deadline", "version")) {
            Object v = m.get(k);
            out.put(k, v instanceof UUID u ? u.toString() : v instanceof Timestamp t ? t.toInstant().toString() : v);
        }
        return out;
    }

    static long num(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }

    static LocalDate today() { return LocalDate.now(IST); }
}
