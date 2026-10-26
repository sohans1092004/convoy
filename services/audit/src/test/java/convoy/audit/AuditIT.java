package convoy.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.platform.messaging.Uuid7;
import convoy.platform.testing.ServiceTest;
import convoy.platform.testing.TestTokens;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * P5.C: every service's events, injected into audit's standard queues reversed and twice each, give exactly one row per
 * event; the log is append-only (grants + trigger) and verify-chain detects a superuser's edit; the viewer is for
 * platform admins only and every read is itself audited (AZ-E163).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "convoy.messaging.enabled=true", "convoy.messaging.wait-seconds=1",
        "spring.flyway.user=test", "spring.flyway.password=test", // Testcontainers' superuser runs the migration (creates the app role)
        "convoy.issuers.convoy-gateway=http://unused", "convoy.issuers.convoy-identity=http://unused"})
@Import(ServiceTest.Config.class)
class AuditIT extends ServiceTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final LocalStackContainer LS = new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14.0"))
            .withServices(LocalStackContainer.Service.SNS, LocalStackContainer.Service.SQS);

    static {
        LS.start();
    }

    @DynamicPropertySource
    static void aws(DynamicPropertyRegistry r) {
        r.add("convoy.aws.endpoint", () -> LS.getEndpoint().toString());
        r.add("convoy.aws.region", LS::getRegion);
    }

    @AfterAll
    static void stop() { LS.stop(); }

    @Autowired SqsClient sqs;
    @Autowired JdbcTemplate jdbc;
    @Autowired Chain chain;

    record Ev(String type, String producer, UUID aggregate, long version, Map<String, Object> payload, UUID id) {
        Ev(String type, String producer, UUID aggregate, long version, Map<String, Object> payload) { this(type, producer, aggregate, version, payload, Uuid7.next()); }
    }

    void inject(List<Ev> events, UUID correlation) throws Exception {
        for (Ev e : events) {
            Map<String, Object> env = new LinkedHashMap<>();
            env.put("event_id", e.id().toString());
            env.put("event_type", e.type());
            env.put("schema_version", 1);
            env.put("aggregate_id", e.aggregate().toString());
            env.put("aggregate_version", e.version());
            env.put("occurred_at", Instant.now().toString());
            env.put("correlation_id", correlation.toString());
            env.put("causation_id", null);
            env.put("producer", e.producer());
            env.put("payload", e.payload());
            String queue = sqs.getQueueUrl(b -> b.queueName("convoy-audit--" + e.producer() + "-events")).queueUrl();
            String body = JSON.writeValueAsString(env);
            sqs.sendMessage(b -> b.queueUrl(queue).messageBody(body));
        }
    }

    long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
        return out;
    }

    @Test
    void reversedBacklogWithDuplicatesIsRecordedOnceAndTamperingIsDetected() throws Exception {
        UUID pool = UUID.randomUUID(), buyer = UUID.randomUUID(), dealership = UUID.randomUUID(), admin = UUID.randomUUID(), user = UUID.randomUUID();
        UUID corr = UUID.randomUUID();
        List<Ev> story = List.of(
                new Ev("MemberCommitted", "pool", pool, 3, m("pool_id", pool, "membership_id", UUID.randomUUID(), "buyer_id", buyer, "payment_id", UUID.randomUUID(),
                        "interested_count", 1, "committed_count", 1, "kept_from_previous_round", false)),
                new Ev("PoolReady", "pool", pool, 4, m("pool_id", pool, "committed_count", 1)),
                new Ev("PoolCompleted", "pool", pool, 9, m("pool_id", pool)),
                new Ev("DealershipVerified", "dealer", dealership, 2, m("dealership_id", dealership, "brands", List.of("Tata"), "city", "Pune",
                        "verified_by", admin, "registered_by", UUID.randomUUID())),
                new Ev("ErasureRequested", "identity", user, 5, m("erasure_id", UUID.randomUUID(), "user_id", user, "requested_at", Instant.now().toString())));
        List<Ev> backlog = new ArrayList<>(story.reversed()); // v9 before v3: the old FULL rule would have dropped v3 and v4
        backlog.addAll(story);
        inject(backlog, corr);

        long end = System.currentTimeMillis() + 60_000;
        while (count("select count(*) from audit_log where correlation_id = ?", corr) < story.size() && System.currentTimeMillis() < end) Thread.sleep(200);
        Thread.sleep(1500); // a late duplicate would show up here
        assertThat(count("select count(*) from audit_log where correlation_id = ?", corr)).isEqualTo(story.size());
        assertThat(count("select count(*) from audit_log where aggregate_id = ? and aggregate_type = 'pool'", pool)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select actor from audit_log where event_type = 'DealershipVerified' and aggregate_id = ?", String.class, dealership))
                .isEqualTo("user:" + admin);
        assertThat(jdbc.queryForObject("select actor from audit_log where event_type = 'PoolReady' and aggregate_id = ?", String.class, pool)).isEqualTo("service:pool");
        assertThat(jdbc.queryForObject("select sensitive from audit_log where event_type = 'ErasureRequested' and aggregate_id = ?", Boolean.class, user)).isTrue();
        assertThat(chain.verify()).isNull();

        // append-only: the app role has INSERT and SELECT only ...
        for (String priv : List.of("UPDATE", "DELETE", "TRUNCATE")) {
            assertThat(jdbc.queryForObject("select has_table_privilege('audit', 'audit_log', ?)", Boolean.class, priv)).as(priv).isFalse();
        }
        for (String priv : List.of("INSERT", "SELECT")) {
            assertThat(jdbc.queryForObject("select has_table_privilege('audit', 'audit_log', ?)", Boolean.class, priv)).as(priv).isTrue();
        }
        // ... and the trigger refuses changes even from a superuser
        long seq = jdbc.queryForObject("select seq from audit_log where event_type = 'PoolReady' and aggregate_id = ?", Long.class, pool);
        assertThatThrownBy(() -> jdbc.update("update audit_log set actor = 'user:someone' where seq = ?", seq)).rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from audit_log where seq = ?", seq)).rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("truncate audit_log")).rootCause().hasMessageContaining("append-only");

        // a superuser who disables the trigger and edits a row: verify-chain finds that exact row
        String original = jdbc.queryForObject("select payload::text from audit_log where seq = ?", String.class, seq);
        tamper("update audit_log set payload = jsonb_set(payload, '{committed_count}', '99') where seq = " + seq);
        assertThat(chain.verify()).isEqualTo(new Chain.Broken(seq, "row content does not match its hash"));
        tamper("update audit_log set payload = '" + original + "'::jsonb where seq = " + seq);
        assertThat(chain.verify()).isNull();
        // deleting a row in the middle breaks the next link
        long last = count("select max(seq) from audit_log");
        long middle = jdbc.queryForObject("select max(seq) from audit_log where seq < ?", Long.class, last);
        jdbc.execute("create temp table saved as select * from audit_log where seq = " + middle);
        tamper("delete from audit_log where seq = " + middle);
        assertThat(chain.verify()).isEqualTo(new Chain.Broken(last, "prev_hash does not match the previous row"));
        jdbc.execute("insert into audit_log overriding system value select * from saved"); // put it back for the other tests
        assertThat(chain.verify()).isNull();
    }

    void tamper(String sql) {
        jdbc.execute("alter table audit_log disable trigger audit_log_append_only");
        try {
            jdbc.execute(sql);
        } finally {
            jdbc.execute("alter table audit_log enable trigger audit_log_append_only");
        }
    }

    @Test
    void viewerIsForPlatformAdminsAndEveryReadIsAudited() throws Exception {
        UUID admin = UUID.randomUUID(), dealership = UUID.randomUUID(), corr = UUID.randomUUID();
        inject(List.of(new Ev("DealershipSuspended", "dealer", dealership, 7, m("dealership_id", dealership, "reason", "test"))), corr);
        long end = System.currentTimeMillis() + 60_000;
        while (count("select count(*) from audit_log where correlation_id = ?", corr) < 1 && System.currentTimeMillis() < end) Thread.sleep(200);

        String token = TestTokens.admin("audit", admin);
        JsonNode page = JSON.readTree(get("/admin/audit?aggregate_id=" + dealership + "&event_type=DealershipSuspended", token).body());
        assertThat(page.path("entries")).hasSize(1);
        assertThat(page.path("entries").get(0).path("actor").asText()).isEqualTo("service:dealer");
        assertThat(JSON.readTree(get("/admin/audit?actor=service:dealer&aggregate_type=dealership&from=2000-01-01T00:00:00Z&to=2999-01-01T00:00:00Z", token).body())
                .path("entries").size()).isGreaterThanOrEqualTo(1);
        // each read above left a sensitive AuditViewed row with the admin as actor and the filters used
        assertThat(count("select count(*) from audit_log where event_type = 'AuditViewed' and actor = ? and sensitive", "user:" + admin)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select payload->>'aggregate_id' from audit_log where event_type = 'AuditViewed' and actor = ? order by seq limit 1",
                String.class, "user:" + admin)).isEqualTo(dealership.toString());
        assertThat(chain.verify()).isNull();

        // AZ-E163: buyer and dealer refused, no token refused; refused reads leave no row
        assertThat(get("/admin/audit", TestTokens.buyer("audit", UUID.randomUUID())).status()).isEqualTo(403);
        assertThat(get("/admin/audit", TestTokens.dealer("audit", UUID.randomUUID(), UUID.randomUUID())).status()).isEqualTo(403);
        assertThat(get("/admin/audit", null).status()).isEqualTo(401);
        assertThat(count("select count(*) from audit_log where event_type = 'AuditViewed'")).isEqualTo(2);
    }

    @Test
    void openApiMatchesWebClient() { assertOpenApiMatchesWebClient("audit"); }
}
