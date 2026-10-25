package convoy.identity;

import convoy.contracts.events.StaffRevoked;
import convoy.platform.idempotency.Idempotent;
import convoy.platform.messaging.Outbox;
import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.security.Caller;
import convoy.platform.web.Dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * E012 staff revocation (pulled forward from P3 for AZ-T6; AMENDMENT-008: it deletes all the member's sessions and
 * emits SessionRevoked per session, which gateway and realtime evict on) and E152, the batch session re-check realtime
 * uses as the fallback when an event is missed.
 */
@RestController
public class StaffController {
    private final JdbcTemplate jdbc;
    private final Outbox outbox;

    public StaffController(JdbcTemplate jdbc, Outbox outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    /** E012. Only an admin of the same dealership; anything else is 404 (no existence leaks). */
    @DeleteMapping("/dealerships/{id}/staff/{userId}")
    @convoy.platform.security.RequiresRole("dealer_admin")
    @Idempotent
    @Transactional
    public Api.Status revoke(@PathVariable UUID id, @PathVariable UUID userId) {
        Caller c = Caller.current();
        if (!id.toString().equals(c.dealershipId())) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such staff member.");
        int n = jdbc.update("update dealership_staff set status = 'REVOKED', revoked_at = clock_timestamp() "
                + "where dealership_id = ? and user_id = ? and revoked_at is null", id, userId);
        if (n == 0) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such staff member.");
        jdbc.update("delete from user_roles where user_id = ? and role in ('dealer_staff', 'dealer_admin') and not exists "
                + "(select 1 from dealership_staff where user_id = ? and revoked_at is null)", userId, userId);
        // AMENDMENT-008 (Q-044): every session of the revoked staff member ends now; gateway and realtime evict on SessionRevoked.
        long at = Instant.now().toEpochMilli();
        List<String> sessions = jdbc.queryForList("delete from sessions where user_id = ? returning id_hash", String.class, userId);
        for (int i = 0; i < sessions.size(); i++) {
            outbox.append(userId, at + i, new convoy.contracts.events.SessionRevoked(userId.toString(), sessions.get(i), "STAFF_REVOKED"));
        }
        outbox.append(userId, at + sessions.size(), new StaffRevoked(id.toString(), userId.toString(), c.userId().toString()));
        return Dto.as(Map.of("status", "REVOKED"), Api.Status.class);
    }

    public record SessionRef(@NotNull String sid, @Nullable String dealershipId) {}

    public record CheckBody(@NotNull @Valid List<SessionRef> sessions) {}

    /**
     * E152 (service:realtime, scope sessions:check). Returns the sids that are no longer good: the session was revoked
     * or expired, or it was opened as dealer staff and that membership has been revoked. One call per check cycle.
     */
    // ponytail: one indexed lookup per sid (~1k sids per realtime instance per 15 s); batch with unnest() if identity load shows it.
    @PostMapping("/internal/sessions/check")
    @convoy.platform.security.RequiresScope("sessions:check")
    public Api.SessionCheck check(@Valid @RequestBody CheckBody body) {
        Caller.current().requireScope("sessions:check");
        List<String> invalid = body.sessions().stream().filter(s -> !Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from sessions s where s.id_hash = ? and s.revoked_at is null and s.expires_at > clock_timestamp() "
                        + "and (?::uuid is null or exists (select 1 from dealership_staff d where d.user_id = s.user_id and d.dealership_id = ?::uuid "
                        + "and d.revoked_at is null)))", Boolean.class, s.sid(), s.dealershipId(), s.dealershipId()))).map(SessionRef::sid).toList();
        return Dto.as(Map.of("invalid", invalid), Api.SessionCheck.class);
    }
}
