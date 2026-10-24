package convoy.platform.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.contracts.events.EventPayload;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

/**
 * Polls one SQS queue per producer topic. Each message is handled in one transaction together with
 * its inbox row (dedupe on event_id) and the aggregate_version bookkeeping; the message is deleted only
 * after commit. A handler exception rolls back and leaves the message for redelivery (DLQ after 5).
 */
public class InboxConsumer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(InboxConsumer.class);
    private final String consumer;
    private final SqsClient sqs;
    private final Topology topology;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Subscriptions subscriptions;
    private final int waitSeconds;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;
    public final AtomicLong duplicates = new AtomicLong(), stale = new AtomicLong(), parked = new AtomicLong(), applied = new AtomicLong();

    public InboxConsumer(String consumer, SqsClient sqs, Topology topology, JdbcTemplate jdbc, TransactionTemplate tx,
                         ObjectMapper json, Subscriptions subscriptions, int waitSeconds) {
        this.consumer = consumer;
        this.sqs = sqs;
        this.topology = topology;
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.subscriptions = subscriptions;
        this.waitSeconds = waitSeconds;
    }

    static final class Park extends RuntimeException {
        Park(String m) { super(m, null, false, false); }
    }

    @Override
    public void start() {
        running = true;
        // One queue per (producer topic, queue kind).
        Map<String, List<Subscriptions.Subscription<?>>> groups = subscriptions.all().stream().collect(Collectors.groupingBy(
                s -> s.producer() + "|" + s.fifoTopic() + "|" + (s.fifoTopic() && s.fifoQueue()), LinkedHashMap::new, Collectors.toList()));
        groups.forEach((k, subs) -> {
            Subscriptions.Subscription<?> first = subs.get(0);
            String url = topology.ensureQueue(consumer, first.producer(), first.fifoTopic(), first.fifoQueue(),
                    subs.stream().map(Subscriptions.Subscription::eventType).toList());
            Map<String, Subscriptions.Subscription<?>> byType = subs.stream()
                    .collect(Collectors.toMap(Subscriptions.Subscription::eventType, s -> s));
            Thread t = Thread.ofPlatform().daemon().name("inbox-" + k).start(() -> poll(url, byType));
            threads.add(t);
        });
    }

    private void poll(String url, Map<String, Subscriptions.Subscription<?>> byType) {
        while (running) {
            try {
                List<Message> msgs = sqs.receiveMessage(b -> b.queueUrl(url).maxNumberOfMessages(10).waitTimeSeconds(waitSeconds)
                        .messageAttributeNames("All")).messages();
                for (Message m : msgs) {
                    try {
                        handle(m, byType);
                        sqs.deleteMessage(b -> b.queueUrl(url).receiptHandle(m.receiptHandle()));
                    } catch (Park p) {
                        parked.incrementAndGet();
                        log.info("parked {}: {}", m.messageId(), p.getMessage());
                        break; // keep order within this batch; the message is redelivered after the visibility timeout
                    } catch (RuntimeException e) {
                        log.warn("handler failed for message {}: {}", m.messageId(), e.toString());
                        break;
                    }
                }
            } catch (RuntimeException e) {
                if (running) {
                    log.warn("receive on {} failed: {}", url, e.toString());
                    sleep(1000);
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    void handle(Message m, Map<String, Subscriptions.Subscription<?>> byType) {
        Envelope env;
        try {
            env = json.readValue(m.body(), Envelope.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("not an event envelope", e);
        }
        Subscriptions.Subscription sub = byType.get(env.eventType());
        if (sub == null) return; // filter policy should prevent this
        MessageAttributeValue tp = m.messageAttributes().get(Tracing.ATTR);
        Span span = Tracing.start("consume " + env.eventType(), SpanKind.CONSUMER, tp == null ? null : tp.stringValue());
        span.setAttribute("convoy.event_id", env.eventId().toString());
        try (Scope ignored = span.makeCurrent()) {
            EventContext.set(env.correlationId(), env.eventId());
            tx.executeWithoutResult(s -> {
                if (jdbc.update("insert into inbox(consumer, event_id) values (?, ?) on conflict do nothing", consumer, env.eventId()) == 0) {
                    duplicates.incrementAndGet();
                    return;
                }
                if (sub.ordering() != Subscriptions.Ordering.NONE && !versionOk(env, sub.ordering())) return;
                EventPayload payload = (EventPayload) json.convertValue(env.payload(), sub.type());
                sub.handler().handle(env, payload);
                applied.incrementAndGet();
            });
        } catch (RuntimeException e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            EventContext.clear();
            span.end();
        }
    }

    /** False = stale (ignored, counted). Throws Park on a gap for STRICT consumers. */
    private boolean versionOk(Envelope env, Subscriptions.Ordering ordering) {
        List<Long> last = jdbc.queryForList("select last_version from aggregate_versions where producer = ? and aggregate_id = ? for update",
                Long.class, env.producer(), env.aggregateId());
        long prev = last.isEmpty() ? 0 : last.get(0);
        if (env.aggregateVersion() <= prev) {
            stale.incrementAndGet();
            log.info("stale {} v{} <= v{} for {}", env.eventType(), env.aggregateVersion(), prev, env.aggregateId());
            return false;
        }
        if (ordering == Subscriptions.Ordering.STRICT && !last.isEmpty() && env.aggregateVersion() > prev + 1) {
            jdbc.update("insert into parked_events(consumer, event_id, aggregate_id, aggregate_version, expected_version) values (?, ?, ?, ?, ?) "
                    + "on conflict do nothing", consumer, env.eventId(), env.aggregateId(), env.aggregateVersion(), prev + 1);
            throw new Park("gap: got v" + env.aggregateVersion() + ", expected v" + (prev + 1));
        }
        jdbc.update("insert into aggregate_versions(producer, aggregate_id, last_version) values (?, ?, ?) "
                + "on conflict (producer, aggregate_id) do update set last_version = excluded.last_version", env.producer(), env.aggregateId(), env.aggregateVersion());
        return true;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop() {
        running = false;
        threads.forEach(Thread::interrupt);
    }

    @Override
    public boolean isRunning() { return running; }
}
