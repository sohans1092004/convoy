package convoy.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.platform.Faults;
import convoy.platform.messaging.InboxConsumer;
import convoy.platform.messaging.Uuid7;
import convoy.platform.testing.ServiceTest;
import convoy.platform.testing.TestTokens;
import convoy.testing.StubServer;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * P5.A: events injected straight into notification's standard queues, in reverse order and twice each (a backlog replay
 * with duplicates). Each event must give exactly one in-app message and one email per recipient; preferences, the
 * always-sent payment/refund messages, email retry -> DEAD, and the inbox authorisation rows AZ-E130/E131/E132.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "convoy.messaging.enabled=true", "convoy.messaging.wait-seconds=1",
        "notification.base-backoff=100ms", "notification.max-attempts=3", "notification.poll-every=200ms",
        "convoy.issuers.convoy-gateway=http://unused", "convoy.issuers.convoy-identity=http://unused"})
@Import({ServiceTest.Config.class, NotificationIT.Stubs.class})
class NotificationIT extends ServiceTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final LocalStackContainer LS = new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14.0"))
            .withServices(LocalStackContainer.Service.SNS, LocalStackContainer.Service.SQS);
    /** AMENDMENT-011: email over SMTP; Mailpit is the sink and its API counts what arrived. */
    static final org.testcontainers.containers.GenericContainer<?> MAILPIT = new org.testcontainers.containers.GenericContainer<>("axllent/mailpit:v1.32.0")
            .withExposedPorts(1025, 8025);
    static final StubServer IDENTITY = new StubServer().on("/internal/users/.*/contact", r -> {
        String id = r.path().split("/")[3];
        return StubServer.Reply.json("{\"user_id\":\"" + id + "\",\"email\":\"" + id + "@convoy.test\"}");
    });

    /** P5.B: a local push service; records what it receives. /push/gone answers 410 (subscription expired). */
    static final List<StubServer.Req> PUSHED = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final StubServer PUSH = new StubServer()
            .on("/push/gone", r -> new StubServer.Reply(410, "{}", 0))
            .on("/push/.*", r -> {
                PUSHED.add(r);
                return new StubServer.Reply(201, "{}", 0);
            });
    static final java.security.KeyPair VAPID;

    static {
        LS.start();
        MAILPIT.start();
        try {
            java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("EC");
            g.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
            VAPID = g.generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] b32(java.math.BigInteger v) {
        byte[] raw = v.toByteArray(), out = new byte[32];
        System.arraycopy(raw, Math.max(0, raw.length - 32), out, Math.max(0, 32 - raw.length), Math.min(32, raw.length));
        return out;
    }

    static String vapidPublic() {
        java.security.spec.ECPoint w = ((java.security.interfaces.ECPublicKey) VAPID.getPublic()).getW();
        byte[] out = new byte[65];
        out[0] = 4;
        System.arraycopy(b32(w.getAffineX()), 0, out, 1, 32);
        System.arraycopy(b32(w.getAffineY()), 0, out, 33, 32);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(out);
    }

    @DynamicPropertySource
    static void aws(DynamicPropertyRegistry r) {
        r.add("convoy.aws.endpoint", () -> LS.getEndpoint().toString());
        r.add("convoy.aws.region", LS::getRegion);
        r.add("spring.mail.host", MAILPIT::getHost);
        r.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
        r.add("notification.vapid-public-key", NotificationIT::vapidPublic);
        r.add("notification.vapid-private-key", () -> java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(b32(((java.security.interfaces.ECPrivateKey) VAPID.getPrivate()).getS())));
        r.add("notification.push-endpoints", () -> java.util.regex.Pattern.quote(PUSH.url()) + "/push/.+");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Stubs {
        @Bean
        @Primary
        RestClient testIdentityClient(RestClient.Builder b) { return b.clone().baseUrl(IDENTITY.url()).build(); }
    }

    @AfterAll
    static void stop() {
        IDENTITY.close();
        PUSH.close();
        LS.stop();
        MAILPIT.stop();
    }

    @Autowired InboxConsumer consumer;
    @Autowired SqsClient sqs;
    @Autowired JdbcTemplate jdbc;

    record Ev(String type, String producer, UUID aggregate, long version, Map<String, Object> payload, UUID id) {
        Ev(String type, String producer, UUID aggregate, long version, Map<String, Object> payload) { this(type, producer, aggregate, version, payload, Uuid7.next()); }
    }

    void inject(List<Ev> events) throws Exception {
        for (Ev e : events) {
            Map<String, Object> env = new LinkedHashMap<>();
            env.put("event_id", e.id().toString());
            env.put("event_type", e.type());
            env.put("schema_version", 1);
            env.put("aggregate_id", e.aggregate().toString());
            env.put("aggregate_version", e.version());
            env.put("occurred_at", Instant.now().toString());
            env.put("correlation_id", UUID.randomUUID().toString());
            env.put("causation_id", null);
            env.put("producer", e.producer());
            env.put("payload", e.payload());
            String queue = sqs.getQueueUrl(b -> b.queueName("convoy-notification--" + e.producer() + "-events")).queueUrl();
            String body = JSON.writeValueAsString(env);
            sqs.sendMessage(b -> b.queueUrl(queue).messageBody(body));
        }
    }

    static void await(String what, BooleanSupplier ok) throws InterruptedException {
        long end = System.currentTimeMillis() + 60_000;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > end) throw new AssertionError("timed out: " + what);
            Thread.sleep(200);
        }
    }

    long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }

    int mailsTo(UUID user) throws Exception {
        URI search = URI.create("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + "/api/v1/search?query=to:" + user + "@convoy.test&limit=1");
        return JSON.readTree(http.send(HttpRequest.newBuilder(search).GET().build(), HttpResponse.BodyHandlers.ofString()).body()).path("messages_count").asInt();
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
        return out;
    }

    @Test
    void reversedBacklogWithDuplicatesGivesExactlyOneMessagePerEventAndChannel() throws Exception {
        UUID buyer = UUID.randomUUID(), late = UUID.randomUUID(), leaver = UUID.randomUUID();
        UUID pool = UUID.randomUUID(), order = UUID.randomUUID(), quote = UUID.randomUUID(), booking = UUID.randomUUID(), dispute = UUID.randomUUID(), payment = UUID.randomUUID();
        List<Ev> story = List.of(
                new Ev("MemberCommitted", "pool", pool, 3, m("pool_id", pool, "membership_id", UUID.randomUUID(), "buyer_id", buyer, "payment_id", payment,
                        "interested_count", 1, "committed_count", 1, "kept_from_previous_round", false)),
                new Ev("MemberCommitted", "pool", pool, 4, m("pool_id", pool, "membership_id", UUID.randomUUID(), "buyer_id", leaver, "payment_id", UUID.randomUUID(),
                        "interested_count", 2, "committed_count", 2, "kept_from_previous_round", false)),
                new Ev("MemberLeft", "pool", pool, 5, m("pool_id", pool, "membership_id", UUID.randomUUID(), "buyer_id", leaver, "payment_id", null, "reason", "BUYER_LEFT",
                        "interested_count", 1, "committed_count", 1)),
                new Ev("PaymentCaptured", "payment", payment, 2, m("payment_id", payment, "buyer_id", buyer, "pool_id", pool, "amount_paise", 99900, "late", false, "test_mode", true)),
                new Ev("PoolReady", "pool", pool, 6, m("pool_id", pool, "committed_count", 1)),
                new Ev("MemberCommitted", "pool", pool, 7, m("pool_id", pool, "membership_id", UUID.randomUUID(), "buyer_id", late, "payment_id", UUID.randomUUID(),
                        "interested_count", 2, "committed_count", 2, "kept_from_previous_round", false)),
                new Ev("DecisionOpened", "pool", pool, 9, m("pool_id", pool, "decision_deadline", "2026-10-27T10:00:00Z", "final_quote_ids", List.of(quote))),
                new Ev("OrderCreated", "order", order, 1, m("order_id", order, "pool_id", pool, "buyer_id", buyer, "quote_id", quote, "variant_id", UUID.randomUUID(),
                        "ceiling_cash_payable_paise", 123456789, "ceiling_snapshot_sha256", "x", "confirmation_deadline", "2026-10-27T12:00:00Z")),
                new Ev("TierResolved", "order", UUID.randomUUID(), 1, m("pool_id", pool, "quote_id", quote, "confirmed_count", 1, "min_fulfilment", 1, "outcome", "RESOLVED",
                        "resolved_tier", 0, "resolved_cash_payable_paise", 120000000)),
                new Ev("BookingIssued", "order", booking, 1, m("booking_id", booking, "order_id", order, "pool_id", pool, "dealership_id", UUID.randomUUID(),
                        "variant_id", UUID.randomUUID(), "final_cash_payable_paise", 120000000, "promised_delivery_date", null, "dealer_confirm_deadline", "2026-10-29T12:00:00Z")),
                new Ev("BookingConfirmedByDealer", "order", booking, 2, m("booking_id", booking, "dealership_id", UUID.randomUUID())),
                new Ev("DeliveryScheduled", "order", booking, 3, m("booking_id", booking, "scheduled_date", "2026-11-05", "later_than_promised", false)),
                new Ev("DisputeOpened", "order", dispute, 1, m("dispute_id", dispute, "order_id", order, "booking_id", booking, "dealership_id", UUID.randomUUID(),
                        "category", "DELIVERY_DELAY", "opened_by", "BUYER")),
                new Ev("DisputeResolved", "order", dispute, 3, m("dispute_id", dispute, "outcome", "NO_ACTION", "resolved_by", UUID.randomUUID())),
                new Ev("RefundCompleted", "payment", payment, 5, m("refund_id", UUID.randomUUID(), "payment_id", payment, "buyer_id", buyer, "manual", false)));
        List<Ev> backlog = new ArrayList<>(story.reversed());
        backlog.addAll(story); // every event delivered twice
        inject(backlog);

        int messages = 10; // everything above except the five membership / booking links
        await("all applied and mailed", () -> count("select count(*) from deliveries d join notifications n on n.id = d.notification_id where n.user_id = ? and d.status = 'SENT'", buyer) == messages);
        assertThat(count("select count(*) from notifications where user_id = ?", buyer)).isEqualTo(messages);
        assertThat(count("select count(*) from (select event_id from notifications group by user_id, event_id having count(*) > 1) x")).isZero();
        assertThat(jdbc.queryForList("select kind from notifications where user_id = ? order by kind", String.class, buyer)).containsExactly(
                "BOOKING_CONFIRMED", "COMPARISON_READY", "CONFIRMATION_NEEDED", "DELIVERY_SCHEDULED", "DEPOSIT_RECEIVED", "DISPUTE_OPENED", "DISPUTE_RESOLVED",
                "POOL_READY", "REFUND_DONE", "TIER_RESOLVED");
        // late joined after PoolReady (v6 < v7): only the decision; the leaver left before both: nothing
        assertThat(jdbc.queryForList("select kind from notifications where user_id = ?", String.class, late)).containsExactly("COMPARISON_READY");
        assertThat(count("select count(*) from notifications where user_id = ?", leaver)).isZero();
        Thread.sleep(1500); // a late duplicate would show up here
        assertThat(mailsTo(buyer)).isEqualTo(messages);

        String token = TestTokens.buyer("notification", buyer);
        JsonNode inbox = JSON.readTree(get("/me/notifications", token).body());
        assertThat(inbox.path("unread_count").asLong()).isEqualTo(messages);
        JsonNode confirm = null;
        for (JsonNode n : inbox.path("notifications")) if (n.path("kind").asText().equals("CONFIRMATION_NEEDED")) confirm = n;
        assertThat(confirm.path("body").asText()).contains("₹12,34,567.89").contains("27 Oct 2026, 17:30 IST");
        assertThat(confirm.path("link").asText()).isEqualTo("/orders/" + order);
        JsonNode hi = JSON.readTree(get("/me/notifications?locale=hi", token).body()).path("notifications").get(0);
        assertThat(hi.path("draft").asBoolean()).isTrue();

        // AZ-E131: read is idempotent; someone else's id is not found; AZ-E130-D: no token
        String id = confirm.path("id").asText();
        assertThat(send("POST", "/me/notifications/" + id + "/read", token, null, Map.of()).status()).isEqualTo(200);
        assertThat(send("POST", "/me/notifications/" + id + "/read", token, null, Map.of()).status()).isEqualTo(200);
        assertThat(send("POST", "/me/notifications/" + id + "/read", TestTokens.buyer("notification", late), null, Map.of()).status()).isEqualTo(404);
        assertThat(get("/me/notifications", null).status()).isEqualTo(401);
        assertThat(JSON.readTree(get("/me/notifications", token).body()).path("unread_count").asLong()).isEqualTo(messages - 1);
    }

    @Test
    void clarificationQuestionReachesTheDealerAndTheAnswerTheCommittedBuyersOnce() throws Exception {
        UUID dealer = UUID.randomUUID(), asker = UUID.randomUUID(), other = UUID.randomUUID(), leaver = UUID.randomUUID();
        UUID pool = UUID.randomUUID(), quote = UUID.randomUUID(), clar = UUID.randomUUID();
        List<Ev> story = List.of(
                new Ev("MemberCommitted", "pool", pool, 1, m("pool_id", pool, "membership_id", UUID.randomUUID(), "buyer_id", asker, "payment_id", UUID.randomUUID(),
                        "interested_count", 1, "committed_count", 1, "kept_from_previous_round", false)),
                new Ev("MemberCommitted", "pool", pool, 2, m("pool_id", pool, "membership_id", UUID.randomUUID(), "buyer_id", other, "payment_id", UUID.randomUUID(),
                        "interested_count", 2, "committed_count", 2, "kept_from_previous_round", false)),
                new Ev("MemberCommitted", "pool", pool, 3, m("pool_id", pool, "membership_id", UUID.randomUUID(), "buyer_id", leaver, "payment_id", UUID.randomUUID(),
                        "interested_count", 3, "committed_count", 3, "kept_from_previous_round", false)),
                new Ev("MemberLeft", "pool", pool, 4, m("pool_id", pool, "membership_id", UUID.randomUUID(), "buyer_id", leaver, "payment_id", null, "reason", "BUYER_LEFT",
                        "interested_count", 2, "committed_count", 2)),
                new Ev("ClarificationAsked", "quote", clar, 1, m("quote_id", quote, "pool_id", pool, "clarification_id", clar, "question_type", "DELIVERY_DATE", "dealer_user_id", dealer)),
                // version 1 of the clarification stream: lower than every membership version, delivered anyway
                new Ev("ClarificationAnswered", "quote", UUID.randomUUID(), 1, m("quote_id", quote, "pool_id", pool, "clarification_id", clar, "question_type", "DELIVERY_DATE")));
        List<Ev> backlog = new ArrayList<>(story.reversed());
        backlog.addAll(story);
        inject(backlog);

        for (UUID u : List.of(dealer, asker, other)) {
            await("mailed " + u, () -> count("select count(*) from deliveries d join notifications n on n.id = d.notification_id where n.user_id = ? and d.status = 'SENT'", u) == 1);
        }
        assertThat(jdbc.queryForList("select kind from notifications where user_id = ?", String.class, dealer)).containsExactly("CLARIFICATION_ASKED");
        assertThat(jdbc.queryForList("select kind from notifications where user_id = ?", String.class, asker)).containsExactly("CLARIFICATION_ANSWERED");
        assertThat(jdbc.queryForList("select kind from notifications where user_id = ?", String.class, other)).containsExactly("CLARIFICATION_ANSWERED");
        assertThat(count("select count(*) from notifications where user_id = ?", leaver)).isZero();
        Thread.sleep(1500);
        assertThat(mailsTo(dealer)).isEqualTo(1);
        assertThat(mailsTo(asker)).isEqualTo(1);
        JsonNode n = JSON.readTree(get("/me/notifications", TestTokens.buyer("notification", dealer)).body()).path("notifications").get(0);
        assertThat(n.path("link").asText()).isEqualTo("/dealer/quotes/" + quote);
        assertThat(n.path("body").asText()).contains("DELIVERY_DATE");
    }

    @Test
    void preferencesAreHonouredExceptForPaymentAndRefundAndFailedEmailsEndDead() throws Exception {
        UUID buyer = UUID.randomUUID(), pool = UUID.randomUUID(), payment = UUID.randomUUID();
        String token = TestTokens.buyer("notification", buyer);
        assertThat(send("PUT", "/me/notification-preferences", token, "{\"in_app\":false,\"email\":false}", Map.of()).body()).contains("\"email\":false");

        inject(List.of(
                new Ev("MemberCommitted", "pool", pool, 1, m("pool_id", pool, "membership_id", UUID.randomUUID(), "buyer_id", buyer, "payment_id", payment,
                        "interested_count", 1, "committed_count", 1, "kept_from_previous_round", false)),
                new Ev("PoolReady", "pool", pool, 2, m("pool_id", pool, "committed_count", 1)),
                new Ev("RefundCompleted", "payment", payment, 3, m("refund_id", UUID.randomUUID(), "payment_id", payment, "buyer_id", buyer, "manual", false))));
        await("refund mailed", () -> count("select count(*) from deliveries d join notifications n on n.id = d.notification_id where n.user_id = ? and n.kind = 'REFUND_DONE' and d.status = 'SENT'", buyer) == 1);
        await("pool event skipped by preference", () -> count("select count(*) from deliveries d join notifications n on n.id = d.notification_id where n.user_id = ? and n.kind = 'POOL_READY' and d.status = 'SKIPPED'", buyer) == 1);
        JsonNode inbox = JSON.readTree(get("/me/notifications", token).body());
        assertThat(inbox.path("notifications")).hasSize(1);
        assertThat(inbox.path("notifications").get(0).path("kind").asText()).isEqualTo("REFUND_DONE");

        // SMTP failing: 3 attempts with backoff, then DEAD (the email channel's dead letter)
        Faults.armUntilDisarmed("notification.send");
        try {
            UUID other = UUID.randomUUID();
            inject(List.of(new Ev("RefundCompleted", "payment", UUID.randomUUID(), 3, m("refund_id", UUID.randomUUID(), "payment_id", UUID.randomUUID(), "buyer_id", other, "manual", true))));
            await("dead", () -> count("select count(*) from deliveries d join notifications n on n.id = d.notification_id where n.user_id = ? and d.status = 'DEAD' and d.attempts = 3", other) == 1);
        } finally {
            Faults.disarm("notification.send");
        }
    }

    /** P5.B: opted-in devices get a VAPID-signed, payload-free push per message; expired ones are dropped; AZ-E160..E162. */
    @Test
    void pushGoesToOptedInDevicesOnlySignedWithVapid() throws Exception {
        UUID buyer = UUID.randomUUID(), other = UUID.randomUUID(), pool = UUID.randomUUID();
        String token = TestTokens.buyer("notification", buyer), otherToken = TestTokens.buyer("notification", other);
        String ok = PUSH.url() + "/push/" + buyer, gone = PUSH.url() + "/push/gone";
        assertThat(JSON.readTree(get("/me/push-subscriptions/key", token).body()).path("public_key").asText()).isEqualTo(vapidPublic());
        assertThat(get("/me/push-subscriptions/key", null).status()).isEqualTo(401);
        for (String e : List.of(ok, gone)) {
            assertThat(send("PUT", "/me/push-subscriptions", token, "{\"endpoint\":\"" + e + "\",\"p256dh\":\"k\",\"auth\":\"a\"}", Map.of()).status()).isEqualTo(200);
        }
        // only allow-listed push services (the service POSTs there: anything else would be an SSRF)
        assertThat(send("PUT", "/me/push-subscriptions", token, "{\"endpoint\":\"http://169.254.169.254/latest\",\"p256dh\":\"k\",\"auth\":\"a\"}", Map.of()).status()).isEqualTo(400);
        assertThat(send("PUT", "/me/push-subscriptions", null, "{\"endpoint\":\"" + ok + "\",\"p256dh\":\"k\",\"auth\":\"a\"}", Map.of()).status()).isEqualTo(401);

        inject(List.of(
                new Ev("MemberCommitted", "pool", pool, 1, m("pool_id", pool, "membership_id", UUID.randomUUID(), "buyer_id", buyer, "payment_id", UUID.randomUUID(),
                        "interested_count", 2, "committed_count", 2, "kept_from_previous_round", false)),
                new Ev("MemberCommitted", "pool", pool, 2, m("pool_id", pool, "membership_id", UUID.randomUUID(), "buyer_id", other, "payment_id", UUID.randomUUID(),
                        "interested_count", 2, "committed_count", 2, "kept_from_previous_round", false)),
                new Ev("PoolReady", "pool", pool, 3, m("pool_id", pool, "committed_count", 2))));
        await("pushed", () -> count("select count(*) from deliveries d join notifications n on n.id = d.notification_id where n.user_id = ? and d.channel = 'PUSH' and d.status = 'SENT'", buyer) == 1);
        assertThat(count("select count(*) from deliveries d join notifications n on n.id = d.notification_id where n.user_id = ? and d.channel = 'PUSH'", other)).isZero();
        assertThat(count("select count(*) from push_subscriptions where endpoint = ?", gone)).isZero();

        List<StubServer.Req> mine = PUSHED.stream().filter(r -> r.path().equals("/push/" + buyer)).toList();
        assertThat(mine).hasSize(1);
        StubServer.Req r = mine.get(0);
        assertThat(r.body()).isEmpty(); // no personal or money data through the push service
        assertThat(r.headers().get("TTL")).containsExactly("86400");
        String auth = r.headers().get("Authorization").get(0);
        assertThat(auth).startsWith("vapid t=").endsWith(", k=" + vapidPublic());
        String[] jwt = auth.substring("vapid t=".length(), auth.indexOf(',')).split("[.]");
        JsonNode claims = JSON.readTree(java.util.Base64.getUrlDecoder().decode(jwt[1]));
        assertThat(claims.path("aud").asText()).isEqualTo(PUSH.url());
        assertThat(claims.path("sub").asText()).startsWith("mailto:");
        java.security.Signature sig = java.security.Signature.getInstance("SHA256withECDSAinP1363Format");
        sig.initVerify(VAPID.getPublic());
        sig.update((jwt[0] + "." + jwt[1]).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThat(sig.verify(java.util.Base64.getUrlDecoder().decode(jwt[2]))).isTrue();

        // opt out: only the caller's own subscription, idempotent
        String q = "/me/push-subscriptions?endpoint=" + java.net.URLEncoder.encode(ok, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(send("DELETE", q, otherToken, null, Map.of()).body()).contains("\"removed\":0");
        assertThat(send("DELETE", q, token, null, Map.of()).body()).contains("\"removed\":1");
        assertThat(send("DELETE", q, token, null, Map.of()).body()).contains("\"removed\":0");
    }

    @Test
    void openApiMatchesWebClient() { assertOpenApiMatchesWebClient("notification"); }
}
