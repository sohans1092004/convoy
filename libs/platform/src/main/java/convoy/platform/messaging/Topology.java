package convoy.platform.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Topic and queue layout (event-catalogue.yaml header, AMENDMENT-006). One SNS topic per producer
 * (convoy-&lt;producer&gt;-events) plus convoy-pool-events.fifo; one SQS queue (+ DLQ, maxReceiveCount 5)
 * per consumer per topic, subscribed with raw delivery and a filter policy on the event_type attribute.
 *
 * Local and CI: create calls are idempotent, so each service ensures what it publishes to and consumes from at startup.
 * prod (P6.A): the layout is infrastructure (infra/aws/shared). Given the account it was applied in, services only
 * check that their topics, queues, DLQs and subscriptions exist and refuse to start, naming what is missing, if not.
 */
public class Topology {
    private final SnsClient sns;
    private final SqsClient sqs;
    private final ObjectMapper json;
    private final Map<String, String> topicArns = new ConcurrentHashMap<>();
    /** DLQ url -> source queue url, for every queue this service consumes (depth alarm, redrive). */
    private final Map<String, String> dlqs = new ConcurrentHashMap<>();
    private final int visibilitySeconds;
    /** null: create the layout (local, CI). Otherwise the AWS account it was applied in: check only (prod). */
    private final String account;

    public Topology(SnsClient sns, SqsClient sqs, ObjectMapper json) { this(sns, sqs, json, 30, null); }

    public Topology(SnsClient sns, SqsClient sqs, ObjectMapper json, int visibilitySeconds, String account) {
        this.sns = sns;
        this.sqs = sqs;
        this.json = json;
        this.visibilitySeconds = visibilitySeconds;
        this.account = account;
    }

    /** Thrown at startup in prod when the applied infrastructure lacks something this service needs. */
    public static final class Missing extends IllegalStateException {
        Missing(String what) {
            super(what + " does not exist or is not as this service needs it; apply infra/aws/shared (it is not created at startup in prod)");
        }
    }

    public Map<String, String> dlqs() { return dlqs; }

    /** Approximate number of messages waiting in a queue (SQS attribute; eventually consistent). */
    public int depth(String queueUrl) {
        return Integer.parseInt(sqs.getQueueAttributes(b -> b.queueUrl(queueUrl).attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                .attributes().getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "0"));
    }

    /**
     * Runbook redrive (docs/runbooks/dlq-redrive.md): move every message from a DLQ back to its source queue, after the
     * cause is fixed. Consumers dedupe on event_id, so a message that was in fact applied is not applied twice.
     */
    public int redrive(String dlqUrl) {
        String source = dlqs.get(dlqUrl);
        if (source == null) throw new IllegalArgumentException("not a DLQ of this service: " + dlqUrl);
        boolean fifo = dlqUrl.endsWith(".fifo");
        int moved = 0;
        while (true) {
            var msgs = sqs.receiveMessage(b -> b.queueUrl(dlqUrl).maxNumberOfMessages(10).waitTimeSeconds(1).messageAttributeNames("All")
                    .messageSystemAttributeNamesWithStrings("MessageGroupId")).messages();
            if (msgs.isEmpty()) return moved;
            for (var m : msgs) {
                sqs.sendMessage(b -> {
                    b.queueUrl(source).messageBody(m.body()).messageAttributes(m.messageAttributes());
                    if (fifo) b.messageGroupId(m.attributesAsStrings().getOrDefault("MessageGroupId", "redrive")).messageDeduplicationId(m.messageId() + "-redrive");
                });
                sqs.deleteMessage(b -> b.queueUrl(dlqUrl).receiptHandle(m.receiptHandle()));
                moved++;
            }
        }
    }

    public static String topicName(String producer, boolean fifo) {
        return "convoy-" + producer + "-events" + (fifo ? ".fifo" : "");
    }

    public String topicArn(String producer, boolean fifo) {
        return topicArns.computeIfAbsent(topicName(producer, fifo), name -> {
            if (account != null) {
                String arn = "arn:aws:sns:" + sns.serviceClientConfiguration().region().id() + ":" + account + ":" + name;
                try {
                    sns.getTopicAttributes(b -> b.topicArn(arn));
                } catch (software.amazon.awssdk.services.sns.model.NotFoundException e) {
                    throw new Missing("SNS topic " + arn);
                }
                return arn;
            }
            Map<String, String> attrs = new HashMap<>();
            if (fifo) {
                attrs.put("FifoTopic", "true");
                attrs.put("ContentBasedDeduplication", "false");
            }
            return sns.createTopic(b -> b.name(name).attributes(attrs)).topicArn();
        });
    }

    /**
     * Ensure the consumer's queue for one producer topic and subscribe it to the given event types.
     * A FIFO topic may feed a standard queue (fifoQueue=false) for consumers that do not need order.
     */
    public String ensureQueue(String consumer, String producer, boolean fifoTopic, boolean fifoQueue, Collection<String> eventTypes) {
        boolean fifo = fifoTopic && fifoQueue;
        String base = "convoy-" + consumer + "--" + producer + "-events";
        String suffix = fifo ? ".fifo" : "";
        if (account != null) return checkQueue(base, suffix, topicArn(producer, fifoTopic), eventTypes);
        Map<QueueAttributeName, String> dlqAttrs = new HashMap<>();
        if (fifo) dlqAttrs.put(QueueAttributeName.FIFO_QUEUE, "true");
        String dlqUrl = sqs.createQueue(b -> b.queueName(base + "-dlq" + suffix).attributes(dlqAttrs)).queueUrl();
        String dlqArn = sqs.getQueueAttributes(b -> b.queueUrl(dlqUrl).attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes().get(QueueAttributeName.QUEUE_ARN);

        String topicArn = topicArn(producer, fifoTopic);
        Map<QueueAttributeName, String> attrs = new HashMap<>();
        if (fifo) attrs.put(QueueAttributeName.FIFO_QUEUE, "true");
        attrs.put(QueueAttributeName.VISIBILITY_TIMEOUT, String.valueOf(visibilitySeconds));
        // Fixed key order: an existing queue is compared attribute by attribute on every start.
        attrs.put(QueueAttributeName.REDRIVE_POLICY, "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"5\"}");
        String url;
        try {
            url = sqs.createQueue(b -> b.queueName(base + suffix).attributes(attrs)).queueUrl();
        } catch (software.amazon.awssdk.services.sqs.model.QueueNameExistsException e) {
            // Created by an earlier version with other attributes: converge instead of failing to start.
            url = sqs.getQueueUrl(b -> b.queueName(base + suffix)).queueUrl();
            Map<QueueAttributeName, String> mutable = new HashMap<>(attrs);
            mutable.remove(QueueAttributeName.FIFO_QUEUE);
            String u = url;
            sqs.setQueueAttributes(b -> b.queueUrl(u).attributes(mutable));
        }
        String queueUrl = url;
        dlqs.put(dlqUrl, queueUrl);
        String arn = sqs.getQueueAttributes(b -> b.queueUrl(queueUrl).attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes().get(QueueAttributeName.QUEUE_ARN);
        Map<String, Object> policy = Map.of("Version", "2012-10-17", "Statement", List.of(Map.of(
                "Effect", "Allow", "Principal", Map.of("Service", "sns.amazonaws.com"), "Action", "sqs:SendMessage",
                "Resource", arn, "Condition", Map.of("ArnEquals", Map.of("aws:SourceArn", topicArn)))));
        sqs.setQueueAttributes(b -> b.queueUrl(queueUrl).attributes(Map.of(QueueAttributeName.POLICY, write(policy))));
        String filter = write(Map.of("event_type", eventTypes.stream().sorted().toList()));
        try {
            sns.subscribe(b -> b.topicArn(topicArn).protocol("sqs").endpoint(arn).returnSubscriptionArn(true).attributes(Map.of(
                    "RawMessageDelivery", "true", "FilterPolicy", filter)));
        } catch (software.amazon.awssdk.services.sns.model.InvalidParameterException e) {
            // Already subscribed with an older filter (the service now consumes more event types): update it in place.
            sns.listSubscriptionsByTopicPaginator(b -> b.topicArn(topicArn)).subscriptions().stream().filter(s -> arn.equals(s.endpoint())).forEach(s ->
                    sns.setSubscriptionAttributes(b -> b.subscriptionArn(s.subscriptionArn()).attributeName("FilterPolicy").attributeValue(filter)));
        }
        return queueUrl;
    }

    private String checkQueue(String base, String suffix, String topicArn, Collection<String> eventTypes) {
        String queueUrl = existingQueue(base + suffix), dlqUrl = existingQueue(base + "-dlq" + suffix);
        var attrs = sqs.getQueueAttributes(b -> b.queueUrl(queueUrl).attributeNames(QueueAttributeName.QUEUE_ARN, QueueAttributeName.REDRIVE_POLICY)).attributes();
        String arn = attrs.get(QueueAttributeName.QUEUE_ARN);
        if (!attrs.getOrDefault(QueueAttributeName.REDRIVE_POLICY, "").contains(base + "-dlq" + suffix)) throw new Missing("redrive of " + base + suffix + " to its DLQ");
        var sub = sns.listSubscriptionsByTopicPaginator(b -> b.topicArn(topicArn)).subscriptions().stream()
                .filter(s -> arn.equals(s.endpoint())).findFirst().orElseThrow(() -> new Missing("subscription of " + base + suffix + " to " + topicArn));
        String filter = sns.getSubscriptionAttributes(b -> b.subscriptionArn(sub.subscriptionArn())).attributes().getOrDefault("FilterPolicy", "{}");
        try {
            java.util.Set<String> delivered = new java.util.HashSet<>();
            json.readTree(filter).path("event_type").forEach(n -> delivered.add(n.asText()));
            List<String> lost = eventTypes.stream().filter(t -> !delivered.contains(t)).sorted().toList();
            // Extra types are harmless (the consumer ignores them); a missing one would be lost silently.
            if (!lost.isEmpty()) throw new Missing("filter policy of " + base + suffix + " for " + lost);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new Missing("a readable filter policy on " + base + suffix);
        }
        dlqs.put(dlqUrl, queueUrl);
        return queueUrl;
    }

    private String existingQueue(String name) {
        try {
            return sqs.getQueueUrl(b -> b.queueName(name)).queueUrl();
        } catch (software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException e) {
            throw new Missing("SQS queue " + name);
        }
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
