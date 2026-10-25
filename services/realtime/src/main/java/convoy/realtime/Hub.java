package convoy.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Connections per aggregate on this instance, and cross-instance fan-out through Redis Pub/Sub channel
 * {@code agg:<type>:<id>} (realtime-recovery.md §6). If Redis is unavailable, an event is delivered only to this
 * instance's connections; the others notice the gap from the next message or heartbeat and resync from the
 * snapshot. Correctness never depends on Redis.
 */
public class Hub implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(Hub.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    final String instance;
    private final StringRedisTemplate redis;
    private final Map<String, Set<Connection>> byKey = new ConcurrentHashMap<>();
    /** Highest version this instance has seen per aggregate; sent in heartbeats so clients can spot a missed delta. */
    final Map<String, Long> latest = new ConcurrentHashMap<>();
    private final Counter delivered, dropped, redisFailures;
    private final Timer fanout;

    public Hub(String instance, StringRedisTemplate redis, MeterRegistry meters) {
        this.instance = instance;
        this.redis = redis;
        meters.gauge("realtime.connections", byKey, m -> m.values().stream().mapToInt(Set::size).sum());
        this.delivered = meters.counter("realtime.messages.delivered");
        this.dropped = meters.counter("realtime.connections.dropped", "reason", "overloaded");
        this.redisFailures = meters.counter("realtime.redis.publish.failures");
        this.fanout = Timer.builder("realtime.fanout.latency").description("owner commit (occurred_at) to queued on a connection")
                .publishPercentiles(0.5, 0.95, 0.99).register(meters);
    }

    void add(Connection c) { byKey.computeIfAbsent(c.key, k -> ConcurrentHashMap.newKeySet()).add(c); }

    void remove(Connection c) {
        Set<Connection> s = byKey.get(c.key);
        if (s != null) s.remove(c);
    }

    Iterable<Connection> all() { return byKey.values().stream().flatMap(Set::stream).toList(); }

    java.util.Set<String> keys() { return byKey.entrySet().stream().filter(e -> !e.getValue().isEmpty()).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()); }

    boolean redisUp() {
        try {
            return "PONG".equalsIgnoreCase(redis.execute((org.springframework.data.redis.core.RedisCallback<String>) c -> c.ping()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    int connections() { return byKey.values().stream().mapToInt(Set::size).sum(); }

    /** Called by the consumer that took the event off SQS. */
    void publish(String type, String id, long version, String eventType, Instant occurredAt, Map<String, Object> delta) {
        send(type, id, version, eventType, occurredAt, delta, false);
    }

    /** After realtime itself resynced from the owner's version (gap fallback): every client refetches its snapshot. */
    void publishResync(String type, String id, long version) {
        send(type, id, version, "Resync", Instant.now(), Map.of(), true);
    }

    private void send(String type, String id, long version, String eventType, Instant occurredAt, Map<String, Object> delta, boolean resync) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("aggregate", type);
        m.put("id", id);
        m.put("version", version);
        m.put("event_type", eventType);
        m.put("occurred_at", occurredAt.toString());
        m.put("delta", delta);
        if (resync) m.put("resync", true);
        String json = write(m);
        try {
            redis.convertAndSend("agg:" + type + ":" + id, json);
        } catch (RuntimeException e) {
            redisFailures.increment();
            log.warn("redis publish failed ({}); delivering on this instance only", e.toString());
            deliver(json);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) { deliver(new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8)); }

    @SuppressWarnings("unchecked")
    void deliver(String json) {
        Map<String, Object> m;
        try {
            m = JSON.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("bad fan-out message: {}", e.toString());
            return;
        }
        String key = m.get("aggregate") + ":" + m.get("id");
        long version = ((Number) m.get("version")).longValue();
        latest.merge(key, version, Math::max);
        Set<Connection> cs = byKey.get(key);
        if (cs == null || cs.isEmpty()) return;
        Map<String, Object> out = new LinkedHashMap<>(m);
        out.put("server_time", Instant.now().toString());
        String data = write(out);
        Connection.Msg msg = Boolean.TRUE.equals(m.get("resync")) ? new Connection.Msg("resync", null, data) : new Connection.Msg("pool.changed", key + ":" + version, data);
        Duration lag = Duration.between(Instant.parse((String) m.get("occurred_at")), Instant.now());
        for (Connection c : cs) {
            if (c.offer(msg)) {
                delivered.increment();
                fanout.record(lag.isNegative() ? Duration.ZERO : lag);
            } else {
                dropped.increment();
            }
        }
    }

    static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
