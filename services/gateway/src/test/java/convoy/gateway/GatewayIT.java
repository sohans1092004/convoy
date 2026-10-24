package convoy.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import convoy.testing.StubServer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Gateway: per-route audience, identity headers stripped (AZ-INT-03), session validation through
 * identity (fail closed), anonymous JWT for public routes, rate limits with Redis unavailable
 * (in-memory fallback), JWKS publishing the signing key.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIT {
    static final List<StubServer.Req> SEEN = new CopyOnWriteArrayList<>();
    static final AtomicBoolean IDENTITY_UP = new AtomicBoolean(true);
    static final StubServer DOWNSTREAM = new StubServer()
            .on("/internal/oauth/token", r -> StubServer.Reply.json("{\"access_token\":\"svc\",\"expires_in\":300}"))
            .on("/internal/sessions/validate", r -> !IDENTITY_UP.get() ? new StubServer.Reply(500, "{}", 0)
                    : r.body().contains("good-session") ? StubServer.Reply.json("{\"user_id\":\"0190f3a0-0000-7000-8000-0000000000b1\",\"roles\":[\"buyer\"],\"dealership_id\":null,\"sid\":\"h\"}")
                    : new StubServer.Reply(401, "{}", 0))
            .on("/.*", r -> {
                SEEN.add(r);
                return StubServer.Reply.json("{\"ok\":true}");
            });

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        for (String s : List.of("identity", "catalog", "pool", "dealer", "quote", "order", "payment")) r.add("gateway.services." + s, DOWNSTREAM::url);
        r.add("gateway.key-dir", () -> System.getProperty("java.io.tmpdir") + "/convoy-gw-test-" + System.nanoTime());
        r.add("gateway.client-secret", () -> "x");
        r.add("gateway.limits.otp.max", () -> "3");
        r.add("spring.data.redis.host", () -> "127.0.0.1");
        r.add("spring.data.redis.port", () -> "1"); // no Redis: session cache bypassed, rate limit falls back to memory
    }

    @AfterAll
    static void stop() { DOWNSTREAM.close(); }

    @Autowired WebTestClient web;

    StubServer.Req last() { return SEEN.get(SEEN.size() - 1); }

    static SignedJWT jwt(StubServer.Req r) throws Exception {
        return SignedJWT.parse(r.headers().get("Authorization").get(0).substring(7));
    }

    @Test
    void routesMintAJwtForTheTargetServiceAndStripClientIdentityHeaders() throws Exception {
        web.get().uri("/api/pools/abc").header("X-Convoy-Session", "good-session").header("X-User-Id", "spoofed").header("Authorization", "Bearer spoofed")
                .exchange().expectStatus().isOk();
        StubServer.Req r = last();
        assertThat(r.path()).isEqualTo("/pools/abc");
        assertThat(r.headers()).doesNotContainKeys("X-user-id", "X-convoy-session");
        SignedJWT t = jwt(r);
        assertThat(t.getJWTClaimsSet().getAudience()).containsExactly("pool");
        assertThat(t.getJWTClaimsSet().getSubject()).isEqualTo("0190f3a0-0000-7000-8000-0000000000b1");
        assertThat(t.getJWTClaimsSet().getStringListClaim("roles")).containsExactly("buyer");
        assertThat(t.getJWTClaimsSet().getExpirationTime().getTime() - t.getJWTClaimsSet().getIssueTime().getTime()).isEqualTo(120_000);
        // The token verifies against the published JWKS.
        String jwks = web.get().uri("/.well-known/jwks.json").exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        ECKey pub = (ECKey) JWKSet.parse(jwks).getKeyByKeyId(t.getHeader().getKeyID());
        assertThat(t.verify(new com.nimbusds.jose.crypto.ECDSAVerifier(pub))).isTrue();
        assertThat(pub.isPrivate()).isFalse();
        // Quote routes get aud=quote, order routes aud=order.
        web.get().uri("/api/pools/abc/quotes").header("X-Convoy-Session", "good-session").exchange().expectStatus().isOk();
        assertThat(jwt(last()).getJWTClaimsSet().getAudience()).containsExactly("quote");
        web.get().uri("/api/dealer/bookings").header("X-Convoy-Session", "good-session").exchange().expectStatus().isOk();
        assertThat(jwt(last()).getJWTClaimsSet().getAudience()).containsExactly("order");
    }

    @Test
    void noSessionGetsAnAnonymousTokenAndABadSessionGets401() throws Exception {
        web.get().uri("/api/catalog/models").exchange().expectStatus().isOk();
        assertThat(jwt(last()).getJWTClaimsSet().getStringListClaim("roles")).isEmpty();
        web.get().uri("/api/pools/abc").header("X-Convoy-Session", "bad").exchange().expectStatus().isUnauthorized()
                .expectHeader().contentType("application/problem+json").expectBody().jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void identityDownFailsClosed() {
        IDENTITY_UP.set(false);
        try {
            web.get().uri("/api/pools/abc").header("X-Convoy-Session", "another-good-session-uncached").exchange().expectStatus().isEqualTo(503)
                    .expectBody().jsonPath("$.code").isEqualTo("DEPENDENCY_UNAVAILABLE");
        } finally {
            IDENTITY_UP.set(true);
        }
    }

    @Test
    void otpStartIsRateLimitedEvenWithoutRedis() {
        String ip = "203.0.113." + (int) (Math.random() * 200);
        for (int i = 0; i < 3; i++) web.post().uri("/api/auth/otp/start").header("X-Forwarded-For", ip).bodyValue(Map.of("email", "a@b.c")).exchange().expectStatus().isOk();
        web.post().uri("/api/auth/otp/start").header("X-Forwarded-For", ip).bodyValue(Map.of("email", "a@b.c")).exchange().expectStatus().isEqualTo(429)
                .expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED");
    }

    @Test
    void webhookIsForwardedWithoutAnyToken() {
        web.post().uri("/webhooks/razorpay").header("X-Razorpay-Signature", "sig").bodyValue("{}").exchange().expectStatus().isOk();
        assertThat(last().headers()).doesNotContainKey("Authorization");
        assertThat(last().path()).isEqualTo("/webhooks/razorpay");
    }
}
