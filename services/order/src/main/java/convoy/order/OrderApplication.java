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
    Rules rules() throws Exception {
        try (InputStream in = OrderApplication.class.getResourceAsStream("/convoy/rules.json")) {
            return new Rules(new ObjectMapper().enable(DeserializationFeature.USE_LONG_FOR_INTS).readValue(in, Map.class));
        }
    }

    @Bean
    Subscriptions subscriptions(Orders o) {
        return new Subscriptions()
                .on(QuoteFinalised.class, Subscriptions.Ordering.NONE, (e, p) -> o.quoteFinalised(p))
                .on(PaymentCaptured.class, Subscriptions.Ordering.NONE, (e, p) -> o.paymentCaptured(p))
                .on(AcceptancesFrozen.class, Subscriptions.Ordering.MONOTONIC, (e, p) -> o.acceptancesFrozen(p))
                .on(DepositHeld.class, Subscriptions.Ordering.NONE, (e, p) -> o.stepDone(UUID.fromString(p.bookingId()), "HOLD_DEPOSIT"))
                .on(StockReserved.class, Subscriptions.Ordering.NONE, (e, p) -> o.stepDone(UUID.fromString(p.bookingId()), "RESERVE_STOCK"));
    }

    private final Orders orders;

    public OrderApplication(Orders orders, OrderSettings settings, TaskScheduler scheduler) {
        this.orders = orders;
        scheduler.scheduleWithFixedDelay(this::sweep, settings.sweeperInterval());
    }

    /** Resolution job: wakes pools whose confirmation deadline passed (one transaction per pool). */
    void sweep() {
        try {
            for (Map<String, Object> r : orders.jdbc().queryForList("select pool_id, round_set from pool_resolutions where state = 'PENDING' and confirmation_deadline <= clock_timestamp()")) {
                try {
                    orders.resolve((UUID) r.get("pool_id"), (int) r.get("round_set"));
                } catch (RuntimeException e) {
                    log.warn("resolution of pool {} deferred: {}", r.get("pool_id"), e.toString());
                }
            }
            int late = orders.jdbc().queryForObject("select count(*) from saga_steps where status = 'PENDING' and next_attempt_at <= clock_timestamp()", Integer.class);
            if (late > 0) log.warn("ALARM {} saga steps past their timeout (retry/compensation is P2)", late);
        } catch (RuntimeException e) {
            log.warn("order sweep failed: {}", e.toString());
        }
    }

    @GetMapping("/me/orders")
    public Map<String, Object> mine() {
        UUID buyer = Caller.current().require("buyer").userId();
        return Map.of("orders", orders.jdbc().queryForList("select id::text as id, pool_id::text as pool_id, quote_id::text as quote_id, state, confirmation_deadline, version "
                + "from orders where buyer_id = ? order by created_at desc", buyer).stream().map(Orders::iso).toList());
    }

    @GetMapping("/orders/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable UUID id) {
        Map<String, Object> o = orders.view(id, Caller.current().require("buyer").userId());
        return ResponseEntity.ok().eTag("\"" + o.get("version") + "\"").body(o);
    }

    public record ConfirmBody(@NotBlank String ceilingSnapshotHash) {}

    @PostMapping("/orders/{id}/confirm")
    @Idempotent
    public Map<String, Object> confirm(@PathVariable UUID id, @Valid @RequestBody ConfirmBody b, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return orders.confirm(id, b.ceilingSnapshotHash(), version(ifMatch));
    }

    @PostMapping({"/orders/{id}/decline", "/orders/{id}/cancel"})
    public void notBuilt(@PathVariable UUID id) {
        throw ApiProblem.of(ProblemType.NOT_IMPLEMENTED_IN_P1, "Decline and cancel (ORD-T03/T04) are specified but not built in Phase 1.");
    }

    @GetMapping("/orders/{id}/voucher")
    public Map<String, Object> voucher(@PathVariable UUID id) {
        return orders.voucher(id, Caller.current().require("buyer").userId());
    }

    @GetMapping("/dealer/bookings")
    public Map<String, Object> dealerBookings() {
        Caller c = Caller.current().require("dealer_staff", "dealer_admin");
        if (c.dealershipId() == null) throw ApiProblem.of(ProblemType.FORBIDDEN, "Your account is not linked to a dealership.");
        return Map.of("bookings", orders.jdbc().queryForList("select id from bookings where dealership_id = ? order by created_at desc", UUID.class,
                UUID.fromString(c.dealershipId())).stream().map(orders::dealerBooking).toList());
    }

    @PostMapping("/dealer/bookings/{id}/confirm")
    @Idempotent
    public Map<String, Object> dealerConfirm(@PathVariable UUID id, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return orders.dealerConfirm(id, version(ifMatch));
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
