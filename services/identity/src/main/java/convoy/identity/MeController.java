package convoy.identity;

import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.security.Caller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** E007 /me and E014 consent records (built early in P1 for the dealer-disclosure purpose). */
@RestController
public class MeController {
    static final Set<String> PURPOSES = Set.of("DEALER_DISCLOSURE");
    private final Users users;
    private final JdbcTemplate jdbc;

    public MeController(Users users, JdbcTemplate jdbc) {
        this.users = users;
        this.jdbc = jdbc;
    }

    static Caller human() {
        Caller c = Caller.current();
        if (c.roles().isEmpty()) throw ApiProblem.of(ProblemType.UNAUTHENTICATED, "Sign in first.");
        return c;
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        Users.User u = users.find(human().userId()).orElseThrow(() -> ApiProblem.of(ProblemType.UNAUTHENTICATED, "Account not active."));
        Map<String, Object> out = new HashMap<>();
        out.put("id", u.id().toString());
        out.put("email", u.email());
        out.put("roles", u.roles());
        out.put("dealership_id", u.dealershipId() == null ? null : u.dealershipId().toString());
        out.put("consents", jdbc.queryForList("select distinct on (purpose) purpose, granted, notice_version, at from consents "
                + "where user_id = ? order by purpose, at desc", u.id()));
        return out;
    }

    public record ConsentBody(@NotNull Boolean granted, @NotBlank String noticeVersion) {}

    /** Append-only: every change is a new row, so the history per purpose is kept. */
    @PutMapping("/me/consents/{purpose}")
    public Map<String, Object> consent(@PathVariable String purpose, @Valid @RequestBody ConsentBody body) {
        if (!PURPOSES.contains(purpose)) throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "Unknown purpose " + purpose + ".");
        Caller c = human();
        jdbc.update("insert into consents(user_id, purpose, granted, notice_version) values (?, ?, ?, ?)",
                c.userId(), purpose, body.granted(), body.noticeVersion());
        List<Map<String, Object>> history = jdbc.queryForList("select granted, notice_version, at from consents where user_id = ? and purpose = ? order by at",
                c.userId(), purpose);
        return Map.of("purpose", purpose, "granted", body.granted(), "history", history);
    }
}
