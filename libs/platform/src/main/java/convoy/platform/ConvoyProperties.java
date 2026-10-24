package convoy.platform;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Platform settings (prefix {@code convoy}). Every value comes from config/env; nothing is hard-coded.
 *
 * @param serviceName  this service's name: JWT audience, outbox producer, queue prefix
 * @param issuers      trusted JWT issuer -> JWKS URI (convoy-gateway for users, convoy-identity for services)
 * @param publicPaths  ant patterns reachable without an internal JWT (health, webhooks)
 * @param identityUrl  base URL of identity, for service tokens
 * @param clientId     this service's client id for service tokens (identity service_clients)
 * @param clientSecret its secret (env only)
 * @param aws          SNS/SQS endpoint override (LocalStack) and region
 * @param messaging    relay and consumer timings
 */
@ConfigurationProperties(prefix = "convoy")
public record ConvoyProperties(
        String serviceName,
        Map<String, String> issuers,
        List<String> publicPaths,
        String identityUrl,
        String clientId,
        String clientSecret,
        Aws aws,
        Messaging messaging) {

    public ConvoyProperties {
        issuers = issuers == null ? Map.of() : issuers;
        publicPaths = publicPaths == null ? List.of("/actuator/health/**", "/actuator/health", "/v3/api-docs/**") : publicPaths;
        aws = aws == null ? new Aws(null, "ap-south-1") : aws;
        messaging = messaging == null ? new Messaging(200, 1, true) : messaging;
    }

    public record Aws(String endpoint, String region) {}

    /**
     * @param relayIntervalMs  outbox poll interval
     * @param waitSeconds      SQS long-poll wait (short locally for a snappy demo)
     * @param enabled          false in tests that have no broker
     */
    public record Messaging(long relayIntervalMs, int waitSeconds, boolean enabled) {}
}
