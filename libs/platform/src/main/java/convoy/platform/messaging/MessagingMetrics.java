package convoy.platform.messaging;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;

/** Inbox counters and DLQ depth; DLQ_NOT_EMPTY is an alarm (runbook: docs/runbooks/dlq-redrive.md). */
public class MessagingMetrics {
    private static final Logger log = LoggerFactory.getLogger(MessagingMetrics.class);
    private final Topology topology;
    private final MeterRegistry meters;
    final Map<String, Integer> depths = new ConcurrentHashMap<>();

    public MessagingMetrics(InboxConsumer consumer, Topology topology, MeterRegistry meters, TaskScheduler scheduler) {
        this.topology = topology;
        this.meters = meters;
        if (consumer != null && meters != null) {
            String c = consumer.consumer();
            FunctionCounter.builder("inbox_duplicates_total", consumer, x -> x.duplicates.get()).tag("consumer", c).register(meters);
            FunctionCounter.builder("stale_events_total", consumer, x -> x.stale.get()).tag("consumer", c).register(meters);
            FunctionCounter.builder("parked_events_total", consumer, x -> x.parked.get()).tag("consumer", c).register(meters);
            FunctionCounter.builder("snapshot_fallbacks_total", consumer, x -> x.snapshots.get()).tag("consumer", c).register(meters);
            FunctionCounter.builder("inbox_applied_total", consumer, x -> x.applied.get()).tag("consumer", c).register(meters);
        }
        scheduler.scheduleWithFixedDelay(this::checkDlqs, Duration.ofSeconds(30));
    }

    /** Returns the DLQs that are not empty (also used by tests). */
    public Map<String, Integer> checkDlqs() {
        Map<String, Integer> nonEmpty = new java.util.HashMap<>();
        for (String dlq : topology.dlqs().keySet()) {
            try {
                int d = topology.depth(dlq);
                if (meters != null && depths.putIfAbsent(dlq, d) == null) {
                    Gauge.builder("dlq_depth", depths, m -> m.getOrDefault(dlq, 0)).tag("queue", dlq.substring(dlq.lastIndexOf('/') + 1)).register(meters);
                }
                depths.put(dlq, d);
                if (d > 0) {
                    nonEmpty.put(dlq, d);
                    log.error("ALARM DLQ_NOT_EMPTY {} has {} message(s): fix the cause, then redrive (docs/runbooks/dlq-redrive.md)", dlq, d);
                }
            } catch (RuntimeException e) {
                log.warn("DLQ depth check for {} failed: {}", dlq, e.toString());
            }
        }
        return nonEmpty;
    }
}
