package convoy.realtime;

import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.security.Caller;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final RestClient identity;
    private final RealtimeApplication.RealtimeSettings settings;

    public StreamController(Hub hub, RestClient poolClient, RestClient identityClient, RealtimeApplication.RealtimeSettings settings, TaskScheduler scheduler) {
        this.hub = hub;
        this.pool = poolClient;
        this.identity = identityClient;
        this.settings = settings;
        scheduler.scheduleWithFixedDelay(this::heartbeat, settings.heartbeat());
        scheduler.scheduleWithFixedDelay(this::revalidate, settings.revalidate());
    }

    @GetMapping(path = "/realtime/pools/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter pool(@PathVariable UUID id, jakarta.servlet.http.HttpServletResponse response) {
        Caller c = Caller.current().require("buyer", "dealer_staff", "dealer_admin");
        // Fail fast (P2.5.B): above the cap a new stream is refused with 503 + Retry-After instead of running the instance out
        // of heap (W5: OutOfMemoryError at ~1,850 streams on a 360 MB instance). The client backs off and may land elsewhere.
        if (hub.connections() >= settings.maxConnections()) {
            hub.refused.increment();
            response.setHeader("Retry-After", String.valueOf(1 + ThreadLocalRandom.current().nextInt(5)));
            throw ApiProblem.of(ProblemType.DEPENDENCY_UNAVAILABLE, "Live updates are busy on this server; retrying shortly. The page keeps working without them.");
        }
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

    /**
     * E121 (P3.C): the caller's own dealership only (no path parameter: a dealer cannot ask for another dealership). Carries its
     * rank updates (RankChanged, own rank and of_n only) and the rounds it is invited to (QuoteRoundOpened). Session and staff
     * revocation close it like a pool stream (E152 re-check, SessionRevoked).
     */
    @GetMapping(path = "/realtime/dealer/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter dealerStream(jakarta.servlet.http.HttpServletResponse response) {
        Caller c = Caller.current().require("dealer_staff", "dealer_admin");
        if (c.dealershipId() == null) throw ApiProblem.of(ProblemType.NOT_FOUND, "No dealership.");
        if (hub.connections() >= settings.maxConnections()) {
            hub.refused.increment();
            response.setHeader("Retry-After", String.valueOf(1 + ThreadLocalRandom.current().nextInt(5)));
            throw ApiProblem.of(ProblemType.DEPENDENCY_UNAVAILABLE, "Live updates are busy on this server; retrying shortly.");
        }
        UUID dealership = UUID.fromString(c.dealershipId());
        SseEmitter emitter = new SseEmitter(0L);
        String key = "dealer:" + dealership;
        Connection conn = new Connection(key, null, null, dealership, c.sessionHash(), emitter, settings.queueCapacity(), hub::remove);
        hub.add(conn);
        try {
            emitter.send(SseEmitter.event().name("ready").reconnectTime(1_000 + ThreadLocalRandom.current().nextLong(4_000))
                    .data(Hub.write(Map.of("aggregate", "dealer", "id", dealership.toString(), "server_time", Instant.now().toString(), "instance", hub.instance))));
        } catch (java.io.IOException e) {
            conn.close(null);
        }
        return emitter;
    }


    /**
     * E122 (P3.E): a buyer's stream for one pool they are a member of (checked now and on every re-check). Carries the pool's
     * quote count during the round and a comparison-ready signal when the final round closes; never any quote content.
     */
    @GetMapping(path = "/realtime/me/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter buyerStream(@org.springframework.web.bind.annotation.RequestParam(value = "pool_id", required = false) UUID poolId,
                                  jakarta.servlet.http.HttpServletResponse response) {
        Caller c = Caller.current().require("buyer"); // the role first, then the parameter (authz matrix: 401/403 before 400)
        if (poolId == null) throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "pool_id is required.");
        if (!allowed(poolId, c.userId(), null)) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such pool.");
        if (hub.connections() >= settings.maxConnections()) {
            hub.refused.increment();
            response.setHeader("Retry-After", String.valueOf(1 + ThreadLocalRandom.current().nextInt(5)));
            throw ApiProblem.of(ProblemType.DEPENDENCY_UNAVAILABLE, "Live updates are busy on this server; retrying shortly.");
        }
        SseEmitter emitter = new SseEmitter(0L);
        Connection conn = new Connection("quotes:" + poolId, poolId, c.userId(), null, c.sessionHash(), emitter, settings.queueCapacity(), hub::remove);
        hub.add(conn);
        try {
            emitter.send(SseEmitter.event().name("ready").reconnectTime(1_000 + ThreadLocalRandom.current().nextLong(4_000))
                    .data(Hub.write(Map.of("aggregate", "quotes", "id", poolId.toString(), "quote_count", hub.quoteCountOf(poolId.toString()),
                            "server_time", Instant.now().toString(), "instance", hub.instance))));
        } catch (java.io.IOException e) {
            conn.close(null);
        }
        return emitter;
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
            if (!c.key.startsWith("pool:")) { // dealer and buyer-quote streams: a liveness heartbeat only (no aggregate version to compare)
                c.offer(new Connection.Msg("heartbeat", null, Hub.write(Map.of("server_time", now, "aggregate", c.key.substring(0, c.key.indexOf(':')), "id", c.key.substring(c.key.indexOf(':') + 1)))));
                continue;
            }
            c.offer(new Connection.Msg("heartbeat", null, Hub.write(Map.of("server_time", now, "aggregate", "pool", "id", c.poolId.toString(),
                    "version", hub.latest.getOrDefault(c.key, 0L)))));
        }
    }

    /**
     * Every {@code realtime.revalidate} (15 s): one batch call asks identity which sessions are no longer good (logged
     * out, or dealer staff whose membership was revoked: AZ-T6) and closes their streams; then each stream's pool access
     * is re-checked. Identity unavailable: connections are kept until the next check (the gateway still refuses API calls).
     */
    @SuppressWarnings("unchecked")
    void revalidate() {
        List<Connection> all = new java.util.ArrayList<>();
        hub.all().forEach(all::add);
        if (!all.isEmpty()) {
            try {
                List<Map<String, Object>> refs = all.stream().map(c -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("sid", c.sessionHash);
                    m.put("dealership_id", c.dealershipId == null ? null : c.dealershipId.toString());
                    return m;
                }).distinct().toList();
                Map<String, Object> r = identity.post().uri("/internal/sessions/check").body(Map.of("sessions", refs)).retrieve().body(Map.class);
                Set<String> invalid = new java.util.HashSet<>((List<String>) r.get("invalid"));
                for (Connection c : all) if (invalid.contains(c.sessionHash)) c.close("session revoked");
            } catch (RestClientException e) {
                log.info("session re-check unavailable ({}); keeping connections until the next check", e.getMessage());
            }
        }
        for (Connection c : hub.all()) {
            try {
                if (c.poolId != null && !allowed(c.poolId, c.buyerId, c.dealershipId)) c.close("access revoked"); // dealer streams: session check above
            } catch (RestClientException e) {
                log.info("access re-check for {} unavailable ({}); keeping the connection until the next check", c.key, e.getMessage());
            }
        }
    }
}
