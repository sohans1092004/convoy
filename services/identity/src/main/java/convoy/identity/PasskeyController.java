package convoy.identity;

import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticationRequest;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.RegistrationRequest;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import convoy.platform.messaging.Uuid7;
import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.security.Caller;
import convoy.platform.web.Dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Passkeys (WebAuthn, auth-contract.md §1): E003 registration options, E004 register, E005 sign-in (options, then the
 * signed assertion). Attestation "none" (we trust the browser's ceremony, not the authenticator vendor); user
 * verification required; credentials are discoverable, so sign-in needs no email. Verification by webauthn4j.
 */
@RestController
public class PasskeyController {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    // ES256 and RS256: what platform authenticators (Android, Apple, Windows Hello) and security keys use.
    private static final List<PublicKeyCredentialParameters> ALGS = List.of(
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256),
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.RS256));

    private final JdbcTemplate jdbc;
    private final Users users;
    private final AuthController auth;
    private final IdentityApplication.IdentitySettings.Passkeys settings;
    private final WebAuthnManager webauthn = WebAuthnManager.createNonStrictWebAuthnManager();
    private final AttestedCredentialDataConverter credentials = new AttestedCredentialDataConverter(new ObjectConverter());

    public PasskeyController(JdbcTemplate jdbc, Users users, AuthController auth, IdentityApplication.IdentitySettings s) {
        this.jdbc = jdbc;
        this.users = users;
        this.auth = auth;
        this.settings = s.passkeys();
    }

    public record Register(@NotBlank String challengeId, @NotBlank String clientDataJson, @NotBlank String attestationObject,
                           @Nullable List<String> transports) {}

    public record Authenticate(@NotBlank String challengeId, @NotBlank String credentialId, @NotBlank String clientDataJson,
                               @NotBlank String authenticatorData, @NotBlank String signature, @Nullable String userHandle) {}

    /** E003. A one-time challenge for the signed-in user; their existing passkeys are excluded (no duplicates). */
    @PostMapping("/auth/passkeys/register/options")
    @Transactional
    public Api.PasskeyRegistrationOptions registerOptions() {
        Users.User u = users.find(Caller.current().userId()).orElseThrow(() -> ApiProblem.of(ProblemType.UNAUTHENTICATED, "Sign in again."));
        Challenge c = challenge(u.id(), "REGISTER");
        List<String> exclude = jdbc.queryForList("select credential_id from passkeys where user_id = ?", byte[].class, u.id()).stream().map(B64::encodeToString).toList();
        return Dto.as(Map.of("challenge_id", c.id().toString(), "challenge", B64.encodeToString(c.bytes()), "rp_id", settings.rpId(), "rp_name", settings.rpName(),
                "user_handle", B64.encodeToString(uuidBytes(u.id())), "user_name", u.email(), "timeout_ms", timeoutMs(), "exclude_credentials", exclude),
                Api.PasskeyRegistrationOptions.class);
    }

    /** E004. Verifies the attestation against the challenge, origin and RP id, then stores the public key. */
    @PostMapping("/auth/passkeys/register")
    @Transactional(noRollbackFor = ApiProblem.class)
    public Api.PasskeyRegistered register(@Valid @RequestBody Register body) {
        UUID userId = Caller.current().userId();
        byte[] challenge = useChallenge(body.challengeId(), "REGISTER", userId, ProblemType.VALIDATION_FAILED);
        RegistrationData data;
        try {
            data = webauthn.verify(new RegistrationRequest(decode(body.attestationObject()), decode(body.clientDataJson()), null,
                            body.transports() == null ? Set.of() : new HashSet<>(body.transports())),
                    new RegistrationParameters(server(challenge), ALGS, true, true));
        } catch (RuntimeException e) { // webauthn4j's DataConversionException and VerificationException are unchecked
            throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "This passkey could not be verified. Try again.");
        }
        AttestedCredentialData cred = data.getAttestationObject().getAuthenticatorData().getAttestedCredentialData();
        UUID id = Uuid7.next();
        String transports = body.transports() == null ? "" : String.join(",", body.transports());
        int n = jdbc.update("insert into passkeys(id, user_id, credential_id, public_key, sign_count, transports) "
                        + "values (?, ?, ?, ?, ?, coalesce(string_to_array(nullif(?, ''), ','), '{}')) on conflict (credential_id) do nothing",
                id, userId, cred.getCredentialId(), credentials.convert(cred), data.getAttestationObject().getAuthenticatorData().getSignCount(), transports);
        if (n == 0) throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "This passkey is already registered.");
        OffsetDateTime at = jdbc.queryForObject("select created_at from passkeys where id = ?", OffsetDateTime.class, id);
        return Dto.as(Map.of("credential_id", B64.encodeToString(cred.getCredentialId()), "created_at", at.toInstant().toString()), Api.PasskeyRegistered.class);
    }

    /** E005, step 1. Anonymous: a one-time challenge for a discoverable-credential sign-in. */
    @PostMapping("/auth/passkeys/authenticate/options")
    @Transactional
    public Api.PasskeyAuthenticationOptions authenticateOptions() {
        Challenge c = challenge(null, "AUTHENTICATE");
        return Dto.as(Map.of("challenge_id", c.id().toString(), "challenge", B64.encodeToString(c.bytes()), "rp_id", settings.rpId(), "timeout_ms", timeoutMs()),
                Api.PasskeyAuthenticationOptions.class);
    }

    /** E005. Verifies the signed assertion (signature, challenge, origin, RP id, counter) and creates a session. */
    @PostMapping("/auth/passkeys/authenticate")
    @Transactional(noRollbackFor = ApiProblem.class)
    public Api.Session authenticate(@Valid @RequestBody Authenticate body) {
        byte[] challenge = useChallenge(body.challengeId(), "AUTHENTICATE", null, ProblemType.UNAUTHENTICATED);
        byte[] credentialId = decode(body.credentialId());
        List<Map<String, Object>> rows = jdbc.queryForList("select id, user_id, public_key, sign_count from passkeys where credential_id = ? for update", (Object) credentialId);
        if (rows.isEmpty()) throw ApiProblem.of(ProblemType.UNAUTHENTICATED, "This passkey is not registered with Convoy. Sign in with an email code.");
        Map<String, Object> row = rows.get(0);
        UUID userId = (UUID) row.get("user_id");
        if (body.userHandle() != null && !java.util.Arrays.equals(decode(body.userHandle()), uuidBytes(userId))) {
            throw ApiProblem.of(ProblemType.UNAUTHENTICATED, "This passkey could not be verified.");
        }
        CredentialRecordImpl record = new CredentialRecordImpl(new NoneAttestationStatement(), null, null, null, (long) row.get("sign_count"),
                credentials.convert((byte[]) row.get("public_key")), null, null, null, null);
        AuthenticationData data;
        try {
            data = webauthn.verify(new AuthenticationRequest(credentialId, body.userHandle() == null ? null : decode(body.userHandle()),
                            decode(body.authenticatorData()), decode(body.clientDataJson()), null, decode(body.signature())),
                    new AuthenticationParameters(server(challenge), record, null, true, true));
        } catch (RuntimeException e) {
            throw ApiProblem.of(ProblemType.UNAUTHENTICATED, "This passkey could not be verified.");
        }
        jdbc.update("update passkeys set sign_count = ?, last_used_at = clock_timestamp() where id = ?",
                data.getAuthenticatorData().getSignCount(), row.get("id"));
        if (users.find(userId).isEmpty()) throw ApiProblem.of(ProblemType.UNAUTHENTICATED, "This account is not active.");
        return auth.session(userId);
    }

    private record Challenge(UUID id, byte[] bytes) {}

    private Challenge challenge(@Nullable UUID userId, String purpose) {
        Challenge c = new Challenge(Uuid7.next(), new byte[32]);
        RANDOM.nextBytes(c.bytes());
        jdbc.update("insert into passkey_challenges(id, user_id, purpose, challenge, expires_at) values (?, ?, ?, ?, clock_timestamp() + make_interval(mins => ?))",
                c.id(), userId, purpose, c.bytes(), settings.challengeMinutes());
        return c;
    }

    /** Consumes a challenge once (DB clock decides expiry); a wrong user, purpose, reuse or expiry all fail the same way. */
    private byte[] useChallenge(String id, String purpose, @Nullable UUID userId, ProblemType onFail) {
        UUID cid;
        try {
            cid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw ApiProblem.of(onFail, "This sign-in attempt has expired. Try again.");
        }
        List<byte[]> c = jdbc.queryForList("update passkey_challenges set used_at = clock_timestamp() where id = ? and purpose = ? and used_at is null "
                + "and expires_at > clock_timestamp() and user_id is not distinct from ? returning challenge", byte[].class, cid, purpose, userId);
        if (c.isEmpty()) throw ApiProblem.of(onFail, "This sign-in attempt has expired. Try again.");
        return c.get(0);
    }

    private ServerProperty server(byte[] challenge) {
        return new ServerProperty(settings.origins().stream().map(Origin::new).collect(Collectors.toSet()), settings.rpId(), new DefaultChallenge(challenge));
    }

    private long timeoutMs() { return settings.challengeMinutes() * 60_000L; }

    private static byte[] decode(String b64url) {
        try {
            return Base64.getUrlDecoder().decode(b64url);
        } catch (IllegalArgumentException e) {
            throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "Malformed passkey data.");
        }
    }

    private static byte[] uuidBytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
