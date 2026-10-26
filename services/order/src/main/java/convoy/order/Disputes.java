package convoy.order;

import convoy.contracts.events.DisputeOpened;
import convoy.contracts.events.DisputeResolved;
import convoy.contracts.events.DisputeStatusChanged;
import convoy.platform.messaging.Outbox;
import convoy.platform.messaging.Uuid7;
import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.security.Caller;
import convoy.platform.statemachine.TransitionTable;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Disputes (dispute.yaml): buyer- or system-opened, admin-owned, with a resolution history and evidence in S3. Money outcomes need a
 * second, different admin (AMENDMENT-001): the owner proposes, another admin confirms.
 */
@Service
public class Disputes {
    public static final TransitionTable DISPUTE = TransitionTable.load("dispute", Set.of("DSP-T01", "DSP-T02", "DSP-T03", "DSP-T04", "DSP-T05", "DSP-T06", "DSP-T07", "DSP-T08"));
    static final Set<String> CATEGORIES = Set.of("NOT_DELIVERED", "DELIVERY_OVERDUE", "PRICE_NOT_HONOURED", "STOCK_UNAVAILABLE", "DEALER_CANCELLED", "DEALER_NOT_HONOURED",
            "WRONG_VARIANT", "CHARGES_DISPUTE", "REFUND_NOT_RECEIVED", "OTHER");
    static final Set<String> OUTCOMES = Set.of("REFUND_BUYER", "CANCEL_BOOKING", "DEALER_WARNING", "PRICE_ADJUSTMENT_RECORDED", "NO_ACTION");
    static final Set<String> MONEY_OUTCOMES = Set.of("REFUND_BUYER", "CANCEL_BOOKING");
    static final Duration DISPUTE_WINDOW = Duration.ofDays(90); // Q-020
    static final Duration RESPOND_WITHIN = Duration.ofHours(72); // Q-022
    static final Duration APPEAL_WINDOW = Duration.ofDays(7); // Q-022
    private final JdbcTemplate jdbc;
    private final Outbox outbox;
    private final Lifecycle lifecycle;

    public Disputes(JdbcTemplate jdbc, Outbox outbox, @Lazy Lifecycle lifecycle) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.lifecycle = lifecycle;
    }

    // ------------------------------------------------------------------ DSP-T01

    /** E092: a buyer opens a dispute on their own order (booked, completed within 90 days of delivery, or cancelled). */
    @Transactional
    public Map<String, Object> openByBuyer(UUID orderId, String category, String description) {
        UUID buyer = Caller.current().require("buyer").userId();
        if (!CATEGORIES.contains(category)) throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "Category must be one of " + CATEGORIES + ".");
        if (description == null || description.isBlank() || description.length() > 4000) throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "Describe the problem (up to 4,000 characters).");
        List<Map<String, Object>> rs = jdbc.queryForList("select o.state, o.quote_id, b.id as booking_id, b.delivered_at, b.dealership_id, clock_timestamp() as db_now, "
                + "(select dealership_id from quote_replica q where q.quote_id = o.quote_id) as quote_dealership from orders o left join bookings b on b.order_id = o.id "
                + "where o.id = ? and o.buyer_id = ? for update of o", orderId, buyer);
        if (rs.isEmpty()) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such order.");
        Map<String, Object> o = rs.get(0);
        if (!Set.of("BOOKED", "COMPLETED", "CANCELLED").contains(o.get("state"))) {
            throw ApiProblem.of(ProblemType.INVALID_TRANSITION, "A dispute can be opened once your booking exists, or after a cancellation.");
        }
        Timestamp delivered = (Timestamp) o.get("delivered_at");
        if (delivered != null && !((Timestamp) o.get("db_now")).toInstant().isBefore(delivered.toInstant().plus(DISPUTE_WINDOW))) {
            throw ApiProblem.of(ProblemType.DEADLINE_PASSED, "Disputes can be opened up to 90 days after delivery.").with("deadline", delivered.toInstant().plus(DISPUTE_WINDOW).toString());
        }
        UUID dealership = (UUID) (o.get("dealership_id") != null ? o.get("dealership_id") : o.get("quote_dealership"));
        return view(open(orderId, (UUID) o.get("booking_id"), dealership, buyer, category, description, "BUYER"), Caller.current());
    }

    /** DSP-T01 by the system inside BKG-T03/BKG-T06/ORD-T10: an admin reviews every dealer-side failure. */
    UUID openBySystem(UUID orderId, UUID bookingId, UUID dealership, UUID buyer, String category, String why) {
        List<UUID> open = jdbc.queryForList("select id from disputes where order_id = ? and category = ? and state in ('OPEN', 'UNDER_REVIEW', 'AWAITING_PARTY')", UUID.class, orderId, category);
        return open.isEmpty() ? open(orderId, bookingId, dealership, buyer, category, why, "SYSTEM") : open.get(0);
    }

    private UUID open(UUID orderId, UUID bookingId, UUID dealership, UUID buyer, String category, String description, String by) {
        DISPUTE.check("(none)", "OPEN");
        if (jdbc.queryForObject("select count(*) from disputes where order_id = ? and category = ? and state in ('OPEN', 'UNDER_REVIEW', 'AWAITING_PARTY')", Integer.class, orderId, category) > 0) {
            throw ApiProblem.of(ProblemType.INVALID_TRANSITION, "A dispute of this kind is already open on this order.");
        }
        UUID id = Uuid7.next();
        jdbc.update("insert into disputes(id, order_id, booking_id, dealership_id, buyer_id, category, description, state, opened_by) values (?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)",
                id, orderId, bookingId, dealership, buyer, category, description, by);
        history(id, by, "BUYER".equals(by) ? buyer : null, "OPENED", null, "OPEN", description);
        outbox.append(id, 1, new DisputeOpened(id.toString(), orderId.toString(), bookingId == null ? null : bookingId.toString(), dealership.toString(), category, by));
        return id;
    }

    // ------------------------------------------------------------------ admin: DSP-T02, T03, T05

    @Transactional
    public Map<String, Object> assign(UUID id, UUID owner) {
        Caller c = Caller.current().require("platform_admin");
        Map<String, Object> d = lock(id);
        move(d, "UNDER_REVIEW", "owner_id = ?", c, "ASSIGNED", "owner " + (owner == null ? c.userId() : owner), owner == null ? c.userId() : owner);
        return view(id, c);
    }

    @Transactional
    public Map<String, Object> requestInfo(UUID id, String party, String question) {
        Caller c = Caller.current().require("platform_admin");
        if (!Set.of("BUYER", "DEALER").contains(party)) throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "party must be BUYER or DEALER.");
        Map<String, Object> d = lock(id);
        ownerOnly(d, c);
        move(d, "AWAITING_PARTY", "awaiting_party = ?, respond_by = clock_timestamp() + ?::interval", c, "INFO_REQUESTED", party + ": " + question,
                party, RESPOND_WITHIN.toMillis() + " milliseconds");
        return view(id, c);
    }

    /** DSP-T05. Money outcomes: the owner proposes; a second, different admin confirms the same outcome (AMENDMENT-001). */
    @Transactional
    public Map<String, Object> resolve(UUID id, String outcome, String note) {
        Caller c = Caller.current().require("platform_admin");
        if (!OUTCOMES.contains(outcome)) throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "outcome must be one of " + OUTCOMES + ".");
        Map<String, Object> d = lock(id);
        DISPUTE.check((String) d.get("state"), "RESOLVED");
        if (MONEY_OUTCOMES.contains(outcome)) {
            if (d.get("proposed_by") == null || !outcome.equals(d.get("proposed_outcome"))) {
                ownerOnly(d, c);
                jdbc.update("update disputes set proposed_outcome = ?, proposed_by = ?, version = version + 1 where id = ?", outcome, c.userId(), id);
                history(id, "ADMIN", c.userId(), "OUTCOME_PROPOSED", (String) d.get("state"), (String) d.get("state"), outcome + ": " + note);
                return view(id, c);
            }
            if (c.userId().equals(d.get("proposed_by"))) {
                throw ApiProblem.of(ProblemType.SECOND_APPROVAL_REQUIRED, "A second, different admin must confirm a money outcome (AMENDMENT-001).");
            }
        } else {
            ownerOnly(d, c);
        }
        move(d, "RESOLVED", "outcome = ?, resolved_at = clock_timestamp(), appeal_until = clock_timestamp() + ?::interval", c, "RESOLVED", outcome + ": " + note,
                outcome, APPEAL_WINDOW.toMillis() + " milliseconds");
        outbox.append(id, nextVersion(id), new DisputeResolved(id.toString(), outcome, c.userId().toString()));
        if (MONEY_OUTCOMES.contains(outcome) && d.get("booking_id") != null) {
            String refundReason = Set.of("PRICE_NOT_HONOURED", "DEALER_NOT_HONOURED").contains(d.get("category")) ? "DEALER_NOT_HONOURED" : "ADMIN_DISPUTE_RESOLUTION";
            lifecycle.cancelBooking((UUID) d.get("booking_id"), "ADMIN_DISPUTE_RESOLUTION", refundReason, "ADMIN:" + c.userId(), false);
        }
        return view(id, c);
    }

    // ------------------------------------------------------------------ parties: DSP-T04, T07, T08

    @Transactional
    public Map<String, Object> respond(UUID id, String text) {
        Caller c = Caller.current().require("buyer", "dealer_staff", "dealer_admin");
        Map<String, Object> d = lock(id);
        String party = party(d, c);
        if (!"AWAITING_PARTY".equals(d.get("state")) || !party.equals(d.get("awaiting_party"))) {
            history(id, party, c.userId(), "MESSAGE", (String) d.get("state"), (String) d.get("state"), text); // a message without a state change
            return view(id, c);
        }
        move(d, "UNDER_REVIEW", "awaiting_party = null, respond_by = null", c, "RESPONDED", text);
        return view(id, c);
    }

    @Transactional
    public Map<String, Object> appeal(UUID id, String note) {
        Caller c = Caller.current().require("buyer");
        Map<String, Object> d = lock(id);
        if (!c.userId().equals(d.get("buyer_id"))) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such dispute.");
        DISPUTE.check((String) d.get("state"), "UNDER_REVIEW");
        if ((int) d.get("appeal_count") > 0) throw ApiProblem.of(ProblemType.INVALID_TRANSITION, "A dispute can be appealed once.");
        if (!jdbc.queryForObject("select clock_timestamp() < appeal_until from disputes where id = ?", Boolean.class, id)) {
            throw ApiProblem.of(ProblemType.DEADLINE_PASSED, "The 7-day appeal window has passed.");
        }
        move(d, "UNDER_REVIEW", "appeal_count = appeal_count + 1, proposed_outcome = null, proposed_by = null", c, "APPEALED", note);
        return view(id, c);
    }

    @Transactional
    public Map<String, Object> withdraw(UUID id) {
        Caller c = Caller.current().require("buyer");
        Map<String, Object> d = lock(id);
        if (!c.userId().equals(d.get("buyer_id"))) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such dispute.");
        if (!"BUYER".equals(d.get("opened_by"))) throw ApiProblem.of(ProblemType.INVALID_TRANSITION, "A case we opened for you stays open until an admin resolves it.");
        move(d, "WITHDRAWN", "", c, "WITHDRAWN", null);
        return view(id, c);
    }

    /** Sweeper: DSP-T04 at respond_by (NO_RESPONSE) and DSP-T06 at appeal_until. */
    void sweep() {
        for (UUID id : jdbc.queryForList("select id from disputes where state = 'AWAITING_PARTY' and respond_by <= clock_timestamp()", UUID.class)) {
            Map<String, Object> d = lock(id);
            if ("AWAITING_PARTY".equals(d.get("state"))) move(d, "UNDER_REVIEW", "awaiting_party = null, respond_by = null", null, "NO_RESPONSE", d.get("awaiting_party") + " did not respond in time");
        }
        for (UUID id : jdbc.queryForList("select id from disputes where state = 'RESOLVED' and appeal_until <= clock_timestamp()", UUID.class)) {
            Map<String, Object> d = lock(id);
            if ("RESOLVED".equals(d.get("state"))) move(d, "CLOSED", "", null, "CLOSED", "appeal window ended");
        }
    }

    // ------------------------------------------------------------------ views

    public Map<String, Object> view(UUID id, Caller c) {
        List<Map<String, Object>> rs = jdbc.queryForList("select id, order_id, booking_id, dealership_id, buyer_id, category, description, state, opened_by, owner_id, awaiting_party, "
                + "respond_by, outcome, proposed_outcome, resolved_at, appeal_until, appeal_count, version, created_at from disputes where id = ?", id);
        if (rs.isEmpty()) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such dispute.");
        Map<String, Object> d = Orders.iso(rs.get(0));
        party(rs.get(0), c); // 404 for anyone who is not a party or an admin (AZ-T7)
        d.put("history", jdbc.queryForList("select actor_role, action, from_state, to_state, note, at from dispute_history where dispute_id = ? order by id", id).stream().map(Orders::iso).toList());
        d.put("evidence", jdbc.queryForList("select id, uploader_role, file_name, content_type, size_bytes, object_key, created_at from dispute_evidence where dispute_id = ? order by created_at", id)
                .stream().map(Orders::iso).toList());
        return d;
    }

    public List<Map<String, Object>> adminQueue() {
        Caller.current().require("platform_admin");
        return jdbc.queryForList("select id, order_id, dealership_id, category, state, opened_by, owner_id, proposed_outcome, proposed_by, created_at from disputes "
                + "where state in ('OPEN', 'UNDER_REVIEW', 'AWAITING_PARTY') order by created_at").stream().map(Orders::iso).toList();
    }

    /** Which party the caller is; 404 if they are none of them. */
    String party(Map<String, Object> d, Caller c) {
        if (c.roles().contains("platform_admin")) return "ADMIN";
        if (c.roles().contains("buyer") && c.userId().equals(d.get("buyer_id"))) return "BUYER";
        if ((c.roles().contains("dealer_staff") || c.roles().contains("dealer_admin")) && c.dealershipId() != null && d.get("dealership_id").toString().equals(c.dealershipId())) return "DEALER";
        throw ApiProblem.of(ProblemType.NOT_FOUND, "No such dispute.");
    }

    private void ownerOnly(Map<String, Object> d, Caller c) {
        if (d.get("owner_id") != null && !c.userId().equals(d.get("owner_id"))) throw ApiProblem.of(ProblemType.FORBIDDEN, "Only the dispute's owner can do this.");
    }

    Map<String, Object> lock(UUID id) {
        List<Map<String, Object>> rs = jdbc.queryForList("select * from disputes where id = ? for update", id);
        if (rs.isEmpty()) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such dispute.");
        return rs.get(0);
    }

    private void move(Map<String, Object> d, String to, String extraSet, Caller c, String action, String note, Object... extra) {
        String from = (String) d.get("state");
        DISPUTE.check(from, to);
        Object[] args = new Object[extra.length + 2];
        args[0] = to;
        System.arraycopy(extra, 0, args, 1, extra.length);
        args[extra.length + 1] = d.get("id");
        jdbc.update("update disputes set state = ?, version = version + 1" + (extraSet.isEmpty() ? "" : ", " + extraSet) + " where id = ?", args);
        UUID id = (UUID) d.get("id");
        history(id, c == null ? "SYSTEM" : c.roles().contains("platform_admin") ? "ADMIN" : party(d, c), c == null ? null : c.userId(), action, from, to, note);
        outbox.append(id, nextVersion(id), new DisputeStatusChanged(id.toString(), from, to));
    }

    private long nextVersion(UUID id) {
        return jdbc.queryForObject("select coalesce(max(aggregate_version), 0) + 1 from outbox where aggregate_id = ?", Long.class, id);
    }

    void history(UUID id, String role, UUID actor, String action, String from, String to, String note) {
        jdbc.update("insert into dispute_history(dispute_id, actor_role, actor_id, action, from_state, to_state, note) values (?, ?, ?, ?, ?, ?, ?)", id, role, actor, action, from, to, note);
    }
}
