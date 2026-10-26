package convoy.audit;

import convoy.contracts.events.EventPayload;
import convoy.contracts.events.EventTypes;
import convoy.platform.messaging.Subscriptions;
import convoy.platform.security.Caller;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Audit (wave 3, P5.C): consumes every event of every service (AMENDMENT-012: EVENTS kind, dedupe on event_id only, so a
 * reordered or replayed backlog loses nothing) into an append-only hash chain, and serves the admin viewer (E163).
 * `java -jar audit.jar verify-chain` checks the chain and exits 0 (intact) or 1 (tampered).
 */
@SpringBootApplication
@RestController
public class AuditApplication {
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("verify-chain")) {
            SpringApplication app = new SpringApplication(AuditApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            app.setDefaultProperties(Map.of("convoy.messaging.enabled", "false", "spring.flyway.enabled", "false"));
            try (ConfigurableApplicationContext ctx = app.run()) {
                Chain.Broken b = ctx.getBean(Chain.class).verify();
                long rows = ctx.getBean(JdbcTemplate.class).queryForObject("select count(*) from audit_log", Long.class);
                System.out.println(b == null ? "audit chain intact: " + rows + " rows" : "AUDIT CHAIN BROKEN at seq " + b.seq() + ": " + b.reason());
                System.exit(b == null ? 0 : 1);
            }
        }
        SpringApplication.run(AuditApplication.class, args);
    }

    private final JdbcTemplate jdbc;
    private final Chain chain;

    public AuditApplication(JdbcTemplate jdbc, Chain chain) {
        this.jdbc = jdbc;
        this.chain = chain;
    }

    @Bean
    @SuppressWarnings({"unchecked", "rawtypes"})
    Subscriptions subscriptions() {
        Subscriptions s = new Subscriptions();
        for (Class<? extends EventPayload> type : EventTypes.ALL.values()) {
            String aggregate = constant(type, "AGGREGATE");
            s.onUnordered((Class) type, (e, p) -> chain.record(e, aggregate));
        }
        return s;
    }

    private static String constant(Class<?> type, String name) {
        try {
            return (String) type.getField(name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Reconciliation (P5.C): audit_events{producer} next to every service's outbox_delivered_events and outbox_lag_seconds.
     * In steady state sum(outbox_delivered_events{service=X}) == audit_events{producer=X} (docs/runbooks/audit.md).
     */
    @Bean
    Map<String, Long> auditCounts(MeterRegistry meters, TaskScheduler scheduler) {
        Map<String, Long> counts = new ConcurrentHashMap<>();
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                jdbc.query("select producer, count(*) n from audit_log where event_id is not null group by producer", rs -> {
                    String producer = rs.getString("producer");
                    if (counts.put(producer, rs.getLong("n")) == null) {
                        Gauge.builder("audit_events", counts, m -> m.getOrDefault(producer, 0L)).tag("producer", producer).register(meters);
                    }
                });
            } catch (RuntimeException ignored) {
                // next pass
            }
        }, Duration.ofSeconds(15));
        return counts;
    }

    public record Entry(long seq, @Nullable String eventId, String eventType, String producer, @Nullable String aggregateType, @Nullable String aggregateId,
                        @Nullable Long aggregateVersion, String actor, @Nullable String correlationId, boolean sensitive, String occurredAt, String recordedAt,
                        String payload, String hash) {}

    public record Page(List<Entry> entries) {}

    /** E163: the audit viewer, platform admins only (a passkey session: AMENDMENT-008). Every read is itself audited. */
    @GetMapping("/admin/audit")
    public Page view(@RequestParam(required = false) String actor, @RequestParam(name = "aggregate_type", required = false) String aggregateType, @RequestParam(name = "aggregate_id", required = false) UUID aggregateId,
                     @RequestParam(name = "event_type", required = false) String eventType, @RequestParam(required = false) Instant from,
                     @RequestParam(required = false) Instant to, @RequestParam(name = "before_seq", required = false) Long beforeSeq,
                     @RequestParam(defaultValue = "50") int limit, @RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId) {
        Caller c = Caller.current().require("platform_admin");
        Map<String, Object> filters = new LinkedHashMap<>();
        StringBuilder where = new StringBuilder(" where true");
        List<Object> args = new ArrayList<>();
        if (actor != null) { where.append(" and actor = ?"); args.add(actor); filters.put("actor", actor); }
        if (aggregateType != null) { where.append(" and aggregate_type = ?"); args.add(aggregateType); filters.put("aggregate_type", aggregateType); }
        if (aggregateId != null) { where.append(" and aggregate_id = ?"); args.add(aggregateId); filters.put("aggregate_id", aggregateId); }
        if (eventType != null) { where.append(" and event_type = ?"); args.add(eventType); filters.put("event_type", eventType); }
        if (from != null) { where.append(" and occurred_at >= ?::timestamptz"); args.add(from.toString()); filters.put("from", from.toString()); }
        if (to != null) { where.append(" and occurred_at < ?::timestamptz"); args.add(to.toString()); filters.put("to", to.toString()); }
        if (beforeSeq != null) { where.append(" and seq < ?"); args.add(beforeSeq); }
        args.add(Math.min(Math.max(limit, 1), 200));
        // the read is recorded before anything is returned: no read without a trace
        chain.own("AuditViewed", c.userId(), correlationId, filters);
        List<Entry> rows = jdbc.query("select * from audit_log" + where + " order by seq desc limit ?", (rs, i) -> new Entry(rs.getLong("seq"),
                rs.getString("event_id"), rs.getString("event_type"), rs.getString("producer"), rs.getString("aggregate_type"), rs.getString("aggregate_id"),
                (Long) rs.getObject("aggregate_version"), rs.getString("actor"), rs.getString("correlation_id"), rs.getBoolean("sensitive"),
                rs.getTimestamp("occurred_at").toInstant().toString(), rs.getTimestamp("recorded_at").toInstant().toString(), rs.getString("payload"), rs.getString("hash")),
                args.toArray());
        return new Page(rows);
    }
}
