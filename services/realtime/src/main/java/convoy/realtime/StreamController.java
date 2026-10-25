package convoy.realtime;

import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.security.Caller;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * E120 {@code GET /realtime/pools/{id}/stream}: members of the pool (any state but LEFT) and staff of dealerships invited to
 * one of its rounds; anyone else gets 404, so the stream does not reveal that a pool exists. Access is re-checked every
 * 30 s and the connection closed when it no longer holds. Heartbeats every 15 s carry the server time and the latest
 * version this instance knows, so a client that missed a delta (Redis down) notices and refetches the snapshot.
 */
@RestController
public class StreamController {
    private static final Logger log = LoggerFactory.getLogger(StreamController.class);
    private final Hub hub;
    private final RestClient pool;
    private final RealtimeApplication.RealtimeSettings settings;

    public StreamController(Hub hub, RestClient poolClient, RealtimeApplication.RealtimeSettings settings, TaskScheduler scheduler) {
        this.hub = hub;
        this.pool = poolClient;
        this.settings = settings;
        scheduler.scheduleWithFixedDelay(this::heartbeat, settings.heartbeat());
        scheduler.scheduleWithFixedDelay(this::revalidate, settings.revalidate());
    }

    @GetMapping(path = "/realtime/pools/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter pool(@PathVariable UUID id) {
        Caller c = Caller.current().require("buyer", "dealer_staff", "dealer_admin");
        UUID buyer = c.has("buyer") ? c.userId() : null;
        UUID dealership = c.dealershipId() == null ? null : UUID.fromString(c.dealershipId());
        if (!allowed(id, buyer, dealership)) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such pool.");
        SseEmitter emitter = new SseEmitter(0L);
        String key = "pool:" + id;
        Connection conn = new Connection(key, id, buyer, dealership, c.sessionHash(), emitter, settings.queueCapacity(), hub::remove);
        hub.add(conn);
        // retry: spreads a reconnect storm (realtime-recovery.md §4); the client adds its own backoff with jitter.
        long retry = 1_000 + ThreadLocalRandom.current().nextLong(4_000);
        try {
            emitter.send(SseEmitter.event().name("ready").reconnectTime(retry).data(Hub.write(Map.of("aggregate", "pool", "id", id.toString(),
                    "version", hub.latest.getOrDefault(key, 0L), "server_time", Instant.now().toString(), "instance", hub.instance))));
        } catch (java.io.IOException e) {
            conn.close(null);
        }
        return emitter;
    }

    /** Graceful stop (deploy, scale-in): tell every client now, instead of leaving a silent stream behind. */
    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextClosedEvent.class)
    void shuttingDown() {
        int n = hub.connections();
        for (Connection c : hub.all()) c.close("instance shutting down; reconnect");
        log.info("closed {} connections on shutdown", n);
    }

    /** E121 / E122 need quote, order and payment events (P2 builds the pool stream only): honest 501 after the role check. */
    @GetMapping("/realtime/dealer/stream")
    public void dealerStream() {
        Caller.current().require("dealer_staff", "dealer_admin");
        throw ApiProblem.of(ProblemType.NOT_IMPLEMENTED_IN_P1, "The dealer stream (E121) is specified but not built yet; the dealer inbox refreshes every 10 s.");
    }

    @GetMapping("/realtime/me/stream")
    public void meStream() {
        Caller.current().require("buyer");
        throw ApiProblem.of(ProblemType.NOT_IMPLEMENTED_IN_P1, "The personal stream (E122) is specified but not built yet; order and payment pages refresh every 10 s.");
    }

    boolean allowed(UUID poolId, UUID buyer, UUID dealership) {
        if (buyer == null && dealership == null) return false;
        Map<?, ?> r = pool.get().uri(u -> {
            u.path("/internal/pools/{id}/access");
            if (buyer != null) u.queryParam("buyer_id", buyer);
            if (dealership != null) u.queryParam("dealership_id", dealership);
            return u.build(poolId);
        }).retrieve().body(Map.class);
        return r != null && Boolean.TRUE.equals(r.get("allowed"));
    }

    void heartbeat() {
        if (!hub.redisUp()) {
            // Redis down: this instance only sees the events it consumed itself. Ask the owner for each watched pool's
            // version, so clients that missed a delta notice it from this heartbeat and refetch the snapshot.
            for (String key : hub.keys()) {
                try {
                    Map<?, ?> v = pool.get().uri("/internal/pools/{id}/version", key.substring(5)).retrieve().body(Map.class);
                    hub.latest.merge(key, ((Number) v.get("version")).longValue(), Math::max);
                } catch (RestClientException e) {
                    log.info("version check for {} unavailable: {}", key, e.getMessage());
                }
            }
        }
        String now = Instant.now().toString();
        for (Connection c : hub.all()) {
            c.offer(new Connection.Msg("heartbeat", null, Hub.write(Map.of("server_time", now, "aggregate", "pool", "id", c.poolId.toString(),
                    "version", hub.latest.getOrDefault(c.key, 0L)))));
        }
    }

    void revalidate() {
        for (Connection c : hub.all()) {
            try {
                if (!allowed(c.poolId, c.buyerId, c.dealershipId)) c.close("access revoked");
            } catch (RestClientException e) {
                log.info("access re-check for {} unavailable ({}); keeping the connection until the next check", c.key, e.getMessage());
            }
        }
    }
}
