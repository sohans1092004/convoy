package convoy.payment;

import org.springframework.lang.Nullable;
/** Response records for every payment endpoint. */
public final class Api {
    private Api() {}

    /** razorpay_order_id is null until the Razorpay order exists. */
    public record PaymentView(String paymentId, String state, long amountPaise, String currency, @Nullable String razorpayOrderId, String razorpayKeyId, boolean testMode) {}

    public record WebhookAck(String status) {}
}
