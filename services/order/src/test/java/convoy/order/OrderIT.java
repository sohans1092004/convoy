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
import convoy.platform.problem.ApiProblem;
import convoy.platform.testing.AuthzDenyChecks;
import convoy.platform.testing.Outboxes;
import convoy.platform.testing.ServiceTest;
import convoy.platform.testing.TestTokens;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Ceiling snapshots, confirmation, one-transaction tier resolution ("price can only fall"), VOID, voucher, dealer confirm. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "convoy.messaging.enabled=false", "order.sweeper-interval=1h",
        "convoy.issuers.convoy-gateway=http://unused", "convoy.issuers.convoy-identity=http://unused"})
@Import(ServiceTest.Config.class)
class OrderIT extends ServiceTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final UUID DEALERSHIP = UUID.randomUUID();
    static final String VARIANT = "11111111-1111-7111-8111-111111111111";

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired Orders orders;
    @Autowired Lifecycle lifecycle;

    /** Swift LXi MT, MH, dealer ex-showroom Rs 5,83,900; tiers 1-4 Rs 10,000 off, 5+ Rs 25,000 off (engine computes each price). */
    static Map<String, Object> swiftInput() {
        Map<String, Object> vehicle = new HashMap<>(Map.of("variant_id", "swift-lxi-mt", "fuel", "PETROL", "engine_cc", 1197, "ex_showroom_paise", 58390000,
                "ex_showroom_evidence", "DEALER_CONFIRMED"));
        Map<String, Object> in = new HashMap<>();
        in.put("ruleset_version", "2026.10.24-p0");
        in.put("mode", "BUYER_CONFIG");
        in.put("state", "MH");
        in.put("vehicle", vehicle);
        in.put("discount", Map.of("amount_paise", 0, "base", "EX_SHOWROOM", "evidence", "DEALER_CONFIRMED"));
        in.put("registration", null);
        in.put("insurance", null);
        for (String k : List.of("insurance_addons", "fees", "accessories", "other_charges")) in.put(k, List.of());
        in.put("extended_warranty", null);
        return in;
    }

    void inTx(Runnable r) {
        try {
            EventContext.set(Uuid7.next(), null);
            tx.executeWithoutResult(s -> r.run());
        } finally {
            EventContext.clear();
        }
    }

    record Setup(UUID pool, UUID quote, List<UUID> buyers) {}

    Setup setup(int buyers, long minFulfilment, Long maxUnits, int confirmationSeconds) {
        UUID pool = Uuid7.next(), quote = Uuid7.next();
        List<Map<String, Object>> tiers = List.of(Map.of("min", 1, "max", 4, "discount_paise", 1000000), new HashMap<>(Map.of("min", 5, "discount_paise", 2500000)));
        tiers.get(1).put("max", null);
        inTx(() -> orders.quoteFinalised(new QuoteFinalised(quote.toString(), pool.toString(), DEALERSHIP.toString(), 1L, List.of(VARIANT), minFulfilment, maxUnits,
                "sha", "2026.10.24-p0", tiers, List.of(Map.of("variant_id", VARIANT, "input", swiftInput(), "stock", Map.of("units", 10, "colours", List.of("White"), "delivery_date", "2026-11-20"))))));
        List<UUID> bs = new ArrayList<>();
        List<Map<String, Object>> acceptances = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            UUID b = UUID.randomUUID();
            bs.add(b);
            inTx(() -> orders.paymentCaptured(new PaymentCaptured(Uuid7.next().toString(), b.toString(), pool.toString(), 99900L, false, true)));
            acceptances.add(Map.of("buyer_id", b.toString(), "membership_id", Uuid7.next().toString(), "quote_id", quote.toString(), "variant_id", VARIANT));
        }
        Instant deadline = Instant.now().plusSeconds(confirmationSeconds);
        inTx(() -> orders.acceptancesFrozen(new AcceptancesFrozen(pool.toString(), 1L, deadline, acceptances, Map.of(quote.toString(), (long) buyers), List.of())));
        return new Setup(pool, quote, bs);
    }

    UUID orderOf(UUID buyer, UUID pool) { return jdbc.queryForObject("select id from orders where buyer_id = ? and pool_id = ?", UUID.class, buyer, pool); }

    Resp confirm(UUID buyer, UUID order, String hash, Long version) {
        Map<String, String> h = new HashMap<>(Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        if (version != null) h.put("If-Match", "\"" + version + "\"");
        return send("POST", "/orders/" + order + "/confirm", TestTokens.buyer("order", buyer), "{\"ceiling_snapshot_hash\":\"" + hash + "\"}", h);
    }

    JsonNode view(UUID buyer, UUID order) throws Exception { return JSON.readTree(get("/orders/" + order, TestTokens.buyer("order", buyer)).body()); }

    @Test
    void confirmedBuyersResolveToALowerTierAndGetVouchers() throws Exception {
        Setup s = setup(5, 1, null, 4);
        long ceiling = -1;
        for (UUID b : s.buyers()) {
            UUID o = orderOf(b, s.pool());
            JsonNode v = view(b, o);
            ceiling = v.at("/snapshots/ceiling/output/cash_payable_paise").asLong();
            assertThat(confirm(b, o, v.at("/snapshots/ceiling/sha256").asText(), v.path("version").asLong()).status()).isEqualTo(200);
        }
        // Ceiling is the tier containing min_fulfilment (1-4): Rs 5,83,900 - 10,000 on-road, as the TS/Java/Python engines compute (P001 inputs).
        assertThat(ceiling).isEqualTo(66347500L);
        Thread.sleep(4500);
        inTx(() -> orders.resolve(s.pool(), 1));
        for (UUID b : s.buyers()) {
            JsonNode v = view(b, orderOf(b, s.pool()));
            assertThat(v.path("state").asText()).isEqualTo("BOOKED");
            long fin = v.at("/snapshots/final/output/cash_payable_paise").asLong();
            assertThat(fin).isLessThan(ceiling); // 5 confirmed -> tier 5+: the price fell
            // P4.B: the voucher is valid only once the deposit is held and the stock reserved (saga step BOOKINGS_ISSUED)
            assertThat(get("/orders/" + orderOf(b, s.pool()) + "/voucher", TestTokens.buyer("order", b)).status()).isEqualTo(409);
        }
        assertThat(jdbc.queryForObject("select count(*) from outbox where event_type = 'DepositHoldRequested' and payload->>'pool_id' = ?", Integer.class, s.pool().toString())).isEqualTo(5);
        assertThat(jdbc.queryForObject("select payload->>'bookings_count' from outbox where event_type = 'BookingsIssued' and payload->>'pool_id' = ?", String.class, s.pool().toString())).isEqualTo("5");
        // Re-running the resolution is a no-op.
        inTx(() -> orders.resolve(s.pool(), 1));
        assertThat(jdbc.queryForObject("select count(*) from bookings b join orders o on o.id = b.order_id where o.pool_id = ?", Integer.class, s.pool())).isEqualTo(5);
        // Dealer confirm needs the stock reservation first (saga step), then succeeds; AZ-E088-X: another dealership gets 404.
        UUID booking = jdbc.queryForObject("select b.id from bookings b join orders o on o.id = b.order_id where o.pool_id = ? limit 1", UUID.class, s.pool());
        String dealer = TestTokens.dealer("order", UUID.randomUUID(), DEALERSHIP);
        Resp early = send("POST", "/dealer/bookings/" + booking + "/confirm", dealer, null, Map.of("Idempotency-Key", UUID.randomUUID().toString(), "If-Match", "\"1\""));
        assertThat(JSON.readTree(early.body()).path("code").asText()).isEqualTo("INVALID_TRANSITION");
        orders.stepDone(booking, "RESERVE_STOCK");
        assertThat(send("POST", "/dealer/bookings/" + booking + "/confirm", TestTokens.dealer("order", UUID.randomUUID(), UUID.randomUUID()), null,
                Map.of("Idempotency-Key", UUID.randomUUID().toString(), "If-Match", "\"1\"")).status()).isEqualTo(404);
        assertThat(send("POST", "/dealer/bookings/" + booking + "/confirm", dealer, null, Map.of("Idempotency-Key", UUID.randomUUID().toString(), "If-Match", "\"1\"")).status()).isEqualTo(200);
        for (UUID bk : jdbc.queryForList("select b.id from bookings b join orders o on o.id = b.order_id where o.pool_id = ?", UUID.class, s.pool())) {
            orders.stepDone(bk, "HOLD_DEPOSIT");
            orders.stepDone(bk, "RESERVE_STOCK");
        }
        try {
            EventContext.set(Uuid7.next(), null);
            lifecycle.advance(s.pool(), 1);
        } finally {
            EventContext.clear();
        }
        for (UUID b : s.buyers()) assertThat(get("/orders/" + orderOf(b, s.pool()) + "/voucher", TestTokens.buyer("order", b)).status()).isEqualTo(200);
        String bookings = get("/dealer/bookings", dealer).body();
        assertThat(bookings).contains(booking.toString());
        // AZ-T2: no buyer identity in dealer booking views (contact details only after consent, later phases).
        for (UUID b : s.buyers()) assertThat(bookings).doesNotContain(b.toString());
    }

    @Test
    void confirmationRulesAndTheDeadline() throws Exception {
        Setup s = setup(3, 1, 2L, 3);
        UUID b0 = s.buyers().get(0), b1 = s.buyers().get(1), b2 = s.buyers().get(2);
        UUID o0 = orderOf(b0, s.pool());
        JsonNode v0 = view(b0, o0);
        String hash = v0.at("/snapshots/ceiling/sha256").asText();
        assertThat(JSON.readTree(confirm(b0, o0, "stale", v0.path("version").asLong()).body()).path("code").asText()).isEqualTo("VERSION_CONFLICT"); // stale ceiling hash
        assertThat(confirm(b0, o0, hash, null).status()).isEqualTo(428); // If-Match required
        assertThat(confirm(b0, o0, hash, 99L).status()).isEqualTo(409); // stale If-Match
        assertThat(confirm(b0, o0, hash, v0.path("version").asLong()).status()).isEqualTo(200);
        assertThat(JSON.readTree(confirm(b0, o0, hash, v0.path("version").asLong() + 1).body()).path("code").asText()).isEqualTo("INVALID_TRANSITION"); // CONFIRMED -> CONFIRMED
        // AZ-E082-X / AZ-T4: another buyer cannot see or confirm this order.
        assertThat(get("/orders/" + o0, TestTokens.buyer("order", b1)).status()).isEqualTo(404);
        // max_units = 2: the second confirmation fills capacity, the third is refused.
        UUID o1 = orderOf(b1, s.pool());
        JsonNode v1 = view(b1, o1);
        assertThat(confirm(b1, o1, v1.at("/snapshots/ceiling/sha256").asText(), v1.path("version").asLong()).status()).isEqualTo(200);
        UUID o2 = orderOf(b2, s.pool());
        JsonNode v2 = view(b2, o2);
        assertThat(JSON.readTree(confirm(b2, o2, v2.at("/snapshots/ceiling/sha256").asText(), v2.path("version").asLong()).body()).path("code").asText()).isEqualTo("CAPACITY_REACHED");
        // After the deadline (DB clock) a confirmation is deadline-passed; at resolution the pending order is declined with a refund request.
        Thread.sleep(3500);
        jdbc.update("update quote_capacity set confirmed_count = 0 where quote_id = ?", s.quote());
        assertThat(JSON.readTree(confirm(b2, o2, v2.at("/snapshots/ceiling/sha256").asText(), v2.path("version").asLong()).body()).path("code").asText()).isEqualTo("DEADLINE_PASSED");
        inTx(() -> orders.resolve(s.pool(), 1));
        assertThat(view(b2, o2).path("state").asText()).isEqualTo("DECLINED");
        assertThat(jdbc.queryForObject("select count(*) from outbox where event_type = 'DepositRefundRequested' and payload->>'reason' = 'MISSED_CONFIRMATION_DEADLINE' and payload->>'buyer_id' = ?",
                Integer.class, b2.toString())).isEqualTo(1);
        assertThat(get("/orders/" + o0 + "/decline", TestTokens.buyer("order", b0)).status()).isEqualTo(405);
        // ORD-T03 is built (P4): a booked order is cancelled through its booking (E086), not the order
        assertThat(JSON.readTree(send("POST", "/orders/" + o0 + "/cancel", TestTokens.buyer("order", b0), null,
                Map.of("Idempotency-Key", UUID.randomUUID().toString(), "If-Match", "\"" + view(b0, o0).path("version").asLong() + "\"")).body()).path("code").asText()).isEqualTo("INVALID_TRANSITION");
    }

    @Test
    void tooFewConfirmationsVoidTheQuoteWithoutAutomaticRefundWhenNoBookingExists() throws Exception {
        Setup s = setup(2, 3, null, 3);
        for (UUID b : s.buyers()) {
            UUID o = orderOf(b, s.pool());
            JsonNode v = view(b, o);
            confirm(b, o, v.at("/snapshots/ceiling/sha256").asText(), v.path("version").asLong());
        }
        Thread.sleep(3500);
        inTx(() -> orders.resolve(s.pool(), 1));
        assertThat(jdbc.queryForObject("select count(*) from orders where pool_id = ? and state = 'VOIDED'", Integer.class, s.pool())).isEqualTo(2);
        JsonNode issued = JSON.readTree(jdbc.queryForObject("select payload::text from outbox where event_type = 'BookingsIssued' and payload->>'pool_id' = ?", String.class, s.pool().toString()));
        assertThat(issued.path("bookings_count").asInt()).isZero();
        assertThat(issued.path("void_buyer_ids")).hasSize(2);
        // AMENDMENT-002: with zero bookings the pool decides (re-round choice or expiry); order requests no QUOTE_VOID refund itself.
        assertThat(jdbc.queryForObject("select count(*) from outbox where event_type = 'DepositRefundRequested' and payload->>'pool_id' = ?", Integer.class, s.pool().toString())).isZero();
    }

    @Test
    void authorisationMatrixForEveryP1Endpoint() {
        List<String> failures = AuthzDenyChecks.run("order", this::send, (m, p) -> p.endsWith("/confirm") && p.startsWith("/orders") ? "{\"ceiling_snapshot_hash\":\"x\"}" : null);
        assertThat(failures).isEmpty();
    }

    @Test
    void transitionTablesAndContracts() throws Exception {
        var all = JSON.readTree(OrderIT.class.getResourceAsStream("/convoy/transitions.json"));
        for (var t : List.of(Orders.ORDER, Orders.BOOKING)) {
            for (var p : all.get(t.machine()).get("invalid_pairs")) {
                assertThatThrownBy(() -> t.check(p.get("from").asText(), p.get("to").asText()))
                        .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.type().slug).isEqualTo("invalid-transition"));
            }
        }
        setup(1, 1, null, 60);
        assertThat(Outboxes.assertAllValid(jdbc, "order")).isPositive();
    }

    @Test
    void openApiMatchesTheCommittedWebClient() { assertOpenApiMatchesWebClient("order"); }
}
