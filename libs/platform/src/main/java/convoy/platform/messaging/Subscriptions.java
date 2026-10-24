package convoy.platform.messaging;

import convoy.contracts.events.EventPayload;
import java.util.ArrayList;
import java.util.List;

/**
 * What a service consumes. Declare it as a bean:
 * <pre>{@code
 * @Bean Subscriptions subscriptions(Handlers h) {
 *     return new Subscriptions()
 *         .on(PaymentCaptured.class, Ordering.MONOTONIC, h::paymentCaptured);
 * }
 * }</pre>
 */
public class Subscriptions {
    /** aggregate_version handling (PLAN §4.3.3). */
    public enum Ordering {
        /** No version check (independent events). */
        NONE,
        /** Ignore and count events not newer than the last one applied for the aggregate. */
        MONOTONIC,
        /** As MONOTONIC, and park (retry later) on a gap; only for consumers that receive every event of the aggregate. */
        STRICT
    }

    @FunctionalInterface
    public interface Handler<T extends EventPayload> {
        void handle(Envelope envelope, T payload);
    }

    public record Subscription<T extends EventPayload>(Class<T> type, Ordering ordering, Handler<T> handler, boolean fifoQueue) {
        public String eventType() {
            try {
                return (String) type.getField("TYPE").get(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException(e);
            }
        }

        public String producer() {
            try {
                return (String) type.getField("PRODUCER").get(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException(e);
            }
        }

        public boolean fifoTopic() { return OutboxRelay.isFifo(eventType()); }
    }

    private final List<Subscription<?>> all = new ArrayList<>();

    /** Subscribe; a FIFO event is consumed through a FIFO queue (ordering consumers, AMENDMENT-006). */
    public <T extends EventPayload> Subscriptions on(Class<T> type, Ordering ordering, Handler<T> handler) {
        all.add(new Subscription<>(type, ordering, handler, true));
        return this;
    }

    /** Subscribe to a FIFO-topic event through a standard queue (consumers that do not need order). */
    public <T extends EventPayload> Subscriptions onUnordered(Class<T> type, Handler<T> handler) {
        all.add(new Subscription<>(type, Ordering.NONE, handler, false));
        return this;
    }

    public List<Subscription<?>> all() { return all; }
}
