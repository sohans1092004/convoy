package convoy.order;

import convoy.platform.messaging.Uuid7;
import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.security.Caller;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Dispute evidence (dispute.yaml): image/pdf only, 10 MB each, 10 per dispute, uploaded straight to S3 (LocalStack locally) with a
 * 10-minute pre-signed PUT bound to the declared type and size; read back through pre-signed GETs for the parties only.
 */
@Service
public class Evidence {
    private static final Logger log = LoggerFactory.getLogger(Evidence.class);
    static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "application/pdf");
    static final long MAX_BYTES = 10L * 1024 * 1024;
    static final int MAX_FILES = 10;
    static final Duration URL_TTL = Duration.ofMinutes(10);
    private final JdbcTemplate jdbc;
    private final Disputes disputes;
    private final S3Presigner presigner;
    private final S3Client s3;
    private final String bucket;
    private final int retentionDays, orderDays;

    public Evidence(JdbcTemplate jdbc, Disputes disputes, @Value("${order.evidence.bucket:convoy-dispute-evidence}") String bucket,
                    @Value("${convoy.aws.endpoint:}") String endpoint, @Value("${order.evidence.public-endpoint:}") String publicEndpoint,
                    @Value("${convoy.aws.region:ap-south-1}") String region,
                    org.springframework.beans.factory.ObjectProvider<convoy.platform.privacy.Privacy.Settings> privacy) {
        this.jdbc = jdbc;
        // The disputes retention period (retention.md) is the one in convoy.privacy.retention; files go with their dispute.
        this.retentionDays = days(privacy, "disputes");
        this.orderDays = days(privacy, "orders");
        this.disputes = disputes;
        this.bucket = bucket;
        S3Presigner.Builder p = S3Presigner.builder().region(Region.of(region)).serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        software.amazon.awssdk.services.s3.S3ClientBuilder c = S3Client.builder().region(Region.of(region)).forcePathStyle(true);
        if (!endpoint.isBlank()) {
            c.endpointOverride(URI.create(endpoint)).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
            // LocalStack: sign for the address the browser uses (published port), not the in-network host name.
            p.endpointOverride(URI.create(publicEndpoint.isBlank() ? endpoint : publicEndpoint)).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
            createLocalBucket(endpoint, region, publicEndpoint);
        } else {
            p.credentialsProvider(DefaultCredentialsProvider.builder().build());
            c.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        this.presigner = p.build();
        this.s3 = c.build();
    }

    private static int days(org.springframework.beans.factory.ObjectProvider<convoy.platform.privacy.Privacy.Settings> privacy, String category) {
        return privacy.stream().flatMap(p -> p.retention().stream()).filter(r -> r.category().equals(category)).mapToInt(r -> r.days())
                .findFirst().orElseThrow(() -> new IllegalStateException("convoy.privacy.retention has no '" + category + "' rule"));
    }

    /** Local only: the bucket and a CORS rule for browser uploads. Nothing is created in a real AWS account (zero-cost rule). */
    private void createLocalBucket(String endpoint, String region, String publicEndpoint) {
        try (S3Client s3 = S3Client.builder().region(Region.of(region)).endpointOverride(URI.create(endpoint)).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build()) {
            if (s3.listBuckets().buckets().stream().noneMatch(b -> b.name().equals(bucket))) s3.createBucket(b -> b.bucket(bucket));
            s3.putBucketCors(c -> c.bucket(bucket).corsConfiguration(cc -> cc.corsRules(CORSRule.builder().allowedMethods("PUT", "GET")
                    .allowedOrigins("*").allowedHeaders("*").maxAgeSeconds(600).build())));
        } catch (RuntimeException e) {
            log.warn("local evidence bucket {} not ready ({}); uploads fail until LocalStack S3 is up", bucket, e.toString());
        }
    }

    /** E097: a pre-signed PUT for one file the caller (a party to the dispute) is about to upload. */
    @Transactional
    public Map<String, Object> upload(UUID disputeId, String fileName, String contentType, long sizeBytes) {
        Caller c = Caller.current().require("buyer", "dealer_staff", "dealer_admin");
        Map<String, Object> d = disputes.lock(disputeId);
        String party = disputes.party(d, c);
        if (Set.of("CLOSED", "WITHDRAWN").contains(d.get("state"))) throw ApiProblem.of(ProblemType.INVALID_TRANSITION, "This dispute is closed.");
        if (!TYPES.contains(contentType)) throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "Upload a JPEG, PNG or PDF.");
        if (sizeBytes <= 0 || sizeBytes > MAX_BYTES) throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "Each file can be at most 10 MB.");
        if (fileName == null || fileName.isBlank() || fileName.length() > 200) throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "Give the file a name (up to 200 characters).");
        if (jdbc.queryForObject("select count(*) from dispute_evidence where dispute_id = ?", Integer.class, disputeId) >= MAX_FILES) {
            throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "A dispute can have at most 10 files.");
        }
        UUID id = Uuid7.next();
        String key = "disputes/" + disputeId + "/" + id;
        jdbc.update("insert into dispute_evidence(id, dispute_id, uploader_role, uploader_id, object_key, file_name, content_type, size_bytes) values (?, ?, ?, ?, ?, ?, ?, ?)",
                id, disputeId, party, c.userId(), key, fileName, contentType, sizeBytes);
        disputes.history(disputeId, party, c.userId(), "EVIDENCE_ADDED", (String) d.get("state"), (String) d.get("state"), fileName);
        String url = presigner.presignPutObject(r -> r.signatureDuration(URL_TTL).putObjectRequest(o -> o.bucket(bucket).key(key).contentType(contentType).contentLength(sizeBytes)))
                .url().toString();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("evidence_id", id.toString());
        out.put("upload_url", url);
        out.put("method", "PUT");
        out.put("headers", Map.of("Content-Type", contentType));
        out.put("expires_in_seconds", URL_TTL.toSeconds());
        return out;
    }

    /**
     * Retention (retention.md, disputes: 2 years after resolution, or with their order after 8 years): deletes the S3 files, then
     * their rows; the disputes and orders retention rules remove a dispute only once it has no evidence rows left, so no file
     * outlives its record. A failed
     * S3 delete keeps the row, and the next run tries again. Returns files deleted.
     */
    public int purgeExpired() {
        int n = 0;
        for (Map<String, Object> e : jdbc.queryForList("select e.id, e.object_key from dispute_evidence e join disputes d on d.id = e.dispute_id join orders o on o.id = d.order_id "
                + "where d.resolved_at < clock_timestamp() - make_interval(days => ?) or o.created_at < clock_timestamp() - make_interval(days => ?) limit 500", retentionDays, orderDays)) {
            try {
                s3.deleteObject(r -> r.bucket(bucket).key((String) e.get("object_key"))); // deleting a missing key succeeds (never uploaded)
                jdbc.update("delete from dispute_evidence where id = ?", e.get("id"));
                n++;
            } catch (RuntimeException ex) {
                log.warn("retention: evidence {} not deleted from S3 ({}); retrying next run", e.get("id"), ex.toString());
            }
        }
        if (n > 0) log.info("retention: deleted {} dispute evidence file(s) past {} days", n, retentionDays);
        return n;
    }

    /** Pre-signed GETs for a dispute view (the view itself already checked the caller is a party or an admin). */
    public List<Map<String, Object>> withDownloadUrls(List<Map<String, Object>> evidence) {
        return evidence.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>(e);
            String key = (String) m.remove("object_key");
            m.put("download_url", presigner.presignGetObject(r -> r.signatureDuration(URL_TTL).getObjectRequest(o -> o.bucket(bucket).key(key))).url().toString());
            return m;
        }).toList();
    }
}
