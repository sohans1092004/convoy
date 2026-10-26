package convoy.dealer;

import convoy.contracts.events.BookingCancelled;
import convoy.contracts.events.BookingDelivered;
import convoy.contracts.events.QuoteWithdrawn;
import convoy.contracts.events.ReliabilityScoreChanged;
import convoy.contracts.events.ReviewPosted;
import convoy.platform.messaging.Envelope;
import convoy.platform.messaging.Outbox;
import convoy.platform.messaging.Subscriptions;
import convoy.platform.messaging.Uuid7;
import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.security.Caller;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reviews and the reliability score (AMENDMENT-009, closes Q-036). Reliability = FINAL commitments honoured through to delivery
 * over the last 90 days: honoured / (honoured + not honoured), always shown with both counts. Not honoured = a booking cancelled
 * by the dealer, not confirmed within 48 h, or out of stock, or a quote withdrawn after FINAL. Buyer cancellations do not count.
 */
@Service
public class Reputation {
    static final int WINDOW_DAYS = 90;
    static final Set<String> DEALER_FAILURES = Set.of("DEALER_CANCELLED", "DEALER_NO_CONFIRM", "STOCK_UNAVAILABLE");
    private final JdbcTemplate jdbc;
    private final Outbox outbox;

    public Reputation(JdbcTemplate jdbc, Outbox outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    @Configuration
    static class Consumes {
        @Bean
        static Subscriptions subscriptions(Reputation r) {
            return new Subscriptions()
                    .on(BookingDelivered.class, (e, p) -> r.delivered(e, p))
                    .on(BookingCancelled.class, (e, p) -> { if (DEALER_FAILURES.contains(p.reason())) r.outcome(e, UUID.fromString(p.dealershipId()), false, "BOOKING_" + p.reason()); })
                    .on(QuoteWithdrawn.class, (e, p) -> { if (Boolean.TRUE.equals(p.afterFinal())) r.outcome(e, UUID.fromString(p.dealershipId()), false, "QUOTE_WITHDRAWN_AFTER_FINAL"); });
        }
    }

    void delivered(Envelope e, BookingDelivered p) {
        jdbc.update("insert into delivered_bookings(booking_id, dealership_id, buyer_id, delivered_at) values (?, ?, ?, ?) on conflict do nothing",
                UUID.fromString(p.bookingId()), UUID.fromString(p.dealershipId()), UUID.fromString(p.buyerId()), Timestamp.from(p.deliveredAt()));
        outcome(e, UUID.fromString(p.dealershipId()), true, "BOOKING_DELIVERED");
    }

    void outcome(Envelope e, UUID dealership, boolean honoured, String cause) {
        if (jdbc.update("insert into reliability_events(source_event_id, dealership_id, honoured, cause) values (?, ?, ?, ?) on conflict do nothing",
                e.eventId(), dealership, honoured, cause) == 0) return; // redelivery
        Map<String, Object> r = reliability(dealership);
        long v = jdbc.queryForObject("update dealerships set version = version + 1 where id = ? returning version", Long.class, dealership);
        outbox.append(dealership, v, new ReliabilityScoreChanged(dealership.toString(), r.get("score_percent") == null ? null : ((Number) r.get("score_percent")).longValue(), cause));
    }

    /** {honoured, total, score_percent (null when total is 0), window_days}; the UI always shows the counts with the percentage. */
    public Map<String, Object> reliability(UUID dealership) {
        Map<String, Object> c = jdbc.queryForMap("select count(*) filter (where honoured) as honoured, count(*) as total from reliability_events "
                + "where dealership_id = ? and occurred_at > clock_timestamp() - make_interval(days => ?)", dealership, WINDOW_DAYS);
        long honoured = ((Number) c.get("honoured")).longValue(), total = ((Number) c.get("total")).longValue();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("honoured", honoured);
        out.put("total", total);
        out.put("score_percent", total == 0 ? null : Math.round(100.0 * honoured / total));
        out.put("window_days", WINDOW_DAYS);
        return out;
    }

    /** E056: only the buyer of a delivered booking from this dealer, once per booking. */
    @Transactional
    public Map<String, Object> review(UUID bookingId, int rating, String body) {
        UUID buyer = Caller.current().require("buyer").userId();
        if (rating < 1 || rating > 5) throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "rating must be 1 to 5.");
        if (body != null && body.length() > 2000) throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "A review can be at most 2,000 characters.");
        List<Map<String, Object>> d = jdbc.queryForList("select dealership_id from delivered_bookings where booking_id = ? and buyer_id = ?", bookingId, buyer);
        if (d.isEmpty()) throw ApiProblem.of(ProblemType.REVIEW_NOT_ELIGIBLE, "Only the buyer of a delivered booking can review its dealer.");
        if (jdbc.queryForObject("select count(*) from reviews where booking_id = ?", Integer.class, bookingId) > 0) {
            throw ApiProblem.of(ProblemType.INVALID_TRANSITION, "You have already reviewed this booking.");
        }
        UUID id = Uuid7.next(), dealership = (UUID) d.get(0).get("dealership_id");
        jdbc.update("insert into reviews(id, booking_id, dealership_id, buyer_id, rating, body) values (?, ?, ?, ?, ?, ?)", id, bookingId, dealership, buyer, rating, body);
        long v = jdbc.queryForObject("update dealerships set version = version + 1 where id = ? returning version", Long.class, dealership);
        outbox.append(dealership, v, new ReviewPosted(dealership.toString(), id.toString(), bookingId.toString(), (long) rating));
        return Map.of("id", id.toString(), "booking_id", bookingId.toString(), "dealership_id", dealership.toString(), "rating", rating, "body", body == null ? "" : body);
    }

    /** E057: public reviews (no buyer identity) with the count and average. */
    public Map<String, Object> reviews(UUID dealership) {
        Map<String, Object> agg = jdbc.queryForMap("select count(*) as count, round(avg(rating)::numeric, 1)::float8 as average from reviews where dealership_id = ?", dealership);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dealership_id", dealership.toString());
        out.put("count", ((Number) agg.get("count")).longValue());
        out.put("average_rating", agg.get("average"));
        out.put("reviews", jdbc.queryForList("select id::text as id, rating, coalesce(body, '') as body, to_char(created_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') as created_at "
                + "from reviews where dealership_id = ? order by created_at desc limit 50", dealership));
        return out;
    }
}
