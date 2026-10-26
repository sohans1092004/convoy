package convoy.order;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.contracts.events.AcceptancesFrozen;
import convoy.contracts.events.DepositHeld;
import convoy.contracts.events.PaymentCaptured;
import convoy.contracts.events.QuoteFinalised;
import convoy.contracts.events.StockReserved;
import convoy.platform.idempotency.Idempotent;
import convoy.platform.messaging.Subscriptions;
import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.security.Caller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@EnableConfigurationProperties(OrderApplication.OrderSettings.class)
@RestController
public class OrderApplication {
    private static final Logger log = LoggerFactory.getLogger(OrderApplication.class);

    public static void main(String[] args) { SpringApplication.run(OrderApplication.class, args); }

    /** @param sweeperInterval resolution/saga sweeper period; @param dealerConfirmWindow 48 h (Q-022); @param sagaStepTimeout 10 min */
    @ConfigurationProperties(prefix = "order")
    public record OrderSettings(Duration sweeperInterval, Duration dealerConfirmWindow, Duration sagaStepTimeout) {}

    public record Rules(Map<String, Object> rules) {}

    @Bean
    @SuppressWarnings("unchecked")
    static Rules rules() throws Exception {
        try (InputStream in = OrderApplication.class.getResourceAsStream("/convoy/rules.json")) {
            return new Rules(new ObjectMapper().enable(DeserializationFeature.USE_LONG_FOR_INTS).readValue(in, Map.class));
        }
    }

    @Bean
    static Subscriptions subscriptions(Orders o, @org.springframework.context.annotation.Lazy Lifecycle l) {
        return new Subscriptions()
                .on(QuoteFinalised.class, (e, p) -> o.quoteFinalised(p))
                .on(PaymentCaptured.class, (e, p) -> o.paymentCaptured(p))
                .on(convoy.contracts.events.AcceptanceChanged.class, (e, p) -> o.acceptanceChanged(p))
                .on(AcceptancesFrozen.class, (e, p) -> o.acceptancesFrozen(p))
                .on(DepositHeld.class, (e, p) -> o.stepDone(UUID.fromString(p.bookingId()), "HOLD_DEPOSIT"))
                .on(StockReserved.class, (e, p) -> o.stepDone(UUID.fromString(p.bookingId()), "RESERVE_STOCK"))
                .on(convoy.contracts.events.DepositHoldRejected.class, (e, p) -> l.stepFailed(UUID.fromString(p.bookingId()), "HOLD_DEPOSIT", p.reason()))
                .on(convoy.contracts.events.StockUnavailable.class, (e, p) -> l.stockUnavailable(UUID.fromString(p.bookingId())))
                .on(convoy.contracts.events.QuoteWithdrawn.class, (e, p) -> { if (Boolean.TRUE.equals(p.afterFinal())) l.quoteWithdrawnAfterFinal(UUID.fromString(p.quoteId())); });
    }

    private final Orders orders;
    private final Lifecycle lifecycle;
    private final Disputes disputes;
    private final Evidence evidence;
    private final org.springframework.web.client.RestClient identity;

    /** identity with a service token (scope contacts:read), for E155. */
    public record IdentityClient(org.springframework.web.client.RestClient http) {}

    @Bean
    static IdentityClient identityClient(convoy.platform.security.ServiceTokens tokens, org.springframework.web.client.RestClient.Builder builder,
                                         @org.springframework.beans.factory.annotation.Value("${order.identity-url:http://identity:8080}") String url) {
        return new IdentityClient(tokens.client(builder, "identity", url));
    }

    public OrderApplication(Orders orders, Lifecycle lifecycle, Disputes disputes, Evidence evidence, OrderSettings settings, TaskScheduler scheduler, IdentityClient identity) {
        this.orders = orders;
        this.lifecycle = lifecycle;
        this.disputes = disputes;
        this.evidence = evidence;
        this.identity = identity.http();
        scheduler.scheduleWithFixedDelay(this::sweep, settings.sweeperInterval());
        scheduler.scheduleWithFixedDelay(evidence::purgeExpired, java.time.Instant.now().plus(java.time.Duration.ofMinutes(5)), java.time.Duration.ofDays(1));
    }

    /** Saga driver (P4.B): every unfinished saga moves as far as it can (the resolution step waits for the deadline), then the lifecycle sweeps. */
    void sweep() {
        try {
            for (Map<String, Object> r : orders.jdbc().queryForList("select pool_id, round_set from sagas where step <> 'CONTACTS_RELEASED'")) {
                try {
                    lifecycle.advance((UUID) r.get("pool_id"), (int) r.get("round_set"));
                } catch (RuntimeException e) {
                    log.warn("saga of pool {} deferred: {}", r.get("pool_id"), e.toString());
                }
            }
            lifecycle.sweep();
            int late = orders.jdbc().queryForObject("select count(*) from saga_steps where status = 'PENDING' and next_attempt_at <= clock_timestamp()", Integer.class);
            if (late > 0) log.warn("ALARM {} saga steps past their timeout (deposit hold or stock reservation not answered)", late);
        } catch (RuntimeException e) {
            log.warn("order sweep failed: {}", e.toString());
        }
    }

    @GetMapping("/me/orders")
    public Api.OrderList mine() {
        UUID buyer = Caller.current().require("buyer").userId();
        return convoy.platform.web.Dto.as(Map.of("orders", orders.jdbc().queryForList("select id::text as id, pool_id::text as pool_id, quote_id::text as quote_id, state, confirmation_deadline, version "
                + "from orders where buyer_id = ? order by created_at desc", buyer).stream().map(Orders::iso).toList()), Api.OrderList.class);
    }

    @GetMapping("/orders/{id}")
    public ResponseEntity<Api.Order> get(@PathVariable UUID id) {
        Map<String, Object> o = orders.view(id, Caller.current().require("buyer").userId());
        return ResponseEntity.ok().eTag("\"" + o.get("version") + "\"").body(convoy.platform.web.Dto.as(o, Api.Order.class));
    }

    public record ConfirmBody(@NotBlank String ceilingSnapshotHash) {}

    @PostMapping("/orders/{id}/confirm")
    @Idempotent
    public Api.Order confirm(@PathVariable UUID id, @Valid @RequestBody ConfirmBody b, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return convoy.platform.web.Dto.as(orders.confirm(id, b.ceilingSnapshotHash(), version(ifMatch)), Api.Order.class);
    }

    /** E083, ORD-T04. */
    @PostMapping("/orders/{id}/decline")
    @Idempotent
    @convoy.platform.security.RequiresRole({"buyer"})
    public Api.Order decline(@PathVariable UUID id) { return convoy.platform.web.Dto.as(lifecycle.decline(id), Api.Order.class); }

    /** E084, ORD-T03 (confirmed, not yet booked). */
    @PostMapping("/orders/{id}/cancel")
    @Idempotent
    @convoy.platform.security.RequiresRole({"buyer"})
    public Api.Order cancel(@PathVariable UUID id, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return convoy.platform.web.Dto.as(lifecycle.cancelOrder(id, version(ifMatch)), Api.Order.class);
    }

    /** E086, BKG-T06 by the buyer. */
    @PostMapping("/bookings/{id}/cancel")
    @Idempotent
    @convoy.platform.security.RequiresRole({"buyer"})
    public Api.Order cancelBooking(@PathVariable UUID id, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return convoy.platform.web.Dto.as(lifecycle.buyerCancelBooking(id, version(ifMatch)), Api.Order.class);
    }

    /** E154, BKG-T05 by the buyer: delivery confirmed, the deposit is released. */
    @PostMapping("/bookings/{id}/confirm-delivery")
    @Idempotent
    @convoy.platform.security.RequiresRole({"buyer"})
    public Api.Order confirmDelivery(@PathVariable UUID id) { return convoy.platform.web.Dto.as(lifecycle.buyerConfirmsDelivery(id), Api.Order.class); }

    public record InvoiceBody(@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Positive Long invoiceTotalPaise) {}

    /** E101 (P5.E): the buyer confirms the invoice total after delivery, once. */
    @org.springframework.web.bind.annotation.PutMapping("/bookings/{id}/invoice")
    @Idempotent
    @convoy.platform.security.RequiresRole({"buyer"})
    public Api.Order confirmInvoice(@PathVariable UUID id, @Valid @RequestBody InvoiceBody b) { return convoy.platform.web.Dto.as(lifecycle.confirmInvoice(id, b.invoiceTotalPaise()), Api.Order.class); }

    public record DeliveryBody(@jakarta.validation.constraints.NotNull java.time.LocalDate scheduledDate, @jakarta.validation.constraints.Size(max = 40) String slot) {}

    /** E089, BKG-T04. */
    @org.springframework.web.bind.annotation.PutMapping("/dealer/bookings/{id}/delivery")
    @Idempotent
    @convoy.platform.security.RequiresRole({"dealer_staff", "dealer_admin"})
    public Api.DealerBooking scheduleDelivery(@PathVariable UUID id, @Valid @RequestBody DeliveryBody b, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return convoy.platform.web.Dto.as(lifecycle.scheduleDelivery(id, b.scheduledDate(), b.slot(), version(ifMatch)), Api.DealerBooking.class);
    }

    public record DeliverBody(@NotBlank String voucherToken) {}

    /** E090, BKG-T05 by the dealer with the buyer's voucher. */
    @PostMapping("/dealer/bookings/{id}/deliver")
    @Idempotent
    @convoy.platform.security.RequiresRole({"dealer_staff", "dealer_admin"})
    public Api.DealerBooking deliver(@PathVariable UUID id, @Valid @RequestBody DeliverBody b) {
        return convoy.platform.web.Dto.as(lifecycle.deliverByDealer(id, b.voucherToken()), Api.DealerBooking.class);
    }

    public record DealerCancelBody(@NotBlank String reason) {}

    /** E091, BKG-T06 by the dealer admin. */
    @PostMapping("/dealer/bookings/{id}/cancel")
    @Idempotent
    @convoy.platform.security.RequiresRole({"dealer_admin"})
    public Api.DealerBooking dealerCancel(@PathVariable UUID id, @Valid @RequestBody DealerCancelBody b, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return convoy.platform.web.Dto.as(lifecycle.dealerCancelBooking(id, b.reason(), version(ifMatch)), Api.DealerBooking.class);
    }

    /** E155: the buyer's contact for this dealership's released booking only. */
    @GetMapping("/dealer/bookings/{id}/contact")
    @SuppressWarnings("unchecked")
    @convoy.platform.security.RequiresRole({"dealer_staff", "dealer_admin"})
    public Api.BuyerContact contact(@PathVariable UUID id) {
        UUID buyer = lifecycle.releasedBuyer(id);
        Map<String, Object> c = identity.get().uri("/internal/users/{id}/contact", buyer).retrieve().body(Map.class);
        Map<String, Object> consent = orders.jdbc().queryForMap("select c.terms_version, c.consented_at, r.released_at from contact_releases r join contact_consents c on c.order_id = r.order_id "
                + "where r.booking_id = ?", id);
        return new Api.BuyerContact(id.toString(), (String) c.get("email"), (String) consent.get("terms_version"),
                ((java.sql.Timestamp) consent.get("consented_at")).toInstant().toString(), ((java.sql.Timestamp) consent.get("released_at")).toInstant().toString());
    }

    // ------------------------------------------------------------------ disputes (E092-E100, E156)

    public record DisputeBody(@NotBlank String category, @NotBlank @jakarta.validation.constraints.Size(max = 4000) String description) {}

    @PostMapping("/orders/{id}/disputes")
    @Idempotent
    @convoy.platform.security.RequiresRole({"buyer"})
    public Api.Dispute openDispute(@PathVariable UUID id, @Valid @RequestBody DisputeBody b) { return dispute(disputes.openByBuyer(id, b.category(), b.description())); }

    @GetMapping("/disputes/{id}")
    @convoy.platform.security.RequiresRole({"buyer", "dealer_staff", "dealer_admin", "platform_admin"})
    public Api.Dispute getDispute(@PathVariable UUID id) { return dispute(disputes.view(id, convoy.platform.security.Caller.current())); }

    public record TextBody(@NotBlank @jakarta.validation.constraints.Size(max = 4000) String text) {}

    @PostMapping("/disputes/{id}/responses")
    @Idempotent
    @convoy.platform.security.RequiresRole({"buyer", "dealer_staff", "dealer_admin"})
    public Api.Dispute respond(@PathVariable UUID id, @Valid @RequestBody TextBody b) { return dispute(disputes.respond(id, b.text())); }

    @PostMapping("/disputes/{id}/appeal")
    @Idempotent
    @convoy.platform.security.RequiresRole({"buyer"})
    public Api.Dispute appeal(@PathVariable UUID id, @Valid @RequestBody TextBody b) { return dispute(disputes.appeal(id, b.text())); }

    @PostMapping("/disputes/{id}/withdraw")
    @Idempotent
    @convoy.platform.security.RequiresRole({"buyer"})
    public Api.Dispute withdraw(@PathVariable UUID id) { return dispute(disputes.withdraw(id)); }

    public record UploadBody(@NotBlank String fileName, @NotBlank String contentType, @jakarta.validation.constraints.NotNull Long sizeBytes) {}

    @PostMapping("/disputes/{id}/evidence-uploads")
    @Idempotent
    @convoy.platform.security.RequiresRole({"buyer", "dealer_staff", "dealer_admin"})
    public Api.EvidenceUpload upload(@PathVariable UUID id, @Valid @RequestBody UploadBody b) {
        return convoy.platform.web.Dto.as(evidence.upload(id, b.fileName(), b.contentType(), b.sizeBytes()), Api.EvidenceUpload.class);
    }

    public record AssignBody(@org.springframework.lang.Nullable UUID ownerId) {}

    @PostMapping("/admin/disputes/{id}/assign")
    @Idempotent
    @convoy.platform.security.RequiresRole({"platform_admin"})
    public Api.Dispute assign(@PathVariable UUID id, @RequestBody(required = false) AssignBody b) { return dispute(disputes.assign(id, b == null ? null : b.ownerId())); }

    public record InfoBody(@NotBlank String party, @NotBlank @jakarta.validation.constraints.Size(max = 2000) String question) {}

    @PostMapping("/admin/disputes/{id}/request-info")
    @Idempotent
    @convoy.platform.security.RequiresRole({"platform_admin"})
    public Api.Dispute requestInfo(@PathVariable UUID id, @Valid @RequestBody InfoBody b) { return dispute(disputes.requestInfo(id, b.party(), b.question())); }

    public record ResolveBody(@NotBlank String outcome, @NotBlank @jakarta.validation.constraints.Size(max = 2000) String note) {}

    @PostMapping("/admin/disputes/{id}/resolve")
    @Idempotent
    @convoy.platform.security.RequiresRole({"platform_admin"})
    public Api.Dispute resolve(@PathVariable UUID id, @Valid @RequestBody ResolveBody b) { return dispute(disputes.resolve(id, b.outcome(), b.note())); }

    @GetMapping("/admin/disputes")
    @convoy.platform.security.RequiresRole({"platform_admin"})
    public Api.DisputeQueue disputeQueue() { return convoy.platform.web.Dto.as(Map.of("disputes", disputes.adminQueue()), Api.DisputeQueue.class); }

    @SuppressWarnings("unchecked")
    private Api.Dispute dispute(Map<String, Object> d) {
        Map<String, Object> out = new java.util.HashMap<>(d);
        out.put("evidence", evidence.withDownloadUrls((List<Map<String, Object>>) d.get("evidence")));
        return convoy.platform.web.Dto.as(out, Api.Dispute.class);
    }

    @GetMapping("/orders/{id}/voucher")
    public Api.Voucher voucher(@PathVariable UUID id) {
        return convoy.platform.web.Dto.as(orders.voucher(id, Caller.current().require("buyer").userId()), Api.Voucher.class);
    }

    @GetMapping("/dealer/bookings")
    public Api.DealerBookings dealerBookings() {
        Caller c = Caller.current().require("dealer_staff", "dealer_admin");
        if (c.dealershipId() == null) throw ApiProblem.of(ProblemType.FORBIDDEN, "Your account is not linked to a dealership.");
        return convoy.platform.web.Dto.as(Map.of("bookings", orders.jdbc().queryForList("select id from bookings where dealership_id = ? order by created_at desc", UUID.class,
                UUID.fromString(c.dealershipId())).stream().map(orders::dealerBooking).toList()), Api.DealerBookings.class);
    }

    @PostMapping("/dealer/bookings/{id}/confirm")
    @Idempotent
    public Api.DealerBooking dealerConfirm(@PathVariable UUID id, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return convoy.platform.web.Dto.as(orders.dealerConfirm(id, version(ifMatch)), Api.DealerBooking.class);
    }

    static long version(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) throw ApiProblem.of(ProblemType.PRECONDITION_REQUIRED, "Send If-Match with the aggregate version.");
        try {
            return Long.parseLong(ifMatch.replace("\"", "").replace("W/", "").trim());
        } catch (NumberFormatException e) {
            throw ApiProblem.of(ProblemType.PRECONDITION_REQUIRED, "If-Match must be the aggregate version.");
        }
    }
}
