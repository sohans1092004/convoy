package convoy.platform.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import convoy.contracts.events.EventTypes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
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
    /** Fault points (messaging-failure-tests.md MF-1, MF-2). */
    public static final String CRASH_BEFORE_PUBLISH = "relay.beforePublish", CRASH_AFTER_PUBLISH = "relay.afterPublish";
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
        // SNS PublishBatch (10 per call, order kept, per-group order on FIFO topics). FIFO and standard rows go to different
        // topics, so a chunk is cut wherever the topic changes; rows stay in id order throughout.
        for (int i = 0; i < rows.size(); ) {
            boolean fifo = isFifo(rows.get(i).type());
            int j = i;
            while (j < rows.size() && j - i < 10 && isFifo(rows.get(j).type()) == fifo) j++;
            List<Row> chunk = rows.subList(i, j);
            convoy.platform.Faults.hit(CRASH_BEFORE_PUBLISH);
            publish(chunk, fifo);
            convoy.platform.Faults.hit(CRASH_AFTER_PUBLISH); // SNS has them; the rows are not marked: they will be published again
            jdbc.batchUpdate("update outbox set delivered_at = clock_timestamp(), attempts = attempts + 1 where id = ?",
                    chunk.stream().map(r -> new Object[] {r.id()}).toList());
            published.addAndGet(chunk.size());
            i = j;
        }
        return rows.size() == 50;
    }

    private void publish(List<Row> chunk, boolean fifo) {
        List<software.amazon.awssdk.services.sns.model.PublishBatchRequestEntry> entries = new java.util.ArrayList<>();
        List<Span> spans = new java.util.ArrayList<>();
        try {
            for (int k = 0; k < chunk.size(); k++) {
                Row r = chunk.get(k);
                Span span = Tracing.start("publish " + r.type(), SpanKind.PRODUCER, r.traceparent());
                spans.add(span);
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
                String tp = Tracing.traceparent(Context.current().with(span));
                if (tp != null) attrs.put(Tracing.ATTR, MessageAttributeValue.builder().dataType("String").stringValue(tp).build());
                var e = software.amazon.awssdk.services.sns.model.PublishBatchRequestEntry.builder().id(String.valueOf(k))
                        .message(json.writeValueAsString(env)).messageAttributes(attrs);
                if (fifo) e.messageGroupId(r.aggregateId().toString()).messageDeduplicationId(r.eventId().toString());
                entries.add(e.build());
            }
            var res = sns.publishBatch(b -> b.topicArn(topology.topicArn(producer, fifo)).publishBatchRequestEntries(entries));
            // Any failed entry fails the whole chunk: the transaction rolls back and everything is republished (consumers dedupe).
            if (!res.failed().isEmpty()) throw new IllegalStateException("SNS rejected " + res.failed().size() + " of " + entries.size() + ": " + res.failed().get(0).message());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        } finally {
            spans.forEach(Span::end);
        }
    }

    /** The topic kinds (fifo or not) this producer publishes to, from the catalogue. */
    public java.util.Set<Boolean> topics() {
        return EventTypes.ALL.keySet().stream().filter(t -> {
            try {
                return producer.equals(EventTypes.ALL.get(t).getField("PRODUCER").get(null));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }).map(OutboxRelay::isFifo).collect(java.util.stream.Collectors.toSet());
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
