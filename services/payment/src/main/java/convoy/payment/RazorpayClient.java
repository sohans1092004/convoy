package convoy.payment;

import java.util.List;
import java.util.Map;

/**
 * The slice of the Razorpay API that payment uses (test mode only). Implemented over HTTP against either
 * real Razorpay test mode or the local fake (tools/razorpay-fake), whose contract test pins the same rules.
 */
public interface RazorpayClient {
    record Order(String id, long amount, String currency, String receipt, String status) {}

    record Refund(String id, String paymentId, long amount, String receipt, String status) {}

    Order createOrder(long amountPaise, String receipt, Map<String, String> notes);

    Refund createRefund(String razorpayPaymentId, long amountPaise, String receipt);

    List<Refund> refunds(String razorpayPaymentId);

    /** Checkout handler signature: HMAC-SHA256(order_id + "|" + payment_id, key_secret). */
    boolean checkoutSignatureValid(String orderId, String paymentId, String signature);

    /** Webhook signature: HMAC-SHA256(raw body, webhook secret). */
    boolean webhookSignatureValid(byte[] rawBody, String signature);

    String keyId();
}
