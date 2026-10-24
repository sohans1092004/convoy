package convoy.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Validates an event envelope against docs/architecture/events/{type}.schema.json (envelope via $ref). */
public final class EventSchemas {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path DIR = Path.of(System.getProperty("convoy.repoRoot", "../.."), "docs", "architecture", "events");
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
            b -> b.schemaMappers(m -> m.mapPrefix("https://convoy.example/schemas/events/", DIR.toUri().toString())));
    private static final ConcurrentHashMap<String, JsonSchema> CACHE = new ConcurrentHashMap<>();

    private EventSchemas() {}

    /** Returns validation errors (empty = valid). */
    public static Set<ValidationMessage> validate(JsonNode envelope) {
        String type = envelope.path("event_type").asText();
        JsonSchema schema = CACHE.computeIfAbsent(type, t -> {
            Path p = DIR.resolve(t + ".schema.json");
            if (!Files.exists(p)) throw new IllegalArgumentException("no schema for event type " + t);
            return FACTORY.getSchema(SchemaLocation.of(p.toUri().toString()));
        });
        return schema.validate(envelope);
    }

    public static void assertValid(String envelopeJson) {
        try {
            Set<ValidationMessage> errs = validate(JSON.readTree(envelopeJson));
            if (!errs.isEmpty()) throw new AssertionError("event does not match its schema: " + errs + "\n" + envelopeJson);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }
}
