package convoy.platform.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.contracts.events.EventPayload;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Transactional outbox: append an event in the same transaction as the state change it describes.
 * {@link OutboxRelay} publishes it to SNS later and marks it delivered only after the broker acks.
 */
public class Outbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public Outbox(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Append inside the caller's transaction. Returns the event id. */
    public UUID append(UUID aggregateId, long aggregateVersion, EventPayload payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox.append needs the business transaction");
        }
        UUID eventId = Uuid7.next();
        try {
            jdbc.update("insert into outbox(event_id, event_type, aggregate_id, aggregate_version, payload, occurred_at, "
                            + "correlation_id, causation_id, traceparent) values (?, ?, ?, ?, ?::jsonb, clock_timestamp(), ?, ?, ?)",
                    eventId, payload.eventType(), aggregateId, aggregateVersion, json.writeValueAsString(payload),
                    EventContext.correlationId(), EventContext.causationId(), Tracing.currentTraceparent());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
        return eventId;
    }
}
