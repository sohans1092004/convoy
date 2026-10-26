package convoy.platform.messaging;

import convoy.contracts.events.EventPayload;
import convoy.contracts.events.SubscriptionKinds;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a service consumes. Declare it as a bean:
 * <pre>{@code
 * @Bean Subscriptions subscriptions(Handlers h) {
 *     return new Subscriptions().on(PaymentCaptured.class, h::paymentCaptured);
 * }
 * }</pre>
 * How aggregate_version is checked is not chosen here: it is the subscription kind in event-catalogue.yaml
 * (generated into {@link SubscriptionKinds}). Every subscription ignores stale versions; FULL ones also park on gaps.
 */
public class Subscriptions {
    public enum Kind {
        /** Receives every event type of the aggregate: stale rule and gap detection (park, retry, snapshot). */
        FULL,
        /** Receives some event types: stale rule only (a gap may be an event it does not receive). */
        PARTIAL,
        /** AMENDMENT-010 (notification): each event is its own message, never a state overwrite: inbox dedupe only, no stale rule. */
        EVENTS;

        public static Kind of(String consumer, String aggregate) {
            String k = SubscriptionKinds.ALL.get(consumer + ":" + aggregate);
            if (k == null) throw new IllegalStateException(consumer + " consumes " + aggregate + " events but event-catalogue.yaml lists no such subscription");
            return valueOf(k);
        }
    }

    @FunctionalInterface
    public interface Handler<T extends EventPayload> {
        void handle(Envelope envelope, T payload);
    }

    /**
     * Applies the owner's current snapshot of an aggregate to this service's state (in the consumer's transaction)
     * and returns the snapshot's aggregate_version. Used when a FULL subscriber's gap outlives the timeout.
     */
    @FunctionalInterface
    public interface SnapshotLoader {
        long load(UUID aggregateId);
    }

    public record Subscription<T extends EventPayload>(Class<T> type, Handler<T> handler, boolean fifoQueue) {
        public String eventType() { return constant("TYPE"); }

        public String producer() { return constant("PRODUCER"); }

        public String aggregate() { return constant("AGGREGATE"); }

        public boolean fifoTopic() { return OutboxRelay.isFifo(eventType()); }

        private String constant(String name) {
            try {
                return (String) type.getField(name).get(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException(e);
            }
        }
    }

    private final List<Subscription<?>> all = new ArrayList<>();
    private final Map<String, SnapshotLoader> snapshots = new HashMap<>();

    /** Subscribe; a FIFO event is consumed through a FIFO queue (ordered per aggregate, AMENDMENT-006). */
    public <T extends EventPayload> Subscriptions on(Class<T> type, Handler<T> handler) {
        all.add(new Subscription<>(type, handler, true));
        return this;
    }

    /** Subscribe to a FIFO-topic event through a standard queue (best-effort order; the stale rule still applies). */
    public <T extends EventPayload> Subscriptions onUnordered(Class<T> type, Handler<T> handler) {
        all.add(new Subscription<>(type, handler, false));
        return this;
    }

    /** Snapshot fallback for a FULL subscription's aggregate (e.g. "pool"). */
    public Subscriptions snapshot(String aggregate, SnapshotLoader loader) {
        snapshots.put(aggregate, loader);
        return this;
    }

    public List<Subscription<?>> all() { return all; }

    public SnapshotLoader snapshotLoader(String aggregate) { return snapshots.get(aggregate); }
}
