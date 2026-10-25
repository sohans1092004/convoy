package convoy.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ConnectionTest {
    /** A client that never reads: the first send blocks forever, like a full TCP window. */
    static final class StuckEmitter extends SseEmitter {
        final CountDownLatch never = new CountDownLatch(1);
        final List<String> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(SseEventBuilder builder) {
            sent.add(builder.build().toString());
            if (sent.size() == 1) {
                try {
                    never.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Test
    void aSlowClientIsDroppedWithAReasonInsteadOfBufferingWithoutLimit() throws Exception {
        StuckEmitter emitter = new StuckEmitter();
        List<Connection> closed = new CopyOnWriteArrayList<>();
        Connection c = new Connection("pool:x", UUID.randomUUID(), UUID.randomUUID(), null, "s", emitter, 256, closed::add);
        Connection.Msg m = new Connection.Msg("pool.changed", "pool:x:1", "{}");
        assertThat(c.offer(m)).isTrue();
        long until = System.currentTimeMillis() + 5_000;
        while (emitter.sent.isEmpty() && System.currentTimeMillis() < until) TimeUnit.MILLISECONDS.sleep(10); // the drain thread is now stuck in send
        int accepted = 0;
        while (c.offer(m)) accepted++;
        assertThat(accepted).isEqualTo(256);
        assertThat(c.isOpen()).isFalse();
        assertThat(c.closeReason).startsWith("overloaded");
        assertThat(closed).containsExactly(c);
        assertThat(c.offer(m)).isFalse();
    }
}
