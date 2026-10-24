package convoy.pool;

import convoy.contracts.events.AcceptanceChanged;
import convoy.contracts.events.BookingsIssued;
import convoy.contracts.events.MemberCommitted;
import convoy.contracts.events.MemberJoined;
import convoy.contracts.events.MemberLeft;
import convoy.contracts.events.PaymentCaptured;
import convoy.contracts.events.PaymentFailed;
import convoy.contracts.events.PoolBookingStarted;
import convoy.contracts.events.PoolCreated;
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
    public static final TransitionTable POOL = TransitionTable.load("pool", Set.of("POOL-T01", "POOL-T02", "POOL-T03", "POOL-T05", "POOL-T07", "POOL-T08"));
    public static final TransitionTable MEMBERSHIP = TransitionTable.load("membership",
            Set.of("MEM-T01", "MEM-T02", "MEM-T03", "MEM-T04", "MEM-T05", "MEM-T07", "MEM-T08", "MEM-T09", "MEM-T10", "MEM-T11"));
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
        Map<String, Object> row = jdbc.queryForMap("""
                insert into pools(id, model_id, model_slug, brand, city, state_code, accepted_variant_ids, variants, min_members, state, purchase_month,
                                  forming_deadline, dealer_wait_deadline, purchase_window_end, created_by)
                values (?, ?, ?, ?, ?, ?, ?::uuid[], ?::jsonb, ?, 'FORMING', ?, clock_timestamp() + ?::interval, clock_timestamp() + ?::interval + ?::interval, ?, ?)
                returning forming_deadline""", id, UUID.fromString((String) model.get("id")), modelSlug, model.get("brand"), city, state,
                "{" + String.join(",", variants) + "}", json(model.get("variants")), settings.minMembers(), purchaseMonth, interval(settings.durations().forming()),
                interval(settings.durations().forming()), interval(settings.durations().dealerWait()), Timestamp.from(windowEnd.toInstant()), c.userId());
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
        Map<String, Object> deposit;
        try {
            deposit = clients.payment().post().uri("/internal/deposits")
                    .header("Idempotency-Key", "deposit:" + c.userId() + ":" + poolId + ":" + roundSet)
                    .body(Map.of("buyer_id", c.userId(), "pool_id", poolId, "round_set", roundSet, "amount_paise", depositAmountPaise))
                    .retrieve().body(Map.class);
        } catch (RestClientException e) {
            log.warn("deposit creation failed: {}", e.toString());
            throw ApiProblem.of(ProblemType.DEPENDENCY_UNAVAILABLE, "Payments are unavailable right now; nothing was charged. Retry with the same Idempotency-Key.");
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
            MEMBERSHIP.check(ms0, "RELEASED"); // MEM-T06 late capture: 501 in P1, so the event is retried and lands in the DLQ
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

    public void paymentFailed(PaymentFailed e) {
        int n = jdbc.update("update memberships set state = 'INTERESTED', version = version + 1 where current_payment_id = ? and state = 'COMMIT_PENDING'",
                UUID.fromString(e.paymentId()));
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
        jdbc.update("update memberships set state = 'LEFT', release_reason = 'BUYER_LEFT', version = version + 1 where id = ?", m.get("id"));
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
            POOL.check("CONFIRMATION", e.voidQuoteCount() > 0 ? "FORMING" : "EXPIRED"); // POOL-T09 / T10: 501 in P1
            return;
        }
        POOL.check("CONFIRMATION", "BOOKING");
        long v = jdbc.queryForObject("update pools set state = 'BOOKING', last_order_version_seen = ?, version = version + 1 where id = ? returning version",
                Long.class, env.aggregateVersion(), poolId);
        outbox.append(poolId, v, new PoolBookingStarted(poolId.toString(), e.bookingsCount()));
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
        for (String k : List.of("id", "pool_id", "state", "current_payment_id", "terms_version", "accepted_quote_id", "accepted_variant_id", "release_reason", "version")) {
            Object v = m.get(k);
            out.put(k, v instanceof UUID u ? u.toString() : v);
        }
        return out;
    }

    static long num(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }

    static LocalDate today() { return LocalDate.now(IST); }
}
