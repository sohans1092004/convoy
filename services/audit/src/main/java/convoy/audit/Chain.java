package convoy.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.platform.messaging.Envelope;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * The append-only, hash-chained audit log. Each row's hash covers the previous row's hash and its own fields
 * (row_hash in V1__audit.sql), so editing, deleting or reordering a row breaks every later link.
 * Deleting the newest rows leaves a valid shorter chain: the reconciliation metric (audit_events vs outbox_delivered_events) catches that.
 * ponytail: one chain behind one advisory lock (a few ms per event); shard the chain per producer if W8 needs it.
 */
@Component
public class Chain {
    /** Explicit actor fields in payloads (event-catalogue.yaml); without one the producing service is the actor. */
    private static final List<String> ACTOR_FIELDS = List.of("verified_by", "resolved_by", "revoked_by", "invited_by", "registered_by");
    /** Events about access to, or the fate of, personal data. */
    static final Set<String> SENSITIVE = Set.of("ErasureRequested", "SessionRevoked", "StaffRevoked");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public Chain(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** One event from a service's outbox (inside the inbox transaction, so a duplicate never reaches here). */
    void record(Envelope e, String aggregateType) {
        JsonNode p = e.payload();
        String actor = "service:" + e.producer();
        for (String f : ACTOR_FIELDS) {
            if (p.hasNonNull(f)) {
                actor = "user:" + p.get(f).asText();
                break;
            }
        }
        append(e.eventId(), e.eventType(), e.producer(), aggregateType, e.aggregateId(), e.aggregateVersion(), actor, e.correlationId(),
                SENSITIVE.contains(e.eventType()), e.occurredAt(), p.toString());
    }

    /** An entry audit writes itself (a viewer read, an export download): always sensitive, occurred now. */
    @org.springframework.transaction.annotation.Transactional
    public void own(String type, UUID actor, @Nullable UUID correlationId, Object details) {
        try {
            append(null, type, "audit", null, null, null, "user:" + actor, correlationId, true, null, json.writeValueAsString(details));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void append(@Nullable UUID eventId, String type, String producer, @Nullable String aggregateType, @Nullable UUID aggregateId,
                        @Nullable Long version, String actor, @Nullable UUID correlationId, boolean sensitive, @Nullable String occurredAt, String payload) {
        jdbc.queryForList("select pg_advisory_xact_lock(7001)");
        jdbc.update("""
                with r as (select ?::uuid event_id, ?::text event_type, ?::text producer, ?::text aggregate_type, ?::uuid aggregate_id, ?::bigint aggregate_version,
                                  ?::text actor, ?::uuid correlation_id, ?::boolean sensitive, coalesce(?::timestamptz, clock_timestamp()) occurred_at, ?::jsonb payload,
                                  coalesce((select hash from audit_log order by seq desc limit 1), 'GENESIS') prev)
                insert into audit_log(event_id, event_type, producer, aggregate_type, aggregate_id, aggregate_version, actor, correlation_id, sensitive,
                                      occurred_at, payload, prev_hash, hash)
                select event_id, event_type, producer, aggregate_type, aggregate_id, aggregate_version, actor, correlation_id, sensitive, occurred_at, payload, prev,
                       row_hash(prev, event_id, event_type, producer, aggregate_type, aggregate_id, aggregate_version, actor, correlation_id, sensitive, occurred_at, payload)
                from r""", eventId, type, producer, aggregateType, aggregateId, version, actor, correlationId, sensitive, occurredAt, payload);
    }

    /** First broken link: a row whose hash does not match its content, or whose prev_hash is not the row before's hash. */
    public record Broken(long seq, String reason) {}

    @Nullable
    public Broken verify() {
        List<Broken> bad = jdbc.query("""
                select seq, case when prev_hash <> expected then 'prev_hash does not match the previous row' else 'row content does not match its hash' end reason
                from (select seq, prev_hash, hash, lag(hash, 1, 'GENESIS') over (order by seq) expected,
                             row_hash(prev_hash, event_id, event_type, producer, aggregate_type, aggregate_id, aggregate_version, actor, correlation_id,
                                      sensitive, occurred_at, payload) recomputed
                      from audit_log) x
                where prev_hash <> expected or hash <> recomputed order by seq limit 1""",
                (rs, i) -> new Broken(rs.getLong("seq"), rs.getString("reason")));
        return bad.isEmpty() ? null : bad.get(0);
    }
}
