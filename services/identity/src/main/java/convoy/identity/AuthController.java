package convoy.identity;

import convoy.contracts.events.SessionRevoked;
import convoy.platform.messaging.Outbox;
import convoy.platform.messaging.Uuid7;
import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.security.Caller;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** E001, E002, E006 (passkeys E003-E005: PasskeyController). */
@RestController
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final int MAX_ATTEMPTS = 5;
    private final JdbcTemplate jdbc;
    private final Crypto crypto;
    private final Users users;
    private final JavaMailSender mail;
    private final Outbox outbox;
    private final IdentityApplication.IdentitySettings settings;

    public AuthController(JdbcTemplate jdbc, Crypto crypto, Users users, JavaMailSender mail, Outbox outbox,
                          IdentityApplication.IdentitySettings settings) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.users = users;
        this.mail = mail;
        this.outbox = outbox;
        this.settings = settings;
    }

    public record OtpStart(@NotBlank @Email String email) {}

    public record OtpVerify(@NotBlank @Email String email, @NotBlank @Pattern(regexp = "\\d{6}") String code) {}

    /** E001. Always 202, whether or not the address has an account (no enumeration). Rate limited at the gateway. */
    @PostMapping("/auth/otp/start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Api.OtpStarted start(@Valid @RequestBody OtpStart body) {
        String email = Users.normalise(body.email());
        String code = Crypto.otpCode();
        jdbc.update("insert into otp_challenges(id, email_hash, code_hash, expires_at) values (?, ?, ?, clock_timestamp() + make_interval(mins => ?))",
                Uuid7.next(), crypto.hash(email), crypto.hash(email + ":" + code), settings.otpMinutes());
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(settings.mailFrom());
        m.setTo(email);
        m.setSubject("Your Convoy sign-in code");
        m.setText("Your Convoy sign-in code is " + code + ". It expires in " + settings.otpMinutes()
                + " minutes. If you did not ask for it, ignore this email.");
        try {
            mail.send(m);
        } catch (org.springframework.mail.MailException e) {
            log.warn("OTP mail failed: {}", e.getMessage());
            throw ApiProblem.of(ProblemType.DEPENDENCY_UNAVAILABLE, "We could not send the code. Try again in a minute.");
        }
        return convoy.platform.web.Dto.as(Map.of("status", "SENT", "expires_in_seconds", settings.otpMinutes() * 60), Api.OtpStarted.class);
    }

    /** E002. Checks the latest unexpired challenge (5 attempts), then creates a session. */
    @PostMapping("/auth/otp/verify")
    @Transactional(noRollbackFor = ApiProblem.class)
    public Api.Session verify(@Valid @RequestBody OtpVerify body) {
        String email = Users.normalise(body.email());
        List<Map<String, Object>> rows = jdbc.queryForList("select id, code_hash, attempts from otp_challenges where email_hash = ? "
                + "and used_at is null and expires_at > clock_timestamp() order by created_at desc limit 1 for update", crypto.hash(email));
        if (rows.isEmpty()) throw ApiProblem.of(ProblemType.UNAUTHENTICATED, "That code has expired. Ask for a new one.");
        Map<String, Object> c = rows.get(0);
        UUID cid = (UUID) c.get("id");
        if ((int) c.get("attempts") >= MAX_ATTEMPTS) throw ApiProblem.of(ProblemType.UNAUTHENTICATED, "Too many attempts. Ask for a new code.");
        if (!Crypto.constantTimeEquals((String) c.get("code_hash"), crypto.hash(email + ":" + body.code()))) {
            jdbc.update("update otp_challenges set attempts = attempts + 1 where id = ?", cid);
            throw ApiProblem.of(ProblemType.UNAUTHENTICATED, "That code is not right. Check the email and try again.");
        }
        jdbc.update("update otp_challenges set used_at = clock_timestamp() where id = ?", cid);
        return session(users.findOrRegister(email));
    }

    /** A new session for the user (OTP or passkey sign-in). Only SHA-256 of the id is stored. */
    Api.Session session(UUID userId) {
        String sessionId = Crypto.randomToken();
        OffsetDateTime expires = jdbc.queryForObject("insert into sessions(id_hash, user_id, expires_at) values (?, ?, clock_timestamp() + make_interval(days => ?)) "
                + "returning expires_at", OffsetDateTime.class, Crypto.sha256(sessionId), userId, settings.sessionDays());
        Users.User u = users.find(userId).orElseThrow();
        return convoy.platform.web.Dto.as(Map.of("session_id", sessionId, "session_expires_at", expires.toInstant().toString(),
                "user", Map.of("id", u.id().toString(), "roles", u.roles())), Api.Session.class);
    }

    /** E006. Revokes the caller's session; the gateway cache entry expires within 30 s (or on SessionRevoked). */
    @PostMapping("/auth/logout")
    @Transactional
    public Api.Status logout() {
        Caller c = Caller.current();
        int n = jdbc.update("update sessions set revoked_at = clock_timestamp() where id_hash = ? and revoked_at is null", c.sessionHash());
        if (n == 1) outbox.append(c.userId(), Instant.now().toEpochMilli(), new SessionRevoked(c.subject(), c.sessionHash(), "LOGOUT"));
        return convoy.platform.web.Dto.as(Map.of("status", "LOGGED_OUT"), Api.Status.class);
    }

}
