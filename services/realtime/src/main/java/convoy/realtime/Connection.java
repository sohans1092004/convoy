package convoy.realtime;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * One SSE client. Messages go through a bounded queue drained by one virtual thread, so a slow client can never
 * make the server buffer without limit: when the queue is full the connection is closed with a final
 * {@code overloaded} event (realtime-recovery.md §3). The client reconnects and resyncs from the snapshot.
 */
final class Connection {
    record Msg(String event, String id, String data) {}

    final String key;           // "pool:<id>"
    final UUID poolId;
    final UUID buyerId;         // null for dealer staff
    final UUID dealershipId;    // null for buyers
    final String sessionHash;
    private final SseEmitter emitter;
    private final BlockingQueue<Msg> queue;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final Consumer<Connection> onClose;
    volatile String closeReason = "client closed";

    Connection(String key, UUID poolId, UUID buyerId, UUID dealershipId, String sessionHash, SseEmitter emitter, int capacity, Consumer<Connection> onClose) {
        this.key = key;
        this.poolId = poolId;
        this.buyerId = buyerId;
        this.dealershipId = dealershipId;
        this.sessionHash = sessionHash;
        this.emitter = emitter;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.onClose = onClose;
        emitter.onCompletion(() -> close(null));
        emitter.onTimeout(() -> close("timeout"));
        emitter.onError(e -> close("network error"));
        Thread.ofVirtual().name("sse-" + key).start(this::drain);
    }

    boolean isOpen() { return open.get(); }

    /** Never blocks the caller. False if the client is too slow (then the connection is closed). */
    boolean offer(Msg m) {
        if (!open.get()) return false;
        if (queue.offer(m)) return true;
        close("overloaded: client did not keep up (queue of " + (queue.size() + queue.remainingCapacity()) + " messages full)");
        return false;
    }

    private void drain() {
        try {
            while (open.get()) {
                Msg m = queue.poll(1, TimeUnit.SECONDS);
                if (m == null) continue;
                SseEmitter.SseEventBuilder b = SseEmitter.event().name(m.event()).data(m.data());
                if (m.id() != null) b.id(m.id());
                emitter.send(b);
            }
        } catch (IOException | IllegalStateException e) {
            close("network error");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Closes once; a reason is sent to the client as a final event (best effort). */
    void close(String reason) {
        if (!open.compareAndSet(true, false)) return;
        if (reason != null) {
            closeReason = reason;
            try {
                emitter.send(SseEmitter.event().name(reason.startsWith("overloaded") ? "overloaded" : "closed").data("{\"reason\":\"" + reason.replace("\"", "'") + "\"}"));
            } catch (IOException | IllegalStateException ignored) {
                // the client is already gone
            }
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                // already completed
            }
        }
        onClose.accept(this);
    }
}
