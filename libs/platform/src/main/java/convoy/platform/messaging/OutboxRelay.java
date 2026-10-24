package convoy.platform.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import convoy.contracts.events.EventTypes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;

/**
 * Publishes outbox rows in id order. A row is marked delivered in the same transaction that locked
 * it, after SNS acknowledged the publish; a crash in between re-publishes it (at-least-once; consumers
 * dedupe on event_id). A failed publish stops the batch so per-aggregate order is kept.
 *
 * ponytail: one relay thread per service instance (FOR UPDATE SKIP LOCKED makes a second instance safe
 * but could reorder a FIFO aggregate across instances). Partition by aggregate if relays are scaled out.
 */
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SnsClient sns;
    private final Topology topology;
    private final ObjectMapper json;
    private final String producer;
    private final AtomicLong published = new AtomicLong();

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, SnsClient sns, Topology topology, ObjectMapper json, String producer) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.sns = sns;
        this.topology = topology;
        this.json = json;
        this.producer = producer;
    }

    record Row(long id, UUID eventId, String type, UUID aggregateId, long version, String payload, OffsetDateTime occurredAt,
               UUID correlation, UUID causation, String traceparent) {}

    /** Called on a fixed delay by the platform scheduler. */
    public void tick() {
        try {
            while (Boolean.TRUE.equals(tx.execute(s -> batch()))) {
                // keep draining while full batches are found
            }
        } catch (RuntimeException e) {
            log.warn("outbox relay: {}", e.toString());
        }
    }

    private boolean batch() {
        List<Row> rows = jdbc.query("select id, event_id, event_type, aggregate_id, aggregate_version, payload::text, occurred_at, "
                        + "correlation_id, causation_id, traceparent from outbox where delivered_at is null order by id limit 50 for update skip locked",
                (rs, i) -> new Row(rs.getLong(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getObject(4, UUID.class), rs.getLong(5),
                        rs.getString(6), rs.getObject(7, OffsetDateTime.class), rs.getObject(8, UUID.class), rs.getObject(9, UUID.class), rs.getString(10)));
        for (Row r : rows) {
            publish(r);
            jdbc.update("update outbox set delivered_at = clock_timestamp() where id = ?", r.id());
            published.incrementAndGet();
        }
        return rows.size() == 50;
    }

    private void publish(Row r) {
        boolean fifo = isFifo(r.type());
        Span span = Tracing.start("publish " + r.type(), SpanKind.PRODUCER, r.traceparent());
        try (Scope ignored = span.makeCurrent()) {
            ObjectNode env = json.createObjectNode();
            env.put("event_id", r.eventId().toString());
            env.put("event_type", r.type());
            env.put("schema_version", 1);
            env.put("aggregate_id", r.aggregateId().toString());
            env.put("aggregate_version", r.version());
            env.put("occurred_at", r.occurredAt().withOffsetSameInstant(ZoneOffset.UTC).toInstant().toString());
            env.put("correlation_id", r.correlation().toString());
            if (r.causation() == null) env.putNull("causation_id");
            else env.put("causation_id", r.causation().toString());
            env.put("producer", producer);
            env.set("payload", json.readTree(r.payload()));
            Map<String, MessageAttributeValue> attrs = new java.util.HashMap<>();
            attrs.put("event_type", MessageAttributeValue.builder().dataType("String").stringValue(r.type()).build());
            String tp = Tracing.traceparent(Context.current());
            if (tp != null) attrs.put(Tracing.ATTR, MessageAttributeValue.builder().dataType("String").stringValue(tp).build());
            String body = json.writeValueAsString(env);
            sns.publish(b -> {
                b.topicArn(topology.topicArn(producer, fifo)).message(body).messageAttributes(attrs);
                if (fifo) b.messageGroupId(r.aggregateId().toString()).messageDeduplicationId(r.eventId().toString());
            });
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        } finally {
            span.end();
        }
    }

    static boolean isFifo(String type) {
        try {
            return EventTypes.ALL.get(type).getField("FIFO").getBoolean(null);
        } catch (ReflectiveOperationException | NullPointerException e) {
            throw new IllegalArgumentException("unknown event type " + type, e);
        }
    }

    public long published() { return published.get(); }
}
