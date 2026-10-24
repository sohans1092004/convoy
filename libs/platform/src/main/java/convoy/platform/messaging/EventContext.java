package convoy.platform.messaging;

import java.util.UUID;

/**
 * Correlation and causation for events written on this thread. HTTP requests start a correlation
 * (X-Correlation-Id header or a new id); consumers inherit the incoming event's correlation and set
 * causation to its event id.
 */
public final class EventContext {
    private record Ids(UUID correlation, UUID causation) {}

    private static final ThreadLocal<Ids> CURRENT = new ThreadLocal<>();

    private EventContext() {}

    public static void set(UUID correlation, UUID causation) { CURRENT.set(new Ids(correlation, causation)); }

    public static void clear() { CURRENT.remove(); }

    public static UUID correlationId() {
        Ids ids = CURRENT.get();
        if (ids == null) {
            ids = new Ids(Uuid7.next(), null);
            CURRENT.set(ids);
        }
        return ids.correlation();
    }

    public static UUID causationId() {
        Ids ids = CURRENT.get();
        return ids == null ? null : ids.causation();
    }
}
