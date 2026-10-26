package convoy.dealer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.contracts.events.BookingCancelled;
import convoy.contracts.events.BookingDelivered;
import convoy.contracts.events.QuoteWithdrawn;
import convoy.platform.messaging.Envelope;
import convoy.platform.messaging.EventContext;
import convoy.platform.messaging.Uuid7;
import convoy.platform.testing.ServiceTest;
import convoy.platform.testing.TestTokens;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** P4.E: reviews only after delivery, once per booking; the reliability score with its counts (AMENDMENT-009). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "convoy.messaging.enabled=false", "convoy.issuers.convoy-gateway=http://unused", "convoy.issuers.convoy-identity=http://unused",
        "dealer.demo=true", "dealer.demo-seed-file=../../deploy/local/seed/dealerships.sql"})
@Import(ServiceTest.Config.class)
class ReputationIT extends ServiceTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final UUID D3 = UUID.fromString("0190f3a0-0000-7000-8000-00000000d003"), D4 = UUID.fromString("0190f3a0-0000-7000-8000-00000000d004");

    @Autowired Reputation reputation;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    Envelope env(Envelope reuse) {
        return reuse != null ? reuse : new Envelope(Uuid7.next(), "x", 1, UUID.randomUUID(), 1, "2026-10-26T00:00:00Z", Uuid7.next(), null, "order", null);
    }

    void consume(Envelope e, Runnable r) {
        EventContext.set(e.correlationId(), e.eventId());
        try {
            tx.executeWithoutResult(s -> r.run());
        } finally {
            EventContext.clear();
        }
    }

    UUID deliver(UUID dealership, UUID buyer) {
        UUID booking = UUID.randomUUID();
        Envelope e = env(null);
        consume(e, () -> reputation.delivered(e, new BookingDelivered(booking.toString(), UUID.randomUUID().toString(), dealership.toString(), buyer.toString(), Instant.now())));
        return booking;
    }

    Resp review(UUID booking, UUID buyer, String body) {
        return send("POST", "/bookings/" + booking + "/review", TestTokens.buyer("dealer", buyer), body, Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    }

    @Test
    void onlyTheBuyerOfADeliveredBookingReviewsAndOnlyOnce() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID booking = deliver(D4, buyer);
        assertThat(JSON.readTree(review(UUID.randomUUID(), buyer, "{\"rating\":5}").body()).path("code").asText()).isEqualTo("REVIEW_NOT_ELIGIBLE"); // not delivered
        assertThat(JSON.readTree(review(booking, UUID.randomUUID(), "{\"rating\":5}").body()).path("code").asText()).isEqualTo("REVIEW_NOT_ELIGIBLE"); // someone else's
        assertThat(review(booking, buyer, "{\"rating\":0}").status()).isEqualTo(400);
        Resp ok = review(booking, buyer, "{\"rating\":4,\"body\":\"Delivered on the promised day.\"}");
        assertThat(ok.status()).as(ok.body()).isEqualTo(200);
        assertThat(JSON.readTree(review(booking, buyer, "{\"rating\":1}").body()).path("code").asText()).isEqualTo("INVALID_TRANSITION"); // once per booking
        Resp pub = get("/dealerships/" + D4 + "/reviews", TestTokens.anonymous("dealer"));
        assertThat(pub.status()).isEqualTo(200);
        assertThat(pub.body()).contains("Delivered on the promised day.").doesNotContain(buyer.toString()); // no buyer identity in public reviews
        assertThat(jdbc.queryForObject("select count(*) from outbox where event_type = 'ReviewPosted' and aggregate_id = ?", Integer.class, D4)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void reliabilityCountsHonouredFinalCommitmentsOverNinetyDaysAndAlwaysCarriesItsCounts() throws Exception {
        JsonNode before = JSON.readTree(get("/dealerships/" + D3, TestTokens.anonymous("dealer")).body()).path("reliability");
        assertThat(before.isMissingNode()).isFalse();
        long h0 = before.path("honoured").asLong(), t0 = before.path("total").asLong();
        for (int i = 0; i < 3; i++) deliver(D3, UUID.randomUUID());
        Envelope cancelled = env(null);
        consume(cancelled, () -> reputation.outcome(cancelled, D3, false, "BOOKING_DEALER_CANCELLED"));
        consume(cancelled, () -> reputation.outcome(cancelled, D3, false, "BOOKING_DEALER_CANCELLED")); // redelivery: counted once
        // buyer cancellations do not count; a FINAL quote withdrawn does (through the real subscription handlers' filters)
        assertThat(Reputation.DEALER_FAILURES).doesNotContain("BUYER_CANCELLED").contains("DEALER_CANCELLED", "DEALER_NO_CONFIRM", "STOCK_UNAVAILABLE");
        Envelope withdrawn = env(null);
        consume(withdrawn, () -> reputation.outcome(withdrawn, D3, false, "QUOTE_WITHDRAWN_AFTER_FINAL"));
        // an outcome older than 90 days is outside the window
        jdbc.update("insert into reliability_events(source_event_id, dealership_id, honoured, cause, occurred_at) values (?, ?, false, 'OLD', clock_timestamp() - interval '91 days')", Uuid7.next(), D3);
        JsonNode r = JSON.readTree(get("/dealerships/" + D3, TestTokens.anonymous("dealer")).body()).path("reliability");
        assertThat(r.path("honoured").asLong()).isEqualTo(h0 + 3);
        assertThat(r.path("total").asLong()).isEqualTo(t0 + 5);
        assertThat(r.path("window_days").asInt()).isEqualTo(90);
        assertThat(r.path("score_percent").asLong()).isEqualTo(Math.round(100.0 * (h0 + 3) / (t0 + 5)));
        assertThat(jdbc.queryForObject("select count(*) from outbox where event_type = 'ReliabilityScoreChanged' and aggregate_id = ?", Integer.class, D3)).isGreaterThanOrEqualTo(5);
        // the handlers ignore what is not a dealer failure
        assertThat(new BookingCancelled("b", "o", D3.toString(), "BUYER_CANCELLED").reason()).isNotIn(Reputation.DEALER_FAILURES);
        assertThat(new QuoteWithdrawn("q", "p", D3.toString(), false, "before deadline").afterFinal()).isFalse();
    }
}
