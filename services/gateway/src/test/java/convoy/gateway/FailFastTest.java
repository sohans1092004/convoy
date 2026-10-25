package convoy.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** P2.5.B fail fast: a full route answers 503 + Retry-After at once, and dropped upstream connections are 503, not 500. */
class FailFastTest {
    static MockServerWebExchange exchange(String routeId) {
        MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.post("/api/pools/p/members").build());
        ex.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async().id(routeId).uri(URI.create("http://pool:8080")).predicate(e -> true).build());
        return ex;
    }

    @Test
    void aFullRouteIsRefusedImmediatelyWith503AndRetryAfter() {
        var limiter = new FailFast.RouteLimiter(new MockEnvironment().withProperty("gateway.route-concurrency.pool", "2"));
        Sinks.Empty<Void> upstream = Sinks.empty(); // two requests held in flight
        GatewayFilterChain slow = e -> upstream.asMono();
        limiter.filter(exchange("pool"), slow).subscribe();
        limiter.filter(exchange("pool"), slow).subscribe();

        MockServerWebExchange third = exchange("pool");
        long t = System.nanoTime();
        limiter.filter(third, e -> Mono.error(new AssertionError("must not reach upstream"))).block(Duration.ofSeconds(1));
        assertThat(Duration.ofNanos(System.nanoTime() - t)).isLessThan(Duration.ofSeconds(1));
        assertThat(third.getResponse().getStatusCode().value()).isEqualTo(503);
        assertThat(third.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(third.getResponse().getBodyAsString().block()).contains("DEPENDENCY_UNAVAILABLE");

        upstream.tryEmitEmpty(); // the two finish: permits come back
        MockServerWebExchange fourth = exchange("pool");
        limiter.filter(fourth, e -> Mono.empty()).block(Duration.ofSeconds(1));
        assertThat(fourth.getResponse().getStatusCode()).isNull(); // passed through
        // Another route has its own permits; streams are never limited here.
        assertThat(limiter.permits("identity").availablePermits()).isEqualTo(256);
    }

    @Test
    void droppedOrRefusedUpstreamConnectionsAre503() {
        var handler = new FailFast.UpstreamErrors();
        MockServerWebExchange ex = exchange("pool");
        handler.handle(ex, new RuntimeException("wrapped", reactor.netty.http.client.PrematureCloseException.TEST_EXCEPTION)).block();
        assertThat(ex.getResponse().getStatusCode().value()).isEqualTo(503);
        assertThat(ex.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(FailFast.UpstreamErrors.isUpstreamFailure(new java.net.ConnectException("refused"))).isTrue();
        assertThat(FailFast.UpstreamErrors.isUpstreamFailure(new IllegalStateException("a bug"))).isFalse(); // still 500
    }
}
