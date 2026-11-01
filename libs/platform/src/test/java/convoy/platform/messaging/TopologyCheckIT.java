package convoy.platform.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/** P6.A: in prod a service checks the applied topology and refuses to start, naming what is missing. */
@Testcontainers
class TopologyCheckIT {
    @Container static final LocalStackContainer LS = new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14.0"))
            .withServices(LocalStackContainer.Service.SNS, LocalStackContainer.Service.SQS);
    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void checksInsteadOfCreating() {
        var creds = StaticCredentialsProvider.create(AwsBasicCredentials.create(LS.getAccessKey(), LS.getSecretKey()));
        SnsClient sns = SnsClient.builder().endpointOverride(LS.getEndpoint()).region(Region.of(LS.getRegion())).credentialsProvider(creds).build();
        SqsClient sqs = SqsClient.builder().endpointOverride(LS.getEndpoint()).region(Region.of(LS.getRegion())).credentialsProvider(creds).build();
        Topology prod = new Topology(sns, sqs, JSON, 30, "000000000000"); // LocalStack's account
        List<String> types = List.of("MemberJoined", "PoolReady");

        var e = assertThrows(Topology.Missing.class, () -> prod.ensureQueue("realtime", "pool", true, true, types));
        assertTrue(e.getMessage().contains("SNS topic arn:aws:sns:" + LS.getRegion() + ":000000000000:convoy-pool-events.fifo"), e.getMessage());
        assertEquals(0, sqs.listQueues().queueUrls().size(), "nothing created in prod mode");

        // Applied with one type fewer than the code consumes: the lost type is named.
        new Topology(sns, sqs, JSON).ensureQueue("realtime", "pool", true, true, List.of("MemberJoined"));
        e = assertThrows(Topology.Missing.class, () -> new Topology(sns, sqs, JSON, 30, "000000000000").ensureQueue("realtime", "pool", true, true, types));
        assertTrue(e.getMessage().contains("filter policy") && e.getMessage().contains("PoolReady"), e.getMessage());

        new Topology(sns, sqs, JSON).ensureQueue("realtime", "pool", true, true, types);
        String url = prod.ensureQueue("realtime", "pool", true, true, types);
        assertTrue(url.endsWith("convoy-realtime--pool-events.fifo"), url);
        assertEquals(1, prod.dlqs().size());
        assertThrows(Topology.Missing.class, () -> prod.topicArn("order", false));
    }
}
