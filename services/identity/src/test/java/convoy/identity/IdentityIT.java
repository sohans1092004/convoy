package convoy.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.platform.testing.AuthzDenyChecks;
import convoy.platform.testing.Outboxes;
import convoy.platform.testing.ServiceTest;
import convoy.platform.testing.TestTokens;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** Email OTP, sessions, service tokens, JWKS, consent; authorisation for every P1 identity endpoint. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "convoy.messaging.enabled=false", "identity.data-key=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
        "identity.clients.gateway.secret=gw-secret", "identity.clients.pool.secret=pool-secret",
        "convoy.issuers.convoy-gateway=http://unused", "convoy.issuers.convoy-identity=http://unused"})
@Import({ServiceTest.Config.class, IdentityIT.Mail.class})
class IdentityIT extends ServiceTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<SimpleMailMessage> SENT = new CopyOnWriteArrayList<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class Mail {
        @Bean
        @Primary
        JavaMailSenderImpl capturingMail() {
            return new JavaMailSenderImpl() {
                @Override public void send(SimpleMailMessage m) { SENT.add(m); }
            };
        }
    }

    @Autowired JdbcTemplate jdbc;

    String anon() { return TestTokens.anonymous("identity"); }

    String lastCode(String email) {
        for (int i = SENT.size() - 1; i >= 0; i--) {
            if (SENT.get(i).getTo()[0].equals(email)) {
                Matcher m = Pattern.compile("\\b(\\d{6})\\b").matcher(SENT.get(i).getText());
                if (m.find()) return m.group(1);
            }
        }
        throw new AssertionError("no mail for " + email);
    }

    @Test
    void otpSignInCreatesAHashedSessionThatOnlyTheGatewayCanValidate() throws Exception {
        String email = "new-" + UUID.randomUUID() + "@convoy.test";
        assertThat(send("POST", "/auth/otp/start", anon(), "{\"email\":\"" + email + "\"}", Map.of()).status()).isEqualTo(202);
        String code = lastCode(email);
        String wrong = code.equals("000000") ? "111111" : "000000";
        assertThat(send("POST", "/auth/otp/verify", anon(), "{\"email\":\"" + email + "\",\"code\":\"" + wrong + "\"}", Map.of()).status()).isEqualTo(401);
        Resp ok = send("POST", "/auth/otp/verify", anon(), "{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}", Map.of());
        assertThat(ok.status()).as(ok.body()).isEqualTo(200);
        JsonNode v = JSON.readTree(ok.body());
        String session = v.path("session_id").asText();
        assertThat(v.at("/user/roles/0").asText()).isEqualTo("buyer");
        // Only the SHA-256 of the session id is stored; the code cannot be reused.
        assertThat(jdbc.queryForObject("select count(*) from sessions where id_hash = ?", Integer.class, Crypto.sha256(session))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from sessions where id_hash = ?", Integer.class, session)).isZero();
        assertThat(send("POST", "/auth/otp/verify", anon(), "{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}", Map.of()).status()).isEqualTo(401);
        // E008 needs sessions:validate (AZ-SVC-01).
        String gw = TestTokens.service("identity", "gateway", List.of("sessions:validate"));
        Resp valid = send("POST", "/internal/sessions/validate", gw, "{\"session_id\":\"" + session + "\"}", Map.of());
        assertThat(JSON.readTree(valid.body()).path("user_id").asText()).isEqualTo(v.at("/user/id").asText());
        assertThat(send("POST", "/internal/sessions/validate", TestTokens.service("identity", "pool", List.of("deposits:create")), "{\"session_id\":\"" + session + "\"}", Map.of()).status()).isEqualTo(403);
        assertThat(send("POST", "/internal/sessions/validate", gw, "{\"session_id\":\"nope\"}", Map.of()).status()).isEqualTo(401);
        // Logout revokes the session.
        String userToken = TestTokens.user("identity", UUID.fromString(v.at("/user/id").asText()), List.of("buyer"), null).replace("sid-", "");
        jdbc.update("update sessions set revoked_at = clock_timestamp() where id_hash = ?", Crypto.sha256(session));
        assertThat(send("POST", "/internal/sessions/validate", gw, "{\"session_id\":\"" + session + "\"}", Map.of()).status()).isEqualTo(401);
        assertThat(Outboxes.assertAllValid(jdbc, "identity")).isPositive();
    }

    @Test
    void fiveWrongCodesLockTheChallenge() throws Exception {
        String email = "lock-" + UUID.randomUUID() + "@convoy.test";
        send("POST", "/auth/otp/start", anon(), "{\"email\":\"" + email + "\"}", Map.of());
        String code = lastCode(email);
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) send("POST", "/auth/otp/verify", anon(), "{\"email\":\"" + email + "\",\"code\":\"" + wrong + "\"}", Map.of());
        assertThat(send("POST", "/auth/otp/verify", anon(), "{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}", Map.of()).status()).isEqualTo(401);
    }

    @Test
    void serviceTokensFollowClientScopesAndJwksIsPublished() throws Exception {
        Resp t = send("POST", "/internal/oauth/token", null, "{\"grant_type\":\"client_credentials\",\"client_id\":\"pool\",\"client_secret\":\"pool-secret\",\"audience\":\"payment\"}", Map.of());
        assertThat(t.status()).isEqualTo(200);
        assertThat(JSON.readTree(t.body()).at("/scope/0").asText()).isEqualTo("deposits:create");
        assertThat(send("POST", "/internal/oauth/token", null, "{\"client_id\":\"pool\",\"client_secret\":\"wrong\",\"audience\":\"payment\"}", Map.of()).status()).isEqualTo(401);
        assertThat(send("POST", "/internal/oauth/token", null, "{\"client_id\":\"pool\",\"client_secret\":\"pool-secret\",\"audience\":\"realtime\"}", Map.of()).status()).isEqualTo(403); // pool has identity since P5.D (privacy:write), never realtime
        JsonNode jwks = JSON.readTree(get("/.well-known/jwks.json", null).body());
        assertThat(jwks.at("/keys/0/kty").asText()).isEqualTo("EC");
        assertThat(jwks.at("/keys/0/d").isMissingNode()).isTrue(); // public keys only
    }

    @Test
    void consentHistoryIsAppendOnly() throws Exception {
        String email = "consent-" + UUID.randomUUID() + "@convoy.test";
        send("POST", "/auth/otp/start", anon(), "{\"email\":\"" + email + "\"}", Map.of());
        JsonNode v = JSON.readTree(send("POST", "/auth/otp/verify", anon(), "{\"email\":\"" + email + "\",\"code\":\"" + lastCode(email) + "\"}", Map.of()).body());
        String token = TestTokens.buyer("identity", UUID.fromString(v.at("/user/id").asText()));
        String n = "{\"granted\":%s,\"notice_version\":\"2026-10-26-p5\"}";
        UUID user = UUID.fromString(v.at("/user/id").asText());
        String contact = "/internal/users/" + user + "/contact";
        assertThat(get(contact, TestTokens.service("identity", "order", java.util.List.of("contacts:read"))).status()).isEqualTo(200);
        send("PUT", "/me/consents/DEALER_DISCLOSURE", token, n.formatted(true), Map.of());
        JsonNode h = JSON.readTree(send("PUT", "/me/consents/DEALER_DISCLOSURE", token, n.formatted(false), Map.of()).body());
        assertThat(h.path("history")).hasSize(2);
        assertThat(JSON.readTree(get("/me", token).body()).path("email").asText()).isEqualTo(email);
        // P5.D: withdrawn -> dealers (through order) no longer get the contact; notification still emails the buyer
        assertThat(get(contact, TestTokens.service("identity", "order", java.util.List.of("contacts:read"))).status()).isEqualTo(404);
        assertThat(get(contact, TestTokens.service("identity", "notification", java.util.List.of("contacts:read"))).status()).isEqualTo(200);
        // only withdrawable purposes can be withdrawn, only against the current notice, only known purposes
        assertThat(send("PUT", "/me/consents/ACCOUNT", token, n.formatted(false), Map.of()).status()).isEqualTo(400);
        assertThat(send("PUT", "/me/consents/DEALER_DISCLOSURE", token, "{\"granted\":true,\"notice_version\":\"n1\"}", Map.of()).status()).isEqualTo(400);
        assertThat(send("PUT", "/me/consents/MARKETING", token, n.formatted(true), Map.of()).status()).isEqualTo(400);
        // E168: every purpose with its notice, my current choice and history
        JsonNode all = JSON.readTree(get("/me/consents", token).body());
        assertThat(all.path("notice_version").asText()).isEqualTo("2026-10-26-p5");
        assertThat(all.path("purposes").findValuesAsText("purpose")).containsExactly("ACCOUNT", "DEPOSITS", "DEALER_DISCLOSURE");
        JsonNode dd = all.path("purposes").get(2);
        assertThat(dd.path("granted").asBoolean(true)).isFalse();
        assertThat(dd.path("withdrawable").asBoolean()).isTrue();
        assertThat(dd.path("history")).hasSize(2);
        assertThat(all.path("purposes").get(0).path("granted").isNull()).isTrue();
    }

    @Autowired Users users;

    String otpSession(String email) throws Exception {
        send("POST", "/auth/otp/start", anon(), "{\"email\":\"" + email + "\"}", Map.of());
        return send("POST", "/auth/otp/verify", anon(), "{\"email\":\"" + email + "\",\"code\":\"" + lastCode(email) + "\"}", Map.of()).body();
    }

    String lastInviteToken(String email) {
        for (int i = SENT.size() - 1; i >= 0; i--) {
            if (SENT.get(i).getTo()[0].equals(email)) {
                Matcher m = Pattern.compile("/staff-invites/([A-Za-z0-9_-]+)").matcher(SENT.get(i).getText());
                if (m.find()) return m.group(1);
            }
        }
        throw new AssertionError("no invite mail for " + email);
    }

    @Test
    void staffInviteWorksOnceForTheInvitedEmailAndExpires() throws Exception {
        UUID dealership = UUID.randomUUID();
        String admin = TestTokens.user("identity", UUID.randomUUID(), List.of("dealer_admin"), dealership);
        String email = "invitee-" + UUID.randomUUID() + "@convoy.test";
        java.util.function.Supplier<String> idem = () -> UUID.randomUUID().toString();
        Resp r = send("POST", "/dealerships/" + dealership + "/staff/invites", admin, "{\"email\":\"" + email + "\",\"role\":\"dealer_staff\"}", Map.of("Idempotency-Key", idem.get()));
        assertThat(r.status()).isEqualTo(201);
        String token = lastInviteToken(email);
        assertThat(jdbc.queryForObject("select count(*) from staff_invites where token_hash = ?", Integer.class, Crypto.sha256(token))).isEqualTo(1); // only the hash is stored
        // Another dealership's admin cannot invite here; a bad role is a validation error.
        assertThat(send("POST", "/dealerships/" + dealership + "/staff/invites", TestTokens.user("identity", UUID.randomUUID(), List.of("dealer_admin"), UUID.randomUUID()),
                "{\"email\":\"x@convoy.test\",\"role\":\"dealer_staff\"}", Map.of("Idempotency-Key", idem.get())).status()).isEqualTo(404);
        assertThat(send("POST", "/dealerships/" + dealership + "/staff/invites", admin, "{\"email\":\"x@convoy.test\",\"role\":\"platform_admin\"}", Map.of("Idempotency-Key", idem.get())).status()).isEqualTo(400);
        // Someone else signed in cannot use it.
        UUID stranger = UUID.fromString(JSON.readTree(otpSession("stranger-" + UUID.randomUUID() + "@convoy.test")).at("/user/id").asText());
        assertThat(send("POST", "/staff-invites/" + token + "/accept", TestTokens.buyer("identity", stranger), null, Map.of("Idempotency-Key", idem.get())).status()).isEqualTo(404);
        // The invitee accepts once: membership + role; a second use is 404.
        UUID invitee = UUID.fromString(JSON.readTree(otpSession(email)).at("/user/id").asText());
        Resp ok = send("POST", "/staff-invites/" + token + "/accept", TestTokens.buyer("identity", invitee), null, Map.of("Idempotency-Key", idem.get()));
        assertThat(ok.status()).isEqualTo(200);
        assertThat(users.find(invitee).orElseThrow().dealershipId()).isEqualTo(dealership);
        assertThat(users.find(invitee).orElseThrow().roles()).contains("dealer_staff");
        assertThat(send("POST", "/staff-invites/" + token + "/accept", TestTokens.buyer("identity", invitee), null, Map.of("Idempotency-Key", idem.get())).status()).isEqualTo(404);
        // An expired invite is 404 (the DB clock decides).
        String email2 = "late-" + UUID.randomUUID() + "@convoy.test";
        send("POST", "/dealerships/" + dealership + "/staff/invites", admin, "{\"email\":\"" + email2 + "\",\"role\":\"dealer_admin\"}", Map.of("Idempotency-Key", idem.get()));
        String late = lastInviteToken(email2);
        jdbc.update("update staff_invites set expires_at = clock_timestamp() - interval '1 second' where token_hash = ?", Crypto.sha256(late));
        UUID lateUser = UUID.fromString(JSON.readTree(otpSession(email2)).at("/user/id").asText());
        assertThat(send("POST", "/staff-invites/" + late + "/accept", TestTokens.buyer("identity", lateUser), null, Map.of("Idempotency-Key", idem.get())).status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select count(*) from outbox where event_type = 'StaffInvited' and payload->>'dealership_id' = ?", Integer.class, dealership.toString())).isEqualTo(2);
    }

    @Test
    void aVerifiedDealershipMakesItsRegistrantTheAdmin() throws Exception {
        UUID registrant = UUID.fromString(JSON.readTree(otpSession("registrant-" + UUID.randomUUID() + "@convoy.test")).at("/user/id").asText());
        UUID dealership = UUID.randomUUID();
        invites.dealershipVerified(new convoy.contracts.events.DealershipVerified(dealership.toString(), List.of("Hyundai"), "Pune", UUID.randomUUID().toString(), registrant.toString()));
        assertThat(users.find(registrant).orElseThrow().dealershipId()).isEqualTo(dealership);
        assertThat(users.find(registrant).orElseThrow().roles()).contains("dealer_admin");
    }

    @Autowired InviteController invites;

    @Test
    void anEmailCodeSessionNeverCarriesPlatformAdmin() throws Exception {
        // AMENDMENT-008 (Q-043): admin powers need a passkey sign-in.
        String email = "admin-" + UUID.randomUUID() + "@convoy.test";
        users.seed(email, List.of("platform_admin"), null);
        JsonNode v = JSON.readTree(otpSession(email));
        assertThat(v.at("/user/roles").toString()).doesNotContain("platform_admin");
        String sid = Crypto.sha256(v.path("session_id").asText());
        assertThat(jdbc.queryForObject("select auth_method from sessions where id_hash = ?", String.class, sid)).isEqualTo("otp");
        assertThat(AuthController.sessionRoles(List.of("buyer", "platform_admin"), "otp")).containsExactly("buyer");
        assertThat(AuthController.sessionRoles(List.of("buyer", "platform_admin"), "passkey")).containsExactly("buyer", "platform_admin");
    }

    @Test
    void revokingStaffDeletesAllTheirSessionsAndEmitsSessionRevokedForEach() throws Exception {
        // AMENDMENT-008 (Q-044).
        UUID dealership = UUID.randomUUID();
        String email = "staff-" + UUID.randomUUID() + "@convoy.test";
        UUID staff = users.seed(email, List.of("dealer_staff"), dealership);
        otpSession(email);
        otpSession(email);
        assertThat(jdbc.queryForObject("select count(*) from sessions where user_id = ?", Integer.class, staff)).isEqualTo(2);
        String admin = TestTokens.user("identity", UUID.randomUUID(), List.of("dealer_admin"), dealership);
        Resp r = send("DELETE", "/dealerships/" + dealership + "/staff/" + staff, admin, null, Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(r.status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from sessions where user_id = ?", Integer.class, staff)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbox where event_type = 'SessionRevoked' and payload->>'user_id' = ? and payload->>'reason' = 'STAFF_REVOKED'",
                Integer.class, staff.toString())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from outbox where event_type = 'StaffRevoked' and payload->>'user_id' = ?", Integer.class, staff.toString())).isEqualTo(1);
        // Another dealership's admin sees 404 (no existence leak).
        String other = TestTokens.user("identity", UUID.randomUUID(), List.of("dealer_admin"), UUID.randomUUID());
        assertThat(send("DELETE", "/dealerships/" + dealership + "/staff/" + staff, other, null, Map.of("Idempotency-Key", UUID.randomUUID().toString())).status()).isEqualTo(404);
    }

    @Test
    void passkeyChallengesAreOneTimeScopedAndUnknownCredentialsAreRejected() throws Exception {
        String email = "pk-" + UUID.randomUUID() + "@convoy.test";
        send("POST", "/auth/otp/start", anon(), "{\"email\":\"" + email + "\"}", Map.of());
        JsonNode v = JSON.readTree(send("POST", "/auth/otp/verify", anon(), "{\"email\":\"" + email + "\",\"code\":\"" + lastCode(email) + "\"}", Map.of()).body());
        String token = TestTokens.buyer("identity", UUID.fromString(v.at("/user/id").asText()));
        JsonNode reg = JSON.readTree(send("POST", "/auth/passkeys/register/options", token, "{}", Map.of()).body());
        assertThat(reg.path("rp_id").asText()).isEqualTo("localhost");
        assertThat(reg.path("user_name").asText()).isEqualTo(email);
        assertThat(java.util.Base64.getUrlDecoder().decode(reg.path("challenge").asText())).hasSize(32);
        // A sign-in challenge cannot be used to register, and a registration challenge cannot sign in.
        JsonNode auth = JSON.readTree(send("POST", "/auth/passkeys/authenticate/options", anon(), "{}", Map.of()).body());
        String regBody = "{\"challenge_id\":\"" + auth.path("challenge_id").asText() + "\",\"client_data_json\":\"e30\",\"attestation_object\":\"oA\"}";
        assertThat(send("POST", "/auth/passkeys/register", token, regBody, Map.of()).status()).isEqualTo(400);
        String authBody = "{\"challenge_id\":\"%s\",\"credential_id\":\"AAAA\",\"client_data_json\":\"e30\",\"authenticator_data\":\"AA\",\"signature\":\"AA\"}";
        assertThat(send("POST", "/auth/passkeys/authenticate", anon(), authBody.formatted(reg.path("challenge_id").asText()), Map.of()).status()).isEqualTo(401);
        // Unknown credential -> 401, and the challenge is spent: replaying it is refused too.
        String fresh = JSON.readTree(send("POST", "/auth/passkeys/authenticate/options", anon(), "{}", Map.of()).body()).path("challenge_id").asText();
        assertThat(send("POST", "/auth/passkeys/authenticate", anon(), authBody.formatted(fresh), Map.of()).status()).isEqualTo(401);
        assertThat(JSON.readTree(send("POST", "/auth/passkeys/authenticate", anon(), authBody.formatted(fresh), Map.of()).body()).path("detail").asText()).contains("expired");
    }

    @Test
    void authorisationMatrixForEveryP1Endpoint() {
        assertThat(AuthzDenyChecks.run("identity", this::send, (m, p) -> switch (p) {
            case "/internal/sessions/validate" -> "{\"session_id\":\"x\"}";
            case "/auth/otp/start" -> "{\"email\":\"a@convoy.test\"}";
            case "/auth/otp/verify" -> "{\"email\":\"a@convoy.test\",\"code\":\"123456\"}";
            default -> "{}";
        })).isEmpty();
    }

    @Test
    void openApiMatchesTheCommittedWebClient() { assertOpenApiMatchesWebClient("identity"); }
}
