package convoy.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Fail fast under overload (P2.5.B): refuse at once with 503 + Retry-After instead of queueing until clients time out.
 * W3 showed the alternative: requests queued behind a hot pool row for 40 s and then failed as connect timeouts and 500s.
 */
final class FailFast {
    private FailFast() {}

    private static final Logger log = LoggerFactory.getLogger(FailFast.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** RFC 9457 body with Retry-After: 1 s (clients retry with the same Idempotency-Key, which is safe by contract). */
    static Mono<Void> unavailable(ServerWebExchange ex, String detail) {
        var res = ex.getResponse();
        if (res.isCommitted()) return Mono.empty();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://convoy.example/problems/dependency-unavailable");
        body.put("title", "A required downstream dependency is unavailable; retry with the same Idempotency-Key");
        body.put("status", 503);
        body.put("code", "DEPENDENCY_UNAVAILABLE");
        body.put("detail", detail);
        body.put("instance", ex.getRequest().getPath().value());
        byte[] bytes;
        try {
            bytes = JSON.writeValueAsBytes(body);
        } catch (Exception e) {
            bytes = "{\"status\":503}".getBytes(StandardCharsets.UTF_8);
        }
        res.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        res.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        res.getHeaders().set("Retry-After", "1");
        return res.writeWith(Mono.just(res.bufferFactory().wrap(bytes)));
    }

    /**
     * At most N requests in flight per route (gateway.route-concurrency.default, overridable per route id); the next one
     * is refused immediately. Long-lived streams are excluded: realtime caps those itself.
     */
    @Component
    static class RouteLimiter implements GlobalFilter, Ordered {
        private final Map<String, Semaphore> permits = new ConcurrentHashMap<>();
        private final Environment env;
        private final int defaultLimit;

        RouteLimiter(Environment env) {
            this.env = env;
            this.defaultLimit = env.getProperty("gateway.route-concurrency.default", Integer.class, 256);
        }

        Semaphore permits(String routeId) {
            return permits.computeIfAbsent(routeId, id -> new Semaphore(env.getProperty("gateway.route-concurrency." + id, Integer.class, defaultLimit)));
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            if (route == null || route.getId().startsWith("stream")) return chain.filter(exchange);
            Semaphore s = permits(route.getId());
            if (!s.tryAcquire()) return unavailable(exchange, "Convoy is busy right now. Try again in a moment.");
            return chain.filter(exchange).doFinally(signal -> s.release());
        }

        @Override
        public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 50; } // before AuthFilter: no identity call for a refused request
    }

    /** A dropped, refused or timed-out upstream connection is "unavailable, retry" (503), not a server bug (500). */
    @Component
    @Order(-2) // ahead of Boot's default handler (-1)
    static class UpstreamErrors implements ErrorWebExceptionHandler {
        @Override
        public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
            if (!isUpstreamFailure(ex)) return Mono.error(ex);
            log.warn("upstream unavailable on {}: {}", exchange.getRequest().getPath().value(), ex.toString());
            return unavailable(exchange, "A Convoy service did not answer. Try again in a moment.");
        }

        static boolean isUpstreamFailure(Throwable ex) {
            for (Throwable t = ex; t != null; t = t.getCause()) {
                if (t instanceof ConnectException || t instanceof TimeoutException || t instanceof io.netty.channel.ConnectTimeoutException
                        || t instanceof reactor.netty.http.client.PrematureCloseException
                        || t instanceof io.netty.handler.timeout.ReadTimeoutException) return true;
            }
            return false;
        }
    }
}
