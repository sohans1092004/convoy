package convoy.dealer;

import convoy.contracts.events.DealershipVerified;
import convoy.platform.idempotency.Idempotent;
import convoy.platform.messaging.Outbox;
import convoy.platform.messaging.Uuid7;
import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.security.Caller;
import convoy.platform.security.RequiresRole;
import convoy.platform.web.Dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.sql.Array;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * P3.A dealer onboarding: E050 self-registration (GSTIN shape + check digit), E153 admin verification queue, E053
 * approve or reject with a reason. Every decision is audited (dealership_audit); approval emits DealershipVerified,
 * which makes the registrant the dealership's admin (identity) and the dealership eligible for rounds (E055 serves
 * VERIFIED only). Admin endpoints need platform_admin, which only passkey sessions carry (AMENDMENT-008).
 */
@RestController
public class OnboardingController {
    private static final String COLUMNS = "id, trade_name, legal_name, gstin, city, state_code, brands, status, rejection_reason, created_at, version";
    private final JdbcTemplate jdbc;
    private final Outbox outbox;

    public OnboardingController(JdbcTemplate jdbc, Outbox outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    public record Register(@NotBlank @Size(max = 120) String tradeName, @NotBlank @Size(max = 200) String legalName, @NotBlank String gstin,
                           @NotBlank @Size(max = 80) String city, @NotEmpty List<@NotBlank String> brands) {}

    public record Decision(@NotBlank String decision, @Nullable @Size(max = 500) String reason) {}

    /** E050. Any signed-in user; the dealership starts PENDING_VERIFICATION and is invisible publicly until approved. */
    @PostMapping("/dealerships")
    @Idempotent
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public Api.Registration register(@Valid @RequestBody Register b) throws SQLException {
        Caller c = Caller.current();
        if (c.userId() == null || c.roles().isEmpty()) throw ApiProblem.of(ProblemType.UNAUTHENTICATED, "Sign in to register a dealership.");
        String gstin = b.gstin().trim().toUpperCase(java.util.Locale.ROOT);
        String why = Gstin.problem(gstin);
        if (why != null) throw ApiProblem.of(ProblemType.VALIDATION_FAILED, why).with("field", "gstin");
        UUID id = Uuid7.next();
        int n = jdbc.update("insert into dealerships(id, trade_name, legal_name, gstin, city, state_code, brands, status, synthetic, registered_by) "
                        + "values (?, ?, ?, ?, ?, ?, ?, 'PENDING_VERIFICATION', false, ?) on conflict (gstin) where gstin is not null do nothing",
                id, b.tradeName().trim(), b.legalName().trim(), gstin, b.city().trim(), gstin.substring(0, 2),
                jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Array>) con -> con.createArrayOf("text", b.brands().stream().map(String::trim).toArray())),
                c.userId());
        if (n == 0) throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "A dealership with this GSTIN is already registered.").with("field", "gstin");
        audit(id, "REGISTERED", c.userId(), null);
        return Dto.as(view(row(id)), Api.Registration.class);
    }

    /** E153. The verification queue, oldest first. */
    @GetMapping("/admin/dealerships")
    @RequiresRole("platform_admin")
    public Api.Queue queue(@RequestParam(defaultValue = "PENDING_VERIFICATION") String status) throws SQLException {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("select " + COLUMNS + " from dealerships where status = ? order by created_at limit 200", status)) out.add(view(r));
        return Dto.as(Map.of("dealerships", out), Api.Queue.class);
    }

    /** E053. APPROVE or REJECT (reason required) a PENDING_VERIFICATION dealership; If-Match = its version. */
    @PostMapping("/admin/dealerships/{id}/verify")
    @RequiresRole("platform_admin")
    @Idempotent
    @Transactional
    public Api.Registration verify(@PathVariable UUID id, @Valid @RequestBody Decision b, @RequestHeader(value = "If-Match", required = false) String ifMatch) throws SQLException {
        Caller c = Caller.current();
        if (ifMatch == null || ifMatch.isBlank()) throw ApiProblem.of(ProblemType.PRECONDITION_REQUIRED, "Send If-Match with the dealership version.");
        long expected;
        try {
            expected = Long.parseLong(ifMatch.replace("\"", "").replace("W/", "").trim());
        } catch (NumberFormatException e) {
            throw ApiProblem.of(ProblemType.PRECONDITION_REQUIRED, "If-Match must be the dealership version.");
        }
        boolean approve = switch (b.decision()) {
            case "APPROVE" -> true;
            case "REJECT" -> false;
            default -> throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "decision must be APPROVE or REJECT.").with("field", "decision");
        };
        String reason = b.reason() == null || b.reason().isBlank() ? null : b.reason().trim();
        if (!approve && reason == null) throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "Give the dealer a reason for the rejection.").with("field", "reason");
        List<Map<String, Object>> rows = jdbc.queryForList("select " + COLUMNS + ", registered_by from dealerships where id = ? for update", id);
        if (rows.isEmpty()) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such dealership.");
        Map<String, Object> d = rows.get(0);
        if ((long) d.get("version") != expected) throw ApiProblem.of(ProblemType.VERSION_CONFLICT, "The dealership changed; reload it.").with("current_version", d.get("version"));
        if (!"PENDING_VERIFICATION".equals(d.get("status"))) throw ApiProblem.of(ProblemType.INVALID_TRANSITION, "Only a dealership waiting for verification can be approved or rejected.");
        jdbc.update("update dealerships set status = ?, verified_by = ?, verified_at = case when ? then clock_timestamp() end, rejection_reason = ?, "
                // On approval the registrant becomes the dealership's admin (identity consumes DealershipVerified): one active staff member.
                + "active_staff_count = case when ? then greatest(active_staff_count, 1) else active_staff_count end, version = version + 1 where id = ?",
                approve ? "VERIFIED" : "REJECTED", c.userId(), approve, approve ? null : reason, approve, id);
        audit(id, approve ? "APPROVED" : "REJECTED", c.userId(), reason);
        if (approve) {
            Map<String, Object> v = view(d);
            @SuppressWarnings("unchecked") List<String> brands = (List<String>) v.get("brands");
            outbox.append(id, (long) d.get("version") + 1, new DealershipVerified(id.toString(), brands, (String) d.get("city"), c.userId().toString(),
                    d.get("registered_by") == null ? null : d.get("registered_by").toString()));
        }
        return Dto.as(view(row(id)), Api.Registration.class);
    }

    private Map<String, Object> row(UUID id) { return jdbc.queryForMap("select " + COLUMNS + " from dealerships where id = ?", id); }

    private void audit(UUID dealership, String action, UUID actor, @Nullable String reason) {
        jdbc.update("insert into dealership_audit(id, dealership_id, action, actor, reason) values (?, ?, ?, ?, ?)", Uuid7.next(), dealership, action, actor, reason);
    }

    private static Map<String, Object> view(Map<String, Object> r) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : List.of("id", "trade_name", "legal_name", "gstin", "city", "state_code", "status", "rejection_reason", "version")) m.put(k, r.get(k));
        m.put("id", r.get("id").toString());
        m.put("brands", List.of((String[]) ((Array) r.get("brands")).getArray()));
        m.put("created_at", ((java.sql.Timestamp) r.get("created_at")).toInstant().toString());
        return m;
    }
}
