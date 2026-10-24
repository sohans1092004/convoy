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

    public DealerApplication(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static void main(String[] args) { SpringApplication.run(DealerApplication.class, args); }

    /** E055: verified dealerships of the brand in the city with at least one active staff member. Service token only. */
    @GetMapping("/internal/eligibility")
    @convoy.platform.security.RequiresScope("eligibility:read")
    public Map<String, Object> eligibility(@RequestParam String brand, @RequestParam String city) {
        Caller.current().requireScope("eligibility:read");
        List<UUID> ids = jdbc.queryForList("select id from dealerships where status = 'VERIFIED' and city = ? and ? = any(brands) "
                + "and active_staff_count >= 1 order by id", UUID.class, city, brand);
        return Map.of("brand", brand, "city", city, "dealership_ids", ids, "count", ids.size());
    }

    /** E052 (built early for P1 as the dealer read API): public dealership profile. */
    @GetMapping("/dealerships/{id}")
    public Map<String, Object> profile(@PathVariable UUID id) throws java.sql.SQLException {
        List<Map<String, Object>> rows = jdbc.queryForList("select id, trade_name, city, state_code, brands, status, synthetic, verified_at "
                + "from dealerships where id = ? and status <> 'PENDING'", id);
        if (rows.isEmpty()) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such dealership.");
        Map<String, Object> r = new java.util.LinkedHashMap<>(rows.get(0));
        r.put("brands", List.of((String[]) ((java.sql.Array) r.get("brands")).getArray()));
        return r;
    }
}
