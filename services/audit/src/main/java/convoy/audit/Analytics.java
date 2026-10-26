package convoy.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.platform.security.Caller;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * E169 (P5.E): the marketplace metrics of docs/product/metrics.md, read from audit_log (every event of every service).
 * Counts, not percentages: the page divides, so every figure shows its sample size. Savings follow savings-model.md:
 * four labels, each its own distribution in integer paise (nearest-rank percentiles), never summed, every item naming its
 * ruleset and snapshot hashes (AMENDMENT-013). {@code simulated} is true while the stack runs on synthetic dealers and buyers.
 * ponytail: computed on every read; materialise into marketplace_metrics_daily once audit_log reaches millions of rows.
 */
@RestController
public class Analytics {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final boolean simulated;

    public Analytics(JdbcTemplate jdbc, @Value("${audit.analytics.simulated:true}") boolean simulated) {
        this.jdbc = jdbc;
        this.simulated = simulated;
    }

    @GetMapping("/admin/analytics")
    public Map<String, Object> analytics() {
        Caller.current().require("platform_admin");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("simulated", simulated);
        out.put("generated_at", jdbc.queryForObject("select clock_timestamp()::text", String.class));
        // Fill rate and time to fill, by creation week.
        out.put("fill", jdbc.queryForList("""
                with c as (select payload->>'pool_id' pool, occurred_at created from audit_log where event_type = 'PoolCreated'),
                     r as (select payload->>'pool_id' pool, min(occurred_at) ready from audit_log where event_type = 'PoolReady' group by 1)
                select date_trunc('week', c.created)::date::text as week, count(*) as created, count(r.ready) as filled,
                       round((percentile_cont(0.5) within group (order by extract(epoch from r.ready - c.created) / 3600.0))::numeric, 1)::float8 as median_hours_to_fill
                from c left join r using (pool) group by 1 order by 1"""));
        // Invited dealerships that submitted a version before the sealed deadline.
        out.put("dealer_response", jdbc.queryForMap("""
                with inv as (select a.payload->>'pool_id' pool, a.payload->>'round_id' round, a.occurred_at opened, (a.payload->>'deadline')::timestamptz deadline, d #>> '{}' dealership
                             from audit_log a, jsonb_array_elements(a.payload->'invited_dealership_ids') d
                             where a.event_type = 'QuoteRoundOpened' and a.payload->>'kind' = 'SEALED'),
                     sub as (select payload->>'pool_id' pool, payload->>'dealership_id' dealership, (payload->>'committed_at')::timestamptz at
                             from audit_log where event_type in ('QuoteSubmitted', 'QuoteRevised'))
                select count(distinct round) as rounds, count(*) as invited,
                       count(*) filter (where exists (select 1 from sub s where s.pool = i.pool and s.dealership = i.dealership and s.at >= i.opened and s.at < i.deadline)) as responded
                from inv i"""));
        // Pools whose final round ended with >= 2 FINAL quotes, of pools that reached QUOTE_OPEN.
        out.put("pools_with_two_valid_quotes", jdbc.queryForMap("""
                with o as (select distinct payload->>'pool_id' pool from audit_log where event_type = 'QuoteRoundOpened'),
                     f as (select payload->>'pool_id' pool, max((payload->>'valid_quote_count')::int) n from audit_log
                           where event_type = 'RoundClosed' and payload->>'kind' = 'FINAL' group by 1)
                select count(*) as quote_open, count(*) filter (where f.n >= 2) as two_or_more from o left join f using (pool)"""));
        // Orders created whose confirmation deadline has passed, and those confirmed.
        out.put("acceptance_to_confirmation", jdbc.queryForMap("""
                select count(*) as orders, count(*) filter (where exists (select 1 from audit_log c where c.event_type = 'ConfirmationRecorded'
                       and c.payload->>'order_id' = o.payload->>'order_id')) as confirmed
                from audit_log o where o.event_type = 'OrderCreated' and (o.payload->>'confirmation_deadline')::timestamptz < clock_timestamp()"""));
        // Bookings whose cohort is closed (delivered, cancelled, or promised date + 30 days passed), and those delivered.
        out.put("confirmation_to_delivery", jdbc.queryForMap("""
                with b as (select payload->>'booking_id' id, (payload->>'promised_delivery_date')::date promised from audit_log where event_type = 'BookingIssued'),
                     d as (select distinct payload->>'booking_id' id from audit_log where event_type = 'BookingDelivered'),
                     x as (select distinct payload->>'booking_id' id from audit_log where event_type = 'BookingCancelled')
                select count(*) as closed_bookings, count(d.id) as delivered from b left join d using (id) left join x using (id)
                where d.id is not null or x.id is not null or b.promised + 30 < current_date"""));
        Map<String, Object> cancel = new LinkedHashMap<>(jdbc.queryForMap("""
                select (select count(*) from audit_log where event_type = 'BookingIssued') as issued,
                       (select count(*) from audit_log where event_type = 'BookingCancelled') as cancelled"""));
        Map<String, Object> byReason = new LinkedHashMap<>();
        for (String g : List.of("buyer", "dealer", "stock", "admin", "other")) byReason.put(g, 0L);
        jdbc.query("""
                select case payload->>'reason' when 'BUYER_CANCELLED' then 'buyer' when 'DEALER_CANCELLED' then 'dealer' when 'DEALER_NO_CONFIRM' then 'dealer'
                       when 'STOCK_UNAVAILABLE' then 'stock' when 'ADMIN_DISPUTE_RESOLUTION' then 'admin' else 'other' end g, count(*) n
                from audit_log where event_type = 'BookingCancelled' group by 1""", rs -> { byReason.put(rs.getString("g"), rs.getLong("n")); });
        cancel.put("by_reason", byReason);
        out.put("cancellation", cancel);
        // Quotes VOID at tier resolution, of quotes with at least one acceptance (an order).
        out.put("void_rate", jdbc.queryForMap("""
                select (select count(distinct payload->>'quote_id') from audit_log where event_type = 'OrderCreated') as accepted_quotes,
                       (select count(distinct payload->>'quote_id') from audit_log where event_type = 'TierResolved' and payload->>'outcome' = 'VOID') as void_quotes"""));
        out.put("refund_latency_hours", durations("RefundRequested", "RefundCompleted", "refund_id", 3600.0));
        out.put("dispute_resolution_days", durations("DisputeOpened", "DisputeResolved", "dispute_id", 86400.0));
        out.put("savings", savings());
        return out;
    }

    /** n, p50 and p95 (continuous) of end - start per id, in the given unit; open ones are not counted. */
    Map<String, Object> durations(String start, String end, String key, double unitSeconds) {
        return jdbc.queryForMap("""
                with s as (select payload->>'%2$s' id, min(occurred_at) t from audit_log where event_type = '%1$s' group by 1),
                     e as (select payload->>'%2$s' id, min(occurred_at) t from audit_log where event_type = '%3$s' group by 1),
                     d as (select extract(epoch from e.t - s.t) / ? x from s join e using (id))
                select count(*) as n, round((percentile_cont(0.5) within group (order by x))::numeric, 2)::float8 as p50,
                       round((percentile_cont(0.95) within group (order by x))::numeric, 2)::float8 as p95 from d""".formatted(start, key, end), unitSeconds);
    }

    /**
     * E058 (dealer_admin): the caller's own dealership only, taken from the session, never from the request, so no input can
     * name another dealership (AZ-T5). Response = invited sealed rounds answered before the deadline; win = FINAL quotes that got
     * at least one booking; reliability = the latest ReliabilityScoreChanged score (AMENDMENT-009), null before any outcome.
     */
    @GetMapping("/dealer/analytics")
    public Map<String, Object> dealerAnalytics() {
        Caller c = Caller.current().require("dealer_admin");
        if (c.dealershipId() == null) throw convoy.platform.problem.ApiProblem.of(convoy.platform.problem.ProblemType.FORBIDDEN, "Your account is not linked to a dealership.");
        String d = c.dealershipId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dealership_id", d);
        out.put("simulated", simulated);
        out.put("response", jdbc.queryForMap("""
                with inv as (select a.payload->>'pool_id' pool, a.occurred_at opened, (a.payload->>'deadline')::timestamptz deadline
                             from audit_log a where a.event_type = 'QuoteRoundOpened' and a.payload->>'kind' = 'SEALED' and a.payload->'invited_dealership_ids' @> jsonb_build_array(?::text))
                select count(*) as invited, count(*) filter (where exists (select 1 from audit_log s where s.event_type in ('QuoteSubmitted', 'QuoteRevised')
                       and s.payload->>'pool_id' = i.pool and s.payload->>'dealership_id' = ? and (s.payload->>'committed_at')::timestamptz >= i.opened
                       and (s.payload->>'committed_at')::timestamptz < i.deadline)) as responded from inv i""", d, d));
        out.put("win", jdbc.queryForMap("""
                select count(distinct f.payload->>'quote_id') as final_quotes,
                       count(distinct f.payload->>'quote_id') filter (where exists (select 1 from audit_log o join audit_log b on b.event_type = 'BookingIssued'
                           and b.payload->>'order_id' = o.payload->>'order_id' where o.event_type = 'OrderCreated' and o.payload->>'quote_id' = f.payload->>'quote_id')) as won
                from audit_log f where f.event_type = 'QuoteFinalised' and f.payload->>'dealership_id' = ?""", d));
        List<Long> score = jdbc.queryForList("select (payload->>'score')::bigint from audit_log where event_type = 'ReliabilityScoreChanged' and payload->>'dealership_id' = ? "
                + "order by occurred_at desc, seq desc limit 1", Long.class, d);
        out.put("reliability_score_percent", score.isEmpty() ? null : score.get(0));
        return out;
    }

    record Fig(String pool, String quote, String dealer, String rules, String variant, long roundSet, long ceiling, long discount, String ceilingSha,
               Long estimate, String estimateSha) {}

    Map<String, Object> savings() {
        List<Fig> figs = new ArrayList<>();
        jdbc.query("select payload::text from audit_log where event_type = 'QuoteFinalised'", rs -> {
            JsonNode p = read(rs.getString(1));
            for (JsonNode f : p.path("figures")) {
                figs.add(new Fig(p.path("pool_id").asText(), p.path("quote_id").asText(), p.path("dealership_id").asText(), p.path("ruleset_version").asText(),
                        f.path("variant_id").asText(), f.path("round_set").asLong(), f.path("ceiling_cash_payable_paise").asLong(), f.path("ceiling_discount_paise").asLong(),
                        f.path("ceiling_snapshot_sha256").asText(), f.path("catalogue_estimate_cash_payable_paise").isNumber() ? f.path("catalogue_estimate_cash_payable_paise").asLong() : null,
                        f.path("catalogue_snapshot_sha256").isTextual() ? f.path("catalogue_snapshot_sha256").asText() : null));
            }
        });
        List<Map<String, Object>> l1 = new ArrayList<>(), l2 = new ArrayList<>(), l3 = new ArrayList<>(), l4 = new ArrayList<>();
        for (Fig f : figs) {
            l1.add(item(f, f.discount(), List.of(f.ceilingSha())));
            if (f.estimate() != null) l2.add(item(f, f.estimate() - f.ceiling(), List.of(f.estimateSha(), f.ceilingSha())));
        }
        // Label 3: against the lowest ceiling among OTHER dealers' FINAL quotes for the same pool, round set and variant.
        Map<String, List<Fig>> same = figs.stream().collect(Collectors.groupingBy(f -> f.pool() + "|" + f.roundSet() + "|" + f.variant()));
        for (Fig f : figs) {
            same.get(f.pool() + "|" + f.roundSet() + "|" + f.variant()).stream().filter(o -> !o.dealer().equals(f.dealer())).min(Comparator.comparingLong(Fig::ceiling))
                    .ifPresent(best -> l3.add(item(f, best.ceiling() - f.ceiling(), List.of(f.ceilingSha(), best.ceilingSha()))));
        }
        // Label 4: only from a buyer-confirmed invoice; the booking names its order (-> quote) and variant.
        Map<String, Fig> byQuoteVariant = figs.stream().collect(Collectors.toMap(f -> f.quote() + "|" + f.variant(), f -> f, (a, b) -> a));
        jdbc.query("""
                select o.payload->>'quote_id' quote, b.payload->>'variant_id' variant, (i.payload->>'invoice_total_paise')::bigint total
                from audit_log i join audit_log b on b.event_type = 'BookingIssued' and b.payload->>'booking_id' = i.payload->>'booking_id'
                join audit_log o on o.event_type = 'OrderCreated' and o.payload->>'order_id' = b.payload->>'order_id'
                where i.event_type = 'InvoiceConfirmed'""", rs -> {
            Fig f = byQuoteVariant.get(rs.getString("quote") + "|" + rs.getString("variant"));
            if (f != null && f.estimate() != null) l4.add(item(f, f.estimate() - rs.getLong("total"), List.of(f.estimateSha())));
        });
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("dealer_stated_discount", dist(l1));
        s.put("vs_catalogue_estimate", dist(l2));
        s.put("vs_best_other_quote", dist(l3));
        s.put("realised", dist(l4));
        return s;
    }

    static Map<String, Object> item(Fig f, long value, List<String> shas) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pool_id", f.pool());
        m.put("quote_id", f.quote());
        m.put("variant_id", f.variant());
        m.put("value_paise", value);
        m.put("ruleset_version", f.rules());
        m.put("snapshot_sha256", shas);
        return m;
    }

    /** Nearest-rank percentiles over integer paise; null with no sample. */
    static Map<String, Object> dist(List<Map<String, Object>> items) {
        List<Long> v = items.stream().map(i -> (Long) i.get("value_paise")).sorted().toList();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("n", v.size());
        d.put("p25_paise", rank(v, 0.25));
        d.put("median_paise", rank(v, 0.5));
        d.put("p75_paise", rank(v, 0.75));
        d.put("items", items);
        return d;
    }

    static Long rank(List<Long> sorted, double p) { return sorted.isEmpty() ? null : sorted.get((int) Math.ceil(p * sorted.size()) - 1); }

    static JsonNode read(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
