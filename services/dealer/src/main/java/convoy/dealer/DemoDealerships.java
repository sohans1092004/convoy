package convoy.dealer;

import convoy.platform.security.Caller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * P6.B public demo (dealer.demo-scenarios=true, lite only). E172: identity creates a visitor's dealership, verified and
 * synthetic, in the demo city and brand, owned by the scenario's buyer so E055 invites it to that buyer's pools only.
 * Hourly, demo dealerships older than 24 h are removed with their rows here (the scenario's accounts are erased by identity).
 */
@RestController
@ConditionalOnProperty(name = "dealer.demo-scenarios", havingValue = "true")
@io.swagger.v3.oas.annotations.Hidden // only on the lite demo: the committed OpenAPI documents stay the same everywhere
class DemoDealerships {
    private static final Logger log = LoggerFactory.getLogger(DemoDealerships.class);
    private final JdbcTemplate jdbc;

    DemoDealerships(JdbcTemplate jdbc, TaskScheduler scheduler) {
        this.jdbc = jdbc;
        scheduler.scheduleWithFixedDelay(this::expire, Instant.now().plusSeconds(90), Duration.ofHours(1));
    }

    public record Create(@NotNull UUID dealershipId, @NotNull UUID owner, @NotBlank String tradeName) {}

    /** E172 (service:identity, scope demo:write). Idempotent on the dealership id. */
    @PostMapping("/internal/demo/dealerships")
    @convoy.platform.security.RequiresScope("demo:write")
    public void create(@Valid @RequestBody Create b) {
        Caller.current().requireScope("demo:write");
        jdbc.update("insert into dealerships(id, trade_name, legal_name, gstin, city, state_code, brands, status, synthetic, active_staff_count, verified_at, demo_owner) "
                + "values (?, ?, 'Demo dealership - not a real business', null, 'Pune', 'MH', array['Maruti Suzuki'], 'VERIFIED', true, 1, clock_timestamp(), ?) "
                + "on conflict (id) do nothing", b.dealershipId(), b.tradeName(), b.owner());
    }

    @Transactional
    void expire() {
        try {
            List<UUID> due = jdbc.queryForList("select id from dealerships where demo_owner is not null and created_at <= clock_timestamp() - interval '24 hours'", UUID.class);
            if (due.isEmpty()) return;
            Object[] ids = {due.toArray(UUID[]::new)};
            for (String t : List.of("dealership_audit", "delivered_bookings", "reviews", "reliability_events")) {
                jdbc.update("delete from " + t + " where dealership_id = any(?)", ids);
            }
            jdbc.update("delete from dealerships where id = any(?)", ids);
            log.info("demo: removed {} expired demo dealership(s)", due.size());
        } catch (RuntimeException e) {
            log.warn("demo dealership cleanup failed: {}", e.toString());
        }
    }
}
