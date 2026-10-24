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
 * Create calls are idempotent, so each service ensures what it publishes to and consumes from at startup.
 * ponytail: in AWS (P6) this layout moves to infrastructure code and services only look ARNs up.
 */
public class Topology {
    private final SnsClient sns;
    private final SqsClient sqs;
    private final ObjectMapper json;
    private final Map<String, String> topicArns = new ConcurrentHashMap<>();

    public Topology(SnsClient sns, SqsClient sqs, ObjectMapper json) {
        this.sns = sns;
        this.sqs = sqs;
        this.json = json;
    }

    public static String topicName(String producer, boolean fifo) {
        return "convoy-" + producer + "-events" + (fifo ? ".fifo" : "");
    }

    public String topicArn(String producer, boolean fifo) {
        return topicArns.computeIfAbsent(topicName(producer, fifo), name -> {
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
        Map<QueueAttributeName, String> dlqAttrs = new HashMap<>();
        if (fifo) dlqAttrs.put(QueueAttributeName.FIFO_QUEUE, "true");
        String dlqUrl = sqs.createQueue(b -> b.queueName(base + "-dlq" + suffix).attributes(dlqAttrs)).queueUrl();
        String dlqArn = sqs.getQueueAttributes(b -> b.queueUrl(dlqUrl).attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes().get(QueueAttributeName.QUEUE_ARN);

        String topicArn = topicArn(producer, fifoTopic);
        Map<QueueAttributeName, String> attrs = new HashMap<>();
        if (fifo) attrs.put(QueueAttributeName.FIFO_QUEUE, "true");
        attrs.put(QueueAttributeName.VISIBILITY_TIMEOUT, "30");
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

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
