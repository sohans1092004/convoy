package convoy.order;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.contracts.events.AcceptancesFrozen;
import convoy.contracts.events.BookingConfirmedByDealer;
import convoy.contracts.events.BookingIssued;
import convoy.contracts.events.BookingsIssued;
import convoy.contracts.events.ConfirmationDeclined;
import convoy.contracts.events.ConfirmationRecorded;
import convoy.contracts.events.DepositHoldRequested;
import convoy.contracts.events.DepositRefundRequested;
import convoy.contracts.events.OrderCreated;
import convoy.contracts.events.PaymentCaptured;
import convoy.contracts.events.QuoteFinalised;
import convoy.contracts.events.StockReservationRequested;
import convoy.contracts.events.TierResolved;
import convoy.platform.messaging.Outbox;
import convoy.platform.messaging.Uuid7;
import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.security.Caller;
import convoy.platform.statemachine.TransitionTable;
import convoy.price.CanonicalJson;
import convoy.price.PriceEngine;
import convoy.price.Tiers;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order and booking machines (order.yaml, booking.yaml) and tier resolution (tier-resolution.md):
 * confirm at the ceiling; at the confirmation deadline one transaction per pool counts confirmations,
 * resolves each quote's tier (the price can only fall) and issues bookings.
 */
@Service
public class Orders {
    private static final Logger log = LoggerFactory.getLogger(Orders.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final TransitionTable ORDER = TransitionTable.load("order", Set.of("ORD-T01", "ORD-T02", "ORD-T03", "ORD-T04", "ORD-T05", "ORD-T06", "ORD-T07", "ORD-T08", "ORD-T09", "ORD-T10"));
    public static final TransitionTable BOOKING = TransitionTable.load("booking", Set.of("BKG-T01", "BKG-T02", "BKG-T03", "BKG-T04", "BKG-T05", "BKG-T06", "BKG-T07", "BKG-T08"));
    /** Shown on the confirm screen: the buyer's name and email go only to this offer's dealer, and only once a booking is issued. */
    public static final String CONTACT_CONSENT_VERSION = "2026-10-26-p4";
    private final JdbcTemplate jdbc;
    private final Outbox outbox;
    private final ObjectMapper json;
    private final OrderApplication.Rules rules;
    private final Vouchers vouchers;
    private final OrderApplication.OrderSettings settings;

    public Orders(JdbcTemplate jdbc, Outbox outbox, ObjectMapper json, OrderApplication.Rules rules, Vouchers vouchers, OrderApplication.OrderSettings settings) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.json = json;
        this.rules = rules;
        this.vouchers = vouchers;
        this.settings = settings;
    }

    // ------------------------------------------------------------------ replicas

    public void quoteFinalised(QuoteFinalised e) {
        jdbc.update("""
                insert into quote_replica(quote_id, pool_id, dealership_id, state, final_version_no, min_fulfilment, max_units, ruleset_version, tiers, variant_inputs)
                values (?, ?, ?, 'FINAL', ?, ?, ?, ?, ?::jsonb, ?::jsonb) on conflict (quote_id) do nothing""",
                UUID.fromString(e.quoteId()), UUID.fromString(e.poolId()), UUID.fromString(e.dealershipId()), e.finalVersionNo(), e.minFulfilment(),
                e.maxUnits(), e.rulesetVersion(), write(e.tiers()), write(e.variantInputs()));
        jdbc.update("insert into quote_capacity(quote_id, max_units) values (?, ?) on conflict do nothing", UUID.fromString(e.quoteId()), e.maxUnits());
    }

    /** Acceptance read model: the latest acceptance per membership (cleared on withdrawal). */
    public void acceptanceChanged(convoy.contracts.events.AcceptanceChanged e) {
        jdbc.update("insert into acceptance_replica(pool_id, membership_id, quote_id, variant_id) values (?, ?, ?, ?) "
                        + "on conflict (pool_id, membership_id) do update set quote_id = excluded.quote_id, variant_id = excluded.variant_id",
                UUID.fromString(e.poolId()), UUID.fromString(e.membershipId()), e.quoteId() == null ? null : UUID.fromString(e.quoteId()),
                e.variantId() == null ? null : UUID.fromString(e.variantId()));
    }

    public void paymentCaptured(PaymentCaptured e) {
        jdbc.update("insert into payment_replica(buyer_id, pool_id, payment_id, captured_at) values (?, ?, ?, clock_timestamp()) on conflict do nothing",
                UUID.fromString(e.buyerId()), UUID.fromString(e.poolId()), UUID.fromString(e.paymentId()));
    }

    // ------------------------------------------------------------------ ORD-T01

    /** One order per accepting buyer with its CEILING snapshot (the tier containing min_fulfilment). */
    public void acceptancesFrozen(AcceptancesFrozen e) {
        UUID pool = UUID.fromString(e.poolId());
        jdbc.update("insert into pool_resolutions(pool_id, round_set, state, confirmation_deadline) values (?, ?, 'PENDING', ?) on conflict do nothing",
                pool, e.roundSet(), Timestamp.from(e.confirmationDeadline()));
        jdbc.update("insert into sagas(pool_id, round_set, step) values (?, ?, 'FROZEN') on conflict do nothing", pool, e.roundSet()); // P4.B
        for (Map<String, Object> a : e.acceptances()) {
            UUID buyer = UUID.fromString((String) a.get("buyer_id")), quote = UUID.fromString((String) a.get("quote_id")), variant = UUID.fromString((String) a.get("variant_id"));
            if (jdbc.queryForObject("select count(*) from orders where pool_id = ? and buyer_id = ? and round_set = ?", Integer.class, pool, buyer, e.roundSet()) > 0) continue;
            Map<String, Object> q = quote(quote); // throws until QuoteFinalised is consumed; the message is redelivered
            ORDER.check("(none)", "CONFIRMATION_PENDING");
            UUID orderId = Uuid7.next();
            jdbc.update("insert into orders(id, pool_id, round_set, buyer_id, quote_id, variant_id, state, confirmation_deadline) values (?, ?, ?, ?, ?, ?, 'CONFIRMATION_PENDING', ?)",
                    orderId, pool, e.roundSet(), buyer, quote, variant, Timestamp.from(e.confirmationDeadline()));
            List<Tiers.Tier> ladder = ladderOf(q, variant);
            List<String> invalid = Tiers.validate(ladder, ((Number) q.get("min_fulfilment")).longValue(), q.get("max_units") == null ? null : ((Number) q.get("max_units")).longValue());
            if (!invalid.isEmpty()) throw new IllegalStateException("ALARM quote " + quote + " has invalid tiers " + invalid + "; no order created");
            int ceiling = Tiers.tierFor(ladder, ((Number) q.get("min_fulfilment")).longValue());
            Snapshot s = snapshot(orderId, "CEILING", q, variant, ceiling);
            outbox.append(orderId, 1, new OrderCreated(orderId.toString(), pool.toString(), buyer.toString(), quote.toString(), variant.toString(),
                    s.cashPayable(), s.sha256(), e.confirmationDeadline()));
        }
    }

    record Snapshot(UUID id, long cashPayable, String sha256) {}

    Snapshot snapshot(UUID orderId, String kind, Map<String, Object> q, UUID variant, int tierIndex) {
        Map<String, Object> input = new LinkedHashMap<>(variantInput(q, variant));
        long discount = ((Number) tiers(q).get(tierIndex).get("discount_paise")).longValue();
        input.put("discount", Map.of("amount_paise", discount, "base", "EX_SHOWROOM", "evidence", "DEALER_CONFIRMED"));
        Map<String, Object> body = PriceEngine.snapshotBody(input, rules.rules());
        @SuppressWarnings("unchecked") Map<String, Object> out = (Map<String, Object>) body.get("output");
        if (!"OK".equals(out.get("status"))) throw new IllegalStateException("engine rejected a FINAL quote input: " + out.get("errors"));
        String sha = CanonicalJson.sha256(body);
        UUID id = Uuid7.next();
        jdbc.update("insert into price_snapshots(id, order_id, kind, tier_index, inputs, output, ruleset_version, sha256) values (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)",
                id, orderId, kind, tierIndex, CanonicalJson.write(input), CanonicalJson.write(out), input.get("ruleset_version"), sha);
        return new Snapshot(id, ((Number) out.get("cash_payable_paise")).longValue(), sha);
    }

    /** Tier ladder for a variant: each tier's price is the engine's cash payable at that tier's discount. */
    List<Tiers.Tier> ladderOf(Map<String, Object> q, UUID variant) {
        List<Tiers.Tier> ladder = new ArrayList<>();
        for (Map<String, Object> t : tiers(q)) {
            Map<String, Object> input = new LinkedHashMap<>(variantInput(q, variant));
            input.put("discount", Map.of("amount_paise", ((Number) t.get("discount_paise")).longValue(), "base", "EX_SHOWROOM", "evidence", "DEALER_CONFIRMED"));
            Map<String, Object> out = PriceEngine.compute(input, rules.rules());
            ladder.add(new Tiers.Tier(((Number) t.get("min")).longValue(), t.get("max") == null ? null : ((Number) t.get("max")).longValue(),
                    ((Number) out.get("cash_payable_paise")).longValue()));
        }
        return ladder;
    }

    // ------------------------------------------------------------------ ORD-T02 confirm (E082)

    @Transactional
    public Map<String, Object> confirm(UUID orderId, String ceilingHash, long ifMatch) {
        UUID buyer = Caller.current().require("buyer").userId();
        Map<String, Object> o = ownOrder(orderId, buyer, true);
        if ((long) o.get("version") != ifMatch) throw ApiProblem.of(ProblemType.VERSION_CONFLICT, "Your order changed; reload it.").with("current_version", o.get("version"));
        ORDER.check((String) o.get("state"), "CONFIRMED");
        String stored = jdbc.queryForObject("select sha256 from price_snapshots where order_id = ? and kind = 'CEILING'", String.class, orderId);
        if (!stored.equals(ceilingHash)) throw ApiProblem.of(ProblemType.VERSION_CONFLICT, "The price you saw is not the current ceiling price. Reload before confirming.");
        if (jdbc.queryForObject("select count(*) from payment_replica where buyer_id = ? and pool_id = ?", Integer.class, buyer, o.get("pool_id")) == 0) {
            log.error("ALARM confirm without a captured-deposit replica: order {}", orderId);
            throw ApiProblem.of(ProblemType.DEPOSIT_NOT_CAPTURED, "Your deposit has not been confirmed yet.");
        }
        Map<String, Object> cap = jdbc.queryForMap("select max_units, confirmed_count from quote_capacity where quote_id = ? for update", o.get("quote_id"));
        if (cap.get("max_units") != null && (int) cap.get("confirmed_count") >= (int) cap.get("max_units")) {
            throw ApiProblem.of(ProblemType.CAPACITY_REACHED, "This dealer's offer has no units left.");
        }
        // Deadline check last, on the DB clock (deadline-rule.md).
        List<Map<String, Object>> ok = jdbc.queryForList("update orders o set state = 'CONFIRMED', confirmed_at = clock_timestamp(), version = version + 1, "
                + "ceiling_snapshot_id = s.id, confirmed_ceiling_paise = (s.output->>'cash_payable_paise')::bigint from price_snapshots s "
                + "where o.id = ? and o.version = ? and clock_timestamp() < o.confirmation_deadline and s.order_id = o.id and s.kind = 'CEILING' "
                + "returning o.confirmed_at, o.version", orderId, ifMatch);
        if (ok.isEmpty()) {
            throw ApiProblem.of(ProblemType.DEADLINE_PASSED, "The confirmation deadline passed before your confirmation reached our database.")
                    .with("deadline", ((Timestamp) o.get("confirmation_deadline")).toInstant().toString())
                    .with("server_time", jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant().toString());
        }
        int count = jdbc.queryForObject("update quote_capacity set confirmed_count = confirmed_count + 1 where quote_id = ? returning confirmed_count", Integer.class, o.get("quote_id"));
        jdbc.update("insert into contact_consents(order_id, buyer_id, dealership_id, terms_version) select ?, ?, dealership_id, ? from quote_replica where quote_id = ? on conflict do nothing",
                orderId, buyer, CONTACT_CONSENT_VERSION, o.get("quote_id"));
        outbox.append(orderId, ((Number) ok.get(0).get("version")).longValue(), new ConfirmationRecorded(orderId.toString(), o.get("pool_id").toString(),
                o.get("quote_id").toString(), ((Timestamp) ok.get(0).get("confirmed_at")).toInstant(), (long) count));
        return view(orderId, buyer);
    }

    // ------------------------------------------------------------------ resolution: ORD-T05/T06/T07 + BKG-T01, one transaction per pool

    @Transactional
    public void resolve(UUID pool, int roundSet) {
        List<Map<String, Object>> rs = jdbc.queryForList("select *, clock_timestamp() as db_now, "
                + "to_char(confirmation_deadline at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') as deadline_text "
                + "from pool_resolutions where pool_id = ? and round_set = ? for update", pool, roundSet);
        if (rs.isEmpty() || !"PENDING".equals(rs.get(0).get("state"))) return;
        Timestamp deadline = (Timestamp) rs.get(0).get("confirmation_deadline");
        if (((Timestamp) rs.get(0).get("db_now")).before(deadline)) return;
        String dl = deadline.toInstant().toString();

        for (Map<String, Object> o : jdbc.queryForList("select id, buyer_id, version from orders where pool_id = ? and round_set = ? and state = 'CONFIRMATION_PENDING'", pool, roundSet)) {
            ORDER.check("CONFIRMATION_PENDING", "DECLINED");
            jdbc.update("update orders set state = 'DECLINED', decline_reason = 'MISSED_CONFIRMATION_DEADLINE', version = version + 1 where id = ?", o.get("id"));
            long v = ((Number) o.get("version")).longValue() + 1;
            outbox.append((UUID) o.get("id"), v, new ConfirmationDeclined(o.get("id").toString(), pool.toString(), "MISSED_CONFIRMATION_DEADLINE"));
            outbox.append((UUID) o.get("id"), bump((UUID) o.get("id")), new DepositRefundRequested(o.get("id").toString(), pool.toString(), o.get("buyer_id").toString(), "MISSED_CONFIRMATION_DEADLINE"));
        }

        int bookings = 0, voidQuotes = 0;
        List<String> voidBuyers = new ArrayList<>();
        List<Map<String, Object>> voided = new ArrayList<>();
        long rv = ((Number) rs.get(0).get("version")).longValue() * 1000; // one version per resolution event (aggregate = pool resolution)
        // Every accepted quote resolves, including one nobody confirmed (count 0 -> VOID, tiers.json T10/T30).
        for (UUID quote : jdbc.queryForList("select distinct quote_id from orders where pool_id = ? and round_set = ?", UUID.class, pool, roundSet)) {
            Map<String, Object> q = quote(quote);
            List<Map<String, Object>> confirmed = jdbc.queryForList("""
                    select o.id, o.buyer_id, o.variant_id, o.version, to_char(o.confirmed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') as confirmed_at,
                           to_char(o.cancelled_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') as cancelled_at,
                           (select to_char(min(p.captured_at) at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') from payment_replica p where p.buyer_id = o.buyer_id and p.pool_id = o.pool_id) as captured_at
                    from orders o where o.quote_id = ? and o.round_set = ? and o.state = 'CONFIRMED' for update""", quote, roundSet);
            List<Tiers.Confirmation> cs = confirmed.stream().map(c -> new Tiers.Confirmation((String) c.get("confirmed_at"), (String) c.get("captured_at"),
                    (String) c.get("cancelled_at"))).toList();
            String dlText = (String) rs.get(0).get("deadline_text"); // same text format on both sides
            long minF = ((Number) q.get("min_fulfilment")).longValue();
            Long maxUnits = q.get("max_units") == null ? null : ((Number) q.get("max_units")).longValue();
            // Not counted (confirmed or captured at/after the deadline; the confirm API refuses both, so this is defensive) -> VOIDED, refunded.
            List<Map<String, Object>> counted = new ArrayList<>();
            for (int i = 0; i < confirmed.size(); i++) {
                if (Tiers.countConfirmed(List.of(cs.get(i)), dlText) == 1) counted.add(confirmed.get(i));
                else uncounted(confirmed.get(i), pool, "MISSED_CONFIRMATION_DEADLINE");
            }
            counted.sort(java.util.Comparator.comparing(c -> (String) c.get("confirmed_at")));
            while (maxUnits != null && counted.size() > maxUnits) { // Q-012: earlier confirmations first; capacity is enforced at confirm, so this is defensive
                log.error("ALARM quote {} has {} counted confirmations over max_units {}", quote, counted.size(), maxUnits);
                uncounted(counted.remove(counted.size() - 1), pool, "QUOTE_VOID");
            }
            confirmed = counted;
            long n = counted.size();
            List<Tiers.Tier> ladder = ladderOf(q, (UUID) (confirmed.isEmpty() ? jdbc.queryForObject("select variant_id from orders where quote_id = ? limit 1", UUID.class, quote)
                    : confirmed.get(0).get("variant_id")));
            Map<String, Object> res = Tiers.resolve(ladder, minF, n, maxUnits);
            if (!"OK".equals(res.get("status"))) throw new IllegalStateException("ALARM quote " + quote + " has invalid tiers " + res.get("errors"));
            if ("VOID".equals(res.get("outcome"))) {
                voidQuotes++;
                outbox.append(pool, ++rv, new TierResolved(pool.toString(), quote.toString(), n, minF, "VOID", null, null));
                for (Map<String, Object> c : confirmed) {
                    ORDER.check("CONFIRMED", "VOIDED");
                    jdbc.update("update orders set state = 'VOIDED', version = version + 1 where id = ?", c.get("id"));
                    voidBuyers.add(c.get("buyer_id").toString());
                    voided.add(c);
                }
                continue;
            }
            int tier = ((Number) res.get("resolved_tier")).intValue();
            outbox.append(pool, ++rv, new TierResolved(pool.toString(), quote.toString(), n, minF, "RESOLVED", (long) tier, ladder.get(tier).pricePaise()));
            for (Map<String, Object> c : confirmed) {
                ORDER.check("CONFIRMED", "BOOKED");
                BOOKING.check("(none)", "ISSUED");
                UUID orderId = (UUID) c.get("id"), variant = (UUID) c.get("variant_id");
                Snapshot fin = snapshot(orderId, "FINAL", q, variant, tier);
                long ceilingCash = ((Number) jdbc.queryForObject("select (output->>'cash_payable_paise')::bigint from price_snapshots where order_id = ? and kind = 'CEILING'",
                        Long.class, orderId)).longValue();
                if (fin.cashPayable() > ceilingCash) throw new IllegalStateException("final price above ceiling for order " + orderId); // the price can only fall
                UUID bookingId = Uuid7.next();
                String code = voucherCode();
                LocalDate promised = promisedDate(q, variant);
                Timestamp confirmBy = jdbc.queryForObject("select clock_timestamp() + ?::interval", Timestamp.class, settings.dealerConfirmWindow().toMillis() + " milliseconds");
                jdbc.update("""
                        insert into bookings(id, order_id, dealership_id, state, voucher_code_hash, voucher_token, final_snapshot_id, promised_delivery_date, dealer_confirm_deadline)
                        values (?, ?, ?, 'ISSUED', ?, ?, ?, ?, ?)""", bookingId, orderId, q.get("dealership_id"), CanonicalJson.sha256(code),
                        vouchers.sign(bookingId, code, confirmBy.toInstant().plusSeconds(180L * 24 * 3600)), fin.id(), promised, confirmBy);
                jdbc.update("update orders set state = 'BOOKED', version = version + 1 where id = ?", orderId);
                for (String step : List.of("HOLD_DEPOSIT", "RESERVE_STOCK")) {
                    jdbc.update("insert into saga_steps(order_id, step, status, next_attempt_at) values (?, ?, 'PENDING', clock_timestamp() + ?::interval)",
                            orderId, step, settings.sagaStepTimeout().toMillis() + " milliseconds");
                }
                long v = ((Number) c.get("version")).longValue() + 1; // the order row moved once (CONFIRMED -> BOOKED); a later event must outrank this one
                outbox.append(bookingId, 1, new BookingIssued(bookingId.toString(), orderId.toString(), pool.toString(), q.get("dealership_id").toString(),
                        variant.toString(), fin.cashPayable(), promised, confirmBy.toInstant()));
                outbox.append(orderId, v, new DepositHoldRequested(orderId.toString(), pool.toString(), c.get("buyer_id").toString(), bookingId.toString()));
                outbox.append(orderId, bump(orderId), new StockReservationRequested(orderId.toString(), bookingId.toString(), quote.toString(), variant.toString(), colour(q, variant)));
                bookings++;
            }
        }
        if (bookings > 0) {
            // No re-round is possible once any booking exists: VOID buyers are refunded now (AMENDMENT-002).
            for (Map<String, Object> c : voided) {
                outbox.append((UUID) c.get("id"), ((Number) c.get("version")).longValue() + 1,
                        new DepositRefundRequested(c.get("id").toString(), pool.toString(), c.get("buyer_id").toString(), "QUOTE_VOID"));
            }
        }
        jdbc.update("update pool_resolutions set state = 'DONE', resolved_at = clock_timestamp(), bookings_count = ?, void_quote_count = ?, version = version + 1 "
                + "where pool_id = ? and round_set = ?", bookings, voidQuotes, pool, roundSet);
        outbox.append(pool, ++rv, new BookingsIssued(pool.toString(), (long) roundSet, (long) bookings, (long) voidQuotes, voidBuyers));
        log.info("pool {} resolved at {}: {} bookings, {} void quotes", pool, dl, bookings, voidQuotes);
    }

    private void uncounted(Map<String, Object> c, UUID pool, String reason) {
        ORDER.check("CONFIRMED", "VOIDED");
        jdbc.update("update orders set state = 'VOIDED', cancel_reason = ?, version = version + 1 where id = ?", reason, c.get("id"));
        outbox.append((UUID) c.get("id"), ((Number) c.get("version")).longValue() + 1, new DepositRefundRequested(c.get("id").toString(), pool.toString(), c.get("buyer_id").toString(), reason));
    }

    // ------------------------------------------------------------------ saga steps

    public void stepDone(UUID bookingId, String step) {
        jdbc.update("update saga_steps set status = 'DONE' where step = ? and order_id = (select order_id from bookings where id = ?)", step, bookingId);
    }

    // ------------------------------------------------------------------ BKG-T02 dealer confirm (E088)

    @Transactional
    public Map<String, Object> dealerConfirm(UUID bookingId, long ifMatch) {
        Caller c = Caller.current().require("dealer_staff", "dealer_admin");
        List<Map<String, Object>> rs = jdbc.queryForList("select *, clock_timestamp() as db_now from bookings where id = ? and dealership_id = ? for update",
                bookingId, c.dealershipId() == null ? null : UUID.fromString(c.dealershipId()));
        if (rs.isEmpty()) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such booking.");
        Map<String, Object> b = rs.get(0);
        if ((long) b.get("version") != ifMatch) throw ApiProblem.of(ProblemType.VERSION_CONFLICT, "The booking changed; reload it.").with("current_version", b.get("version"));
        BOOKING.check((String) b.get("state"), "DEALER_CONFIRMED");
        if (!((Timestamp) b.get("db_now")).before((Timestamp) b.get("dealer_confirm_deadline"))) {
            throw ApiProblem.of(ProblemType.DEADLINE_PASSED, "The 48-hour confirmation window has passed.")
                    .with("deadline", ((Timestamp) b.get("dealer_confirm_deadline")).toInstant().toString());
        }
        String stock = jdbc.queryForObject("select status from saga_steps where order_id = ? and step = 'RESERVE_STOCK'", String.class, b.get("order_id"));
        if (!"DONE".equals(stock)) throw ApiProblem.of(ProblemType.INVALID_TRANSITION, "Stock reservation is still in progress; try again shortly.");
        jdbc.update("update bookings set state = 'DEALER_CONFIRMED', version = version + 1 where id = ?", bookingId);
        outbox.append(bookingId, (long) b.get("version") + 1, new BookingConfirmedByDealer(bookingId.toString(), c.dealershipId()));
        return dealerBooking(bookingId);
    }

    // ------------------------------------------------------------------ views

    Map<String, Object> ownOrder(UUID orderId, UUID buyer, boolean lock) {
        List<Map<String, Object>> rs = jdbc.queryForList("select * from orders where id = ? and buyer_id = ?" + (lock ? " for update" : ""), orderId, buyer);
        if (rs.isEmpty()) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such order.");
        return rs.get(0);
    }

    public Map<String, Object> view(UUID orderId, UUID buyer) {
        Map<String, Object> o = iso(ownOrder(orderId, buyer, false));
        Map<String, Object> snaps = new HashMap<>();
        for (Map<String, Object> s : jdbc.queryForList("select kind, tier_index, output::text as output, sha256, computed_at from price_snapshots where order_id = ?", orderId)) {
            snaps.put(((String) s.get("kind")).toLowerCase(), Map.of("tier_index", s.get("tier_index"), "output", read((String) s.get("output")), "sha256", s.get("sha256"),
                    "computed_at", ((Timestamp) s.get("computed_at")).toInstant().toString()));
        }
        o.put("snapshots", snaps);
        List<Map<String, Object>> b = jdbc.queryForList("select id, state, version, promised_delivery_date::text as promised_delivery_date, dealer_confirm_deadline, dealership_id, "
                + "voucher_active, scheduled_date::text as scheduled_date, delivery_slot, delivered_at, overdue_flag, cancel_reason, cancelled_at, "
                + "(select released_at from contact_releases r where r.booking_id = b.id) as contact_released_at from bookings b where order_id = ?", orderId);
        o.put("booking", b.isEmpty() ? null : iso(b.get(0)));
        o.put("disputes", jdbc.queryForList("select id, category, state, opened_by, created_at from disputes where order_id = ? order by created_at", orderId).stream().map(Orders::iso).toList());
        o.put("dealership_id", jdbc.queryForObject("select dealership_id::text from quote_replica where quote_id = ?::uuid", String.class, o.get("quote_id")));
        o.put("server_time", jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant().toString());
        return o;
    }

    public Map<String, Object> voucher(UUID orderId, UUID buyer) {
        ownOrder(orderId, buyer, false);
        List<Map<String, Object>> b = jdbc.queryForList("select id, state, voucher_token, promised_delivery_date::text as promised_delivery_date, dealership_id from bookings where order_id = ?", orderId);
        if (b.isEmpty() || !jdbc.queryForObject("select voucher_active from bookings where order_id = ?", Boolean.class, orderId)) {
            throw ApiProblem.of(ProblemType.INVALID_TRANSITION, "The voucher appears once your deposit is held and the dealer's stock is reserved (usually seconds).");
        }
        Map<String, Object> out = iso(b.get(0));
        out.put("qr_payload", out.remove("voucher_token"));
        out.put("verification", "ES256-signed token; the dealer scans it at handover (booking.yaml).");
        return out;
    }

    public Map<String, Object> dealerBooking(UUID bookingId) {
        return iso(jdbc.queryForMap("select b.id, b.order_id, b.state, b.version, b.promised_delivery_date::text as promised_delivery_date, b.dealer_confirm_deadline, "
                + "b.scheduled_date::text as scheduled_date, b.delivery_slot, b.delivered_at, b.overdue_flag, b.cancel_reason, "
                + "exists (select 1 from contact_releases r where r.booking_id = b.id) as contact_released, "
                + "o.variant_id, (s.output->>'cash_payable_paise')::bigint as final_cash_payable_paise from bookings b join orders o on o.id = b.order_id "
                + "join price_snapshots s on s.id = b.final_snapshot_id where b.id = ?", bookingId));
    }

    Map<String, Object> quote(UUID quoteId) {
        List<Map<String, Object>> rs = jdbc.queryForList("select * from quote_replica where quote_id = ?", quoteId);
        if (rs.isEmpty()) throw new IllegalStateException("quote " + quoteId + " not replicated yet");
        return rs.get(0);
    }

    List<Map<String, Object>> tiers(Map<String, Object> q) { return readList(q.get("tiers").toString()); }

    Map<String, Object> variantInput(Map<String, Object> q, UUID variant) {
        for (Map<String, Object> vi : readList(q.get("variant_inputs").toString())) {
            if (variant.toString().equals(vi.get("variant_id"))) {
                @SuppressWarnings("unchecked") Map<String, Object> in = (Map<String, Object>) vi.get("input");
                return in;
            }
        }
        throw new IllegalStateException("variant " + variant + " not in quote " + q.get("quote_id"));
    }

    @SuppressWarnings("unchecked")
    LocalDate promisedDate(Map<String, Object> q, UUID variant) {
        for (Map<String, Object> vi : readList(q.get("variant_inputs").toString())) {
            if (variant.toString().equals(vi.get("variant_id"))) {
                Object d = ((Map<String, Object>) vi.get("stock")).get("delivery_date");
                return d == null ? null : LocalDate.parse(d.toString());
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    String colour(Map<String, Object> q, UUID variant) {
        for (Map<String, Object> vi : readList(q.get("variant_inputs").toString())) {
            if (variant.toString().equals(vi.get("variant_id"))) {
                List<Object> colours = (List<Object>) ((Map<String, Object>) vi.get("stock")).get("colours");
                return colours == null || colours.isEmpty() ? "ANY" : String.valueOf(colours.get(0));
            }
        }
        return "ANY";
    }

    static String voucherCode() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static Map<String, Object> iso(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> out.put(k, v instanceof Timestamp t ? t.toInstant().toString() : v instanceof UUID u ? u.toString() : v));
        return out;
    }

    List<Map<String, Object>> readList(String s) {
        try {
            return json.readValue(s, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    Map<String, Object> read(String s) {
        try {
            return json.readValue(s, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    JdbcTemplate jdbc() { return jdbc; }

    /** Every order event has its own version (P5.0 guard: the outbox refuses a reused one): a second event moves the row on. */
    long bump(UUID orderId) { return jdbc.queryForObject("update orders set version = version + 1 where id = ? returning version", Long.class, orderId); }

    static Instant now() { return Instant.now(); }
}
