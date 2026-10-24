package convoy.payment;

import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Razorpay over HTTP with Basic auth (key id : key secret). 3 s timeout, 2 retries for idempotent calls. */
public class HttpRazorpayClient implements RazorpayClient {
    private final RestClient http;
    private final String keyId, keySecret, webhookSecret;

    public HttpRazorpayClient(String baseUrl, String keyId, String keySecret, String webhookSecret) {
        if (!keyId.startsWith("rzp_test_")) throw new IllegalStateException("Convoy only runs Razorpay in TEST mode (key id must start rzp_test_)");
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(3));
        f.setReadTimeout(Duration.ofSeconds(3));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(f)
                .defaultHeaders(h -> h.setBasicAuth(keyId, keySecret)).build();
        this.keyId = keyId;
        this.keySecret = keySecret;
        this.webhookSecret = webhookSecret;
    }

    @Override public String keyId() { return keyId; }

    @SuppressWarnings("unchecked")
    @Override
    public Order createOrder(long amountPaise, String receipt, Map<String, String> notes) {
        Map<String, Object> r = call(() -> http.post().uri("/v1/orders")
                .body(Map.of("amount", amountPaise, "currency", "INR", "receipt", receipt, "notes", notes)).retrieve().body(Map.class));
        return new Order((String) r.get("id"), ((Number) r.get("amount")).longValue(), (String) r.get("currency"), (String) r.get("receipt"), (String) r.get("status"));
    }

    @SuppressWarnings("unchecked")
    @Override
    public Refund createRefund(String razorpayPaymentId, long amountPaise, String receipt) {
        Map<String, Object> r = http.post().uri("/v1/payments/{id}/refund", razorpayPaymentId)
                .body(Map.of("amount", amountPaise, "receipt", receipt, "speed", "normal")).retrieve().body(Map.class);
        return refund(r);
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<Refund> refunds(String razorpayPaymentId) {
        Map<String, Object> r = call(() -> http.get().uri("/v1/payments/{id}/refunds", razorpayPaymentId).retrieve().body(Map.class));
        return ((List<Map<String, Object>>) r.get("items")).stream().map(HttpRazorpayClient::refund).toList();
    }

    private static Refund refund(Map<String, Object> r) {
        return new Refund((String) r.get("id"), (String) r.get("payment_id"), ((Number) r.get("amount")).longValue(), (String) r.get("receipt"), (String) r.get("status"));
    }

    private static <T> T call(java.util.function.Supplier<T> s) {
        RestClientException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return s.get();
            } catch (RestClientException e) {
                last = e;
            }
        }
        throw ApiProblem.of(ProblemType.DEPENDENCY_UNAVAILABLE, "Razorpay (test mode) is unavailable: " + last.getMessage());
    }

    @Override
    public boolean checkoutSignatureValid(String orderId, String paymentId, String signature) {
        return equal(hmac(keySecret, (orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8)), signature);
    }

    @Override
    public boolean webhookSignatureValid(byte[] rawBody, String signature) {
        return equal(hmac(webhookSecret, rawBody), signature);
    }

    static String hmac(String secret, byte[] data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(m.doFinal(data));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean equal(String a, String b) {
        return b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
