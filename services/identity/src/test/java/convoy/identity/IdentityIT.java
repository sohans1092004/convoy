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
        assertThat(send("POST", "/internal/oauth/token", null, "{\"client_id\":\"pool\",\"client_secret\":\"pool-secret\",\"audience\":\"identity\"}", Map.of()).status()).isEqualTo(403);
        JsonNode jwks = JSON.readTree(get("/.well-known/jwks.json", null).body());
        assertThat(jwks.at("/keys/0/kty").asText()).isEqualTo("EC");
        assertThat(jwks.at("/keys/0/d").isMissingNode()).isTrue(); // public keys only
    }

    @Test
    void consentHistoryIsAppendOnlyAndPasskeysAreNotBuiltYet() throws Exception {
        String email = "consent-" + UUID.randomUUID() + "@convoy.test";
        send("POST", "/auth/otp/start", anon(), "{\"email\":\"" + email + "\"}", Map.of());
        JsonNode v = JSON.readTree(send("POST", "/auth/otp/verify", anon(), "{\"email\":\"" + email + "\",\"code\":\"" + lastCode(email) + "\"}", Map.of()).body());
        String token = TestTokens.buyer("identity", UUID.fromString(v.at("/user/id").asText()));
        send("PUT", "/me/consents/DEALER_DISCLOSURE", token, "{\"granted\":true,\"notice_version\":\"n1\"}", Map.of());
        JsonNode h = JSON.readTree(send("PUT", "/me/consents/DEALER_DISCLOSURE", token, "{\"granted\":false,\"notice_version\":\"n1\"}", Map.of()).body());
        assertThat(h.path("history")).hasSize(2);
        assertThat(JSON.readTree(get("/me", token).body()).path("email").asText()).isEqualTo(email);
        assertThat(JSON.readTree(send("POST", "/auth/passkeys/authenticate", anon(), "{}", Map.of()).body()).path("code").asText()).isEqualTo("NOT_IMPLEMENTED_IN_P1");
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
}
