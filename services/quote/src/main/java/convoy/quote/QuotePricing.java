package convoy.quote;

import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.price.CanonicalJson;
import convoy.price.PriceEngine;
import convoy.price.Tiers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a dealer's draft into price-engine inputs and validates it (pricing-model.md, tier-resolution.md).
 * A tier's price for a variant is the engine's cash payable for the buyer configuration with
 * discount = tier.discount_paise; the ladder of those prices must satisfy validate_tiers.
 */
public final class QuotePricing {
    private QuotePricing() {}

    static final String DC = "DEALER_CONFIRMED";

    @SuppressWarnings("unchecked")
    static Map<String, Object> m(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    static List<Object> l(Object o) { return o == null ? List.of() : (List<Object>) o; }

    static Long lng(Object o) { return o == null ? null : ((Number) o).longValue(); }

    /** Engine input for one variant line, with the given discount; dealer-entered figures are DEALER_CONFIRMED. */
    public static Map<String, Object> input(Map<String, Object> facts, Map<String, Object> line, String stateCode, String rulesetVersion, long discount, String mode) {
        Map<String, Object> vehicle = new LinkedHashMap<>();
        vehicle.put("variant_id", facts.get("code"));
        vehicle.put("fuel", facts.get("fuel"));
        vehicle.put("engine_cc", lng(facts.get("engine_cc")));
        vehicle.put("ex_showroom_paise", lng(line.get("ex_showroom_paise")));
        vehicle.put("ex_showroom_evidence", DC);
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("ruleset_version", rulesetVersion);
        in.put("mode", mode);
        in.put("state", stateCode);
        in.put("vehicle", vehicle);
        in.put("discount", Map.of("amount_paise", discount, "base", "EX_SHOWROOM", "evidence", DC));
        in.put("registration", line.get("registration_paise") == null ? null : Map.of("amount_paise", lng(line.get("registration_paise")), "evidence", DC));
        Map<String, Object> ins = line.get("insurance") == null ? null : m(line.get("insurance"));
        if (ins == null) {
            in.put("insurance", null);
        } else {
            Map<String, Object> pol = new LinkedHashMap<>();
            pol.put("od_paise", lng(ins.get("od_paise")));
            pol.put("tp_paise", lng(ins.get("tp_paise")));
            pol.put("gst_paise", lng(ins.get("gst_paise")));
            pol.put("evidence", DC);
            pol.put("binding", Boolean.TRUE.equals(ins.get("binding")));
            pol.put("declared_cover", ins.get("declared_cover"));
            in.put("insurance", pol);
        }
        in.put("insurance_addons", withEvidence(l(line.get("insurance_addons"))));
        in.put("fees", withEvidence(l(line.get("fees"))));
        in.put("accessories", withEvidence(l(line.get("accessories"))));
        in.put("extended_warranty", line.get("extended_warranty") == null ? null : withEvidence(List.of(line.get("extended_warranty"))).get(0));
        in.put("other_charges", withEvidence(l(line.get("other_charges"))));
        return in;
    }

    private static List<Map<String, Object>> withEvidence(List<Object> items) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : items) {
            Map<String, Object> x = new LinkedHashMap<>(m(o));
            if (x.get("amount_paise") != null) x.put("amount_paise", lng(x.get("amount_paise")));
            x.put("evidence", DC);
            out.add(x);
        }
        return out;
    }

    public record Priced(Map<String, Object> snapshots, String sha256, List<Map<String, Object>> variantInputs) {}

    /**
     * Validate and price a draft against the round's variants and the ruleset. Throws the specific
     * problem (discount-exceeds-limit, invalid-tiers, quote-invalid) on the first failing check.
     */
    public static Priced price(Map<String, Object> draft, Map<String, Map<String, Object>> facts, String stateCode, Map<String, Object> rules) {
        String rv = (String) m(rules.get("ruleset")).get("ruleset_version");
        List<Object> lines = l(draft.get("variants"));
        List<Object> tiers = l(draft.get("tiers"));
        if (lines.isEmpty()) throw ApiProblem.of(ProblemType.QUOTE_INVALID, "Price at least one variant.").with("errors", List.of("NO_VARIANTS"));
        Long minF = lng(draft.get("min_fulfilment"));
        Long maxUnits = lng(draft.get("max_units"));
        Map<String, Object> snapshots = new LinkedHashMap<>();
        List<Map<String, Object>> variantInputs = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Object o : lines) {
            Map<String, Object> line = m(o);
            String vid = String.valueOf(line.get("variant_id"));
            Map<String, Object> f = facts.get(vid);
            if (f == null) throw ApiProblem.of(ProblemType.QUOTE_INVALID, "Variant " + vid + " is not accepted by this pool.").with("errors", List.of("VARIANT_NOT_IN_POOL"));
            if (!seen.add(vid)) throw ApiProblem.of(ProblemType.QUOTE_INVALID, "Variant " + vid + " is listed twice.").with("errors", List.of("DUPLICATE_VARIANT"));
            Map<String, Object> stock = line.get("stock") == null ? null : m(line.get("stock"));
            if (stock == null || lng(stock.get("units")) == null || lng(stock.get("units")) < 0) {
                throw ApiProblem.of(ProblemType.QUOTE_INVALID, "Declare the stock you hold for " + f.get("name") + ".").with("errors", List.of("STOCK_CLAIM_MISSING"));
            }
            List<Map<String, Object>> perTier = new ArrayList<>();
            List<Tiers.Tier> ladder = new ArrayList<>();
            for (int i = 0; i < tiers.size(); i++) {
                Map<String, Object> t = m(tiers.get(i));
                long discount = lng(t.get("discount_paise")) == null ? 0 : lng(t.get("discount_paise"));
                Map<String, Object> in = input(f, line, stateCode, rv, discount, "BUYER_CONFIG");
                Map<String, Object> body = PriceEngine.snapshotBody(in, rules);
                Map<String, Object> out = m(body.get("output"));
                if (!"OK".equals(out.get("status"))) {
                    List<Object> errs = l(out.get("errors"));
                    if (errs.contains("DISCOUNT_EXCEEDS_LIMIT")) {
                        throw ApiProblem.of(ProblemType.DISCOUNT_EXCEEDS_LIMIT, "Tier " + (i + 1) + " discount is above 25% of ex-showroom for " + f.get("name") + ".")
                                .with("errors", errs).with("variant_id", vid).with("tier_index", i);
                    }
                    throw ApiProblem.of(ProblemType.QUOTE_INVALID, "The price engine rejected " + f.get("name") + " tier " + (i + 1) + ": " + errs + ".")
                            .with("errors", errs).with("variant_id", vid).with("tier_index", i);
                }
                ladder.add(new Tiers.Tier(lng(t.get("min")), lng(t.get("max")), lng(out.get("cash_payable_paise"))));
                Map<String, Object> snap = new LinkedHashMap<>();
                snap.put("tier_index", (long) i);
                snap.put("discount_paise", discount);
                snap.put("cash_payable_paise", out.get("cash_payable_paise"));
                snap.put("sha256", CanonicalJson.sha256(body));
                snap.put("output", out);
                perTier.add(snap);
            }
            List<String> tierErrors = Tiers.validate(ladder, minF == null ? 0 : minF, maxUnits);
            if (!tierErrors.isEmpty()) {
                throw ApiProblem.of(ProblemType.INVALID_TIERS, "The tier ladder is not valid for " + f.get("name") + ": " + tierErrors + ".").with("errors", tierErrors).with("variant_id", vid);
            }
            snapshots.put(vid, perTier);
            Map<String, Object> vi = new LinkedHashMap<>();
            vi.put("variant_id", vid);
            vi.put("input", input(f, line, stateCode, rv, 0L, "BUYER_CONFIG"));
            Map<String, Object> claim = new LinkedHashMap<>(stock);
            claim.put("evidence", "DEALER_CONFIRMED"); // a stock claim is the dealer's own statement
            vi.put("stock", claim);
            variantInputs.add(vi);
        }
        return new Priced(snapshots, CanonicalJson.sha256(snapshots), variantInputs);
    }

    static final Set<String> LIVE = Set.of("SUBMITTED", "REVISED", "FINAL");
}
