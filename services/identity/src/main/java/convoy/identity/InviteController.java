package convoy.identity;

import convoy.contracts.events.StaffInvited;
import convoy.platform.idempotency.Idempotent;
import convoy.platform.messaging.Outbox;
import convoy.platform.messaging.Uuid7;
import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.security.Caller;
import convoy.platform.security.RequiresRole;
import convoy.platform.web.Dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff invites (P3.A): E010 a dealership admin invites an email address as staff or admin; E011 the invitee accepts.
 * The link carries a random token (only its SHA-256 is stored), expires (identity.invite-hours, DB clock) and works once,
 * and only for the invited email address. Revocation is E012 (StaffController).
 */
@RestController
public class InviteController {
    private static final Logger log = LoggerFactory.getLogger(InviteController.class);
    private final JdbcTemplate jdbc;
    private final Crypto crypto;
    private final Users users;
    private final Outbox outbox;
    private final JavaMailSender mail;
    private final IdentityApplication.IdentitySettings settings;

    public InviteController(JdbcTemplate jdbc, Crypto crypto, Users users, Outbox outbox, JavaMailSender mail, IdentityApplication.IdentitySettings settings) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.users = users;
        this.outbox = outbox;
        this.mail = mail;
        this.settings = settings;
    }

    public record Invite(@NotBlank @Email String email, @NotBlank @Pattern(regexp = "dealer_staff|dealer_admin") String role) {}

    /** E010. Admin of the same dealership only (anything else: 404, no existence leak). */
    @PostMapping("/dealerships/{id}/staff/invites")
    @RequiresRole("dealer_admin")
    @Idempotent
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public Api.StaffInvite invite(@PathVariable UUID id, @Valid @RequestBody Invite b) {
        Caller c = Caller.current();
        if (!id.toString().equals(c.dealershipId())) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such dealership.");
        String email = Users.normalise(b.email());
        String token = Crypto.randomToken();
        UUID inviteId = Uuid7.next();
        OffsetDateTime expires = jdbc.queryForObject("insert into staff_invites(id, dealership_id, email_hash, role, token_hash, invited_by, expires_at) "
                        + "values (?, ?, ?, ?, ?, ?, clock_timestamp() + make_interval(hours => ?)) returning expires_at", OffsetDateTime.class,
                inviteId, id, crypto.hash(email), b.role(), Crypto.sha256(token), c.userId(), settings.inviteHours());
        outbox.append(inviteId, 1, new StaffInvited(id.toString(), inviteId.toString(), b.role(), c.userId().toString()));
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(settings.mailFrom());
        m.setTo(email);
        m.setSubject("You are invited to a dealership on Convoy");
        m.setText("You have been invited to join a dealership on Convoy as " + (b.role().equals("dealer_admin") ? "an admin" : "staff")
                + ". Sign in with this email address and open: " + settings.webUrl() + "/staff-invites/" + token
                + "\nThe link works once and expires in " + settings.inviteHours() + " hours.");
        try {
            mail.send(m);
        } catch (org.springframework.mail.MailException e) {
            log.warn("invite mail failed: {}", e.getMessage());
            throw ApiProblem.of(ProblemType.DEPENDENCY_UNAVAILABLE, "We could not send the invite. Try again in a minute.");
        }
        return Dto.as(Map.of("invite_id", inviteId.toString(), "role", b.role(), "expires_at", expires.toInstant().toString()), Api.StaffInvite.class);
    }

    /** E011. The signed-in user must own the invited email; unknown, used, expired or someone else's invite: 404. */
    @PostMapping("/staff-invites/{token}/accept")
    @Idempotent
    @Transactional
    public Api.StaffMembership accept(@PathVariable String token) {
        Caller c = Caller.current();
        Users.User u = users.find(c.userId()).orElseThrow(() -> ApiProblem.of(ProblemType.UNAUTHENTICATED, "Sign in again."));
        List<Map<String, Object>> rows = jdbc.queryForList("update staff_invites set accepted_at = clock_timestamp(), accepted_by = ? where token_hash = ? "
                + "and email_hash = ? and accepted_at is null and expires_at > clock_timestamp() returning dealership_id, role", u.id(), Crypto.sha256(token), crypto.hash(u.email()));
        if (rows.isEmpty()) throw ApiProblem.of(ProblemType.NOT_FOUND, "This invite link is not valid: it may have expired, been used, or be for another email address.");
        UUID dealership = (UUID) rows.get(0).get("dealership_id");
        String role = (String) rows.get(0).get("role");
        grant(u.id(), dealership, role);
        return Dto.as(Map.of("dealership_id", dealership.toString(), "role", role), Api.StaffMembership.class);
    }

    /** Active membership with this role (re-activates a revoked one) and the matching user role. */
    void grant(UUID user, UUID dealership, String role) {
        jdbc.update("insert into dealership_staff(dealership_id, user_id, role) values (?, ?, ?) on conflict (dealership_id, user_id) "
                + "do update set role = excluded.role, status = 'ACTIVE', revoked_at = null", dealership, user, role);
        jdbc.update("insert into user_roles(user_id, role) values (?, ?) on conflict do nothing", user, role);
    }

    /** DealershipVerified: the user who registered the dealership becomes its admin. */
    void dealershipVerified(convoy.contracts.events.DealershipVerified p) {
        if (p.registeredBy() == null) return; // seeded / synthetic dealerships
        UUID user = UUID.fromString(p.registeredBy());
        if (users.find(user).isEmpty()) return;
        grant(user, UUID.fromString(p.dealershipId()), "dealer_admin");
        log.info("dealership {} verified at {}: registrant is its admin", p.dealershipId(), Instant.now());
    }
}
