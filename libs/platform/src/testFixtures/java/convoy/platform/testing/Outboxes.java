package convoy.platform.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import convoy.contracts.EventSchemas;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Contract checks on what a service wrote to its outbox: every row, wrapped in the envelope, matches its schema. */
public final class Outboxes {
    private static final ObjectMapper JSON = new ObjectMapper();

    private Outboxes() {}

    /** Validates every outbox row; returns the number of rows checked. */
    public static int assertAllValid(JdbcTemplate jdbc, String producer) {
        List<Map<String, Object>> rows = jdbc.queryForList("select event_id::text as event_id, event_type, aggregate_id::text as aggregate_id, aggregate_version, "
                + "payload::text as payload, to_char(occurred_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') as occurred_at, "
                + "correlation_id::text as correlation_id, causation_id::text as causation_id from outbox order by id");
        for (Map<String, Object> r : rows) {
            try {
                ObjectNode env = JSON.createObjectNode();
                env.put("event_id", (String) r.get("event_id"));
                env.put("event_type", (String) r.get("event_type"));
                env.put("schema_version", 1);
                env.put("aggregate_id", (String) r.get("aggregate_id"));
                env.put("aggregate_version", ((Number) r.get("aggregate_version")).longValue());
                env.put("occurred_at", (String) r.get("occurred_at"));
                env.put("correlation_id", (String) r.get("correlation_id"));
                if (r.get("causation_id") == null) env.putNull("causation_id");
                else env.put("causation_id", (String) r.get("causation_id"));
                env.put("producer", producer);
                env.set("payload", JSON.readTree((String) r.get("payload")));
                EventSchemas.assertValid(JSON.writeValueAsString(env));
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        }
        return rows.size();
    }
}
