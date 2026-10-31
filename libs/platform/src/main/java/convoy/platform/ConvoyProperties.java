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
 * @param aws          SNS/SQS endpoint override (LocalStack), region, and in prod the account the topology was applied in
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
        publicPaths = publicPaths == null ? List.of("/actuator/health/**", "/actuator/health", "/actuator/prometheus", "/v3/api-docs/**") : publicPaths;
        aws = aws == null ? new Aws(null, "ap-south-1", null) : aws;
        messaging = messaging == null ? new Messaging(0, 0, null, null, 0, 0, null) : messaging;
    }

    /** @param accountId prod only (CONVOY_AWS_ACCOUNT_ID): topics and queues are checked in this account, not created */
    public record Aws(String endpoint, String region, String accountId) {}

    /**
     * @param relayIntervalMs  outbox poll interval
     * @param waitSeconds      SQS long-poll wait (short locally for a snappy demo)
     * @param enabled          false in tests that have no broker
     * @param gapSnapshotAfter a FULL subscriber's version gap older than this falls back to the owner snapshot (catalogue: 5 min)
     * @param visibilityTimeoutSeconds SQS visibility timeout of the consumer queues (30 s; failure tests shorten it)
     * @param pollers          concurrent long polls per queue (4; lite runs 1: each idle poll is a billed SQS request)
     * @param dlqCheckInterval how often DLQ depth is read for the dlq_depth gauge and ALARM log line (30 s; lite 15 min,
     *                         where CloudWatch alarms on the DLQs are the real alert)
     */
    public record Messaging(long relayIntervalMs, int waitSeconds, Boolean enabled, java.time.Duration gapSnapshotAfter, int visibilityTimeoutSeconds,
                            int pollers, java.time.Duration dlqCheckInterval) {
        public Messaging {
            pollers = pollers <= 0 ? 4 : pollers;
            dlqCheckInterval = dlqCheckInterval == null ? java.time.Duration.ofSeconds(30) : dlqCheckInterval;
            gapSnapshotAfter = gapSnapshotAfter == null ? java.time.Duration.ofMinutes(5) : gapSnapshotAfter;
            visibilityTimeoutSeconds = visibilityTimeoutSeconds <= 0 ? 30 : visibilityTimeoutSeconds;
            // Setting any one messaging property binds the whole record: unset fields keep their defaults, never 0 / false.
            relayIntervalMs = relayIntervalMs <= 0 ? 200 : relayIntervalMs;
            waitSeconds = waitSeconds <= 0 ? 1 : waitSeconds;
            enabled = enabled == null || enabled;
        }
    }
}
