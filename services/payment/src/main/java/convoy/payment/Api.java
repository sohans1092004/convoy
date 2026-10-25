package convoy.payment;

import org.springframework.lang.Nullable;
/** Response records for every payment endpoint. */
public final class Api {
    private Api() {}

    /** razorpay_order_id is null until the Razorpay order exists. */
    public record PaymentView(String paymentId, String state, long amountPaise, String currency, @Nullable String razorpayOrderId, String razorpayKeyId, boolean testMode) {}

    public record WebhookAck(String status) {}

    public record QueuedRefund(String id, String paymentId, String buyerId, String poolId, String kind, String reasonCode, long amountPaise, String state,
                               int attempts, @org.springframework.lang.Nullable String failureReason, String nextAttemptAt, String createdAt,
                               @org.springframework.lang.Nullable String requestedBy) {}

    public record RefundQueue(java.util.List<QueuedRefund> refunds) {}

    public record ManualRefund(String id, String state, boolean manual, @org.springframework.lang.Nullable String requestedBy,
                               @org.springframework.lang.Nullable String approvedBy, @org.springframework.lang.Nullable String evidenceRef) {}

    public record Mismatch(String mismatchClass, @org.springframework.lang.Nullable String paymentId, String detail) {}

    public record ReconciliationRun(String id, String startedAt, @org.springframework.lang.Nullable String finishedAt, int checked, int mismatchCount,
                                    java.util.List<Mismatch> mismatches) {}

    public record ReconciliationRuns(java.util.List<ReconciliationRun> runs) {}

    public record RefundAttempt(String id, String state, int attempts, @org.springframework.lang.Nullable String failureReason,
                                @org.springframework.lang.Nullable String razorpayRefundId) {}
}
