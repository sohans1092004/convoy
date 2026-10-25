package convoy.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.contracts.events.EventPayload;
import convoy.contracts.events.EventTypes;
import convoy.platform.messaging.Envelope;
import convoy.platform.messaging.Subscriptions;
import convoy.platform.security.ServiceTokens;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.client.RestClient;

/**
 * Realtime (wave 2, realtime-recovery.md): consumes every pool-aggregate event (a FULL subscription, so version
 * gaps are real), turns each into a version-stamped delta without any buyer or dealer identity, and fans it out
 * over Redis Pub/Sub to the SSE connections on every instance. Holds no business state.
 */
@SpringBootApplication
@EnableConfigurationProperties(RealtimeApplication.RealtimeSettings.class)
public class RealtimeApplication {
    public static void main(String[] args) { SpringApplication.run(RealtimeApplication.class, args); }

    @ConfigurationProperties(prefix = "realtime")
    public record RealtimeSettings(String instance, String poolUrl, Duration heartbeat, int queueCapacity, Duration revalidate, int maxConnections) {}

    /**
     * Fields a client may see. Everything else (buyer, membership, payment and dealership ids, acceptances,
     * released buyers, invited dealerships, variant lists) never leaves this service.
     */
    static final Set<String> DELTA_FIELDS = Set.of("state", "end_state", "end_reason", "interested_count", "committed_count", "min_members",
            "round_id", "round_set", "round_no", "kind", "deadline", "closed_at", "valid_count", "final_count", "decision_deadline",
            "confirmation_deadline", "forming_deadline", "acceptance_counts", "bookings_count", "reason");

    static Map<String, Object> delta(Map<String, Object> payload) {
        Map<String, Object> d = new LinkedHashMap<>();
        payload.forEach((k, v) -> {
            if (DELTA_FIELDS.contains(k)) d.put(k, v);
        });
        return d;
    }

    @Bean
    Hub hub(RealtimeSettings s, StringRedisTemplate redis, MeterRegistry meters) { return new Hub(s.instance(), redis, meters); }

    /** After a Redis restart, reconnect (publisher and subscriber) within ~1 s instead of Lettuce's exponential backoff up to 30 s. */
    @Bean(destroyMethod = "shutdown")
    io.lettuce.core.resource.ClientResources lettuceResources() {
        return io.lettuce.core.resource.ClientResources.builder()
                .reconnectDelay(io.lettuce.core.resource.Delay.constant(Duration.ofSeconds(1))).build();
    }

    @Bean
    RedisMessageListenerContainer fanout(RedisConnectionFactory redis, Hub hub) {
        RedisMessageListenerContainer c = new RedisMessageListenerContainer();
        c.setConnectionFactory(redis);
        c.addMessageListener(hub, new PatternTopic("agg:*")); // includes Hub.REVOKED
        c.setRecoveryInterval(2_000); // Redis restarts: resubscribe every 2 s until it is back
        return c;
    }

    @Bean
    RestClient poolClient(ServiceTokens tokens, RestClient.Builder builder, RealtimeSettings s) { return tokens.client(builder, "pool", s.poolUrl()); }

    /** E152: the batch session re-check (revoked staff, logged-out sessions). */
    @Bean
    RestClient identityClient(ServiceTokens tokens, RestClient.Builder builder, convoy.platform.ConvoyProperties p) { return tokens.client(builder, "identity", p.identityUrl()); }

    /** Every pool-aggregate event type (realtime is a FULL pool subscriber in the catalogue). */
    @Bean
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Subscriptions subscriptions(Hub hub, RestClient poolClient, ObjectMapper json) {
        Subscriptions s = new Subscriptions();
        for (Class<? extends EventPayload> type : EventTypes.ALL.values()) {
            if (!"pool".equals(constant(type, "AGGREGATE"))) continue;
            if (type == convoy.contracts.events.QuoteRoundOpened.class) continue; // registered below: pool stream + dealer streams (E121)
            s.on((Class) type, (Subscriptions.Handler<EventPayload>) (Envelope e, EventPayload p) -> hub.publish("pool", e.aggregateId().toString(),
                    e.aggregateVersion(), e.eventType(), Instant.parse(e.occurredAt()), delta(json.convertValue(p, Map.class))));
        }
        // E121 (P3.C): a dealer's own rank (never competitors') and the rounds it is invited to.
        s.on(convoy.contracts.events.RankChanged.class, (Envelope e, convoy.contracts.events.RankChanged p) -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("quote_id", p.quoteId());
            d.put("pool_id", p.poolId());
            d.put("variant_id", p.variantId());
            d.put("rank", p.rank());
            d.put("of_n", p.ofN());
            d.put("rule_version", p.ruleVersion());
            hub.publish("dealer", p.dealershipId(), Instant.parse(e.occurredAt()).toEpochMilli(), e.eventType(), Instant.parse(e.occurredAt()), d);
        });
        s.on(convoy.contracts.events.QuoteRoundOpened.class, (Envelope e, convoy.contracts.events.QuoteRoundOpened p) -> {
            hub.publish("pool", e.aggregateId().toString(), e.aggregateVersion(), e.eventType(), Instant.parse(e.occurredAt()), delta(json.convertValue(p, Map.class)));
            for (String dealership : p.invitedDealershipIds()) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("pool_id", p.poolId());
                d.put("round_id", p.roundId());
                d.put("round_no", p.roundNo());
                d.put("kind", p.kind());
                d.put("deadline", p.deadline().toString());
                hub.publish("dealer", dealership, Instant.parse(e.occurredAt()).toEpochMilli(), e.eventType(), Instant.parse(e.occurredAt()), d);
            }
        });
        // AMENDMENT-008 (Q-044): logout or staff revocation closes the session's streams at once (the 15 s re-check is the fallback).
        s.on(convoy.contracts.events.SessionRevoked.class, (Envelope e, convoy.contracts.events.SessionRevoked p) -> hub.revokeSession(p.sessionIdHash()));
        // A gap that outlives 5 min: take the owner's current version, and tell every client to refetch its snapshot.
        return s.snapshot("pool", id -> {
            long v = ((Number) poolClient.get().uri("/internal/pools/{id}/version", id).retrieve().body(Map.class).get("version")).longValue();
            hub.publishResync("pool", id.toString(), v);
            return v;
        });
    }

    private static String constant(Class<?> type, String name) {
        try {
            return (String) type.getField(name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static UUID uuid(String s) { return s == null ? null : UUID.fromString(s); }
}
