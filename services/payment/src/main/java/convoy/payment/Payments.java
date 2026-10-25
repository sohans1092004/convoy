package convoy.payment;

import convoy.contracts.events.DepositHeld;
import convoy.contracts.events.DepositReleased;
import convoy.contracts.events.PaymentVoided;
import convoy.contracts.events.RefundFailed;
import convoy.contracts.events.PaymentCaptured;
import convoy.contracts.events.PaymentFailed;
import convoy.contracts.events.RefundCompleted;
import convoy.contracts.events.RefundRequested;
import convoy.platform.messaging.Outbox;
import convoy.platform.messaging.Uuid7;
import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.statemachine.TransitionTable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payment and refund machines (payment.yaml, refund.yaml). P1 builds the journey's transitions; every
 * other (from, to) pair is rejected by the transition table (invalid-transition or 501).
 */
@Service
public class Payments {
    private static final Logger log = LoggerFactory.getLogger(Payments.class);
    public static final TransitionTable PAYMENT = TransitionTable.load("payment",
            Set.of("PAY-T01", "PAY-T02", "PAY-T03", "PAY-T04", "PAY-T05", "PAY-T06", "PAY-T07", "PAY-T08", "PAY-T09", "PAY-T10", "PAY-T11"));
    public static final TransitionTable REFUND = TransitionTable.load("refund", Set.of("REF-T01", "REF-T02", "REF-T03", "REF-T04", "REF-T05"));

    public record Payment(UUID id, String depositKey, UUID buyerId, UUID poolId, int roundSet, long amountPaise, String state,
                          String razorpayOrderId, String razorpayPaymentId, UUID bookingId, long version) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RazorpayClient razorpay;
    private final Ledger ledger;
    private final Outbox outbox;
    private final PaymentApplication.PaymentSettings settings;

    public Payments(JdbcTemplate jdbc, TransactionTemplate tx, RazorpayClient razorpay, Ledger ledger, Outbox outbox, PaymentApplication.PaymentSettings settings) {
        this.settings = settings;
        this.jdbc = jdbc;
        this.tx = tx;
        this.razorpay = razorpay;
        this.ledger = ledger;
        this.outbox = outbox;
    }

    static final String COLS = "id, deposit_key, buyer_id, pool_id, round_set, amount_paise, state, razorpay_order_id, razorpay_payment_id, booking_id, version";

    private Optional<Payment> one(String where, Object... args) {
        return jdbc.query("select " + COLS + " from payments where " + where, (rs, i) -> new Payment(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getInt(5), rs.getLong(6), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getObject(10, UUID.class), rs.getLong(11)), args).stream().findFirst();
    }

    public Optional<Payment> find(UUID id) { return one("id = ?", id); }

    /** Move state with an optimistic version check; returns the new version. */
    private long move(Payment p, String to, String extraSet, Object... extraArgs) {
        PAYMENT.check(p.state(), to);
        Object[] args = new Object[extraArgs.length + 3];
        args[0] = to;
        System.arraycopy(extraArgs, 0, args, 1, extraArgs.length);
        args[extraArgs.length + 1] = p.id();
        args[extraArgs.length + 2] = p.version();
        int n = jdbc.update("update payments set state = ?, version = version + 1" + (extraSet.isEmpty() ? "" : ", " + extraSet)
                + " where id = ? and version = ?", args);
        if (n == 0) throw ApiProblem.of(ProblemType.VERSION_CONFLICT, "Payment changed concurrently.");
        return p.version() + 1;
    }

    // ------------------------------------------------------------------ PAY-T01

    /** E110: same deterministic key -> same row -> same Razorpay order (a double click yields one order). */
    public Api.PaymentView createDeposit(UUID buyer, UUID pool, int roundSet, long amountPaise) {
        String key = "deposit:" + buyer + ":" + pool + ":" + roundSet;
        Payment p = one("deposit_key = ?", key).orElseGet(() -> {
            PAYMENT.check("(none)", "INITIATED");
            UUID id = Uuid7.next();
            jdbc.update("insert into payments(id, deposit_key, buyer_id, pool_id, round_set, amount_paise, state) values (?, ?, ?, ?, ?, ?, 'INITIATED') "
                    + "on conflict (deposit_key) do nothing", id, key, buyer, pool, roundSet, amountPaise);
            return one("deposit_key = ?", key).orElseThrow();
        });
        if (p.razorpayOrderId() == null) {
            // EXTERNAL_CALL_THEN_COMMIT: the row is committed first; at most one order id is ever stored.
            RazorpayClient.Order order = razorpay.createOrder(p.amountPaise(), p.id().toString(),
                    Map.of("pool_id", pool.toString(), "buyer_id", buyer.toString()));
            jdbc.update("update payments set razorpay_order_id = ? where id = ? and razorpay_order_id is null", order.id(), p.id());
            p = find(p.id()).orElseThrow();
        }
        return view(p);
    }

    public Api.PaymentView view(Payment p) {
        return new Api.PaymentView(p.id().toString(), p.state(), p.amountPaise(), "INR", p.razorpayOrderId(), razorpay.keyId(), true);
    }

    // ------------------------------------------------------------------ PAY-T02 (browser callback)

    @Transactional
    public Api.PaymentView checkoutCallback(UUID paymentId, UUID buyer, String orderId, String rpPaymentId, String signature) {
        Payment p = one("id = ? and buyer_id = ? for update", paymentId, buyer)
                .orElseThrow(() -> ApiProblem.of(ProblemType.NOT_FOUND, "No such payment."));
        if (!orderId.equals(p.razorpayOrderId()) || !razorpay.checkoutSignatureValid(orderId, rpPaymentId, signature)) {
            throw ApiProblem.of(ProblemType.WEBHOOK_SIGNATURE_INVALID, "The checkout signature did not verify.");
        }
        // Only the signed webhook sets CAPTURED; the callback moves INITIATED to PENDING ("confirming").
        if (p.state().equals("INITIATED")) move(p, "PENDING", "razorpay_payment_id = ?", rpPaymentId);
        return view(find(paymentId).orElseThrow());
    }

    // ------------------------------------------------------------------ webhooks (PAY-T02/T03/T04, REF-T03)

    /** Returns false for a duplicate event id (still 200 to Razorpay). Signature is checked by the caller. */
    @SuppressWarnings("unchecked")
    @Transactional
    public boolean webhook(String eventId, Map<String, Object> body) {
        String event = (String) body.get("event");
        if (jdbc.update("insert into webhook_inbox(event_id, event) values (?, ?) on conflict do nothing", eventId, event) == 0) return false;
        Map<String, Object> payload = (Map<String, Object>) body.get("payload");
        switch (event) {
            case "payment.authorized", "payment.captured", "payment.failed" -> {
                Map<String, Object> e = (Map<String, Object>) ((Map<String, Object>) payload.get("payment")).get("entity");
                Optional<Payment> found = one("razorpay_order_id = ? for update", e.get("order_id"));
                if (found.isEmpty()) {
                    log.warn("webhook {} for unknown order {}", event, e.get("order_id"));
                    return true;
                }
                Payment p = found.get();
                String rpPaymentId = (String) e.get("id");
                if (event.equals("payment.authorized") && p.state().equals("INITIATED")) move(p, "PENDING", "razorpay_payment_id = ?", rpPaymentId);
                if (event.equals("payment.captured")) captured(p, rpPaymentId, ((Number) e.get("amount")).longValue(), (String) e.get("currency"));
                if (event.equals("payment.failed")) {
                    if (Set.of("INITIATED", "PENDING").contains(p.state())) {
                        long v = move(p, "FAILED", "failure_code = ?", String.valueOf(e.get("error_code")));
                        outbox.append(p.id(), v, new PaymentFailed(p.id().toString(), p.buyerId().toString(), p.poolId().toString(), String.valueOf(e.get("error_code"))));
                    } else {
                        log.info("stale payment.failed for {} in {}", p.id(), p.state());
                    }
                }
            }
            case "refund.processed" -> {
                Map<String, Object> e = (Map<String, Object>) ((Map<String, Object>) payload.get("refund")).get("entity");
                refundProcessed((String) e.get("id"), (String) e.get("receipt"));
            }
            case "refund.failed" -> {
                Map<String, Object> e = (Map<String, Object>) ((Map<String, Object>) payload.get("refund")).get("entity");
                refundFailed((String) e.get("id"), (String) e.get("receipt"), "Razorpay reported refund.failed (status " + e.get("status") + ")");
            }
            default -> log.info("webhook {} not handled in P1", event);
        }
        return true;
    }

    private void captured(Payment p, String rpPaymentId, long amount, String currency) {
        if (amount != p.amountPaise() || !"INR".equals(currency)) {
            log.error("capture amount/currency mismatch on {}: {} {}", p.id(), amount, currency);
            return; // listed by reconciliation (AMOUNT_MISMATCH); never captured
        }
        if (p.state().equals("VOIDED_UNPAID")) {
            lateCapture(p, rpPaymentId, amount);
            return;
        }
        if (!Set.of("INITIATED", "PENDING", "FAILED").contains(p.state())) {
            log.info("stale payment.captured for {} in {}", p.id(), p.state());
            return;
        }
        long v = move(p, "CAPTURED", "razorpay_payment_id = ?, captured_at = clock_timestamp()", rpPaymentId);
        ledger.capture(p.id(), amount);
        outbox.append(p.id(), v, new PaymentCaptured(p.id().toString(), p.buyerId().toString(), p.poolId().toString(), amount, false, true));
    }

    /** PAY-T10: money arrived for a deposit we had already voided (pool closed, buyer left): refund it in full, automatically. */
    private void lateCapture(Payment p, String rpPaymentId, long amount) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select refund_on_capture from payments where id = ?", Boolean.class, p.id()))) {
            log.error("ALARM capture on voided payment {} without refund_on_capture", p.id());
            return;
        }
        PAYMENT.check("VOIDED_UNPAID", "REFUND_PENDING");
        REFUND.check("(none)", "REFUND_REQUESTED");
        long v = move(p, "REFUND_PENDING", "razorpay_payment_id = ?, captured_at = clock_timestamp()", rpPaymentId);
        ledger.capture(p.id(), amount);
        UUID refundId = Uuid7.next();
        jdbc.update("insert into refunds(id, payment_id, kind, reason_code, amount_paise, source_account, state) values (?, ?, 'REFUND', 'LATE_CAPTURE', ?, ?, 'REFUND_REQUESTED')",
                refundId, p.id(), amount, Ledger.HELD);
        outbox.append(p.id(), v, new PaymentCaptured(p.id().toString(), p.buyerId().toString(), p.poolId().toString(), amount, true, true));
        outbox.append(p.id(), bump(p.id()), new RefundRequested(refundId.toString(), p.id().toString(), p.buyerId().toString(), "LATE_CAPTURE", amount));
        log.info("late capture on voided payment {}: full refund requested (LATE_CAPTURE)", p.id());
    }

    /** A second event in the same transaction gets its own aggregate version. */
    private long bump(UUID paymentId) {
        return jdbc.queryForObject("update payments set version = version + 1 where id = ? returning version", Long.class, paymentId);
    }

    // ------------------------------------------------------------------ PAY-T05 void unpaid

    /** PAY-T05: an unpaid deposit is voided; if Razorpay captures it later, PAY-T10 refunds it automatically. */
    void voidUnpaid(Payment p) {
        long v = move(p, "VOIDED_UNPAID", "refund_on_capture = true");
        outbox.append(p.id(), v, new PaymentVoided(p.id().toString(), p.buyerId().toString(), p.poolId().toString()));
    }

    /** Unpaid deposits older than the configured limit (payment.void-unpaid-after, 24 h) are voided by the sweeper. */
    public void voidStaleUnpaid() {
        for (UUID id : jdbc.queryForList("select id from payments where state in ('INITIATED', 'PENDING', 'FAILED') and created_at < clock_timestamp() - ?::interval limit 100",
                UUID.class, settings.voidUnpaidAfter().toMillis() + " milliseconds")) {
            tx.executeWithoutResult(s -> one("id = ? for update", id).filter(p -> Set.of("INITIATED", "PENDING", "FAILED").contains(p.state())).ifPresent(this::voidUnpaid));
        }
    }

    /** PoolClosed: every deposit of the pool is settled with the pool's end reason (refund if captured, void if unpaid). */
    public void poolClosed(UUID pool, String endState, String endReason) {
        // deposit-contract.md §3: POOL_EXPIRED / POOL_NO_QUOTES / POOL_CANCELLED by end state; every quote VOID without a re-round is QUOTE_VOID (D27).
        String reason = "ALL_QUOTES_VOID".equals(endReason) ? "QUOTE_VOID" : "POOL_" + endState;
        for (UUID buyer : jdbc.queryForList("select distinct buyer_id from payments where pool_id = ?", UUID.class, pool)) requestRefund(buyer, pool, reason);
    }

    // ------------------------------------------------------------------ PAY-T06 hold for booking

    public void hold(UUID buyer, UUID pool, UUID bookingId) {
        Payment p = one("buyer_id = ? and pool_id = ? and state in ('CAPTURED', 'HELD_FOR_BOOKING', 'REFUND_PENDING') order by created_at desc limit 1 for update", buyer, pool)
                .orElseThrow(() -> ApiProblem.of(ProblemType.DEPOSIT_NOT_CAPTURED, "No captured deposit for buyer " + buyer + " in pool " + pool + "."));
        if (p.state().equals("HELD_FOR_BOOKING")) return;
        long v = move(p, "HELD_FOR_BOOKING", "booking_id = ?", bookingId);
        ledger.hold(p.id(), p.amountPaise());
        outbox.append(p.id(), v, new DepositHeld(p.id().toString(), bookingId.toString()));
    }

    // ------------------------------------------------------------------ PAY-T09 delivery: release the deposit, post the success fee

    /** The deposit is refunded in full after delivery (AMENDMENT-003); the fee is the dealer's receivable (AMENDMENT-007). */
    public void bookingDelivered(UUID bookingId) {
        Optional<Payment> found = one("booking_id = ? for update", bookingId);
        if (found.isEmpty()) {
            log.error("ALARM BookingDelivered for booking {} with no held deposit", bookingId);
            return;
        }
        Payment p = found.get();
        if (!p.state().equals("HELD_FOR_BOOKING") || jdbc.queryForObject("select count(*) from refunds where payment_id = ?", Integer.class, p.id()) > 0) {
            log.error("ALARM BookingDelivered for booking {} but payment {} is {} with a refund already: the existing refund wins", bookingId, p.id(), p.state());
            return;
        }
        requestRefund(p, "DELIVERY_RELEASE", "RELEASE");
        if (settings.successFeePaise() > 0) ledger.successFee(p.id(), settings.successFeePaise());
    }

    // ------------------------------------------------------------------ PAY-T07 + REF-T01

    /** Request the (full) refund of a buyer's deposit in a pool; no-op if none is held or a refund exists. */
    public void requestRefund(UUID buyer, UUID pool, String reason) {
        List<Payment> ps = jdbc.query("select " + COLS + " from payments where buyer_id = ? and pool_id = ? and state in ('CAPTURED', 'HELD_FOR_BOOKING', 'INITIATED', 'PENDING', 'FAILED') for update",
                (rs, i) -> find(rs.getObject(1, UUID.class)).orElseThrow(), buyer, pool);
        for (Payment p : ps) {
            if (Set.of("CAPTURED", "HELD_FOR_BOOKING").contains(p.state())) requestRefund(p, reason, "REFUND");
            else voidUnpaid(p); // PAY-T05: nothing was paid; a late capture is refunded by PAY-T10
        }
        if (ps.isEmpty()) log.info("refund {} for buyer {} pool {}: no open deposit", reason, buyer, pool);
    }

    void requestRefund(Payment p, String reason, String kind) {
        if (jdbc.queryForObject("select count(*) from refunds where payment_id = ?", Integer.class, p.id()) > 0) {
            jdbc.update("update refunds set related_reasons = array_append(related_reasons, ?) where payment_id = ?", reason, p.id());
            return;
        }
        REFUND.check("(none)", "REFUND_REQUESTED");
        UUID refundId = Uuid7.next();
        String source = p.state().equals("HELD_FOR_BOOKING") ? Ledger.HELD_FOR_BOOKING : Ledger.HELD;
        jdbc.update("insert into refunds(id, payment_id, kind, reason_code, amount_paise, source_account, state) values (?, ?, ?, ?, ?, ?, 'REFUND_REQUESTED')",
                refundId, p.id(), kind, reason, p.amountPaise(), source);
        long v = move(p, "REFUND_PENDING", "");
        outbox.append(p.id(), v, new RefundRequested(refundId.toString(), p.id().toString(), p.buyerId().toString(), reason, p.amountPaise()));
    }

    // ------------------------------------------------------------------ REF-T02 worker, REF-T03 + PAY-T08

    /** Calls Razorpay for due refunds; lookup by receipt before create so a crash never refunds twice. */
    public void refundWorker() {
        List<UUID> due = jdbc.queryForList("select id from refunds where state in ('REFUND_REQUESTED', 'REFUND_FAILED') and next_attempt_at <= clock_timestamp() "
                + "and attempts < ? limit 10", UUID.class, settings.refundRetrySchedule().size());
        for (UUID refundId : due) attempt(refundId);
    }

    /** One REF-T02 attempt. Retryable failures follow the schedule; a 4xx from Razorpay will not fix itself, so it stops retrying. */
    void attempt(UUID refundId) {
        try {
            tx.executeWithoutResult(s -> initiate(refundId));
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.warn("refund {} rejected by Razorpay: {}", refundId, e.getStatusCode());
            jdbc.update("update refunds set attempts = ?, failure_reason = ? where id = ?", settings.refundRetrySchedule().size(),
                    "Razorpay rejected the refund (" + e.getStatusCode().value() + "): " + e.getResponseBodyAsString(), refundId);
            stuck(refundId);
        } catch (RuntimeException e) {
            log.warn("refund {} attempt failed: {}", refundId, e.toString());
            int attempts = jdbc.queryForObject("update refunds set attempts = attempts + 1, failure_reason = ? where id = ? returning attempts", Integer.class,
                    e.getMessage(), refundId);
            schedule(refundId, attempts);
        }
    }

    /** After failed attempt n, next_attempt_at = schedule[n-1] (refund.yaml: 1 min, 5 min, 30 min, 2 h, 12 h); the stuck alarm when it runs out. */
    private void schedule(UUID refundId, int attempts) {
        List<java.time.Duration> s = settings.refundRetrySchedule();
        if (attempts >= s.size()) {
            stuck(refundId);
            return;
        }
        jdbc.update("update refunds set next_attempt_at = clock_timestamp() + ?::interval where id = ?", s.get(attempts - 1).toMillis() + " milliseconds", refundId);
    }

    private void stuck(UUID refundId) {
        log.error("ALARM REFUND_STUCK refund {}: automatic retries exhausted; it is in the admin refunds queue (GET /admin/refunds)", refundId);
    }

    // ------------------------------------------------------------------ REF-T04 refund.failed

    private void refundFailed(String razorpayRefundId, String receipt, String reason) {
        List<Map<String, Object>> rows = jdbc.queryForList("select id, payment_id, state, attempts from refunds where razorpay_refund_id = ? or id::text = ? for update",
                razorpayRefundId, receipt);
        if (rows.isEmpty() || !"REFUND_INITIATED".equals(rows.get(0).get("state"))) {
            log.info("stale refund.failed for {} ({})", razorpayRefundId, rows.isEmpty() ? "unknown" : rows.get(0).get("state"));
            return;
        }
        Map<String, Object> r = rows.get(0);
        REFUND.check("REFUND_INITIATED", "REFUND_FAILED");
        UUID refundId = (UUID) r.get("id"), paymentId = (UUID) r.get("payment_id");
        int attempts = (int) r.get("attempts");
        jdbc.update("update refunds set state = 'REFUND_FAILED', failure_reason = ?, version = version + 1 where id = ?", reason, refundId);
        schedule(refundId, attempts);
        outbox.append(paymentId, bump(paymentId), new RefundFailed(refundId.toString(), paymentId.toString(), (long) attempts, reason));
    }

    // ------------------------------------------------------------------ admin refunds queue (E114, E115)

    /** Refunds that need a person: failed at Razorpay, or out of automatic attempts. */
    public List<Map<String, Object>> adminQueue() {
        return jdbc.queryForList("select r.id::text as id, r.payment_id::text as payment_id, p.buyer_id::text as buyer_id, p.pool_id::text as pool_id, r.kind, r.reason_code, "
                + "r.amount_paise, r.state, r.attempts, r.failure_reason, r.next_attempt_at, r.created_at from refunds r join payments p on p.id = r.payment_id "
                + "where r.state in ('REFUND_REQUESTED', 'REFUND_FAILED') and (r.state = 'REFUND_FAILED' or r.attempts >= ?) order by r.created_at",
                settings.refundRetrySchedule().size());
    }

    /** E115: an admin retries now (REF-T02 without the schedule or attempt limit). */
    public Map<String, Object> adminRetry(UUID refundId) {
        String state = jdbc.queryForList("select state from refunds where id = ?", String.class, refundId).stream().findFirst()
                .orElseThrow(() -> ApiProblem.of(ProblemType.NOT_FOUND, "No such refund."));
        REFUND.check(state, "REFUND_INITIATED");
        attempt(refundId);
        return jdbc.queryForMap("select id::text as id, state, attempts, failure_reason, razorpay_refund_id from refunds where id = ?", refundId);
    }

    private void initiate(UUID refundId) {
        Map<String, Object> r = jdbc.queryForMap("select r.state, r.amount_paise, p.razorpay_payment_id from refunds r join payments p on p.id = r.payment_id "
                + "where r.id = ? for update of r skip locked", refundId);
        REFUND.check((String) r.get("state"), "REFUND_INITIATED");
        String rpPayment = (String) r.get("razorpay_payment_id");
        String receipt = refundId.toString();
        RazorpayClient.Refund rf = razorpay.refunds(rpPayment).stream().filter(x -> receipt.equals(x.receipt())).findFirst()
                .orElseGet(() -> razorpay.createRefund(rpPayment, ((Number) r.get("amount_paise")).longValue(), receipt));
        jdbc.update("update refunds set state = 'REFUND_INITIATED', razorpay_refund_id = ?, attempts = attempts + 1, version = version + 1 where id = ?", rf.id(), refundId);
    }

    private void refundProcessed(String razorpayRefundId, String receipt) {
        List<Map<String, Object>> rows = jdbc.queryForList("select id, payment_id, kind, source_account, amount_paise, state from refunds "
                + "where razorpay_refund_id = ? or id::text = ? for update", razorpayRefundId, receipt);
        if (rows.isEmpty()) {
            log.warn("refund.processed for unknown refund {}", razorpayRefundId);
            return;
        }
        Map<String, Object> r = rows.get(0);
        if ("REFUNDED".equals(r.get("state"))) return;
        REFUND.check((String) r.get("state"), "REFUNDED");
        UUID refundId = (UUID) r.get("id"), paymentId = (UUID) r.get("payment_id");
        String kind = (String) r.get("kind");
        jdbc.update("update refunds set state = 'REFUNDED', razorpay_refund_id = coalesce(razorpay_refund_id, ?), processed_at = clock_timestamp(), version = version + 1 where id = ?",
                razorpayRefundId, refundId);
        Payment p = find(paymentId).orElseThrow();
        long v = move(p, "RELEASE".equals(kind) ? "RELEASED" : "REFUNDED", "");
        ledger.refund(paymentId, kind, (String) r.get("source_account"), ((Number) r.get("amount_paise")).longValue());
        outbox.append(paymentId, v, new RefundCompleted(refundId.toString(), paymentId.toString(), p.buyerId().toString(), false));
        if ("RELEASE".equals(kind)) outbox.append(paymentId, bump(paymentId), new DepositReleased(paymentId.toString(), String.valueOf(p.bookingId()))); // PAY-T11
    }
}
