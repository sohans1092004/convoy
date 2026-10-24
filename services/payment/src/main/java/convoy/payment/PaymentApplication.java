package convoy.payment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.contracts.events.AcceptancesFrozen;
import convoy.contracts.events.DepositHoldRequested;
import convoy.contracts.events.DepositRefundRequested;
import convoy.contracts.events.MemberLeft;
import convoy.platform.idempotency.Idempotent;
import convoy.platform.messaging.Subscriptions;
import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.security.Caller;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@EnableConfigurationProperties(PaymentApplication.PaymentSettings.class)
@RestController
public class PaymentApplication {
    public static void main(String[] args) { SpringApplication.run(PaymentApplication.class, args); }

    /** Razorpay TEST mode settings (env only) and the configured deposit (ruleset default 99900 paise). */
    @ConfigurationProperties(prefix = "payment")
    public record PaymentSettings(String razorpayBaseUrl, String razorpayKeyId, String razorpayKeySecret, String razorpayWebhookSecret, long depositAmountPaise) {}

    @Bean
    static RazorpayClient razorpayClient(PaymentSettings s) {
        return new HttpRazorpayClient(s.razorpayBaseUrl(), s.razorpayKeyId(), s.razorpayKeySecret(), s.razorpayWebhookSecret());
    }

    /** What payment consumes in P1 (pool events through a FIFO queue: membership order matters). */
    @Bean
    static Subscriptions subscriptions(Payments payments) {
        return new Subscriptions()
                .on(DepositHoldRequested.class, Subscriptions.Ordering.NONE, (e, p) ->
                        payments.hold(UUID.fromString(p.buyerId()), UUID.fromString(p.poolId()), UUID.fromString(p.bookingId())))
                .on(DepositRefundRequested.class, Subscriptions.Ordering.NONE, (e, p) ->
                        payments.requestRefund(UUID.fromString(p.buyerId()), UUID.fromString(p.poolId()), p.reason()))
                .on(MemberLeft.class, Subscriptions.Ordering.MONOTONIC, (e, p) ->
                        payments.requestRefund(UUID.fromString(p.buyerId()), UUID.fromString(p.poolId()), p.reason()))
                .on(AcceptancesFrozen.class, Subscriptions.Ordering.MONOTONIC, (e, p) -> p.releasedBuyers().forEach(r ->
                        payments.requestRefund(UUID.fromString(String.valueOf(r.get("buyer_id"))), UUID.fromString(p.poolId()), "NO_ACCEPTANCE")));
    }

    private final Payments payments;
    private final RazorpayClient razorpay;
    private final PaymentSettings settings;
    private final ObjectMapper json;

    public PaymentApplication(Payments payments, RazorpayClient razorpay, PaymentSettings settings, ObjectMapper json) {
        this.payments = payments;
        this.razorpay = razorpay;
        this.settings = settings;
        this.json = json;
    }

    public record DepositBody(@NotNull UUID buyerId, @NotNull UUID poolId, @NotNull Integer roundSet, @NotNull Long amountPaise) {}

    /** E110: pool creates (or gets) the deposit. Service token with deposits:create (AZ-SVC-03). */
    @PostMapping("/internal/deposits")
    @Idempotent
    public Map<String, Object> deposit(@Valid @RequestBody DepositBody b) {
        Caller.current().requireScope("deposits:create");
        if (b.amountPaise() != settings.depositAmountPaise()) {
            throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "Deposit amount must be " + settings.depositAmountPaise() + " paise.");
        }
        return payments.createDeposit(b.buyerId(), b.poolId(), b.roundSet(), b.amountPaise());
    }

    public record CallbackBody(@NotBlank String razorpayPaymentId, @NotBlank String razorpayOrderId, @NotBlank String razorpaySignature) {}

    /** E111: checkout handler result. Verified, but it only moves INITIATED to PENDING (webhook-only success rule). */
    @PostMapping("/payments/{id}/checkout-callback")
    public Map<String, Object> callback(@PathVariable UUID id, @Valid @RequestBody CallbackBody b) {
        Caller c = Caller.current().require("buyer");
        return payments.checkoutCallback(id, c.userId(), b.razorpayOrderId(), b.razorpayPaymentId(), b.razorpaySignature());
    }

    /** E112: the buyer's own payment (404 for anyone else's). */
    @GetMapping("/me/payments/{id}")
    public Map<String, Object> mine(@PathVariable UUID id) {
        Caller c = Caller.current().require("buyer");
        return payments.find(id).filter(p -> p.buyerId().equals(c.userId())).map(payments::view)
                .orElseThrow(() -> ApiProblem.of(ProblemType.NOT_FOUND, "No such payment."));
    }

    /** E113: signature over the raw body, dedupe on x-razorpay-event-id. Public path (no JWT). */
    @PostMapping("/webhooks/razorpay")
    public Map<String, Object> webhook(HttpServletRequest req, @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
                                       @RequestHeader(value = "x-razorpay-event-id", required = false) String eventId) throws IOException {
        byte[] raw = req.getInputStream().readAllBytes();
        if (signature == null || !razorpay.webhookSignatureValid(raw, signature)) {
            org.slf4j.LoggerFactory.getLogger("convoy.security").warn("razorpay webhook with invalid signature rejected");
            throw ApiProblem.of(ProblemType.WEBHOOK_SIGNATURE_INVALID, "Signature check failed.");
        }
        if (eventId == null || eventId.isBlank()) throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "Missing x-razorpay-event-id.");
        Map<String, Object> body = json.readValue(raw, new TypeReference<>() {});
        boolean fresh = payments.webhook(eventId, body);
        return Map.of("status", fresh ? "PROCESSED" : "DUPLICATE");
    }

    @Scheduled(fixedDelay = 2_000, initialDelay = 5_000)
    void refunds() { payments.refundWorker(); }
}
