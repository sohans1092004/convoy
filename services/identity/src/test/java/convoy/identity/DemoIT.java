package convoy.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.platform.testing.ServiceTest;
import convoy.testing.StubServer;
import convoy.platform.testing.TestTokens;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** P6.B public demo: E170 scenarios (accounts, sessions, the dealer's demo dealership), E171 bot sessions only, 24 h cleanup. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "convoy.messaging.enabled=false", "identity.data-key=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
        "identity.clients.gateway.secret=gw-secret", "identity.clients.pool.secret=pool-secret", "identity.clients.demo-bots.secret=bots-secret",
        "identity.demo-scenarios=true", "identity.email-sign-in=false",
        "convoy.issuers.convoy-gateway=http://unused", "convoy.issuers.convoy-identity=http://unused"})
@Import(ServiceTest.Config.class)
class DemoIT extends ServiceTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<StubServer.Req> DEALER_CALLS = new CopyOnWriteArrayList<>();
    static final StubServer DEALER = new StubServer().on("/internal/demo/dealerships", r -> {
        DEALER_CALLS.add(r);
        return new StubServer.Reply(200, "", 0);
    });

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        Path bots = Files.createTempFile("bots", ".json");
        Files.writeString(bots, "[{\"email\":\"sim-test@convoy.test\",\"dealership_id\":\"0190f3a0-0000-7000-8000-00000000d002\",\"strategy\":\"aggressive\"}]");
        r.add("identity.demo-bots-file", bots::toString);
        r.add("identity.demo-dealer-url", DEALER::url);
    }

    @AfterAll static void stop() { DEALER.close(); }

    @Autowired JdbcTemplate jdbc;
    @Autowired DemoController demo;

    String validate(String session) {
        return send("POST", "/internal/sessions/validate", TestTokens.service("identity", "gateway", List.of("sessions:validate")),
                "{\"session_id\":\"" + session + "\"}", Map.of()).body();
    }

    @Test
    void scenarioGivesABuyerAndADealerAdminOfAFreshDemoDealershipWithoutEmail() throws Exception {
        Resp r = send("POST", "/auth/demo", TestTokens.anonymous("identity"), null, Map.of());
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        JsonNode s = JSON.readTree(r.body());
        JsonNode buyer = JSON.readTree(validate(s.at("/buyer/session_id").asText()));
        JsonNode dealer = JSON.readTree(validate(s.at("/dealer/session_id").asText()));
        assertThat(buyer.path("roles").toString()).contains("buyer", "demo");
        assertThat(dealer.path("roles").toString()).contains("dealer_admin", "demo");
        assertThat(dealer.path("dealership_id").asText()).isEqualTo(s.path("dealership_id").asText());
        // dealer E172 got the dealership, owned by this scenario's buyer, with identity's own service token
        StubServer.Req call = DEALER_CALLS.get(DEALER_CALLS.size() - 1);
        JsonNode body = JSON.readTree(call.body());
        assertThat(body.path("dealership_id").asText()).isEqualTo(s.path("dealership_id").asText());
        assertThat(body.path("owner").asText()).isEqualTo(buyer.path("user_id").asText());
        assertThat(call.headers().get("Authorization").get(0)).startsWith("Bearer ");
        // 24 h, not the usual 30 days; addresses are .invalid, so nothing is ever emailed
        assertThat(java.time.Duration.between(java.time.Instant.now(), java.time.Instant.parse(s.path("expires_at").asText())).toHours()).isBetween(23L, 24L);
        assertThat(jdbc.queryForObject("select count(*) from sessions where user_id = ?::uuid and expires_at < clock_timestamp() + interval '25 hours'",
                Integer.class, buyer.path("user_id").asText())).isEqualTo(1);
    }

    @Test
    void botSessionsOnlyForSimulatedDealersAndOnlyWithTheScope() throws Exception {
        String bots = TestTokens.service("identity", "demo-bots", List.of("demo:sessions"));
        Resp ok = send("POST", "/internal/demo/sessions", bots, "{\"email\":\"sim-test@convoy.test\"}", Map.of());
        assertThat(ok.status()).as(ok.body()).isEqualTo(200);
        assertThat(JSON.readTree(validate(JSON.readTree(ok.body()).path("session_id").asText())).path("roles").toString()).contains("dealer_staff");
        // a real (non-bot) account: no session, whoever asks
        String real = "real-" + UUID.randomUUID() + "@convoy.test";
        jdbc.update("insert into users(id, email_hash, email_enc) values (gen_random_uuid(), ?, ?)", "h-" + real, new byte[0]);
        assertThat(send("POST", "/internal/demo/sessions", bots, "{\"email\":\"" + real + "\"}", Map.of()).status()).isEqualTo(404);
        assertThat(send("POST", "/internal/demo/sessions", TestTokens.service("identity", "pool", List.of("privacy:write")),
                "{\"email\":\"sim-test@convoy.test\"}", Map.of()).status()).isEqualTo(403);
        assertThat(send("POST", "/internal/demo/sessions", TestTokens.anonymous("identity"), "{\"email\":\"sim-test@convoy.test\"}", Map.of()).status()).isIn(401, 403);
    }

    @Test
    void expiredScenariosAreErasedAndSignedOut() throws Exception {
        JsonNode s = JSON.readTree(send("POST", "/auth/demo", TestTokens.anonymous("identity"), null, Map.of()).body());
        String buyer = JSON.readTree(validate(s.at("/buyer/session_id").asText())).path("user_id").asText();
        jdbc.update("update demo_users set expires_at = clock_timestamp() - interval '1 minute' where scenario_id = ?::uuid", s.path("scenario_id").asText());
        demo.expire();
        assertThat(jdbc.queryForObject("select count(*) from erasures where user_id = ?::uuid and state = 'PENDING'", Integer.class, buyer)).isEqualTo(1);
        assertThat(send("POST", "/internal/sessions/validate", TestTokens.service("identity", "gateway", List.of("sessions:validate")),
                "{\"session_id\":\"" + s.at("/buyer/session_id").asText() + "\"}", Map.of()).status()).isEqualTo(401);
        demo.expire(); // once only
        assertThat(jdbc.queryForObject("select count(*) from erasures where user_id = ?::uuid", Integer.class, buyer)).isEqualTo(1);
    }

    @Test
    void emailSignInIsOffWithoutSesProductionAccess() {
        Resp r = send("POST", "/auth/otp/start", TestTokens.anonymous("identity"), "{\"email\":\"a@b.com\"}", Map.of());
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.body()).contains("EMAIL_SIGN_IN_DISABLED");
    }
}
