package convoy.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * auth-contract.md §2-3. Strips identity headers from the client, validates X-Convoy-Session with
 * identity (Redis cache, TTL <= 30 s, fail closed when identity is down and nothing is cached), applies
 * Redis rate limits, and forwards with a freshly minted ES256 internal JWT whose aud is the route's
 * service. Requests without a session get an anonymous JWT (no roles), so every service still sees
 * only gateway-signed callers.
 */
@Component
public class AuthFilter implements GlobalFilter, Ordered {
    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);
    static final String SESSION_HEADER = "X-Convoy-Session";
    private final GatewayKeys keys;
    private final GatewayApplication.GatewaySettings settings;
    private final ReactiveStringRedisTemplate redis;
    private final WebClient identity;
    private final ObjectMapper json = new ObjectMapper();
    private volatile String serviceToken;
    private volatile Instant serviceTokenExpiry = Instant.EPOCH;
    /** Fallback buckets when Redis is down (per instance; services.md "Dependencies down"). */
    private final Map<String, AtomicInteger> localBuckets = new ConcurrentHashMap<>();

    public AuthFilter(GatewayKeys keys, GatewayApplication.GatewaySettings settings, ReactiveStringRedisTemplate redis, WebClient.Builder web) {
        this.keys = keys;
        this.settings = settings;
        this.redis = redis;
        // Idle connections are dropped before Tomcat's 20 s keep-alive closes them: reusing a server-closed connection failed
        // the first burst after a quiet spell ("Connection prematurely closed", found by W3).
        reactor.netty.resources.ConnectionProvider pool = reactor.netty.resources.ConnectionProvider.builder("identity")
                .maxConnections(500).maxIdleTime(java.time.Duration.ofSeconds(15)).evictInBackground(java.time.Duration.ofSeconds(30)).build();
        this.identity = web.baseUrl(settings.services().get("identity"))
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(reactor.netty.http.client.HttpClient.create(pool))).build();
    }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 100; }

    record Identity(String userId, List<String> roles, String dealershipId, String sid) {
        static final Identity ANONYMOUS = new Identity("anonymous", List.of(), null, null);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest req = exchange.getRequest();
        String session = req.getHeaders().getFirst(SESSION_HEADER);
        ServerHttpRequest.Builder cleaned = req.mutate().headers(h -> {
            h.remove(HttpHeaders.AUTHORIZATION);
            h.keySet().removeIf(n -> {
                String l = n.toLowerCase();
                return l.startsWith("x-user-") || l.startsWith("x-convoy-") || l.equals("x-forwarded-user") || l.equals("x-roles");
            });
        });
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String audience = route == null ? null : (String) route.getMetadata().get(GatewayApplication.AUDIENCE);
        if (audience == null) return chain.filter(exchange.mutate().request(cleaned.build()).build()); // webhook: HMAC-checked by payment

        Mono<Identity> who = session == null || session.isBlank() ? Mono.just(Identity.ANONYMOUS) : validate(session);
        return who.flatMap(id -> rateLimit(exchange, id).flatMap(limited -> {
            if (!limited.isEmpty()) return problem(exchange, HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "RATE_LIMITED", "Too many requests", limited);
            String jwt = mint(id, audience);
            return chain.filter(exchange.mutate().request(cleaned.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt).build()).build());
        })).onErrorResume(GatewayProblem.class, p -> problem(exchange, p.status, p.slug, p.code, p.title, p.getMessage()));
    }

    String mint(Identity id, String audience) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder c = new JWTClaimsSet.Builder().issuer("convoy-gateway").audience(audience).subject(id.userId())
                .claim("roles", id.roles()).issueTime(Date.from(now)).notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plus(settings.tokenTtl()))).jwtID(UUID.randomUUID().toString());
        if (id.dealershipId() != null) c.claim("dealership_id", id.dealershipId());
        if (id.sid() != null) c.claim("sid", id.sid());
        return keys.sign(c.build());
    }

    Mono<Identity> validate(String session) {
        String key = "sess:" + sha256(session);
        Mono<Identity> fromIdentity = serviceToken().flatMap(token -> identity.post().uri("/internal/sessions/validate")
                        .headers(h -> h.setBearerAuth(token)).bodyValue(Map.of("session_id", session))
                        .retrieve().bodyToMono(String.class).timeout(Duration.ofSeconds(3)))
                .flatMap(body -> {
                    Identity id = parse(body);
                    return redis.opsForValue().set(key, body, settings.sessionCacheTtl()).onErrorReturn(false).thenReturn(id);
                })
                .onErrorMap(WebClientResponseException.Unauthorized.class, e -> new GatewayProblem(HttpStatus.UNAUTHORIZED, "unauthenticated",
                        "UNAUTHENTICATED", "No valid session or token", "Your session has ended. Sign in again."))
                .onErrorMap(e -> !(e instanceof GatewayProblem), e -> {
                    log.warn("session validation unavailable: {}", e.toString());
                    return new GatewayProblem(HttpStatus.SERVICE_UNAVAILABLE, "dependency-unavailable", "DEPENDENCY_UNAVAILABLE",
                            "A required downstream dependency is unavailable; retry with the same Idempotency-Key", "Sign-in service unavailable. Try again shortly.");
                });
        return redis.opsForValue().get(key).map(this::parse).onErrorResume(e -> Mono.empty()).switchIfEmpty(fromIdentity);
    }

    private Identity parse(String body) {
        try {
            Map<String, Object> m = json.readValue(body, new TypeReference<>() {});
            @SuppressWarnings("unchecked") List<String> roles = (List<String>) m.get("roles");
            return new Identity((String) m.get("user_id"), roles, (String) m.get("dealership_id"), (String) m.get("sid"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    Mono<String> serviceToken() {
        if (serviceToken != null && Instant.now().isBefore(serviceTokenExpiry.minusSeconds(30))) return Mono.just(serviceToken);
        return identity.post().uri("/internal/oauth/token").bodyValue(Map.of("grant_type", "client_credentials", "client_id", settings.clientId(),
                        "client_secret", settings.clientSecret(), "audience", "identity"))
                .retrieve().bodyToMono(Map.class).timeout(Duration.ofSeconds(3)).map(m -> {
                    serviceToken = (String) m.get("access_token");
                    serviceTokenExpiry = Instant.now().plusSeconds(((Number) m.get("expires_in")).longValue());
                    return serviceToken;
                });
    }

    /** Returns a detail message when the request is over a limit, else empty. */
    Mono<String> rateLimit(ServerWebExchange exchange, Identity id) {
        ServerHttpRequest r = exchange.getRequest();
        String path = r.getPath().value();
        String rule = null, subject = null;
        if (r.getMethod() == HttpMethod.POST && path.equals("/api/auth/otp/start")) {
            rule = "otp";
            String fwd = r.getHeaders().getFirst("X-Forwarded-For");
            subject = fwd != null ? fwd.split(",")[0].trim() : String.valueOf(r.getRemoteAddress() == null ? "?" : r.getRemoteAddress().getAddress());
        } else if (r.getMethod() == HttpMethod.POST && path.matches("/api/pools/[^/]+/members")) {
            rule = "join";
            subject = id.userId();
        }
        GatewayApplication.GatewaySettings.Limit limit = rule == null ? null : settings.limits().get(rule);
        if (limit == null) return Mono.just("");
        String key = "rl:" + rule + ":" + sha256(subject) + ":" + (Instant.now().getEpochSecond() / limit.window().toSeconds());
        String detail = "Too many attempts. Wait a few minutes and try again.";
        return redis.opsForValue().increment(key)
                .flatMap(n -> (n == 1 ? redis.expire(key, limit.window()) : Mono.just(true)).thenReturn(n))
                .onErrorResume(e -> Mono.just((long) localBuckets.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet()))
                .map(n -> n > limit.max() ? detail : "");
    }

    private Mono<Void> problem(ServerWebExchange ex, HttpStatus status, String slug, String code, String title, String detail) {
        if (detail != null && detail.isEmpty()) detail = null;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://convoy.example/problems/" + slug);
        body.put("title", title);
        body.put("status", status.value());
        body.put("code", code);
        if (detail != null) body.put("detail", detail);
        body.put("instance", ex.getRequest().getPath().value());
        body.put("server_time", Instant.now().toString());
        ex.getResponse().setStatusCode(status);
        ex.getResponse().getHeaders().setContentType(MediaType.parseMediaType("application/problem+json"));
        try {
            byte[] bytes = json.writeValueAsBytes(body);
            return ex.getResponse().writeWith(Mono.just(ex.getResponse().bufferFactory().wrap(bytes)));
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final class GatewayProblem extends RuntimeException {
        final HttpStatus status;
        final String slug, code, title;

        GatewayProblem(HttpStatus status, String slug, String code, String title, String detail) {
            super(detail, null, false, false);
            this.status = status;
            this.slug = slug;
            this.code = code;
            this.title = title;
        }
    }
}
