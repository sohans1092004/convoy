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
    private final java.time.Duration gapSnapshotAfter;
    private final Map<String, Subscriptions.Subscription<?>> byType;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;
    public final AtomicLong duplicates = new AtomicLong(), stale = new AtomicLong(), parked = new AtomicLong(), applied = new AtomicLong(),
            snapshots = new AtomicLong();

    public InboxConsumer(String consumer, SqsClient sqs, Topology topology, JdbcTemplate jdbc, TransactionTemplate tx,
                         ObjectMapper json, Subscriptions subscriptions, int waitSeconds, java.time.Duration gapSnapshotAfter) {
        this.consumer = consumer;
        this.sqs = sqs;
        this.topology = topology;
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.subscriptions = subscriptions;
        this.waitSeconds = waitSeconds;
        this.gapSnapshotAfter = gapSnapshotAfter;
        // Fail at startup if code consumes something the catalogue does not list.
        subscriptions.all().forEach(s -> Subscriptions.Kind.of(consumer, s.aggregate()));
        this.byType = subscriptions.all().stream().collect(Collectors.toMap(Subscriptions.Subscription::eventType, s -> s));
    }

    /** A FULL subscription's gap: the inbox transaction rolls back and the envelope moves to parked_events. */
    static final class Park extends RuntimeException {
        final long expected;

        Park(String m, long expected) {
            super(m, null, false, false);
            this.expected = expected;
        }
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
            Thread t = Thread.ofPlatform().daemon().name("inbox-" + k).start(() -> poll(url));
            threads.add(t);
        });
        threads.add(Thread.ofPlatform().daemon().name("inbox-parked-" + consumer).start(() -> {
            while (running) {
                try {
                    retryParked();
                } catch (RuntimeException e) {
                    if (running) log.warn("parked retry failed: {}", e.toString());
                }
                sleep(1000);
            }
        }));
    }

    private void poll(String url) {
        while (running) {
            try {
                List<Message> msgs = sqs.receiveMessage(b -> b.queueUrl(url).maxNumberOfMessages(10).waitTimeSeconds(waitSeconds)
                        .messageAttributeNames("All")).messages();
                for (Message m : msgs) {
                    try {
                        MessageAttributeValue tp = m.messageAttributes().get(Tracing.ATTR);
                        handle(m.body(), tp == null ? null : tp.stringValue());
                        sqs.deleteMessage(b -> b.queueUrl(url).receiptHandle(m.receiptHandle()));
                    } catch (RuntimeException e) {
                        log.warn("handler failed for message {}: {}", m.messageId(), e.toString());
                        break; // keep order within this batch; the message is redelivered after the visibility timeout (DLQ after 5)
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

    /** Handles one delivered envelope. A gap parks it in the database, so SQS can delete it and the queue keeps flowing. */
    void handle(String body, String traceparent) {
        Envelope env = parse(body);
        try {
            process(env, traceparent);
        } catch (Park p) {
            int fresh = jdbc.update("insert into parked_events(consumer, event_id, aggregate_id, aggregate_version, expected_version, envelope) "
                    + "values (?, ?, ?, ?, ?, ?) on conflict do nothing", consumer, env.eventId(), env.aggregateId(), env.aggregateVersion(), p.expected, body);
            if (fresh == 1) parked.incrementAndGet();
            log.info("parked {} v{} for {}: {}", env.eventType(), env.aggregateVersion(), env.aggregateId(), p.getMessage());
        }
    }

    /** Re-applies parked envelopes in version order; each leaves parked_events once applied, stale or duplicate. */
    void retryParked() {
        for (Map<String, Object> row : jdbc.queryForList("select event_id, envelope from parked_events where consumer = ? "
                + "order by aggregate_id, aggregate_version limit 100", consumer)) {
            try {
                process(parse((String) row.get("envelope")), null);
                jdbc.update("delete from parked_events where consumer = ? and event_id = ?", consumer, row.get("event_id"));
            } catch (Park stillMissing) {
                // the earlier version has not arrived yet; parked_at keeps the first time, for the snapshot timeout
            }
        }
    }

    private Envelope parse(String body) {
        try {
            return json.readValue(body, Envelope.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("not an event envelope", e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void process(Envelope env, String traceparent) {
        Subscriptions.Subscription sub = byType.get(env.eventType());
        if (sub == null) return; // filter policy should prevent this
        Span span = Tracing.start("consume " + env.eventType(), SpanKind.CONSUMER, traceparent);
        span.setAttribute("convoy.event_id", env.eventId().toString());
        try (Scope ignored = span.makeCurrent()) {
            EventContext.set(env.correlationId(), env.eventId());
            tx.executeWithoutResult(s -> {
                if (jdbc.update("insert into inbox(consumer, event_id) values (?, ?) on conflict do nothing", consumer, env.eventId()) == 0) {
                    duplicates.incrementAndGet();
                    return;
                }
                if (!versionOk(env, sub.aggregate(), Subscriptions.Kind.of(consumer, sub.aggregate()))) return;
                EventPayload payload = (EventPayload) json.convertValue(env.payload(), sub.type());
                sub.handler().handle(env, payload);
                applied.incrementAndGet();
            });
        } catch (Park p) {
            throw p;
        } catch (RuntimeException e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            EventContext.clear();
            span.end();
        }
    }

    /**
     * The aggregate_version rule (PLAN §4.3.3). False = stale (ignored and counted). A FULL subscription parks on a gap
     * (the message comes back after the visibility timeout); a gap older than gapSnapshotAfter loads the owner snapshot.
     */
    boolean versionOk(Envelope env, String aggregate, Subscriptions.Kind kind) {
        List<Long> last = jdbc.queryForList("select last_version from aggregate_versions where consumer = ? and producer = ? and aggregate_id = ? for update",
                Long.class, consumer, env.producer(), env.aggregateId());
        long prev = last.isEmpty() ? 0 : last.get(0);
        if (kind == Subscriptions.Kind.FULL && !last.isEmpty() && env.aggregateVersion() > prev + 1) {
            Subscriptions.SnapshotLoader loader = subscriptions.snapshotLoader(aggregate);
            Boolean overdue = jdbc.queryForObject("select min(parked_at) < clock_timestamp() - make_interval(secs => ?) from parked_events "
                    + "where consumer = ? and aggregate_id = ?", Boolean.class, gapSnapshotAfter.toMillis() / 1000.0, consumer, env.aggregateId());
            if (loader == null || !Boolean.TRUE.equals(overdue)) {
                if (Boolean.TRUE.equals(overdue)) log.error("ALARM gap on {} {} older than {} and no snapshot loader", aggregate, env.aggregateId(), gapSnapshotAfter);
                throw new Park("gap: got v" + env.aggregateVersion() + ", expected v" + (prev + 1), prev + 1);
            }
            try {
                prev = loader.load(env.aggregateId());
            } catch (RuntimeException e) {
                // The owner is unavailable: stay parked (SQS still deletes the message, so a FIFO group is never blocked).
                log.warn("snapshot for {} {} failed: {}; still parked", aggregate, env.aggregateId(), e.toString());
                throw new Park("gap and snapshot unavailable: got v" + env.aggregateVersion(), prev + 1);
            }
            snapshots.incrementAndGet();
            log.warn("gap on {} {} outlived {}: applied owner snapshot v{}", aggregate, env.aggregateId(), gapSnapshotAfter, prev);
            setLast(env, prev);
            jdbc.update("delete from parked_events where consumer = ? and aggregate_id = ? and aggregate_version <= ?", consumer, env.aggregateId(), prev);
            if (env.aggregateVersion() > prev + 1) throw new Park("gap after snapshot v" + prev + ": got v" + env.aggregateVersion(), prev + 1);
        }
        if (env.aggregateVersion() <= prev) {
            stale.incrementAndGet();
            log.info("stale {} v{} <= v{} for {}", env.eventType(), env.aggregateVersion(), prev, env.aggregateId());
            return false;
        }
        setLast(env, env.aggregateVersion());
        jdbc.update("delete from parked_events where consumer = ? and event_id = ?", consumer, env.eventId());
        return true;
    }

    private void setLast(Envelope env, long version) {
        jdbc.update("insert into aggregate_versions(consumer, producer, aggregate_id, last_version) values (?, ?, ?, ?) "
                + "on conflict (consumer, producer, aggregate_id) do update set last_version = excluded.last_version", consumer, env.producer(), env.aggregateId(), version);
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
