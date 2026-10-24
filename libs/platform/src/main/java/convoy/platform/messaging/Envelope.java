package convoy.platform.messaging;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** Event envelope (docs/architecture/events/envelope.schema.json). */
public record Envelope(
        @JsonProperty("event_id") UUID eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("schema_version") int schemaVersion,
        @JsonProperty("aggregate_id") UUID aggregateId,
        @JsonProperty("aggregate_version") long aggregateVersion,
        @JsonProperty("occurred_at") String occurredAt,
        @JsonProperty("correlation_id") UUID correlationId,
        @JsonProperty("causation_id") UUID causationId,
        @JsonProperty("producer") String producer,
        @JsonProperty("payload") JsonNode payload) {}
