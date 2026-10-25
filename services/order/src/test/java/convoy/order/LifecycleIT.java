package convoy.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.contracts.events.AcceptancesFrozen;
import convoy.contracts.events.PaymentCaptured;
import convoy.contracts.events.QuoteFinalised;
import convoy.platform.messaging.EventContext;
import convoy.platform.messaging.Uuid7;
import convoy.platform.testing.ServiceTest;
import convoy.platform.testing.TestTokens;
import convoy.testing.StubServer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/** P4.B saga and crash recovery, contact release; P4.D booking, delivery and every cancellation rule; P4.E disputes and evidence. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "convoy.messaging.enabled=false", "order.sweeper-interval=1h", "convoy.aws.endpoint=http://127.0.0.1:1",
        "convoy.issuers.convoy-gateway=http://unused", "convoy.issuers.convoy-identity=http://unused"})
@Import({ServiceTest.Config.class, LifecycleIT.Stubs.class})
class LifecycleIT extends ServiceTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String VARIANT = OrderIT.VARIANT;
    static final StubServer IDENTITY = new StubServer()
            .on("/internal/users/.*/contact", r -> StubServer.Reply.json("{\"user_id\":\"" + r.path().split("/")[3] + "\",\"email\":\"buyer-" + r.path().split("/")[3].substring(0, 8) + "@convoy.test\"}"));

    @TestConfiguration(proxyBeanMethods = false)
    static class Stubs {
        @Bean
        @Primary
        OrderApplication.IdentityClient testIdentity() { return new OrderApplication.IdentityClient(RestClient.builder().baseUrl(IDENTITY.url()).build()); }
    }

    @AfterAll
    static void stop() { IDENTITY.close(); }

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired Orders orders;
    @Autowired Lifecycle lifecycle;

    @AfterEach
    void noHooks() { lifecycle.crashHooks(s -> {}, s -> {}); }

    void inTx(Runnable r) {
        try {
            EventContext.set(Uuid7.next(), null);
            tx.executeWithoutResult(s -> r.run());
        } finally {
            EventContext.clear();
        }
    }

    void advance(UUID pool) {
        try {
            EventContext.set(Uuid7.next(), null);
            lifecycle.advance(pool, 1);
        } finally {
            EventContext.clear();
        }
    }

    record Pool(UUID id, Map<UUID, UUID> quoteOf, Map<UUID, UUID> dealershipOf, List<UUID> buyers) {
        UUID order(JdbcTemplate jdbc, UUID buyer) { return jdbc.queryForObject("select id from orders where buyer_id = ? and pool_id = ?", UUID.class, buyer, id); }
        UUID booking(JdbcTemplate jdbc, UUID buyer) { return jdbc.queryForObject("select b.id from bookings b join orders o on o.id = b.order_id where o.buyer_id = ? and o.pool_id = ?", UUID.class, buyer, id); }
    }

    /** buyersPerQuote[i] buyers accept quote i (each quote from its own dealership); everyone confirms unless confirm = false. */
    Pool pool(boolean confirm, int... buyersPerQuote) throws Exception {
        UUID pool = Uuid7.next();
        Map<UUID, UUID> quoteOf = new HashMap<>(), dealershipOf = new HashMap<>();
        List<UUID> buyers = new ArrayList<>();
        List<Map<String, Object>> acceptances = new ArrayList<>();
        Map<String, Object> counts = new HashMap<>();
        for (int q = 0; q < buyersPerQuote.length; q++) {
            UUID quote = Uuid7.next(), dealership = UUID.randomUUID();
            List<Map<String, Object>> tiers = List.of(Map.of("min", 1, "max", 4, "discount_paise", 1000000), new HashMap<>(Map.of("min", 5, "discount_paise", 2500000)));
            tiers.get(1).put("max", null);
            inTx(() -> orders.quoteFinalised(new QuoteFinalised(quote.toString(), pool.toString(), dealership.toString(), 1L, List.of(VARIANT), 1L, null, "sha", "2026.10.24-p0",
                    tiers, List.of(Map.of("variant_id", VARIANT, "input", OrderIT.swiftInput(), "stock", Map.of("units", 10, "colours", List.of("White"), "delivery_date",
                            LocalDate.now().plusDays(20).toString()))))));
            counts.put(quote.toString(), (long) buyersPerQuote[q]);
            for (int i = 0; i < buyersPerQuote[q]; i++) {
                UUID b = UUID.randomUUID();
                buyers.add(b);
                quoteOf.put(b, quote);
                dealershipOf.put(b, dealership);
                inTx(() -> orders.paymentCaptured(new PaymentCaptured(Uuid7.next().toString(), b.toString(), pool.toString(), 99900L, false, true)));
                acceptances.add(Map.of("buyer_id", b.toString(), "membership_id", Uuid7.next().toString(), "quote_id", quote.toString(), "variant_id", VARIANT));
            }
        }
        inTx(() -> orders.acceptancesFrozen(new AcceptancesFrozen(pool.toString(), 1L, Instant.now().plusSeconds(3600), acceptances, counts, List.of())));
        Pool p = new Pool(pool, quoteOf, dealershipOf, buyers);
        if (confirm) for (UUID b : buyers) assertThat(confirm(p, b).status()).isEqualTo(200);
        return p;
    }

    Resp confirm(Pool p, UUID buyer) throws Exception {
        UUID order = p.order(jdbc, buyer);
        JsonNode v = view(buyer, order);
        return send("POST", "/orders/" + order + "/confirm", TestTokens.buyer("order", buyer), "{\"ceiling_snapshot_hash\":\"" + v.at("/snapshots/ceiling/sha256").asText() + "\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString(), "If-Match", "\"" + v.path("version").asLong() + "\""));
    }

    JsonNode view(UUID buyer, UUID order) throws Exception { return JSON.readTree(get("/orders/" + order, TestTokens.buyer("order", buyer)).body()); }

    void deadlinePassed(Pool p) {
        jdbc.update("update orders set confirmation_deadline = clock_timestamp() where pool_id = ?", p.id());
        jdbc.update("update pool_resolutions set confirmation_deadline = clock_timestamp() where pool_id = ?", p.id());
    }

    void holdsAndStock(Pool p, boolean holds, boolean stock) {
        for (UUID b : jdbc.queryForList("select b.id from bookings b join orders o on o.id = b.order_id where o.pool_id = ?", UUID.class, p.id())) {
            if (holds) orders.stepDone(b, "HOLD_DEPOSIT");
            if (stock) orders.stepDone(b, "RESERVE_STOCK");
        }
    }

    /** Booked and fully issued (saga at CONTACTS_RELEASED). */
    Pool booked(int... buyersPerQuote) throws Exception {
        Pool p = pool(true, buyersPerQuote);
        deadlinePassed(p);
        advance(p.id());
        holdsAndStock(p, true, true);
        advance(p.id());
        assertThat(saga(p)).isEqualTo("CONTACTS_RELEASED");
        return p;
    }

    String saga(Pool p) { return jdbc.queryForObject("select step from sagas where pool_id = ?", String.class, p.id()); }

    Map<String, Object> s(String sql, Object... args) { return jdbc.queryForMap(sql, args); }

    int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }

    /** Events of the pool's orders, bookings and the pool itself, as type -> count, plus terminal business state. */
    Map<String, Object> fingerprint(Pool p) {
        Map<String, Object> f = new TreeMap<>();
        jdbc.queryForList("select event_type, count(*) as n from outbox where aggregate_id = ? or aggregate_id in (select id from orders where pool_id = ?) "
                + "or aggregate_id in (select b.id from bookings b join orders o on o.id = b.order_id where o.pool_id = ?) group by event_type", p.id(), p.id(), p.id())
                .forEach(r -> f.put("event " + r.get("event_type"), r.get("n")));
        f.put("orders", jdbc.queryForList("select state from orders where pool_id = ? order by state", String.class, p.id()));
        f.put("bookings", jdbc.queryForList("select b.state || ':' || b.voucher_active from bookings b join orders o on o.id = b.order_id where o.pool_id = ? order by 1", String.class, p.id()));
        f.put("contacts", count("select count(*) from contact_releases r join orders o on o.id = r.order_id where o.pool_id = ?", p.id()));
        f.put("refunds", jdbc.queryForList("select payload->>'reason' from outbox where event_type = 'DepositRefundRequested' and payload->>'pool_id' = ? order by 1", String.class, p.id().toString()));
        f.put("saga", saga(p));
        return f;
    }

    /** One run of the saga with an optional crash: thrown inside the step's transaction (it rolls back, like a kill before commit) or right after it commits. */
    Map<String, Object> run(String crashStep, boolean afterCommit) throws Exception {
        Pool p = pool(true, 3);
        deadlinePassed(p);
        boolean[] crashed = {false};
        java.util.function.Consumer<String> hook = step -> {
            if (step.equals(crashStep) && !crashed[0]) {
                crashed[0] = true;
                throw new IllegalStateException("injected crash at " + step + (afterCommit ? " after commit" : " before commit"));
            }
        };
        if (afterCommit) lifecycle.crashHooks(s -> {}, hook);
        else lifecycle.crashHooks(hook, s -> {});
        Runnable restartable = () -> {
            try {
                advance(p.id());
            } catch (IllegalStateException e) {
                assertThat(e.getMessage()).startsWith("injected crash");
                noHooks();
                advance(p.id()); // the process restarts: the sweeper resumes the saga from its persisted step
            }
        };
        restartable.run();
        holdsAndStock(p, true, false); // DepositHeld x3 arrive
        restartable.run();
        holdsAndStock(p, false, true); // StockReserved x3 arrive
        restartable.run();
        advance(p.id()); // an extra sweep is a no-op
        if (crashStep != null) assertThat(crashed[0]).as("crash at " + crashStep + " injected").isTrue();
        return fingerprint(p);
    }

    @Test
    void aCrashAtEverySagaBoundaryResumesToTheSameBusinessResult() throws Exception {
        Map<String, Object> clean = run(null, false);
        assertThat(clean).containsEntry("saga", "CONTACTS_RELEASED").containsEntry("contacts", 3).containsEntry("event BookingIssued", 3L)
                .containsEntry("event DepositHoldRequested", 3L).containsEntry("refunds", List.of());
        for (String step : List.of("RESOLVED", "DEPOSITS_HELD", "BOOKINGS_ISSUED", "CONTACTS_RELEASED")) {
            for (boolean after : List.of(false, true)) {
                assertThat(run(step, after)).as("crash at " + step + (after ? " after" : " before") + " commit").isEqualTo(clean);
            }
        }
    }

    @Test
    void aRejectedHoldAndMissingStockAreCompensatedAndTheSagaStillFinishes() throws Exception {
        Pool p = pool(true, 3);
        deadlinePassed(p);
        advance(p.id());
        UUID noDeposit = p.booking(jdbc, p.buyers().get(0)), noStock = p.booking(jdbc, p.buyers().get(1)), fine = p.booking(jdbc, p.buyers().get(2));
        inTx(() -> lifecycle.stepFailed(noDeposit, "HOLD_DEPOSIT", "NO_CAPTURED_DEPOSIT"));
        orders.stepDone(noStock, "HOLD_DEPOSIT");
        orders.stepDone(fine, "HOLD_DEPOSIT");
        advance(p.id());
        assertThat(saga(p)).isEqualTo("DEPOSITS_HELD");
        inTx(() -> lifecycle.stockUnavailable(noStock));
        orders.stepDone(fine, "RESERVE_STOCK");
        advance(p.id());
        assertThat(saga(p)).isEqualTo("CONTACTS_RELEASED");
        assertThat(s("select state, cancel_reason from bookings where id = ?", noDeposit)).containsEntry("state", "CANCELLED").containsEntry("cancel_reason", "DEPOSIT_UNAVAILABLE");
        assertThat(s("select state, cancel_reason from bookings where id = ?", noStock)).containsEntry("state", "CANCELLED").containsEntry("cancel_reason", "STOCK_UNAVAILABLE");
        assertThat(s("select state, voucher_active from bookings where id = ?", fine)).containsEntry("state", "ISSUED").containsEntry("voucher_active", true);
        assertThat(fingerprint(p).get("refunds")).isEqualTo(List.of("DEPOSIT_UNAVAILABLE", "STOCK_UNAVAILABLE"));
        assertThat(count("select count(*) from disputes where booking_id = ? and category = 'STOCK_UNAVAILABLE' and opened_by = 'SYSTEM'", noStock)).isEqualTo(1);
        assertThat(count("select count(*) from contact_releases where booking_id in (?, ?)", noDeposit, noStock)).isZero(); // a cancelled booking releases nothing
        assertThat(count("select count(*) from contact_releases where booking_id = ?", fine)).isEqualTo(1);
    }

    @Test
    void contactGoesOnlyToTheDealerWhoseQuoteTheBuyerConfirmed() throws Exception {
        Pool p = booked(1, 1); // two quotes from two dealerships in one pool
        UUID a = p.buyers().get(0), b = p.buyers().get(1);
        UUID bookingA = p.booking(jdbc, a);
        UUID dealerA = p.dealershipOf().get(a), dealerB = p.dealershipOf().get(b);
        assertThat(s("select buyer_id, dealership_id from contact_releases where booking_id = ?", bookingA)).containsEntry("buyer_id", a).containsEntry("dealership_id", dealerA);
        assertThat(count("select count(*) from contact_consents where order_id = ? and dealership_id = ?", p.order(jdbc, a), dealerA)).isEqualTo(1); // consent recorded at confirmation
        Resp own = get("/dealer/bookings/" + bookingA + "/contact", TestTokens.dealer("order", UUID.randomUUID(), dealerA));
        assertThat(own.status()).as(own.body()).isEqualTo(200);
        assertThat(JSON.readTree(own.body()).path("email").asText()).startsWith("buyer-" + a.toString().substring(0, 8));
        assertThat(JSON.readTree(own.body()).path("consent_version").asText()).isEqualTo(Orders.CONTACT_CONSENT_VERSION);
        // the competing dealer in the same pool, and a buyer, get nothing
        assertThat(get("/dealer/bookings/" + bookingA + "/contact", TestTokens.dealer("order", UUID.randomUUID(), dealerB)).status()).isEqualTo(404);
        assertThat(get("/dealer/bookings/" + bookingA + "/contact", TestTokens.buyer("order", a)).status()).isEqualTo(403);
        assertThat(count("select count(*) from contact_releases where buyer_id = ? and dealership_id <> ?", a, dealerA)).isZero();
    }

    // ------------------------------------------------------------------ P4.D cancellation rules (cancellation-rules.md §1)

    Map<String, String> idem() { return Map.of("Idempotency-Key", UUID.randomUUID().toString()); }

    Map<String, String> idem(long version) { return Map.of("Idempotency-Key", UUID.randomUUID().toString(), "If-Match", "\"" + version + "\""); }

    String refundReason(UUID order) { return jdbc.queryForObject("select payload->>'reason' from outbox where event_type = 'DepositRefundRequested' and aggregate_id = ?", String.class, order); }

    @Test
    void buyerDeclinesOrCancelsBeforeTheDeadlineAndIsRefundedWithoutChangingAnyoneElsesPrice() throws Exception {
        Pool p = pool(false, 3);
        UUID decliner = p.buyers().get(0), canceller = p.buyers().get(1), stays = p.buyers().get(2);
        assertThat(send("POST", "/orders/" + p.order(jdbc, decliner) + "/decline", TestTokens.buyer("order", decliner), null, idem()).status()).isEqualTo(200); // ORD-T04
        assertThat(refundReason(p.order(jdbc, decliner))).isEqualTo("BUYER_DECLINED");
        assertThat(confirm(p, canceller).status()).isEqualTo(200);
        assertThat(confirm(p, stays).status()).isEqualTo(200);
        UUID co = p.order(jdbc, canceller);
        assertThat(send("POST", "/orders/" + co + "/cancel", TestTokens.buyer("order", canceller), null, idem(99)).status()).isEqualTo(409); // version-conflict
        Resp cancelled = send("POST", "/orders/" + co + "/cancel", TestTokens.buyer("order", canceller), null, idem(view(canceller, co).path("version").asLong()));
        assertThat(cancelled.status()).as(cancelled.body()).isEqualTo(200); // ORD-T03
        assertThat(refundReason(co)).isEqualTo("BUYER_CANCELLED");
        assertThat(count("select confirmed_count from quote_capacity where quote_id = ?", p.quoteOf().get(stays))).isEqualTo(1);
        deadlinePassed(p);
        advance(p.id());
        // the one who stayed is booked at the tier their own count gives; the ceiling never moves up
        assertThat(view(stays, p.order(jdbc, stays)).path("state").asText()).isEqualTo("BOOKED");
        assertThat(jdbc.queryForObject("select payload->>'confirmed_count' from outbox where event_type = 'TierResolved' and payload->>'pool_id' = ?", String.class, p.id().toString())).isEqualTo("1");
    }

    @Test
    void buyerCancelsABookingAndOnlyTheirOwnBookingChanges() throws Exception {
        Pool p = booked(2);
        UUID leaver = p.buyers().get(0), other = p.buyers().get(1);
        UUID booking = p.booking(jdbc, leaver);
        long otherFinal = jdbc.queryForObject("select (s.output->>'cash_payable_paise')::bigint from bookings b join price_snapshots s on s.id = b.final_snapshot_id where b.id = ?",
                Long.class, p.booking(jdbc, other));
        long v = jdbc.queryForObject("select version from bookings where id = ?", Long.class, booking);
        assertThat(send("POST", "/bookings/" + booking + "/cancel", TestTokens.buyer("order", other), null, idem(v)).status()).isEqualTo(404); // not their booking
        assertThat(send("POST", "/bookings/" + booking + "/cancel", TestTokens.buyer("order", leaver), null, idem(v)).status()).isEqualTo(200); // BKG-T06
        assertThat(s("select b.state as b, o.state as o from bookings b join orders o on o.id = b.order_id where b.id = ?", booking)).containsEntry("b", "CANCELLED").containsEntry("o", "CANCELLED");
        assertThat(refundReason(p.order(jdbc, leaver))).isEqualTo("BUYER_CANCELLED");
        assertThat(count("select count(*) from disputes where booking_id = ?", booking)).isZero(); // a buyer cancellation opens no dispute
        assertThat(jdbc.queryForObject("select (s.output->>'cash_payable_paise')::bigint from bookings b join price_snapshots s on s.id = b.final_snapshot_id where b.id = ?",
                Long.class, p.booking(jdbc, other))).isEqualTo(otherFinal);
        assertThat(jdbc.queryForObject("select state from bookings where id = ?", String.class, p.booking(jdbc, other))).isEqualTo("ISSUED");
    }

    @Test
    void dealerSideFailuresRefundTheBuyerOpenADisputeAndTellTheDealerService() throws Exception {
        Pool p = booked(3);
        UUID dealership = p.dealershipOf().get(p.buyers().get(0));
        // (1) the dealer admin cancels; staff may not
        UUID b1 = p.booking(jdbc, p.buyers().get(0));
        long v1 = jdbc.queryForObject("select version from bookings where id = ?", Long.class, b1);
        assertThat(send("POST", "/dealer/bookings/" + b1 + "/cancel", TestTokens.dealer("order", UUID.randomUUID(), dealership), "{\"reason\":\"DEALER_CANCELLED\"}", idem(v1)).status()).isEqualTo(403);
        String dealerAdmin = TestTokens.user("order", UUID.randomUUID(), List.of("dealer_admin"), dealership);
        assertThat(send("POST", "/dealer/bookings/" + b1 + "/cancel", dealerAdmin, "{\"reason\":\"DEALER_CANCELLED\"}", idem(v1)).status()).isEqualTo(200);
        // (2) the 48-hour dealer confirmation deadline passes (DB clock)
        UUID b2 = p.booking(jdbc, p.buyers().get(1));
        jdbc.update("update bookings set dealer_confirm_deadline = clock_timestamp() - interval '1 second' where id = ?", b2);
        lifecycle.sweep();
        // (3) stock vanishes
        UUID b3 = p.booking(jdbc, p.buyers().get(2));
        inTx(() -> lifecycle.stockUnavailable(b3));
        Map<UUID, String[]> expected = Map.of(b1, new String[] {"DEALER_CANCELLED", "DEALER_CANCELLED", "DEALER_CANCELLED"},
                b2, new String[] {"DEALER_NO_CONFIRM", "DEALER_NOT_HONOURED", "DEALER_NOT_HONOURED"}, b3, new String[] {"STOCK_UNAVAILABLE", "STOCK_UNAVAILABLE", "STOCK_UNAVAILABLE"});
        for (var e : expected.entrySet()) {
            UUID order = jdbc.queryForObject("select order_id from bookings where id = ?", UUID.class, e.getKey());
            assertThat(s("select state, cancel_reason from bookings where id = ?", e.getKey())).containsEntry("state", "CANCELLED").containsEntry("cancel_reason", e.getValue()[0]);
            assertThat(refundReason(order)).isEqualTo(e.getValue()[1]);
            assertThat(s("select category, opened_by, state from disputes where booking_id = ?", e.getKey()))
                    .containsEntry("category", e.getValue()[2]).containsEntry("opened_by", "SYSTEM").containsEntry("state", "OPEN"); // admin review queue
            assertThat(jdbc.queryForObject("select payload->>'reason' from outbox where event_type = 'BookingCancelled' and aggregate_id = ?", String.class, e.getKey()))
                    .isEqualTo(e.getValue()[0]); // dealer service: reliability; quote: stock release
        }
        lifecycle.sweep(); // repeat: nothing twice
        assertThat(count("select count(*) from outbox where event_type = 'DepositRefundRequested' and payload->>'pool_id' = ?", p.id().toString())).isEqualTo(3);
        assertThat(count("select count(*) from outbox where event_type = 'AllBookingsClosed' and aggregate_id = ?", p.id())).isEqualTo(1);
        assertThat(get("/admin/disputes", TestTokens.admin("order", UUID.randomUUID())).body()).contains("DEALER_CANCELLED");
    }

    @Test
    void aQuoteWithdrawnAfterFinalCancelsEveryAcceptingBuyerWithARefundAndADispute() throws Exception {
        Pool before = pool(false, 2); // still confirming
        UUID quote = before.quoteOf().get(before.buyers().get(0));
        assertThat(confirm(before, before.buyers().get(0)).status()).isEqualTo(200);
        inTx(() -> lifecycle.quoteWithdrawnAfterFinal(quote));
        for (UUID b : before.buyers()) {
            UUID o = before.order(jdbc, b);
            assertThat(s("select state, cancel_reason from orders where id = ?", o)).containsEntry("state", "CANCELLED").containsEntry("cancel_reason", "DEALER_CANCELLED"); // ORD-T10
            assertThat(refundReason(o)).isEqualTo("DEALER_CANCELLED");
            assertThat(count("select count(*) from disputes where order_id = ? and category = 'DEALER_CANCELLED' and booking_id is null", o)).isEqualTo(1); // Q-023
        }
        Pool after = booked(1);
        UUID q2 = after.quoteOf().get(after.buyers().get(0));
        inTx(() -> lifecycle.quoteWithdrawnAfterFinal(q2));
        assertThat(s("select state, cancel_reason from bookings where id = ?", after.booking(jdbc, after.buyers().get(0)))).containsEntry("state", "CANCELLED").containsEntry("cancel_reason", "DEALER_CANCELLED");
    }

    // ------------------------------------------------------------------ P4.D delivery, overdue

    @Test
    void confirmScheduleDeliverReleasesTheBookingAndClosesThePool() throws Exception {
        Pool p = booked(2);
        UUID dealership = p.dealershipOf().get(p.buyers().get(0));
        String staff = TestTokens.dealer("order", UUID.randomUUID(), dealership);
        for (UUID buyer : p.buyers()) {
            UUID b = p.booking(jdbc, buyer);
            assertThat(send("POST", "/dealer/bookings/" + b + "/confirm", staff, null, idem(1)).status()).isEqualTo(200); // BKG-T02
            assertThat(send("PUT", "/dealer/bookings/" + b + "/delivery", staff, "{\"scheduled_date\":\"" + LocalDate.now(Lifecycle.IST).minusDays(1) + "\",\"slot\":\"10:00-12:00\"}", idem(2))
                    .status()).isEqualTo(400); // the past is refused (validation-failed)
            Resp sched = send("PUT", "/dealer/bookings/" + b + "/delivery", staff, "{\"scheduled_date\":\"" + LocalDate.now(Lifecycle.IST).plusDays(3) + "\",\"slot\":\"10:00-12:00\"}", idem(2));
            assertThat(sched.status()).as(sched.body()).isEqualTo(200); // BKG-T04
        }
        // buyer 1 confirms delivery themselves; buyer 2's voucher is scanned by the dealer (a wrong token is refused)
        UUID b1 = p.booking(jdbc, p.buyers().get(0)), b2 = p.booking(jdbc, p.buyers().get(1));
        Resp got = send("POST", "/bookings/" + b1 + "/confirm-delivery", TestTokens.buyer("order", p.buyers().get(0)), null, idem());
        assertThat(got.status()).as(got.body()).isEqualTo(200);
        assertThat(JSON.readTree(got.body()).path("state").asText()).isEqualTo("COMPLETED"); // ORD-T08
        String wrong = jdbc.queryForObject("select voucher_token from bookings where id = ?", String.class, b1);
        assertThat(send("POST", "/dealer/bookings/" + b2 + "/deliver", staff, "{\"voucher_token\":\"" + wrong + "\"}", idem()).status()).isEqualTo(403);
        assertThat(count("select count(*) from outbox where event_type = 'AllBookingsClosed' and aggregate_id = ?", p.id())).isZero();
        String token = jdbc.queryForObject("select voucher_token from bookings where id = ?", String.class, b2);
        assertThat(send("POST", "/dealer/bookings/" + b2 + "/deliver", staff, "{\"voucher_token\":\"" + token + "\"}", idem()).status()).isEqualTo(200); // BKG-T05
        for (UUID b : List.of(b1, b2)) {
            assertThat(s("select state from bookings where id = ?", b)).containsEntry("state", "DELIVERED");
            assertThat(count("select count(*) from outbox where event_type = 'BookingDelivered' and aggregate_id = ?", b)).isEqualTo(1); // payment: release + J-FEE
        }
        assertThat(jdbc.queryForObject("select payload->>'delivered' from outbox where event_type = 'AllBookingsClosed' and aggregate_id = ?", String.class, p.id())).isEqualTo("2");
        assertThat(send("POST", "/bookings/" + b1 + "/cancel", TestTokens.buyer("order", p.buyers().get(0)), null, idem(5)).status()).isIn(409); // delivered: dispute instead
    }

    @Test
    void aBookingSevenDaysPastItsPromisedDateIsFlaggedOverdueOnce() throws Exception {
        Pool p = booked(1);
        UUID b = p.booking(jdbc, p.buyers().get(0));
        jdbc.update("update bookings set state = 'DEALER_CONFIRMED', promised_delivery_date = (clock_timestamp() at time zone 'Asia/Kolkata')::date - 7 where id = ?", b);
        lifecycle.sweep();
        assertThat(s("select overdue_flag from bookings where id = ?", b)).containsEntry("overdue_flag", false); // the grace day is not over yet
        jdbc.update("update bookings set promised_delivery_date = promised_delivery_date - 1 where id = ?", b);
        lifecycle.sweep();
        lifecycle.sweep();
        assertThat(s("select overdue_flag, state from bookings where id = ?", b)).containsEntry("overdue_flag", true).containsEntry("state", "DEALER_CONFIRMED"); // BKG-T07 self-transition
        assertThat(count("select count(*) from outbox where event_type = 'BookingOverdue' and aggregate_id = ?", b)).isEqualTo(1);
        assertThat(view(p.buyers().get(0), p.order(jdbc, p.buyers().get(0))).at("/booking/overdue_flag").asBoolean()).isTrue(); // the buyer is prompted to open a dispute
    }

    // ------------------------------------------------------------------ P4.E disputes

    @Test
    void disputeLifecycleWithEvidenceSecondAdminAndAppeal() throws Exception {
        Pool p = booked(1);
        UUID buyer = p.buyers().get(0), order = p.order(jdbc, buyer), booking = p.booking(jdbc, buyer);
        UUID dealership = p.dealershipOf().get(buyer);
        String buyerT = TestTokens.buyer("order", buyer);
        Resp opened = send("POST", "/orders/" + order + "/disputes", buyerT, "{\"category\":\"PRICE_NOT_HONOURED\",\"description\":\"The showroom asked for Rs 20,000 more.\"}", idem());
        assertThat(opened.status()).as(opened.body()).isEqualTo(200);
        String id = JSON.readTree(opened.body()).path("id").asText();
        assertThat(code(send("POST", "/orders/" + order + "/disputes", buyerT, "{\"category\":\"PRICE_NOT_HONOURED\",\"description\":\"again\"}", idem()))).isEqualTo("INVALID_TRANSITION");
        assertThat(code(send("POST", "/orders/" + order + "/disputes", buyerT, "{\"category\":\"NOPE\",\"description\":\"x\"}", idem()))).isEqualTo("VALIDATION_FAILED");
        // only the parties and admins can see it
        assertThat(get("/disputes/" + id, TestTokens.buyer("order", UUID.randomUUID())).status()).isEqualTo(404);
        assertThat(get("/disputes/" + id, TestTokens.dealer("order", UUID.randomUUID(), UUID.randomUUID())).status()).isEqualTo(404);
        assertThat(get("/disputes/" + id, TestTokens.dealer("order", UUID.randomUUID(), dealership)).status()).isEqualTo(200);
        // evidence: type, size and count limits, then a pre-signed PUT
        String up = "/disputes/" + id + "/evidence-uploads";
        assertThat(code(send("POST", up, buyerT, "{\"file_name\":\"a.exe\",\"content_type\":\"application/x-msdownload\",\"size_bytes\":10}", idem()))).isEqualTo("VALIDATION_FAILED");
        assertThat(code(send("POST", up, buyerT, "{\"file_name\":\"a.pdf\",\"content_type\":\"application/pdf\",\"size_bytes\":10485761}", idem()))).isEqualTo("VALIDATION_FAILED");
        Resp put = send("POST", up, buyerT, "{\"file_name\":\"invoice.pdf\",\"content_type\":\"application/pdf\",\"size_bytes\":2048}", idem());
        assertThat(put.status()).as(put.body()).isEqualTo(200);
        assertThat(JSON.readTree(put.body()).path("upload_url").asText()).contains("disputes/" + id).contains("X-Amz-Signature").contains("X-Amz-Expires=600");
        for (int i = 1; i < 10; i++) assertThat(send("POST", up, buyerT, "{\"file_name\":\"p" + i + ".png\",\"content_type\":\"image/png\",\"size_bytes\":100}", idem()).status()).isEqualTo(200);
        assertThat(code(send("POST", up, buyerT, "{\"file_name\":\"p11.png\",\"content_type\":\"image/png\",\"size_bytes\":100}", idem()))).isEqualTo("VALIDATION_FAILED");
        // admin: assign, ask the dealer, the dealer answers
        UUID owner = UUID.randomUUID(), second = UUID.randomUUID();
        String ownerT = TestTokens.admin("order", owner), secondT = TestTokens.admin("order", second);
        assertThat(send("POST", "/admin/disputes/" + id + "/assign", ownerT, "{}", idem()).status()).isEqualTo(200);
        assertThat(send("POST", "/admin/disputes/" + id + "/request-info", ownerT, "{\"party\":\"DEALER\",\"question\":\"Was the price changed?\"}", idem()).status()).isEqualTo(200);
        assertThat(send("POST", "/disputes/" + id + "/responses", TestTokens.dealer("order", UUID.randomUUID(), dealership), "{\"text\":\"Accessories were added.\"}", idem()).status()).isEqualTo(200);
        assertThat(JSON.readTree(get("/disputes/" + id, ownerT).body()).path("state").asText()).isEqualTo("UNDER_REVIEW");
        // a money outcome: the owner proposes, the owner cannot also confirm, a second admin confirms (AMENDMENT-001)
        String cancel = "{\"outcome\":\"CANCEL_BOOKING\",\"note\":\"Price not honoured.\"}";
        assertThat(JSON.readTree(send("POST", "/admin/disputes/" + id + "/resolve", ownerT, cancel, idem()).body()).path("proposed_outcome").asText()).isEqualTo("CANCEL_BOOKING");
        assertThat(code(send("POST", "/admin/disputes/" + id + "/resolve", ownerT, cancel, idem()))).isEqualTo("SECOND_APPROVAL_REQUIRED");
        assertThat(s("select state from bookings where id = ?", booking)).containsEntry("state", "ISSUED"); // nothing moved yet
        Resp resolved = send("POST", "/admin/disputes/" + id + "/resolve", secondT, cancel, idem());
        assertThat(resolved.status()).as(resolved.body()).isEqualTo(200);
        assertThat(s("select state, cancel_reason from bookings where id = ?", booking)).containsEntry("state", "CANCELLED").containsEntry("cancel_reason", "ADMIN_DISPUTE_RESOLUTION");
        assertThat(refundReason(order)).isEqualTo("DEALER_NOT_HONOURED");
        // appeal once, within 7 days
        assertThat(send("POST", "/disputes/" + id + "/appeal", buyerT, "{\"text\":\"I also want the price difference.\"}", idem()).status()).isEqualTo(200);
        JsonNode d = JSON.readTree(get("/disputes/" + id, buyerT).body());
        assertThat(d.path("state").asText()).isEqualTo("UNDER_REVIEW");
        assertThat(d.path("evidence")).hasSize(10);
        assertThat(d.path("evidence").get(0).path("download_url").asText()).contains("X-Amz-Signature");
        List<String> actions = new ArrayList<>();
        d.path("history").forEach(h -> actions.add(h.path("action").asText()));
        assertThat(actions).startsWith("OPENED").contains("EVIDENCE_ADDED", "ASSIGNED", "INFO_REQUESTED", "RESPONDED", "OUTCOME_PROPOSED", "RESOLVED", "APPEALED");
        assertThat(code(send("POST", "/disputes/" + id + "/withdraw", buyerT, null, idem()))).isNull(); // the buyer opened it: they may withdraw
    }

    @Test
    void disputesOpenUpTo90DaysAfterDeliveryAndSystemCasesCannotBeWithdrawn() throws Exception {
        Pool p = booked(1);
        UUID buyer = p.buyers().get(0), order = p.order(jdbc, buyer), booking = p.booking(jdbc, buyer);
        jdbc.update("update bookings set state = 'DELIVERED', delivered_at = clock_timestamp() - interval '91 days' where id = ?", booking);
        jdbc.update("update orders set state = 'COMPLETED' where id = ?", order);
        assertThat(code(send("POST", "/orders/" + order + "/disputes", TestTokens.buyer("order", buyer), "{\"category\":\"WRONG_VARIANT\",\"description\":\"x\"}", idem()))).isEqualTo("DEADLINE_PASSED");
        jdbc.update("update bookings set delivered_at = clock_timestamp() - interval '89 days' where id = ?", booking);
        assertThat(send("POST", "/orders/" + order + "/disputes", TestTokens.buyer("order", buyer), "{\"category\":\"WRONG_VARIANT\",\"description\":\"x\"}", idem()).status()).isEqualTo(200);
        Pool q = booked(1);
        UUID qb = q.booking(jdbc, q.buyers().get(0));
        inTx(() -> lifecycle.stockUnavailable(qb));
        UUID system = jdbc.queryForObject("select id from disputes where booking_id = ?", UUID.class, qb);
        assertThat(code(send("POST", "/disputes/" + system + "/withdraw", TestTokens.buyer("order", q.buyers().get(0)), null, idem()))).isEqualTo("INVALID_TRANSITION");
    }

    String code(Resp r) throws Exception {
        if (r.status() < 400) return null;
        return JSON.readTree(r.body()).path("code").asText();
    }
}
