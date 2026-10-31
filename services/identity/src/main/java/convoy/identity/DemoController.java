package convoy.identity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
import convoy.platform.messaging.Uuid7;
import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.security.Caller;
import convoy.platform.security.JwtVerifier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * P6.B public demo (identity.demo-scenarios=true, the lite deploy only).
 * E170 gives a visitor an isolated scenario: a temporary buyer and the admin of a fresh demo dealership (dealer E172), each
 * with a 24 h session and no email (addresses under .invalid, which notification never sends to). Role 'demo' keeps the
 * buyer's pools to the scenario (pool) and the dealership out of everyone else's rounds (dealer E055).
 * E171 opens a session for a simulated dealer (tools/simulator/demo-bots.mjs), and only for those.
 * Every hour, scenarios past 24 h go through the P5.D erasure flow, so every service removes their data.
 * The gateway caps E170 per IP and per day.
 */
@RestController
@ConditionalOnProperty(name = "identity.demo-scenarios", havingValue = "true")
@io.swagger.v3.oas.annotations.Hidden // only on the lite demo: the committed OpenAPI documents stay the same everywhere
class DemoController implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoController.class);
    static final Duration TTL = Duration.ofHours(24);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Users users;
    private final DataRights dataRights;
    private final SigningKeys keys;
    private final ObjectMapper json;
    private final RestClient dealer;
    private final String botsFile;

    DemoController(JdbcTemplate jdbc, TransactionTemplate tx, Users users, DataRights dataRights, SigningKeys keys, ObjectMapper json,
                   RestClient.Builder http, TaskScheduler scheduler, @Value("${identity.demo-dealer-url:http://dealer:8080}") String dealerUrl,
                   @Value("${identity.demo-bots-file:}") String botsFile) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.users = users;
        this.dataRights = dataRights;
        this.keys = keys;
        this.json = json;
        this.dealer = http.baseUrl(dealerUrl).build();
        this.botsFile = botsFile;
        scheduler.scheduleWithFixedDelay(this::expire, Instant.now().plusSeconds(60), Duration.ofHours(1));
    }

    record Bot(String email, @JsonProperty("dealership_id") UUID dealershipId) {}

    /** The simulated dealers' staff users (tools/simulator/dealers.json); their dealerships come from the demo dealer seed. */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (botsFile.isBlank() || !new File(botsFile).exists()) return;
        Bot[] bots = json.readerFor(Bot[].class).without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(new File(botsFile));
        for (Bot b : bots) {
            tx.executeWithoutResult(s -> {
                UUID id = users.seed(b.email(), List.of("dealer_staff"), b.dealershipId());
                jdbc.update("insert into demo_users(user_id, kind) values (?, 'BOT') on conflict do nothing", id);
            });
        }
        log.info("demo: {} simulated dealer users ready", bots.length);
    }

    public record Scenario(String scenarioId, String expiresAt, String dealershipId, Api.Session buyer, Api.Session dealer) {}

    /** E170 (anonymous; the gateway applies 20 per IP and 200 in total per day). */
    @PostMapping("/auth/demo")
    public Scenario create() {
        UUID scenario = Uuid7.next(), dealership = Uuid7.next();
        String tag = scenario.toString().substring(24);
        Instant expires = Instant.now().plus(TTL);
        // Accounts first: if dealer is down they stay unused and the hourly cleanup erases them after 24 h.
        UUID[] ids = tx.execute(s -> {
            UUID buyer = users.seed("buyer-" + tag + "@demo.invalid", List.of("buyer", "demo"), null);
            UUID admin = users.seed("dealer-" + tag + "@demo.invalid", List.of("dealer_admin", "demo"), dealership);
            for (UUID u : List.of(buyer, admin)) {
                jdbc.update("insert into demo_users(user_id, kind, scenario_id, expires_at) values (?, 'SCENARIO', ?, ?)", u, scenario, java.sql.Timestamp.from(expires));
            }
            return new UUID[] {buyer, admin};
        });
        try {
            // The buyer owns the dealership: it is invited only to that buyer's pools (E055).
            dealer.post().uri("/internal/demo/dealerships").header("Authorization", "Bearer " + token("dealer", "demo:write"))
                    .body(Map.of("dealership_id", dealership.toString(), "owner", ids[0].toString(),
                            "trade_name", "Demo Motors " + tag.substring(0, 4).toUpperCase() + " (your demo dealership)"))
                    .retrieve().toBodilessEntity();
        } catch (org.springframework.web.client.RestClientException e) {
            throw ApiProblem.of(ProblemType.DEPENDENCY_UNAVAILABLE, "The demo could not be set up. Try again in a minute.");
        }
        return new Scenario(scenario.toString(), expires.toString(), dealership.toString(), session(ids[0], expires), session(ids[1], expires));
    }

    public record BotBody(@NotBlank String email) {}

    /** E171 (service:demo-bots, scope demo:sessions): a session for a simulated dealer, never for any other account. */
    @PostMapping("/internal/demo/sessions")
    @convoy.platform.security.RequiresScope("demo:sessions")
    public Api.Session botSession(@Valid @RequestBody BotBody b) {
        Caller.current().requireScope("demo:sessions");
        UUID id = users.idByEmail(b.email()).filter(u -> jdbc.queryForObject("select count(*) from demo_users where user_id = ? and kind = 'BOT'", Integer.class, u) == 1)
                .orElseThrow(() -> ApiProblem.of(ProblemType.NOT_FOUND, "No such simulated dealer."));
        return session(id, Instant.now().plus(TTL));
    }

    /** Hourly: scenarios past their 24 h start the erasure flow (every service deletes or pseudonymises), sessions end now. */
    void expire() {
        try {
            List<UUID> due = jdbc.queryForList("select user_id from demo_users where kind = 'SCENARIO' and not erased and expires_at <= clock_timestamp()", UUID.class);
            for (UUID u : due) {
                tx.executeWithoutResult(s -> {
                    jdbc.update("update sessions set revoked_at = clock_timestamp() where user_id = ? and revoked_at is null", u);
                    dataRights.startErasure(u);
                    jdbc.update("update demo_users set erased = true where user_id = ?", u);
                });
            }
            if (!due.isEmpty()) log.info("demo: {} expired scenario account(s) sent to erasure", due.size());
        } catch (RuntimeException e) {
            log.warn("demo cleanup failed: {}", e.toString());
        }
    }

    private Api.Session session(UUID user, Instant expires) {
        String id = Crypto.randomToken();
        jdbc.update("insert into sessions(id_hash, user_id, expires_at, auth_method) values (?, ?, ?, 'demo')", Crypto.sha256(id), user, java.sql.Timestamp.from(expires));
        Users.User u = users.find(user).orElseThrow();
        return convoy.platform.web.Dto.as(Map.of("session_id", id, "session_expires_at", expires.toString(),
                "user", Map.of("id", user.toString(), "roles", AuthController.sessionRoles(u.roles(), "demo"))), Api.Session.class);
    }

    /** Identity is the service-token issuer, so it signs its own token for dealer (aud dealer, scope demo:write). */
    private String token(String audience, String scope) {
        Instant now = Instant.now();
        return keys.sign(new JWTClaimsSet.Builder().issuer(JwtVerifier.IDENTITY).subject("identity").audience(audience).claim("scope", List.of(scope))
                .issueTime(Date.from(now)).notBeforeTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(60))).jwtID(Uuid7.next().toString()).build());
    }
}
