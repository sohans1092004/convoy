package convoy.notification;

import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.security.Caller;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web push (P5.B): per-device subscriptions (E160-E162) and the PUSH channel sender. Pushes carry no payload, only a
 * VAPID-signed (RFC 8292, ES256) wake-up; the service worker then fetches the newest message over the user's session,
 * so no personal or money data passes through the browser vendor's push service. Endpoints are allow-listed (the
 * service POSTs to them, so an arbitrary URL would be an SSRF). 404/410 from the push service = the subscription is gone.
 * ponytail: a user with several devices gets every device re-pushed when one fails transiently; per-subscription rows if it matters.
 */
@RestController
public class Pusher {
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final NotificationApplication.Settings s;
    private final Pattern allowed;
    private final @Nullable PrivateKey key;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public Pusher(JdbcTemplate jdbc, TransactionTemplate tx, NotificationApplication.Settings s, TaskScheduler scheduler) throws Exception {
        this.jdbc = jdbc;
        this.tx = tx;
        this.s = s;
        this.allowed = Pattern.compile(s.pushEndpoints());
        this.key = s.vapidPrivateKey().isBlank() ? null : privateKey(s.vapidPrivateKey());
        if (key != null) scheduler.scheduleWithFixedDelay(this::drain, s.pollEvery());
    }

    /** VAPID private key as the raw 32-byte P-256 scalar, base64url (the format web-push tools print). */
    static PrivateKey privateKey(String d) throws Exception {
        AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
        p.init(new ECGenParameterSpec("secp256r1"));
        return KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, Base64.getUrlDecoder().decode(d)), p.getParameterSpec(ECParameterSpec.class)));
    }

    /** `vapid t=<ES256 JWT for the endpoint's origin>, k=<public key>`, valid 12 h. */
    String authorization(URI endpoint) throws Exception {
        String aud = endpoint.getScheme() + "://" + endpoint.getRawAuthority();
        String header = B64.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = B64.encodeToString(("{\"aud\":\"" + aud + "\",\"exp\":" + Instant.now().plus(Duration.ofHours(12)).getEpochSecond()
                + ",\"sub\":\"" + s.vapidSubject() + "\"}").getBytes(StandardCharsets.UTF_8));
        Signature sig = Signature.getInstance("SHA256withECDSAinP1363Format");
        sig.initSign(key);
        sig.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
        return "vapid t=" + header + "." + claims + "." + B64.encodeToString(sig.sign()) + ", k=" + s.vapidPublicKey();
    }

    void drain() {
        try {
            while (Boolean.TRUE.equals(tx.execute(st -> sendOne()))) {
                // next
            }
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(Pusher.class).warn("pusher pass failed: {}", e.toString());
        }
    }

    private boolean sendOne() {
        List<Map<String, Object>> due = jdbc.queryForList("""
                select d.notification_id, d.attempts, n.user_id from deliveries d join notifications n on n.id = d.notification_id
                where d.status = 'PENDING' and d.channel = 'PUSH' and d.next_attempt_at <= clock_timestamp()
                order by d.next_attempt_at limit 1 for update of d skip locked""");
        if (due.isEmpty()) return false;
        UUID id = (UUID) due.get(0).get("notification_id");
        int attempts = ((Number) due.get(0).get("attempts")).intValue() + 1;
        try {
            convoy.platform.Faults.hit("notification.push");
            int sent = 0;
            for (String endpoint : jdbc.queryForList("select endpoint from push_subscriptions where user_id = ?", String.class, due.get(0).get("user_id"))) {
                URI uri = URI.create(endpoint);
                HttpResponse<Void> r = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("Authorization", authorization(uri))
                        .header("TTL", "86400").header("Urgency", "normal").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
                if (r.statusCode() == 404 || r.statusCode() == 410) jdbc.update("delete from push_subscriptions where endpoint = ?", endpoint);
                else if (r.statusCode() / 100 == 2) sent++;
                else throw new IllegalStateException("push service answered " + r.statusCode());
            }
            jdbc.update("update deliveries set status = ?, attempts = ?, sent_at = clock_timestamp(), last_error = null where notification_id = ? and channel = 'PUSH'",
                    sent > 0 ? "SENT" : "SKIPPED", attempts, id);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Mailer.failed(jdbc, s, id, "PUSH", attempts, e);
        } catch (Exception e) {
            Mailer.failed(jdbc, s, id, "PUSH", attempts, e);
        }
        return true;
    }

    // ---------------------------------------------------------------- API

    public record PushKey(@Nullable String publicKey) {}

    public record PushSubscription(String endpoint, String p256dh, String auth) {}

    public record Removed(int removed) {}

    /** E160: the VAPID public key for PushManager.subscribe; null when push is not configured. */
    @GetMapping("/me/push-subscriptions/key")
    public PushKey key() {
        Caller.current();
        return new PushKey(key == null ? null : s.vapidPublicKey());
    }

    /** E161: opt this device in (an endpoint seen before moves to the caller). */
    @PutMapping("/me/push-subscriptions")
    public PushSubscription subscribe(@RequestBody PushSubscription p) {
        UUID me = Caller.current().userId();
        if (key == null) throw ApiProblem.of(ProblemType.DEPENDENCY_UNAVAILABLE, "Push notifications are not configured.");
        if (p.endpoint() == null || p.endpoint().length() > 1000 || !allowed.matcher(p.endpoint()).matches() || blank(p.p256dh()) || blank(p.auth())) {
            throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "Not a supported push subscription.");
        }
        jdbc.update("insert into push_subscriptions(endpoint, user_id, p256dh, auth) values (?, ?, ?, ?) on conflict (endpoint) do update set "
                + "user_id = excluded.user_id, p256dh = excluded.p256dh, auth = excluded.auth", p.endpoint(), me, p.p256dh(), p.auth());
        return p;
    }

    /** E162: opt this device out (idempotent; only the caller's own subscription). */
    @DeleteMapping("/me/push-subscriptions")
    public Removed unsubscribe(@RequestParam String endpoint) {
        return new Removed(jdbc.update("delete from push_subscriptions where endpoint = ? and user_id = ?", endpoint, Caller.current().userId()));
    }

    private static boolean blank(@Nullable String v) { return v == null || v.isBlank() || v.length() > 200; }
}
