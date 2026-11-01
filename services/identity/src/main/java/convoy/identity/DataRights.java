package convoy.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import convoy.contracts.events.DataExportDownloaded;
import convoy.contracts.events.ErasureRequested;
import convoy.contracts.events.SessionRevoked;
import convoy.contracts.events.UserExportRequested;
import convoy.platform.idempotency.Idempotent;
import convoy.platform.messaging.Outbox;
import convoy.platform.messaging.Uuid7;
import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.security.Caller;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * DPDP data rights (P5.D), coordinated by identity:
 * <ul>
 * <li>consents per purpose with their notice and full history (E168; E014 records changes, withdrawal only where allowed);</li>
 * <li>export (E015 -> UserExportRequested; each service sends its part, E165; one JSON in a private, encrypted S3 bucket;
 *     E164 hands out a 15-minute pre-signed link, each one audited as DataExportDownloaded; files are deleted after 24 h);</li>
 * <li>erasure (E016 -> ErasureRequested; tracked until every service confirms, E017; then identity erases itself, E166 shows progress).</li>
 * </ul>
 * docs/privacy/retention.md lists what each service deletes, what it keeps pseudonymised and for how long.
 */
@RestController
public class DataRights {
    private static final Logger log = LoggerFactory.getLogger(DataRights.class);
    static final Duration LINK_TTL = Duration.ofMinutes(15);
    static final Duration FILE_TTL = Duration.ofHours(24);
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Outbox outbox;
    private final Crypto crypto;
    private final JsonNode notices;
    private final List<String> services;
    private final String bucket;
    private final S3Client s3;
    private final S3Presigner presigner;

    public DataRights(JdbcTemplate jdbc, ObjectMapper json, Outbox outbox, Crypto crypto, TaskScheduler scheduler,
                      @Value("${identity.privacy.services}") List<String> services, @Value("${identity.privacy.export-bucket}") String bucket,
                      @Value("${convoy.aws.endpoint:}") String endpoint, @Value("${identity.privacy.public-endpoint:}") String publicEndpoint,
                      @Value("${convoy.aws.region:ap-south-1}") String region) throws java.io.IOException {
        this.jdbc = jdbc;
        this.json = json;
        this.outbox = outbox;
        this.crypto = crypto;
        this.services = services;
        this.bucket = bucket;
        try (var in = DataRights.class.getResourceAsStream("/privacy-notices.json")) {
            this.notices = json.readTree(in);
        }
        var c = S3Client.builder().region(Region.of(region)).forcePathStyle(true);
        S3Presigner.Builder p = S3Presigner.builder().region(Region.of(region)).serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        if (!endpoint.isBlank()) { // LocalStack (zero-cost rule); the link is signed for the address the browser uses
            var creds = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
            c.endpointOverride(URI.create(endpoint)).credentialsProvider(creds);
            p.endpointOverride(URI.create(publicEndpoint.isBlank() ? endpoint : publicEndpoint)).credentialsProvider(creds);
        } else {
            c.credentialsProvider(DefaultCredentialsProvider.builder().build());
            p.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        this.s3 = c.build();
        this.presigner = p.build();
        if (!endpoint.isBlank()) localBucket();
        scheduler.scheduleWithFixedDelay(this::expireFiles, Instant.now().plusSeconds(30), Duration.ofMinutes(10));
    }

    /** Local only: private bucket (no public access) with default encryption. P6 creates the real one with a 1-day lifecycle rule. */
    private void localBucket() {
        try {
            if (s3.listBuckets().buckets().stream().noneMatch(b -> b.name().equals(bucket))) s3.createBucket(b -> b.bucket(bucket));
            s3.putPublicAccessBlock(b -> b.bucket(bucket).publicAccessBlockConfiguration(x -> x.blockPublicAcls(true).blockPublicPolicy(true)
                    .ignorePublicAcls(true).restrictPublicBuckets(true)));
            s3.putBucketEncryption(b -> b.bucket(bucket).serverSideEncryptionConfiguration(x -> x.rules(r -> r.applyServerSideEncryptionByDefault(
                    d -> d.sseAlgorithm(ServerSideEncryption.AES256)))));
        } catch (RuntimeException e) {
            log.warn("export bucket {} not ready ({}); exports wait until LocalStack S3 is up", bucket, e.toString());
        }
    }

    // ---------------------------------------------------------------- consents

    JsonNode notice(String purpose) {
        for (JsonNode p : notices.path("purposes")) if (p.path("purpose").asText().equals(purpose)) return p;
        throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "Unknown purpose " + purpose + ".");
    }

    String noticeVersion() { return notices.path("version").asText(); }

    /** True when the user's latest choice for the purpose is a withdrawal. */
    boolean withdrawn(UUID user, String purpose) {
        List<Boolean> last = jdbc.queryForList("select granted from consents where user_id = ? and purpose = ? order by at desc, id desc limit 1", Boolean.class, user, purpose);
        return !last.isEmpty() && !last.get(0);
    }

    public record ConsentEntry(boolean granted, String noticeVersion, String at) {}

    public record Purpose(String purpose, String title, String notice, List<String> data, String retention, boolean withdrawable, @Nullable Boolean granted,
                          List<ConsentEntry> history) {}

    public record Consents(String noticeVersion, List<Purpose> purposes) {}

    /** E168: every purpose with its notice, my current choice (null = never asked) and the full history. */
    @GetMapping("/me/consents")
    public Consents consents() {
        UUID me = MeController.human().userId();
        List<Purpose> out = new ArrayList<>();
        for (JsonNode p : notices.path("purposes")) {
            String purpose = p.path("purpose").asText();
            List<ConsentEntry> history = jdbc.query("select granted, notice_version, at from consents where user_id = ? and purpose = ? order by at, id",
                    (rs, i) -> new ConsentEntry(rs.getBoolean("granted"), rs.getString("notice_version"), rs.getTimestamp("at").toInstant().toString()), me, purpose);
            List<String> data = new ArrayList<>();
            p.path("data").forEach(d -> data.add(d.asText()));
            out.add(new Purpose(purpose, p.path("title").asText(), p.path("notice").asText(), data, p.path("retention").asText(), p.path("withdrawable").asBoolean(),
                    history.isEmpty() ? null : history.get(history.size() - 1).granted(), history));
        }
        return new Consents(noticeVersion(), out);
    }

    // ---------------------------------------------------------------- export

    public record ExportStatus(String exportId, String state, int partsReceived, int partsExpected, String requestedAt, @Nullable String downloadUrl,
                               @Nullable String linkExpiresAt, @Nullable String fileDeletedAfter) {}

    /** E015: request one JSON file with everything every service holds about me. */
    @PostMapping("/me/data-export")
    @Idempotent
    @Transactional
    public ExportStatus requestExport() {
        UUID me = MeController.human().userId();
        UUID id = Uuid7.next();
        jdbc.update("insert into data_exports(id, user_id) values (?, ?)", id, me);
        jdbc.update("insert into data_export_parts(export_id, service, data) values (?, 'identity', ?::jsonb)", id, identityPart(me).toString());
        outbox.append(id, 1, new UserExportRequested(id.toString(), me.toString(), Instant.now()));
        return status(id, me, false);
    }

    /** E164: progress, and once ready a 15-minute download link (each link issued is audited). */
    @GetMapping("/me/data-export/{id}")
    @Transactional
    public ExportStatus export(@PathVariable UUID id) { return status(id, MeController.human().userId(), true); }

    private ExportStatus status(UUID id, UUID me, boolean link) {
        List<Map<String, Object>> rows = jdbc.queryForList("select state, requested_at, ready_at, object_key from data_exports where id = ? and user_id = ?", id, me);
        if (rows.isEmpty()) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such export.");
        Map<String, Object> r = rows.get(0);
        int parts = jdbc.queryForObject("select count(*) from data_export_parts where export_id = ?", Integer.class, id);
        String state = (String) r.get("state");
        String url = null, linkExpires = null, deleteAfter = null;
        if (state.equals("READY")) {
            parts = services.size() + 1;
            Instant ready = ((java.sql.Timestamp) r.get("ready_at")).toInstant();
            deleteAfter = ready.plus(FILE_TTL).toString();
            if (link) {
                String key = (String) r.get("object_key");
                url = presigner.presignGetObject(g -> g.signatureDuration(LINK_TTL).getObjectRequest(o -> o.bucket(bucket).key(key))).url().toString();
                Instant expires = Instant.now().plus(LINK_TTL);
                linkExpires = expires.toString();
                outbox.append(id, Instant.now().toEpochMilli(), new DataExportDownloaded(id.toString(), me.toString(), expires));
            }
        }
        return new ExportStatus(id.toString(), state, parts, services.size() + 1, ((java.sql.Timestamp) r.get("requested_at")).toInstant().toString(), url, linkExpires, deleteAfter);
    }

    public record Part(String service, JsonNode data) {}

    public record Accepted(String status) {}

    /** E165 (service:any, scope privacy:write): one service's part; the last one assembles the file. Repeats replace the part. */
    @PostMapping("/internal/exports/{id}/parts")
    @Transactional
    public Accepted part(@PathVariable UUID id, @RequestBody Part p) {
        Caller c = Caller.current().requireScope("privacy:write");
        if (!services.contains(c.subject()) || !c.subject().equals(p.service())) throw ApiProblem.of(ProblemType.FORBIDDEN, "Not a contributing service.");
        List<String> state = jdbc.queryForList("select state from data_exports where id = ? for update", String.class, id);
        if (state.isEmpty()) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such export.");
        if (!state.get(0).equals("PENDING")) return new Accepted(state.get(0));
        jdbc.update("insert into data_export_parts(export_id, service, data) values (?, ?, ?::jsonb) on conflict (export_id, service) do update set data = excluded.data",
                id, p.service(), p.data().toString());
        if (jdbc.queryForObject("select count(*) from data_export_parts where export_id = ?", Integer.class, id) == services.size() + 1) assemble(id);
        return new Accepted("RECEIVED");
    }

    private void assemble(UUID id) {
        ObjectNode file = json.createObjectNode();
        Map<String, Object> e = jdbc.queryForMap("select user_id, requested_at from data_exports where id = ?", id);
        file.put("export_id", id.toString());
        file.put("user_id", e.get("user_id").toString());
        file.put("requested_at", ((java.sql.Timestamp) e.get("requested_at")).toInstant().toString());
        file.put("generated_at", Instant.now().toString());
        ObjectNode byService = file.putObject("services");
        jdbc.query("select service, data::text as data from data_export_parts where export_id = ? order by service", rs -> {
            try {
                byService.set(rs.getString("service"), json.readTree(rs.getString("data")));
            } catch (java.io.IOException ex) {
                throw new IllegalStateException(ex);
            }
        }, id);
        String key = "exports/" + id + ".json";
        s3.putObject(b -> b.bucket(bucket).key(key).contentType("application/json").serverSideEncryption(ServerSideEncryption.AES256),
                software.amazon.awssdk.core.sync.RequestBody.fromString(file.toString()));
        jdbc.update("update data_exports set state = 'READY', ready_at = clock_timestamp(), object_key = ? where id = ?", key, id);
        jdbc.update("delete from data_export_parts where export_id = ?", id);
    }

    /** The 24 h cleanup: files are deleted from S3, the request stays as EXPIRED (no personal data). */
    void expireFiles() {
        try {
            for (Map<String, Object> r : jdbc.queryForList("select id, object_key from data_exports where state = 'READY' and ready_at < clock_timestamp() - make_interval(secs => ?)",
                    (double) FILE_TTL.toSeconds())) {
                s3.deleteObject(b -> b.bucket(bucket).key((String) r.get("object_key")));
                jdbc.update("update data_exports set state = 'EXPIRED', object_key = null where id = ?", r.get("id"));
            }
        } catch (RuntimeException e) {
            log.warn("export cleanup failed: {}", e.toString());
        }
    }

    /** Identity's own part: the account, roles, consents and passkey ids (never key material or session ids). */
    private ObjectNode identityPart(UUID me) {
        ObjectNode out = json.createObjectNode();
        Map<String, Object> u = jdbc.queryForMap("select id, email_enc, locale, status, created_at from users where id = ?", me);
        ObjectNode user = out.putObject("user");
        user.put("id", me.toString());
        user.put("email", crypto.decrypt((byte[]) u.get("email_enc")));
        user.put("locale", (String) u.get("locale"));
        user.put("created_at", ((java.sql.Timestamp) u.get("created_at")).toInstant().toString());
        out.set("roles", json.valueToTree(jdbc.queryForList("select role from user_roles where user_id = ?", String.class, me)));
        out.set("consents", json.valueToTree(jdbc.queryForList("select purpose, granted, notice_version, at::text as at from consents where user_id = ? order by at", me)));
        out.set("passkeys", json.valueToTree(jdbc.queryForList("select id::text as id, created_at::text as created_at from passkeys where user_id = ?", me)));
        out.set("sessions", json.valueToTree(jdbc.queryForList("select created_at::text as created_at, last_seen_at::text as last_seen_at from sessions where user_id = ?", me)));
        return out;
    }

    // ---------------------------------------------------------------- erasure

    public record ErasureStatus(String erasureId, String state, List<String> confirmed, List<String> pending, String requestedAt, @Nullable String completedAt) {}

    /** E016: delete my account. Every service deletes or pseudonymises its part; I stay signed in until the last one confirms. */
    @PostMapping("/me/erasure")
    @Idempotent
    @Transactional
    public ErasureStatus requestErasure() {
        UUID me = MeController.human().userId();
        return erasure(startErasure(me), me);
    }

    /** E016's request, also used by the demo cleanup (P6.B) for expired scenario accounts. Call inside a transaction. */
    UUID startErasure(UUID user) {
        List<UUID> open = jdbc.queryForList("select id from erasures where user_id = ? and state = 'PENDING'", UUID.class, user);
        if (!open.isEmpty()) return open.get(0);
        UUID id = Uuid7.next();
        jdbc.update("insert into erasures(id, user_id) values (?, ?)", id, user);
        outbox.append(user, Instant.now().toEpochMilli(), new ErasureRequested(id.toString(), user.toString(), Instant.now()));
        return id;
    }

    /** E166: which services have confirmed. */
    @GetMapping("/me/erasure/{id}")
    public ErasureStatus erasureStatus(@PathVariable UUID id) { return erasure(id, MeController.human().userId()); }

    ErasureStatus erasure(UUID id, UUID me) {
        List<Map<String, Object>> rows = jdbc.queryForList("select state, requested_at, completed_at from erasures where id = ? and user_id = ?", id, me);
        if (rows.isEmpty()) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such request.");
        List<String> done = jdbc.queryForList("select service from erasure_confirmations where erasure_id = ? order by service", String.class, id);
        List<String> pending = services.stream().filter(s -> !done.contains(s)).toList();
        java.sql.Timestamp completed = (java.sql.Timestamp) rows.get(0).get("completed_at");
        return new ErasureStatus(id.toString(), (String) rows.get(0).get("state"), done, pending,
                ((java.sql.Timestamp) rows.get(0).get("requested_at")).toInstant().toString(), completed == null ? null : completed.toInstant().toString());
    }

    public record Confirm(String service) {}

    /** E017 (service:any, scope privacy:write): a service has erased its part; the last confirmation completes the erasure here. */
    @PostMapping("/internal/erasure/{id}/confirm")
    @Transactional
    public Accepted confirm(@PathVariable UUID id, @RequestBody Confirm body) {
        Caller c = Caller.current().requireScope("privacy:write");
        if (!services.contains(c.subject()) || !c.subject().equals(body.service())) throw ApiProblem.of(ProblemType.FORBIDDEN, "Not a confirming service.");
        List<Map<String, Object>> rows = jdbc.queryForList("select user_id, state from erasures where id = ? for update", id);
        if (rows.isEmpty()) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such erasure.");
        if (rows.get(0).get("state").equals("COMPLETED")) return new Accepted("COMPLETED");
        jdbc.update("insert into erasure_confirmations(erasure_id, service) values (?, ?) on conflict do nothing", id, body.service());
        if (jdbc.queryForObject("select count(*) from erasure_confirmations where erasure_id = ?", Integer.class, id) < services.size()) return new Accepted("RECEIVED");
        eraseIdentity((UUID) rows.get(0).get("user_id"));
        jdbc.update("update erasures set state = 'COMPLETED', completed_at = clock_timestamp() where id = ?", id);
        return new Accepted("COMPLETED");
    }

    /**
     * Last step: the users row stays as a tombstone (id only: other services' kept rows hold pseudonyms, not this id),
     * everything else about the person goes. Sessions end now (SessionRevoked, reason ERASURE).
     */
    private void eraseIdentity(UUID user) {
        long at = Instant.now().toEpochMilli();
        List<String> sessions = jdbc.queryForList("delete from sessions where user_id = ? returning id_hash", String.class, user);
        for (int i = 0; i < sessions.size(); i++) outbox.append(user, at + i, new SessionRevoked(user.toString(), sessions.get(i), "ERASURE"));
        String emailHash = jdbc.queryForObject("select email_hash from users where id = ?", String.class, user);
        jdbc.update("delete from otp_challenges where email_hash = ?", emailHash);
        jdbc.update("delete from passkey_challenges where user_id = ?", user);
        jdbc.update("delete from passkeys where user_id = ?", user);
        jdbc.update("delete from user_roles where user_id = ?", user);
        jdbc.update("delete from dealership_staff where user_id = ?", user);
        jdbc.update("delete from consents where user_id = ?", user);
        jdbc.update("delete from data_export_parts where export_id in (select id from data_exports where user_id = ?)", user);
        for (String key : jdbc.queryForList("select object_key from data_exports where user_id = ? and object_key is not null", String.class, user)) {
            s3.deleteObject(b -> b.bucket(bucket).key(key));
        }
        jdbc.update("update data_exports set state = 'EXPIRED', object_key = null where user_id = ?", user);
        jdbc.update("update users set email_hash = ?, email_enc = ?, status = 'ERASED' where id = ?", "erased:" + user, new byte[0], user);
        log.info("erasure complete for a user: identity data removed, {} session(s) ended", sessions.size());
    }

    /** For E157: the dealer no longer gets the contact once the buyer withdrew DEALER_DISCLOSURE. */
    boolean dealerDisclosureWithdrawn(UUID user) { return withdrawn(user, "DEALER_DISCLOSURE"); }
}
