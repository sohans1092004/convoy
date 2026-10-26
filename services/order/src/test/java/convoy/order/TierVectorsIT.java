package convoy.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.contracts.events.AcceptancesFrozen;
import convoy.contracts.events.PaymentCaptured;
import convoy.contracts.events.QuoteFinalised;
import convoy.platform.messaging.EventContext;
import convoy.platform.messaging.Uuid7;
import convoy.platform.testing.ServiceTest;
import java.io.File;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every tiers.json vector (T01-T30) through the order service itself: ORD-T01 (ceiling snapshot, tier validation), confirmations
 * on the vector's timeline, then the one-transaction resolution. Tier prices come from the price engine (as in production): each
 * vector price maps to a discount, so ordering and equality between tiers are the vector's own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "convoy.messaging.enabled=false", "order.sweeper-interval=1h",
        "convoy.issuers.convoy-gateway=http://unused", "convoy.issuers.convoy-identity=http://unused"})
@Import(ServiceTest.Config.class)
class TierVectorsIT extends ServiceTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String VARIANT = OrderIT.VARIANT;

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired Orders orders;

    static JsonNode vectors() throws Exception { return JSON.readTree(new File("../../spec-tools/vectors/tiers.json")).get("cases"); }

    static Stream<String> ids() throws Exception { return StreamSupport.stream(vectors().spliterator(), false).map(c -> c.get("id").asText()); }

    void inTx(Runnable r) {
        try {
            EventContext.set(Uuid7.next(), null);
            tx.executeWithoutResult(s -> r.run());
        } finally {
            EventContext.clear();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ids")
    void vectorThroughTheOrderService(String id) throws Exception {
        JsonNode c = StreamSupport.stream(vectors().spliterator(), false).filter(v -> v.get("id").asText().equals(id)).findFirst().orElseThrow();
        JsonNode in = c.get("input"), ex = c.get("expected");
        UUID pool = Uuid7.next(), quote = Uuid7.next();
        List<Map<String, Object>> tiers = new ArrayList<>();
        for (JsonNode t : in.get("tiers")) {
            Map<String, Object> m = new HashMap<>();
            m.put("min", t.get("min").asLong());
            m.put("max", t.get("max").isNull() ? null : t.get("max").asLong());
            m.put("discount_paise", (90_500_000L - t.get("price_paise").asLong()) / 10); // higher vector price -> smaller discount -> higher engine price
            tiers.add(m);
        }
        long minF = in.get("min_fulfilment").asLong();
        Long maxUnits = in.get("max_units").isNull() ? null : in.get("max_units").asLong();
        inTx(() -> orders.quoteFinalised(new QuoteFinalised(quote.toString(), pool.toString(), UUID.randomUUID().toString(), 1L, List.of(VARIANT), minF, maxUnits,
                "sha", "2026.10.24-p0", tiers, List.of(Map.of("variant_id", VARIANT, "input", OrderIT.swiftInput(), "stock", Map.of("units", 200, "colours", List.of("White"), "delivery_date", "2026-12-20"))))));

        JsonNode timeline = in.get("confirmations");
        int n = timeline != null ? timeline.size() : in.get("confirmed_count").asInt();
        List<UUID> buyers = new ArrayList<>();
        List<Map<String, Object>> acceptances = new ArrayList<>();
        for (int i = 0; i < Math.max(n, 1); i++) { // a count-0 vector still has one accepting buyer, who never confirms
            UUID b = UUID.randomUUID();
            buyers.add(b);
            inTx(() -> orders.paymentCaptured(new PaymentCaptured(Uuid7.next().toString(), b.toString(), pool.toString(), 99900L, false, true)));
            acceptances.add(Map.of("buyer_id", b.toString(), "membership_id", Uuid7.next().toString(), "quote_id", quote.toString(), "variant_id", VARIANT));
        }
        AcceptancesFrozen frozen = new AcceptancesFrozen(pool.toString(), 1L, Instant.now().plusSeconds(3600), acceptances, Map.of(quote.toString(), (long) acceptances.size()), List.of());
        if ("INVALID".equals(ex.get("status").asText())) {
            assertThatThrownBy(() -> inTx(() -> orders.acceptancesFrozen(frozen))).hasMessageContaining(ex.get("errors").get(0).asText());
            assertThat(jdbc.queryForObject("select count(*) from orders where pool_id = ?", Integer.class, pool)).isZero();
            return;
        }
        inTx(() -> orders.acceptancesFrozen(frozen));

        // Confirmations on the vector's timeline, shifted so its deadline is one minute ago (the DB clock decides).
        Instant deadline = Instant.now().minusSeconds(60);
        Instant vectorDeadline = timeline != null ? Instant.parse(in.get("confirmation_deadline").asText()) : deadline;
        Duration shift = Duration.between(vectorDeadline, deadline);
        for (int i = 0; i < n; i++) {
            JsonNode t = timeline != null ? timeline.get(i) : null;
            Instant confirmedAt = t != null ? Instant.parse(t.get("confirmed_at").asText()).plus(shift) : deadline.minusSeconds(600);
            Instant captured = t == null ? deadline.minusSeconds(86_400) : t.get("deposit_captured_at").isNull() ? null : Instant.parse(t.get("deposit_captured_at").asText()).plus(shift);
            Instant cancelled = t != null && t.hasNonNull("cancelled_at") ? Instant.parse(t.get("cancelled_at").asText()).plus(shift) : null;
            boolean cancelledBefore = cancelled != null && cancelled.isBefore(deadline); // ORD-T03 before the deadline; after it the order stays counted
            UUID order = jdbc.queryForObject("select id from orders where buyer_id = ? and pool_id = ?", UUID.class, buyers.get(i), pool);
            jdbc.update("update orders o set state = ?, confirmed_at = ?, cancelled_at = ?, ceiling_snapshot_id = s.id, confirmed_ceiling_paise = (s.output->>'cash_payable_paise')::bigint "
                    + "from price_snapshots s where o.id = ? and s.order_id = o.id and s.kind = 'CEILING'", cancelledBefore ? "CANCELLED" : "CONFIRMED", Timestamp.from(confirmedAt),
                    cancelledBefore ? Timestamp.from(cancelled) : null, order);
            if (captured == null) jdbc.update("delete from payment_replica where buyer_id = ? and pool_id = ?", buyers.get(i), pool);
            else jdbc.update("update payment_replica set captured_at = ? where buyer_id = ? and pool_id = ?", Timestamp.from(captured), buyers.get(i), pool);
        }
        jdbc.update("update orders set confirmation_deadline = ? where pool_id = ?", Timestamp.from(deadline), pool);
        jdbc.update("update pool_resolutions set confirmation_deadline = ? where pool_id = ?", Timestamp.from(deadline), pool);

        inTx(() -> orders.resolve(pool, 1));

        JsonNode tr = JSON.readTree(jdbc.queryForObject("select payload::text from outbox where event_type = 'TierResolved' and payload->>'quote_id' = ?", String.class, quote.toString()));
        assertThat(tr.get("outcome").asText()).as(id + " outcome").isEqualTo(ex.get("outcome").asText());
        assertThat(tr.get("confirmed_count").asLong()).as(id + " count").isEqualTo(ex.get("confirmed_count").asLong());
        assertThat(jdbc.queryForList("select distinct tier_index from price_snapshots s join orders o on o.id = s.order_id where o.pool_id = ? and s.kind = 'CEILING'", Integer.class, pool))
                .as(id + " ceiling tier").containsExactly(ex.get("ceiling_tier").asInt());
        int bookings = jdbc.queryForObject("select count(*) from bookings b join orders o on o.id = b.order_id where o.pool_id = ?", Integer.class, pool);
        if ("RESOLVED".equals(ex.get("outcome").asText())) {
            assertThat(tr.get("resolved_tier").asLong()).as(id + " tier").isEqualTo(ex.get("resolved_tier").asLong());
            assertThat(bookings).as(id + " bookings").isEqualTo(ex.get("confirmed_count").asInt());
            assertThat(jdbc.queryForList("select distinct s.tier_index from bookings b join price_snapshots s on s.id = b.final_snapshot_id join orders o on o.id = b.order_id where o.pool_id = ?",
                    Integer.class, pool)).containsExactly(ex.get("resolved_tier").asInt());
            // the price can only fall: every FINAL is at or below the ceiling the buyer confirmed
            assertThat(jdbc.queryForObject("select count(*) from bookings b join price_snapshots f on f.id = b.final_snapshot_id join orders o on o.id = b.order_id "
                    + "where o.pool_id = ? and (f.output->>'cash_payable_paise')::bigint > o.confirmed_ceiling_paise", Integer.class, pool)).isZero();
        } else {
            assertThat(tr.get("resolved_tier").isNull()).isTrue();
            assertThat(bookings).isZero();
            // no booking in the pool: no automatic QUOTE_VOID refund; the pool decides between a re-round and closing (AMENDMENT-002)
            assertThat(jdbc.queryForObject("select count(*) from outbox where event_type = 'DepositRefundRequested' and payload->>'pool_id' = ? and payload->>'reason' = 'QUOTE_VOID'",
                    Integer.class, pool.toString())).isZero();
        }
    }
}
