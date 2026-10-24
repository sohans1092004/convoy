package convoy.quote;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.contracts.events.AcceptanceChanged;
import convoy.contracts.events.AcceptancesFrozen;
import convoy.contracts.events.ConfirmationRecorded;
import convoy.contracts.events.FinalRoundOpened;
import convoy.contracts.events.MemberCommitted;
import convoy.contracts.events.MemberLeft;
import convoy.contracts.events.QuoteRoundOpened;
import convoy.contracts.events.RoundClosed;
import convoy.contracts.events.StockReservationRequested;
import convoy.platform.idempotency.Idempotent;
import convoy.platform.messaging.Subscriptions;
import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.security.Caller;
import convoy.price.Tiers;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class QuoteApplication {
    public static void main(String[] args) { SpringApplication.run(QuoteApplication.class, args); }

    public record Rules(Map<String, Object> rules) {}

    @Bean
    @SuppressWarnings("unchecked")
    static Rules rules() throws Exception {
        try (InputStream in = QuoteApplication.class.getResourceAsStream("/convoy/rules.json")) {
            return new Rules(new ObjectMapper().enable(DeserializationFeature.USE_LONG_FOR_INTS).readValue(in, Map.class));
        }
    }

    @Bean
    static Subscriptions subscriptions(Quotes q) {
        return new Subscriptions()
                .on(QuoteRoundOpened.class, Subscriptions.Ordering.NONE, (e, p) -> q.roundOpened(p))
                .on(FinalRoundOpened.class, Subscriptions.Ordering.NONE, (e, p) -> q.roundOpened(new QuoteRoundOpened(p.poolId(), p.roundId(), p.roundSet(),
                        p.roundNo(), p.kind(), p.deadline(), p.invitedDealershipIds(), p.acceptedVariantIds(), p.committedCount(), p.stateCode(), p.variants())))
                .on(RoundClosed.class, Subscriptions.Ordering.NONE, (e, p) -> q.roundClosed(p))
                .on(MemberCommitted.class, Subscriptions.Ordering.NONE, (e, p) -> q.memberCommitted(p))
                .on(MemberLeft.class, Subscriptions.Ordering.NONE, (e, p) -> q.memberLeft(p))
                .on(AcceptanceChanged.class, Subscriptions.Ordering.MONOTONIC, (e, p) -> q.acceptanceChanged(p))
                .on(AcceptancesFrozen.class, Subscriptions.Ordering.NONE, (e, p) -> q.acceptancesFrozen(p))
                .on(ConfirmationRecorded.class, Subscriptions.Ordering.NONE, (e, p) -> q.confirmationRecorded(p))
                .on(StockReservationRequested.class, Subscriptions.Ordering.NONE, (e, p) -> q.reserveStock(p));
    }

    private final Quotes quotes;

    public QuoteApplication(Quotes quotes) { this.quotes = quotes; }

    // ------------------------------------------------------------------ dealer (E060-E063, E067)

    @GetMapping("/dealer/inbox")
    public Map<String, Object> inbox() { return quotes.inbox(); }

    @PostMapping("/dealer/pools/{poolId}/quotes")
    @Idempotent
    public ResponseEntity<Map<String, Object>> create(@PathVariable UUID poolId) {
        return ResponseEntity.status(201).body(quotes.createDraft(poolId));
    }

    @PutMapping("/dealer/quotes/{id}/draft")
    public Map<String, Object> draft(@PathVariable UUID id, @RequestBody Map<String, Object> draft, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return quotes.saveDraft(id, draft, version(ifMatch));
    }

    @PostMapping("/dealer/quotes/{id}/submit")
    @Idempotent
    public Map<String, Object> submit(@PathVariable UUID id, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return quotes.submit(id, version(ifMatch));
    }

    @GetMapping("/dealer/quotes/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable UUID id) {
        Map<String, Object> q = quotes.dealerView(id, Quotes.dealership(Caller.current()));
        return ResponseEntity.ok().eTag("\"" + q.get("version") + "\"").body(q);
    }

    @PostMapping({"/dealer/quotes/{id}/revisions", "/dealer/quotes/{id}/withdraw", "/dealer/quotes/{id}/cancel"})
    public void notBuilt(@PathVariable UUID id) {
        throw ApiProblem.of(ProblemType.NOT_IMPLEMENTED_IN_P1, "Revisions, withdrawal and cancellation (QUO-T03/T05/T06) are specified but not built in Phase 1.");
    }

    // ------------------------------------------------------------------ internal (E073)

    @GetMapping("/internal/rounds/{id}/summary")
    @convoy.platform.security.RequiresScope("rounds:summary")
    public Map<String, Object> summary(@PathVariable UUID id) {
        Caller.current().requireScope("rounds:summary");
        return quotes.summary(id);
    }

    // ------------------------------------------------------------------ buyer comparison (E069, E070)

    /** Live quotes of a pool for its committed members: ceiling price plus a labelled projection (AMENDMENT-005). */
    @GetMapping("/pools/{poolId}/quotes")
    public Map<String, Object> compare(@PathVariable UUID poolId) {
        UUID buyer = Caller.current().require("buyer").userId();
        if (quotes.member(poolId, buyer).isEmpty()) throw ApiProblem.of(ProblemType.NOT_FOUND, "Offers are shown to committed members of this pool.");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> q : quotes.jdbc().queryForList("select id from quotes where pool_id = ? and state in ('SUBMITTED', 'REVISED', 'FINAL') order by submitted_at", poolId)) {
            out.add(buyerView(poolId, (UUID) q.get("id")));
        }
        return Map.of("pool_id", poolId.toString(), "quotes", out, "ranking_rule", "Cash payable at the ceiling price for mandatory items and baseline insurance (ranking-rule.md).");
    }

    @GetMapping("/pools/{poolId}/quotes/{quoteId}")
    public Map<String, Object> one(@PathVariable UUID poolId, @PathVariable UUID quoteId) {
        UUID buyer = Caller.current().require("buyer").userId();
        if (quotes.member(poolId, buyer).isEmpty()) throw ApiProblem.of(ProblemType.NOT_FOUND, "Offers are shown to committed members of this pool.");
        return buyerView(poolId, quoteId);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> buyerView(UUID poolId, UUID quoteId) {
        List<Map<String, Object>> rs = quotes.jdbc().queryForList("select q.*, coalesce(c.accepted, 0) as accepted, coalesce(c.confirmed, 0) as confirmed from quotes q "
                + "left join count_replica c on c.pool_id = q.pool_id and c.quote_id = q.id where q.id = ? and q.pool_id = ? and q.state in ('SUBMITTED', 'REVISED', 'FINAL')", quoteId, poolId);
        if (rs.isEmpty()) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such offer.");
        Map<String, Object> q = rs.get(0);
        Map<String, Object> ver = quotes.jdbc().queryForMap("select inputs::text as inputs, snapshots::text as snapshots, snapshot_sha256, committed_at from quote_versions "
                + "where quote_id = ? and version_no = ?", quoteId, q.get("current_version"));
        Map<String, Object> inputs = quotes.read((String) ver.get("inputs"));
        Map<String, Object> snapshots = quotes.read((String) ver.get("snapshots"));
        Map<String, Object> draft = QuotePricing.m(inputs.get("draft"));
        long minF = QuotePricing.lng(draft.get("min_fulfilment"));
        List<Map<String, Object>> tiers = Quotes.readList(draft.get("tiers"));
        int confirmed = ((Number) q.get("confirmed")).intValue(), accepted = ((Number) q.get("accepted")).intValue();
        long n = confirmed > 0 ? confirmed : accepted;
        String basis = confirmed > 0 ? "confirmed buyers" : "buyers accepting now";
        List<Map<String, Object>> variants = new ArrayList<>();
        for (Map<String, Object> vi : Quotes.readList(inputs.get("variant_inputs"))) {
            String vid = (String) vi.get("variant_id");
            List<Map<String, Object>> perTier = (List<Map<String, Object>>) snapshots.get(vid);
            List<Tiers.Tier> ladder = new ArrayList<>();
            for (int i = 0; i < tiers.size(); i++) {
                ladder.add(new Tiers.Tier(QuotePricing.lng(tiers.get(i).get("min")), QuotePricing.lng(tiers.get(i).get("max")),
                        QuotePricing.lng(perTier.get(i).get("cash_payable_paise"))));
            }
            int ceiling = Tiers.tierFor(ladder, minF);
            Integer projected = n >= minF ? Tiers.tierFor(ladder, n) : null;
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("variant_id", vid);
            v.put("variant_code", QuotePricing.m(QuotePricing.m(vi.get("input")).get("vehicle")).get("variant_id"));
            v.put("ceiling", Map.of("tier_index", ceiling, "cash_payable_paise", ladder.get(ceiling).pricePaise(), "output", perTier.get(ceiling).get("output"),
                    "snapshot_sha256", perTier.get(ceiling).get("sha256")));
            Map<String, Object> projection = new LinkedHashMap<>();
            projection.put("label", "If the count stays at " + n + " (" + basis + ")");
            projection.put("count", n);
            projection.put("guaranteed", false);
            projection.put("cash_payable_paise", projected == null ? null : ladder.get(projected).pricePaise());
            projection.put("note", projected == null ? "Below the dealer's minimum of " + minF + "; the offer would not go ahead at this count." : "Projection, not guaranteed.");
            v.put("projection", projection);
            List<Map<String, Object>> ladderView = new ArrayList<>();
            for (int i = 0; i < ladder.size(); i++) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("min", ladder.get(i).min());
                t.put("max", ladder.get(i).max());
                t.put("cash_payable_paise", ladder.get(i).pricePaise());
                t.put("is_ceiling", i == ceiling);
                ladderView.add(t);
            }
            v.put("tiers", ladderView);
            v.put("stock", vi.get("stock"));
            variants.add(v);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("quote_id", quoteId.toString());
        out.put("dealership_id", q.get("dealership_id").toString());
        out.put("state", q.get("state"));
        out.put("version_no", q.get("current_version"));
        out.put("committed_at", ((java.sql.Timestamp) ver.get("committed_at")).toInstant().toString());
        out.put("min_fulfilment", minF);
        out.put("max_units", draft.get("max_units"));
        out.put("snapshot_sha256", ver.get("snapshot_sha256"));
        out.put("variants", variants);
        return out;
    }

    static long version(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) throw ApiProblem.of(ProblemType.PRECONDITION_REQUIRED, "Send If-Match with the quote version.");
        try {
            return Long.parseLong(ifMatch.replace("\"", "").replace("W/", "").trim());
        } catch (NumberFormatException e) {
            throw ApiProblem.of(ProblemType.PRECONDITION_REQUIRED, "If-Match must be the quote version.");
        }
    }
}
