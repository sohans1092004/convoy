package convoy.platform.testing;

import convoy.platform.ConvoyProperties;
import convoy.platform.security.JwtVerifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base wiring for a service's integration tests: a real PostgreSQL 16 (Testcontainers, one per test
 * class), messaging off (no broker), and a JwtVerifier that trusts {@link TestTokens} only.
 * Use with {@code @SpringBootTest(webEnvironment = RANDOM_PORT, properties = "convoy.messaging.enabled=false")}
 * and {@code @Import(ServiceTest.Config.class)}.
 */
public abstract class ServiceTest {
    @Value("${local.server.port}") protected int port;
    protected final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        @ServiceConnection
        static PostgreSQLContainer<?> postgres() { return new PostgreSQLContainer<>("postgres:16.15-alpine"); }

        @Bean
        @Primary
        JwtVerifier testJwtVerifier(ConvoyProperties p) { return TestTokens.verifier(p.serviceName()); }
    }

    public record Resp(int status, String body, java.net.http.HttpHeaders headers) {}

    protected Resp send(String method, String path, String token, String body, Map<String, String> headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json");
            if (token != null) b.header("Authorization", "Bearer " + token);
            headers.forEach(b::header);
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), r.body(), r.headers());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected Resp get(String path, String token) { return send("GET", path, token, null, Map.of()); }
}
