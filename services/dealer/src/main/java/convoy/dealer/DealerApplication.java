package convoy.dealer;

import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.security.Caller;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Thin dealer service for P1: one seeded synthetic dealership, eligibility (E055) and profile read (E052). */
@SpringBootApplication
@RestController
public class DealerApplication {
    private final JdbcTemplate jdbc;
    private final Reputation reputation;

    public DealerApplication(JdbcTemplate jdbc, Reputation reputation) {
        this.jdbc = jdbc;
        this.reputation = reputation;
    }

    public static void main(String[] args) { SpringApplication.run(DealerApplication.class, args); }

    /** E055: verified dealerships of the brand in the city with at least one active staff member. Service token only. */
    @GetMapping("/internal/eligibility")
    @convoy.platform.security.RequiresScope("eligibility:read")
    public Api.Eligibility eligibility(@RequestParam String brand, @RequestParam String city) {
        Caller.current().requireScope("eligibility:read");
        List<UUID> ids = jdbc.queryForList("select id from dealerships where status = 'VERIFIED' and city = ? and ? = any(brands) "
                + "and active_staff_count >= 1 order by id", UUID.class, city, brand);
        return convoy.platform.web.Dto.as(Map.of("brand", brand, "city", city, "dealership_ids", ids, "count", ids.size()), Api.Eligibility.class);
    }

    /** E052 (built early for P1 as the dealer read API): public dealership profile. */
    @GetMapping("/dealerships/{id}")
    public Api.Dealership profile(@PathVariable UUID id) throws java.sql.SQLException {
        List<Map<String, Object>> rows = jdbc.queryForList("select id, trade_name, city, state_code, brands, status, synthetic, verified_at "
                + "from dealerships where id = ? and status in ('VERIFIED', 'SUSPENDED')", id); // not public until approved (P3.A)
        if (rows.isEmpty()) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such dealership.");
        Map<String, Object> r = new java.util.LinkedHashMap<>(rows.get(0));
        r.put("brands", List.of((String[]) ((java.sql.Array) r.get("brands")).getArray()));
        r.put("reliability", reputation.reliability(id)); // AMENDMENT-009: always with its counts
        return convoy.platform.web.Dto.as(r, Api.Dealership.class);
    }

    public record ReviewBody(@jakarta.validation.constraints.NotNull Integer rating, @jakarta.validation.constraints.Size(max = 2000) String body) {}

    /** E056: a buyer reviews the dealer of their delivered booking, once. */
    @org.springframework.web.bind.annotation.PostMapping("/bookings/{bookingId}/review")
    @convoy.platform.idempotency.Idempotent
    @convoy.platform.security.RequiresRole({"buyer"})
    public Api.Review review(@PathVariable UUID bookingId, @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody ReviewBody b) {
        return convoy.platform.web.Dto.as(reputation.review(bookingId, b.rating(), b.body()), Api.Review.class);
    }

    /** E057: public reviews of a dealership. */
    @GetMapping("/dealerships/{id}/reviews")
    public Api.Reviews reviews(@PathVariable UUID id) {
        if (jdbc.queryForObject("select count(*) from dealerships where id = ? and status in ('VERIFIED', 'SUSPENDED')", Integer.class, id) == 0) {
            throw ApiProblem.of(ProblemType.NOT_FOUND, "No such dealership.");
        }
        return convoy.platform.web.Dto.as(reputation.reviews(id), Api.Reviews.class);
    }
}
