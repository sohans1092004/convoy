package convoy.identity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
import convoy.platform.messaging.Uuid7;
import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.security.Caller;
import convoy.platform.security.JwtVerifier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** E008 session validation (gateway only), E009 service tokens (client credentials), JWKS. */
@RestController
public class InternalController {
    static final long SERVICE_TOKEN_SECONDS = 300;
    private final JdbcTemplate jdbc;
    private final Users users;
    private final SigningKeys keys;
    private final ObjectMapper json;

    public InternalController(JdbcTemplate jdbc, Users users, SigningKeys keys, ObjectMapper json) {
        this.jdbc = jdbc;
        this.users = users;
        this.keys = keys;
        this.json = json;
    }

    public record ValidateBody(@NotBlank String sessionId) {}

    /** E008: only the gateway (scope sessions:validate, AZ-SVC-01). Touches last_seen at most once a minute. */
    @PostMapping("/internal/sessions/validate")
    @convoy.platform.security.RequiresScope("sessions:validate")
    public Api.SessionValidation validate(@Valid @RequestBody ValidateBody body) {
        Caller.current().requireScope("sessions:validate");
        String hash = Crypto.sha256(body.sessionId());
        // A read on the hot path; last_seen is written only when over a minute old (an unconditional UPDATE wrote the row on
        // every request and made identity the W3 bottleneck).
        List<Map<String, Object>> rows = jdbc.queryForList("select user_id, expires_at from sessions where id_hash = ? and revoked_at is null "
                + "and expires_at > clock_timestamp()", hash);
        if (!rows.isEmpty()) jdbc.update("update sessions set last_seen_at = clock_timestamp() where id_hash = ? and last_seen_at < clock_timestamp() - interval '1 minute'", hash);
        if (rows.isEmpty()) throw ApiProblem.of(ProblemType.UNAUTHENTICATED, "Session is not valid.");
        UUID userId = (UUID) rows.get(0).get("user_id");
        Users.User u = users.find(userId).orElseThrow(() -> ApiProblem.of(ProblemType.UNAUTHENTICATED, "Account not active."));
        Map<String, Object> out = new HashMap<>();
        out.put("user_id", u.id().toString());
        out.put("roles", u.roles());
        out.put("dealership_id", u.dealershipId() == null ? null : u.dealershipId().toString());
        out.put("sid", hash);
        out.put("session_expires_at", ((java.sql.Timestamp) rows.get(0).get("expires_at")).toInstant().toString());
        return convoy.platform.web.Dto.as(out, Api.SessionValidation.class);
    }

    public record TokenBody(String grantType, @NotBlank String clientId, @NotBlank String clientSecret, @NotBlank String audience) {}

    /** E009: client credentials -> ES256 service token {iss convoy-identity, sub client, aud callee, scope}. Public path. */
    @PostMapping("/internal/oauth/token")
    public Api.ServiceToken token(@Valid @RequestBody TokenBody body) throws Exception {
        List<Map<String, Object>> rows = jdbc.queryForList("select secret_hash, scopes::text as scopes from service_clients where client_id = ?", body.clientId());
        if (rows.isEmpty() || !Crypto.constantTimeEquals((String) rows.get(0).get("secret_hash"), Crypto.sha256(body.clientSecret()))) {
            throw ApiProblem.of(ProblemType.UNAUTHENTICATED, "Unknown client or wrong secret.");
        }
        Map<String, List<String>> scopes = json.readValue((String) rows.get(0).get("scopes"), new TypeReference<>() {});
        List<String> granted = scopes.get(body.audience());
        if (granted == null) throw ApiProblem.of(ProblemType.FORBIDDEN, body.clientId() + " may not call " + body.audience() + ".");
        Instant now = Instant.now();
        String jwt = keys.sign(new JWTClaimsSet.Builder().issuer(JwtVerifier.IDENTITY).subject(body.clientId()).audience(body.audience())
                .claim("scope", granted).issueTime(Date.from(now)).notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(SERVICE_TOKEN_SECONDS))).jwtID(Uuid7.next().toString()).build());
        return convoy.platform.web.Dto.as(Map.of("access_token", jwt, "token_type", "Bearer", "expires_in", SERVICE_TOKEN_SECONDS, "scope", granted), Api.ServiceToken.class);
    }

    @GetMapping("/.well-known/jwks.json")
    public convoy.contracts.api.Jwks jwks() { return convoy.platform.web.Dto.as(keys.jwks(), convoy.contracts.api.Jwks.class); }
}
