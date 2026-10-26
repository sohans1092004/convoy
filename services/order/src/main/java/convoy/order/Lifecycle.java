package convoy.order;

import static convoy.order.Orders.BOOKING;
import static convoy.order.Orders.ORDER;

import convoy.contracts.events.AllBookingsClosed;
import convoy.contracts.events.BookingCancelled;
import convoy.contracts.events.BookingDelivered;
import convoy.contracts.events.BookingOverdue;
import convoy.contracts.events.ConfirmationDeclined;
import convoy.contracts.events.DeliveryScheduled;
import convoy.contracts.events.DepositRefundRequested;
import convoy.contracts.events.OrderCancelled;
import convoy.platform.messaging.Outbox;
import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.security.Caller;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * After the confirmation deadline (P4.B): the persisted order saga FROZEN -> RESOLVED -> DEPOSITS_HELD -> BOOKINGS_ISSUED ->
 * CONTACTS_RELEASED, one transaction per step, each step idempotent under the saga row lock. Then the booking's life (P4.D):
 * dealer confirmation, delivery, overdue, and every cancellation rule of cancellation-rules.md §1 with its refund and dispute.
 */
@Service
public class Lifecycle {
    private static final Logger log = LoggerFactory.getLogger(Lifecycle.class);
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final int OVERDUE_GRACE_DAYS = 7; // Q-019
    /** Booking cancel reasons that are the dealer's failure: a system dispute (admin review) and a reliability hit. */
    static final Map<String, String> DEALER_FAILURE_CATEGORY = Map.of("DEALER_CANCELLED", "DEALER_CANCELLED", "DEALER_NO_CONFIRM", "DEALER_NOT_HONOURED",
            "STOCK_UNAVAILABLE", "STOCK_UNAVAILABLE");
    private final JdbcTemplate jdbc;
    private final Outbox outbox;
    private final TransactionTemplate tx;
    private final Orders orders;
    private final Disputes disputes;
    private final Vouchers vouchers;

    /** Test-only crash hooks (P4.B crash tests): inside a step's transaction just before it commits, and right after the commit. */
    private volatile Consumer<String> beforeCommit = s -> {};
    private volatile Consumer<String> afterCommit = s -> {};

    /** Set through a method: this bean is a transactional proxy, so a field written from outside would never reach it. */
    void crashHooks(Consumer<String> before, Consumer<String> after) {
        this.beforeCommit = before;
        this.afterCommit = after;
    }

    public Lifecycle(JdbcTemplate jdbc, Outbox outbox, TransactionTemplate tx, Orders orders, Disputes disputes, Vouchers vouchers) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.tx = tx;
        this.orders = orders;
        this.disputes = disputes;
        this.vouchers = vouchers;
    }

    // ------------------------------------------------------------------ the saga

    /** Moves the pool round's saga as far as it can go now; safe to call any number of times, from any instance. */
    public void advance(UUID pool, int roundSet) {
        for (int i = 0; i < 5; i++) {
            String reached = tx.execute(s -> step(pool, roundSet));
            if (reached == null) return;
            afterCommit.accept(reached);
        }
    }

    private String step(UUID pool, int rs) {
        List<String> current = jdbc.queryForList("select step from sagas where pool_id = ? and round_set = ? for update", String.class, pool, rs);
        if (current.isEmpty()) return null;
        String next = switch (current.get(0)) {
            case "FROZEN" -> resolveTiers(pool, rs);
            case "RESOLVED" -> depositsHeld(pool, rs);
            case "DEPOSITS_HELD" -> issueBookings(pool, rs);
            case "BOOKINGS_ISSUED" -> releaseContacts(pool, rs);
            default -> null;
        };
        if (next == null) return null;
        jdbc.update("update sagas set step = ?, updated_at = clock_timestamp() where pool_id = ? and round_set = ?", next, pool, rs);
        beforeCommit.accept(next);
        return next;
    }

    /** Step 1: the one-transaction tier resolution, at the confirmation deadline on the DB clock. */
    private String resolveTiers(UUID pool, int rs) {
        if (!jdbc.queryForObject("select confirmation_deadline <= clock_timestamp() from pool_resolutions where pool_id = ? and round_set = ?", Boolean.class, pool, rs)) return null;
        orders.resolve(pool, rs);
        return "RESOLVED";
    }

    /** Step 2: every booking's deposit is held (DepositHeld), or the booking is compensated (cancelled, refunded) if the hold was rejected. */
    private String depositsHeld(UUID pool, int rs) {
        List<Map<String, Object>> holds = stepStatus(pool, rs, "HOLD_DEPOSIT");
        if (holds.stream().anyMatch(h -> "PENDING".equals(h.get("status")))) return null;
        for (Map<String, Object> h : holds) {
            if ("FAILED".equals(h.get("status"))) cancelBooking((UUID) h.get("booking_id"), "DEPOSIT_UNAVAILABLE", "DEPOSIT_UNAVAILABLE", "SYSTEM", false);
        }
        return "DEPOSITS_HELD";
    }

    /** Step 3: stock reserved for every remaining booking; its voucher becomes valid (StockUnavailable already cancelled the others). */
    private String issueBookings(UUID pool, int rs) {
        if (stepStatus(pool, rs, "RESERVE_STOCK").stream().anyMatch(h -> "PENDING".equals(h.get("status")))) return null;
        jdbc.update("update bookings b set voucher_active = true from orders o where o.id = b.order_id and o.pool_id = ? and o.round_set = ? and b.state <> 'CANCELLED'", pool, rs);
        return "BOOKINGS_ISSUED";
    }

    /** Step 4: each buyer's contact goes to the dealer of the quote they confirmed, and to no one else (consent recorded at confirmation). */
    private String releaseContacts(UUID pool, int rs) {
        jdbc.update("""
                insert into contact_releases(booking_id, order_id, buyer_id, dealership_id)
                select b.id, o.id, o.buyer_id, b.dealership_id from bookings b join orders o on o.id = b.order_id
                join contact_consents c on c.order_id = o.id and c.dealership_id = b.dealership_id
                where o.pool_id = ? and o.round_set = ? and b.state <> 'CANCELLED' on conflict (booking_id) do nothing""", pool, rs);
        return "CONTACTS_RELEASED";
    }

    private List<Map<String, Object>> stepStatus(UUID pool, int rs, String step) {
        return jdbc.queryForList("select b.id as booking_id, s.status from bookings b join orders o on o.id = b.order_id join saga_steps s on s.order_id = o.id and s.step = ? "
                + "where o.pool_id = ? and o.round_set = ? and b.state <> 'CANCELLED'", step, pool, rs);
    }

    public void stepFailed(UUID bookingId, String step, String why) {
        jdbc.update("update saga_steps set status = 'FAILED', last_error = ? where step = ? and status = 'PENDING' and order_id = (select order_id from bookings where id = ?)", why, step, bookingId);
    }

    // ------------------------------------------------------------------ buyer: decline (ORD-T04), cancel (ORD-T03, BKG-T06)

    @Transactional
    public Map<String, Object> decline(UUID orderId) {
        UUID buyer = Caller.current().require("buyer").userId();
        Map<String, Object> o = orders.ownOrder(orderId, buyer, true);
        ORDER.check((String) o.get("state"), "DECLINED");
        long v = ver(jdbc.queryForList("update orders set state = 'DECLINED', decline_reason = 'BUYER_DECLINED', version = version + 1 "
                + "where id = ? and clock_timestamp() < confirmation_deadline returning version", orderId), o);
        outbox.append(orderId, v, new ConfirmationDeclined(orderId.toString(), o.get("pool_id").toString(), "BUYER_DECLINED"));
        outbox.append(orderId, orders.bump(orderId), new DepositRefundRequested(orderId.toString(), o.get("pool_id").toString(), buyer.toString(), "BUYER_DECLINED"));
        return orders.view(orderId, buyer);
    }

    /** E084, ORD-T03: confirmed, not yet booked. Full refund (test mode); counted out of the tier only if before the deadline (tiers.json T23/T24). */
    @Transactional
    public Map<String, Object> cancelOrder(UUID orderId, long ifMatch) {
        UUID buyer = Caller.current().require("buyer").userId();
        Map<String, Object> o = orders.ownOrder(orderId, buyer, true);
        if ((long) o.get("version") != ifMatch) throw ApiProblem.of(ProblemType.VERSION_CONFLICT, "Your order changed; reload it.").with("current_version", o.get("version"));
        if ("BOOKED".equals(o.get("state"))) throw ApiProblem.of(ProblemType.INVALID_TRANSITION, "Your booking is issued: cancel the booking instead.");
        ORDER.check((String) o.get("state"), "CANCELLED");
        long v = jdbc.queryForObject("update orders set state = 'CANCELLED', cancelled_at = clock_timestamp(), cancel_reason = 'BUYER_CANCELLED', version = version + 1 where id = ? returning version",
                Long.class, orderId);
        jdbc.update("update quote_capacity set confirmed_count = confirmed_count - 1 where quote_id = ? and confirmed_count > 0", o.get("quote_id"));
        outbox.append(orderId, v, new OrderCancelled(orderId.toString(), o.get("pool_id").toString(), "BUYER_CANCELLED"));
        outbox.append(orderId, orders.bump(orderId), new DepositRefundRequested(orderId.toString(), o.get("pool_id").toString(), buyer.toString(), "BUYER_CANCELLED"));
        return orders.view(orderId, buyer);
    }

    /** E086, BKG-T06 by the buyer: full refund; nobody else's price changes. */
    @Transactional
    public Map<String, Object> buyerCancelBooking(UUID bookingId, long ifMatch) {
        UUID buyer = Caller.current().require("buyer").userId();
        Map<String, Object> b = lockBooking(bookingId);
        if (!buyer.equals(b.get("buyer_id"))) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such booking.");
        if ((long) b.get("version") != ifMatch) throw ApiProblem.of(ProblemType.VERSION_CONFLICT, "Your booking changed; reload it.").with("current_version", b.get("version"));
        cancelBooking(bookingId, "BUYER_CANCELLED", "BUYER_CANCELLED", "BUYER", true);
        return orders.view((UUID) b.get("order_id"), buyer);
    }

    // ------------------------------------------------------------------ dealer: confirm is in Orders (BKG-T02); schedule, deliver, cancel

    /** E089, BKG-T04: a delivery date from today (IST) on. */
    @Transactional
    public Map<String, Object> scheduleDelivery(UUID bookingId, LocalDate date, String slot, long ifMatch) {
        Caller c = dealer();
        Map<String, Object> b = dealerBooking(bookingId, c);
        if ((long) b.get("version") != ifMatch) throw ApiProblem.of(ProblemType.VERSION_CONFLICT, "The booking changed; reload it.").with("current_version", b.get("version"));
        BOOKING.check((String) b.get("state"), "DELIVERY_SCHEDULED");
        if (date == null || date.isBefore(LocalDate.now(IST))) throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "Choose today or a later date.");
        boolean again = "DELIVERY_SCHEDULED".equals(b.get("state"));
        jdbc.update("update bookings set state = 'DELIVERY_SCHEDULED', scheduled_date = ?, delivery_slot = ?, reschedule_count = reschedule_count + ?, version = version + 1 where id = ?",
                date, slot, again ? 1 : 0, bookingId);
        LocalDate promised = b.get("promised_delivery_date") == null ? null : ((java.sql.Date) b.get("promised_delivery_date")).toLocalDate();
        outbox.append(bookingId, (long) b.get("version") + 1, new DeliveryScheduled(bookingId.toString(), date, promised != null && date.isAfter(promised)));
        return orders.dealerBooking(bookingId);
    }

    /** E090, BKG-T05 by the dealer: the buyer's QR voucher, scanned at handover, proves the buyer is there. */
    @Transactional
    public Map<String, Object> deliverByDealer(UUID bookingId, String voucherToken) {
        Caller c = dealer();
        Map<String, Object> b = dealerBooking(bookingId, c);
        if (!vouchers.valid(voucherToken, bookingId)) throw ApiProblem.of(ProblemType.FORBIDDEN, "That voucher is not valid for this booking.");
        deliver(b);
        return orders.dealerBooking(bookingId);
    }

    /** E154, BKG-T05 by the buyer: "I have my car" releases the deposit (AMENDMENT-003) and posts the dealer's success fee (AMENDMENT-007). */
    @Transactional
    public Map<String, Object> buyerConfirmsDelivery(UUID bookingId) {
        UUID buyer = Caller.current().require("buyer").userId();
        Map<String, Object> b = lockBooking(bookingId);
        if (!buyer.equals(b.get("buyer_id"))) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such booking.");
        deliver(b);
        return orders.view((UUID) b.get("order_id"), buyer);
    }

    private void deliver(Map<String, Object> b) {
        UUID bookingId = (UUID) b.get("id");
        if ("DELIVERED".equals(b.get("state"))) return; // repeat
        BOOKING.check((String) b.get("state"), "DELIVERED");
        ORDER.check("BOOKED", "COMPLETED");
        Timestamp at = jdbc.queryForObject("update bookings set state = 'DELIVERED', delivered_at = clock_timestamp(), version = version + 1 where id = ? returning delivered_at",
                Timestamp.class, bookingId);
        long ov = jdbc.queryForObject("update orders set state = 'COMPLETED', version = version + 1 where id = ? returning version", Long.class, b.get("order_id"));
        outbox.append(bookingId, (long) b.get("version") + 1, new BookingDelivered(bookingId.toString(), b.get("order_id").toString(), b.get("dealership_id").toString(),
                b.get("buyer_id").toString(), at.toInstant()));
        closeIfAllDone((UUID) b.get("pool_id"), (int) b.get("round_set"));
        log.info("booking {} delivered (order version {})", bookingId, ov);
    }

    /** E091, BKG-T06 by the dealer admin: a dealer cancellation (or stock gone) refunds the buyer and opens a dispute for admin review. */
    @Transactional
    public Map<String, Object> dealerCancelBooking(UUID bookingId, String reason, long ifMatch) {
        Caller c = Caller.current().require("dealer_admin");
        Map<String, Object> b = dealerBooking(bookingId, c);
        if (!Set.of("DEALER_CANCELLED", "STOCK_UNAVAILABLE").contains(reason)) throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "reason must be DEALER_CANCELLED or STOCK_UNAVAILABLE.");
        if ((long) b.get("version") != ifMatch) throw ApiProblem.of(ProblemType.VERSION_CONFLICT, "The booking changed; reload it.").with("current_version", b.get("version"));
        cancelBooking(bookingId, reason, reason, "DEALER:" + c.userId(), true);
        return orders.dealerBooking(bookingId);
    }

    // ------------------------------------------------------------------ system-driven cancellations

    /** ORD-T10 (+ BKG-T06 for booked orders): a quote withdrawn after FINAL is a dealer cancellation for every buyer who accepted it (Q-023). */
    @Transactional
    public void quoteWithdrawnAfterFinal(UUID quoteId) {
        for (Map<String, Object> o : jdbc.queryForList("select o.id, o.pool_id, o.buyer_id, o.state, o.version, q.dealership_id from orders o join quote_replica q on q.quote_id = o.quote_id "
                + "where o.quote_id = ? and o.state in ('CONFIRMATION_PENDING', 'CONFIRMED') for update of o", quoteId)) {
            ORDER.check((String) o.get("state"), "CANCELLED");
            UUID orderId = (UUID) o.get("id");
            long v = jdbc.queryForObject("update orders set state = 'CANCELLED', cancelled_at = clock_timestamp(), cancel_reason = 'DEALER_CANCELLED', version = version + 1 where id = ? returning version",
                    Long.class, orderId);
            outbox.append(orderId, v, new OrderCancelled(orderId.toString(), o.get("pool_id").toString(), "DEALER_CANCELLED"));
            outbox.append(orderId, orders.bump(orderId), new DepositRefundRequested(orderId.toString(), o.get("pool_id").toString(), o.get("buyer_id").toString(), "DEALER_CANCELLED"));
            disputes.openBySystem(orderId, null, (UUID) o.get("dealership_id"), (UUID) o.get("buyer_id"), "DEALER_CANCELLED", "The dealer withdrew its final offer.");
        }
        for (UUID booking : jdbc.queryForList("select b.id from bookings b join orders o on o.id = b.order_id where o.quote_id = ? and b.state in ('ISSUED', 'DEALER_CONFIRMED', 'DELIVERY_SCHEDULED')",
                UUID.class, quoteId)) {
            cancelBooking(booking, "DEALER_CANCELLED", "DEALER_CANCELLED", "SYSTEM", false);
        }
    }

    /** BKG-T03: the reservation failed (StockUnavailable from quote). */
    @Transactional
    public void stockUnavailable(UUID bookingId) {
        stepFailed(bookingId, "RESERVE_STOCK", "StockUnavailable");
        Map<String, Object> b = lockBooking(bookingId);
        if (Set.of("ISSUED", "DEALER_CONFIRMED", "DELIVERY_SCHEDULED").contains(b.get("state"))) cancelBooking(bookingId, "STOCK_UNAVAILABLE", "STOCK_UNAVAILABLE", "SYSTEM", false);
    }

    /** Sweeper: BKG-T03 at the 48 h dealer confirmation deadline (Q-022), and BKG-T07/T08 overdue flags (Q-019), on the DB clock. */
    void sweep() {
        for (UUID id : jdbc.queryForList("select id from bookings where state = 'ISSUED' and dealer_confirm_deadline <= clock_timestamp()", UUID.class)) {
            guarded(() -> tx.executeWithoutResult(s -> {
                Map<String, Object> b = lockBooking(id);
                if ("ISSUED".equals(b.get("state"))) cancelBooking(id, "DEALER_NO_CONFIRM", "DEALER_NOT_HONOURED", "SYSTEM", false);
            }));
        }
        for (Map<String, Object> r : jdbc.queryForList("select id, version, promised_delivery_date from bookings where state in ('DEALER_CONFIRMED', 'DELIVERY_SCHEDULED') "
                + "and not overdue_flag and promised_delivery_date + ? < (clock_timestamp() at time zone 'Asia/Kolkata')::date", OVERDUE_GRACE_DAYS)) {
            guarded(() -> tx.executeWithoutResult(s -> {
                if (jdbc.update("update bookings set overdue_flag = true, version = version + 1 where id = ? and not overdue_flag", r.get("id")) == 0) return;
                outbox.append((UUID) r.get("id"), (long) r.get("version") + 1, new BookingOverdue(r.get("id").toString(), ((java.sql.Date) r.get("promised_delivery_date")).toLocalDate()));
            }));
        }
        guarded(() -> tx.executeWithoutResult(s -> disputes.sweep())); // its row locks need a transaction
    }

    private static void guarded(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.warn("order lifecycle sweep step deferred: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ the one cancellation path

    /**
     * BKG-T03/BKG-T06 with ORD-T09 in the caller's transaction: booking and order CANCELLED, a full refund, the dealer notified
     * (BookingCancelled also drives the reliability score and releases the reserved stock), and for a dealer-side failure a system
     * dispute for admin review.
     */
    void cancelBooking(UUID bookingId, String bookingReason, String refundReason, String actor, boolean byParty) {
        Map<String, Object> b = lockBooking(bookingId);
        if ("CANCELLED".equals(b.get("state"))) return; // repeat
        if ("DELIVERED".equals(b.get("state"))) throw ApiProblem.of(ProblemType.INVALID_TRANSITION, "A delivered booking cannot be cancelled; open a dispute instead.");
        BOOKING.check((String) b.get("state"), "CANCELLED");
        ORDER.check("BOOKED", "CANCELLED");
        jdbc.update("update bookings set state = 'CANCELLED', cancel_reason = ?, cancelled_at = clock_timestamp(), cancelled_by = ?, voucher_active = false, version = version + 1 where id = ?",
                bookingReason, actor, bookingId);
        UUID orderId = (UUID) b.get("order_id"), pool = (UUID) b.get("pool_id");
        long ov = jdbc.queryForObject("update orders set state = 'CANCELLED', cancelled_at = clock_timestamp(), cancel_reason = ?, version = version + 1 where id = ? returning version",
                Long.class, refundReason, orderId);
        outbox.append(bookingId, (long) b.get("version") + 1, new BookingCancelled(bookingId.toString(), orderId.toString(), b.get("dealership_id").toString(), bookingReason));
        outbox.append(orderId, ov, new OrderCancelled(orderId.toString(), pool.toString(), refundReason));
        outbox.append(orderId, orders.bump(orderId), new DepositRefundRequested(orderId.toString(), pool.toString(), b.get("buyer_id").toString(), refundReason));
        String category = DEALER_FAILURE_CATEGORY.get(bookingReason);
        if (category != null) {
            disputes.openBySystem(orderId, bookingId, (UUID) b.get("dealership_id"), (UUID) b.get("buyer_id"), category, "Opened automatically: booking cancelled (" + bookingReason + ").");
        }
        closeIfAllDone(pool, (int) b.get("round_set"));
        log.info("booking {} cancelled: {} by {} (refund {})", bookingId, bookingReason, actor, refundReason);
    }

    /** AllBookingsClosed once every booking of the pool round is delivered or cancelled (pool POOL-T14). */
    private void closeIfAllDone(UUID pool, int rs) {
        Map<String, Object> c = jdbc.queryForMap("select count(*) filter (where b.state = 'DELIVERED') as delivered, count(*) filter (where b.state = 'CANCELLED') as cancelled, "
                + "count(*) filter (where b.state not in ('DELIVERED', 'CANCELLED')) as open from bookings b join orders o on o.id = b.order_id where o.pool_id = ? and o.round_set = ?", pool, rs);
        if (((Number) c.get("open")).longValue() > 0) return;
        List<Long> v = jdbc.queryForList("update pool_resolutions set version = version + 1, all_closed_at = clock_timestamp() where pool_id = ? and round_set = ? and all_closed_at is null "
                + "and state = 'DONE' returning version", Long.class, pool, rs);
        if (v.isEmpty()) return;
        outbox.append(pool, v.get(0) * 1000, new AllBookingsClosed(pool.toString(), ((Number) c.get("delivered")).longValue(), ((Number) c.get("cancelled")).longValue()));
    }

    // ------------------------------------------------------------------ contact release (dealer side)

    /** E155: the buyer's contact, only for this dealership's own released booking (purpose-limited, threat-model.md order/I). */
    public UUID releasedBuyer(UUID bookingId) {
        Caller c = dealer();
        List<UUID> rs = jdbc.queryForList("select buyer_id from contact_releases where booking_id = ? and dealership_id = ?", UUID.class, bookingId, UUID.fromString(c.dealershipId()));
        if (rs.isEmpty()) throw ApiProblem.of(ProblemType.NOT_FOUND, "No released contact for this booking.");
        return rs.get(0);
    }

    // ------------------------------------------------------------------ helpers

    Map<String, Object> lockBooking(UUID bookingId) {
        List<Map<String, Object>> rs = jdbc.queryForList("select b.*, o.pool_id, o.round_set, o.buyer_id from bookings b join orders o on o.id = b.order_id where b.id = ? for update of b, o", bookingId);
        if (rs.isEmpty()) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such booking.");
        return rs.get(0);
    }

    private Map<String, Object> dealerBooking(UUID bookingId, Caller c) {
        Map<String, Object> b = lockBooking(bookingId);
        if (c.dealershipId() == null || !b.get("dealership_id").toString().equals(c.dealershipId())) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such booking.");
        return b;
    }

    private static Caller dealer() {
        Caller c = Caller.current().require("dealer_staff", "dealer_admin");
        if (c.dealershipId() == null) throw ApiProblem.of(ProblemType.FORBIDDEN, "Your account is not linked to a dealership.");
        return c;
    }

    private long ver(List<Map<String, Object>> updated, Map<String, Object> o) {
        if (updated.isEmpty()) {
            throw ApiProblem.of(ProblemType.DEADLINE_PASSED, "The confirmation deadline has passed; you will be refunded automatically.")
                    .with("deadline", ((Timestamp) o.get("confirmation_deadline")).toInstant().toString());
        }
        return ((Number) updated.get(0).get("version")).longValue();
    }
}
