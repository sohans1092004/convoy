package convoy.gateway;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(GatewayApplication.GatewaySettings.class)
public class GatewayApplication {
    public static final String AUDIENCE = "convoy.audience";

    public static void main(String[] args) { SpringApplication.run(GatewayApplication.class, args); }

    /**
     * @param services    service name -> base URL
     * @param keyDir      directory of ES256 JWK files (generated at first boot)
     * @param clientId    gateway's service client id at identity
     * @param clientSecret its secret (env)
     * @param tokenTtl    internal JWT lifetime (auth-contract.md: 120 s)
     * @param sessionCacheTtl session-validation cache TTL (<= 30 s)
     * @param limits      fixed-window rate limits: name -> {max, window}
     */
    @ConfigurationProperties(prefix = "gateway")
    public record GatewaySettings(Map<String, String> services, String keyDir, String clientId, String clientSecret,
                                  Duration tokenTtl, Duration sessionCacheTtl, Map<String, Limit> limits) {
        public record Limit(int max, Duration window) {}
    }

    @Bean GatewayKeys gatewayKeys(GatewaySettings s) { return new GatewayKeys(Path.of(s.keyDir())); }

    /**
     * AMENDMENT-008 (Q-044): a revoked session's cache entry is dropped as soon as realtime relays SessionRevoked on the
     * Redis channel, so the next request re-validates with identity (refused). The 25 s TTL stays the fallback.
     */
    @Bean(destroyMethod = "dispose")
    reactor.core.Disposable sessionEviction(org.springframework.data.redis.connection.ReactiveRedisConnectionFactory factory,
                                            org.springframework.data.redis.core.ReactiveStringRedisTemplate redis) {
        // Deferred: the container connects on creation, and the gateway must start (and keep serving) while Redis is down.
        return reactor.core.publisher.Flux.defer(() -> new org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer(factory)
                        .receive(org.springframework.data.redis.listener.ChannelTopic.of("agg:sessions:revoked")))
                .flatMap(m -> redis.delete("sess:" + m.getMessage()).onErrorResume(e -> reactor.core.publisher.Mono.just(0L)))
                .retryWhen(reactor.util.retry.Retry.fixedDelay(Long.MAX_VALUE, Duration.ofSeconds(2))) // Redis restarts: resubscribe
                .subscribe();
    }

    /** Routes, most specific first. Every /api route carries the audience of the internal JWT minted for it. */
    @Bean
    RouteLocator routes(RouteLocatorBuilder b, GatewaySettings s) {
        Map<String, String> u = s.services();
        return b.routes()
                .route("webhook", r -> r.path("/webhooks/razorpay").uri(u.get("payment")))
                // E120 pool stream (SSE), balanced across the realtime instances.
                // E122: a buyer's quote-count stream for one pool (?pool_id=), same instance retry as the pool stream.
                .route("stream-me", r -> r.path("/api/stream/me").filters(f -> f.rewritePath("/api/stream/me", "/realtime/me/stream")
                                .retry(c -> c.setRetries(2).setMethods(org.springframework.http.HttpMethod.GET).setSeries()
                                        .setExceptions(java.net.ConnectException.class, java.io.IOException.class)))
                        .metadata(AUDIENCE, "realtime").uri(u.get("realtime")))
                .route("stream-pool", r -> r.path("/api/stream/pools/*").filters(f -> f.rewritePath("/api/stream/pools/(?<id>[^/]+)", "/realtime/pools/${id}/stream")
                                // A refused connect (instance down) is retried; the load balancer then picks the other instance.
                                .retry(c -> c.setRetries(2).setMethods(org.springframework.http.HttpMethod.GET).setSeries()
                                        .setExceptions(java.net.ConnectException.class, java.io.IOException.class)))
                        .metadata(AUDIENCE, "realtime").uri(u.get("realtime")))
                .route("me-orders", r -> r.path("/api/me/orders", "/api/me/orders/**").filters(f -> f.stripPrefix(1)).metadata(AUDIENCE, "order").uri(u.get("order")))
                .route("me-payments", r -> r.path("/api/me/payments/**").filters(f -> f.stripPrefix(1)).metadata(AUDIENCE, "payment").uri(u.get("payment")))
                .route("me-saved-cars", r -> r.path("/api/me/saved-cars", "/api/me/saved-cars/**").filters(f -> f.stripPrefix(1)).metadata(AUDIENCE, "catalog").uri(u.get("catalog")))
                .route("me-matching", r -> r.path("/api/me/preferences", "/api/me/pool-suggestions").filters(f -> f.stripPrefix(1)).metadata(AUDIENCE, "pool").uri(u.get("pool")))
                .route("identity", r -> r.path("/api/auth/**", "/api/me", "/api/me/**", "/api/dealerships/*/staff/**", "/api/staff-invites/**").filters(f -> f.stripPrefix(1)).metadata(AUDIENCE, "identity").uri(u.get("identity")))
                .route("catalog", r -> r.path("/api/catalog/**").filters(f -> f.stripPrefix(1)).metadata(AUDIENCE, "catalog").uri(u.get("catalog")))
                .route("pool-quotes", r -> r.path("/api/pools/*/quotes", "/api/pools/*/quotes/**").filters(f -> f.stripPrefix(1)).metadata(AUDIENCE, "quote").uri(u.get("quote")))
                .route("pool", r -> r.path("/api/pools", "/api/pools/**").filters(f -> f.stripPrefix(1)).metadata(AUDIENCE, "pool").uri(u.get("pool")))
                .route("dealer-bookings", r -> r.path("/api/dealer/bookings", "/api/dealer/bookings/**").filters(f -> f.stripPrefix(1)).metadata(AUDIENCE, "order").uri(u.get("order")))
                .route("dealer-quotes", r -> r.path("/api/dealer/**").filters(f -> f.stripPrefix(1)).metadata(AUDIENCE, "quote").uri(u.get("quote")))
                .route("dealerships", r -> r.path("/api/dealerships/**").filters(f -> f.stripPrefix(1)).metadata(AUDIENCE, "dealer").uri(u.get("dealer")))
                .route("orders", r -> r.path("/api/orders/**").filters(f -> f.stripPrefix(1)).metadata(AUDIENCE, "order").uri(u.get("order")))
                // P4: reviews live with the dealer (E056); booking actions, disputes and the admin queues with their owners.
                .route("booking-review", r -> r.path("/api/bookings/*/review").filters(f -> f.stripPrefix(1)).metadata(AUDIENCE, "dealer").uri(u.get("dealer")))
                .route("bookings", r -> r.path("/api/bookings/**").filters(f -> f.stripPrefix(1)).metadata(AUDIENCE, "order").uri(u.get("order")))
                .route("disputes", r -> r.path("/api/disputes/**", "/api/admin/disputes", "/api/admin/disputes/**").filters(f -> f.stripPrefix(1)).metadata(AUDIENCE, "order").uri(u.get("order")))
                .route("admin-money", r -> r.path("/api/admin/refunds", "/api/admin/refunds/**", "/api/admin/reconciliation/**").filters(f -> f.stripPrefix(1)).metadata(AUDIENCE, "payment").uri(u.get("payment")))
                .route("admin-dealerships", r -> r.path("/api/admin/dealerships", "/api/admin/dealerships/**").filters(f -> f.stripPrefix(1)).metadata(AUDIENCE, "dealer").uri(u.get("dealer")))
                .route("payments", r -> r.path("/api/payments/**").filters(f -> f.stripPrefix(1)).metadata(AUDIENCE, "payment").uri(u.get("payment")))
                .build();
    }
}
