package convoy.payment;

import org.springframework.lang.Nullable;
/** Response records for every payment endpoint. */
public final class Api {
    private Api() {}

    /** razorpay_order_id is null until the Razorpay order exists. */
    public record PaymentView(String paymentId, String state, long amountPaise, String currency, @Nullable String razorpayOrderId, String razorpayKeyId, boolean testMode) {}

    public record WebhookAck(String status) {}

    public record QueuedRefund(String id, String paymentId, String buyerId, String poolId, String kind, String reasonCode, long amountPaise, String state,
                               int attempts, @org.springframework.lang.Nullable String failureReason, String nextAttemptAt, String createdAt) {}

    public record RefundQueue(java.util.List<QueuedRefund> refunds) {}

    public record RefundAttempt(String id, String state, int attempts, @org.springframework.lang.Nullable String failureReason,
                                @org.springframework.lang.Nullable String razorpayRefundId) {}
}
