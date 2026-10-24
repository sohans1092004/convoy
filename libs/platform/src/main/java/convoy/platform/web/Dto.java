package convoy.platform.web;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * Converts an internal JSON-shaped value (snake_case map) into the endpoint's response record, so every
 * endpoint has a typed contract in OpenAPI. Unknown keys fail the conversion: a response can never carry a
 * field its DTO does not declare.
 */
public final class Dto {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private Dto() {}

    public static <T> T as(Object value, Class<T> type) { return JSON.convertValue(value, type); }

    /** The reverse: a request record as the snake_case JSON map it is stored as. */
    @SuppressWarnings("unchecked")
    public static java.util.Map<String, Object> map(Object record) { return JSON.convertValue(record, java.util.Map.class); }
}
