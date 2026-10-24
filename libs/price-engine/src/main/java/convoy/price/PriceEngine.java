package convoy.price;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Convoy price engine (Java). A line-for-line port of spec-tools/price_ref.py
 * (docs/rules/pricing-model.md). It must reproduce spec-tools/vectors/pricing.json byte for byte (ADR-011).
 *
 * Inputs and outputs are plain JSON-shaped values (Map, List, String, Long, Boolean, null) so the
 * canonical JSON of an output can be compared with the vectors directly. Money is integer paise
 * (long), rates are integer basis points. No floating point.
 */
public final class PriceEngine {
    private static final Map<String, Integer> RANK = Map.of("DEALER_CONFIRMED", 2, "CATALOGUE_ESTIMATE", 1, "UNVERIFIED", 0);
    private static final Map<String, String> RULE_EVIDENCE = new java.util.HashMap<>();
    static {
        RULE_EVIDENCE.put("VERIFIED", "DEALER_CONFIRMED");
        RULE_EVIDENCE.put("SECONDARY", "CATALOGUE_ESTIMATE");
        RULE_EVIDENCE.put("ASSUMPTION", "CATALOGUE_ESTIMATE");
        RULE_EVIDENCE.put("CONFLICTING", "UNVERIFIED");
        RULE_EVIDENCE.put("UNVERIFIED", null);
    }

    private PriceEngine() {}

    /** amount x bp / 10000, rounded half up to the whole rupee, returned in paise. amount >= 0. */
    public static long pct(long amountPaise, long bp) {
        long prod = Math.multiplyExact(amountPaise, bp);
        long rupees = Math.floorDiv(prod, 1_000_000L);
        long rem = prod - rupees * 1_000_000L;
        if (rem * 2 >= 1_000_000L) rupees += 1;
        return rupees * 100;
    }

    static String weakest(String... levels) {
        String w = levels[0];
        for (String l : levels) if (RANK.get(l) < RANK.get(w)) w = l;
        return w;
    }

    static boolean isInt(Object v) {
        return v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof java.math.BigInteger;
    }

    static long asLong(Object v) { return ((Number) v).longValue(); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) { return o == null ? List.of() : (List<Object>) o; }

    static Object get(Map<String, Object> m, String k) { return m == null ? null : m.get(k); }

    static Map<String, Object> line(String code, long amount, String evidence, boolean inCash, List<String> flags) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("code", code);
        l.put("amount_paise", amount);
        l.put("evidence", evidence);
        l.put("in_cash_payable", inCash);
        List<String> f = new ArrayList<>(flags);
        f.sort(null);
        l.put("flags", f);
        return l;
    }

    static Map<String, Object> line(String code, long amount, String evidence) { return line(code, amount, evidence, true, List.of()); }

    static long slab(List<Object> slabs, long base) {
        for (Object o : slabs) {
            Map<String, Object> s = map(o);
            if (s.get("upto_paise") == null || base <= asLong(s.get("upto_paise"))) return asLong(s.get("rate_bp"));
        }
        throw new IllegalStateException("slab table has no unbounded last slab");
    }

    static Map<String, Object> invalid(java.util.Collection<String> errors) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "INVALID");
        out.put("errors", new ArrayList<>(new TreeSet<>(errors)));
        return out;
    }

    public static boolean rankableInsurance(Map<String, Object> pol, Map<String, Object> ins) {
        Map<String, Object> cover = pol.get("declared_cover") == null ? Map.of() : map(pol.get("declared_cover"));
        Object addons = cover.get("addons");
        return Boolean.TRUE.equals(pol.get("binding")) && addons instanceof List<?> a && a.isEmpty()
                && isInt(cover.get("idv_bp")) && asLong(cover.get("idv_bp")) == 10000 - asLong(ins.get("idv_depreciation_bp"));
    }

    /** Return the engine output for one calculation input (pricing-model.md §Inputs). */
    public static Map<String, Object> compute(Map<String, Object> inp, Map<String, Object> rules) {
        Map<String, Object> rs = map(rules.get("ruleset")), tcs = map(rules.get("tcs")), rto = map(rules.get("rto")),
                ins = map(rules.get("insurance-baseline"));
        List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
        List<Map<String, Object>> lines = new ArrayList<>();
        boolean ranking = "RANKING".equals(inp.get("mode"));

        java.util.function.BiPredicate<Object, String> amountOk = (v, code) -> {
            if (!isInt(v) || asLong(v) < 0) { errors.add("NEGATIVE_OR_INVALID_AMOUNT:" + code); return false; }
            return true;
        };
        java.util.function.BiPredicate<Object, String> evidenceOk = (e, code) -> {
            if (!(e instanceof String s) || !RANK.containsKey(s)) { errors.add("INVALID_EVIDENCE:" + code); return false; }
            return true;
        };

        if (!rs.get("ruleset_version").equals(inp.get("ruleset_version"))) errors.add("RULESET_VERSION_MISMATCH");
        Map<String, Object> veh = inp.get("vehicle") == null ? Map.of() : map(inp.get("vehicle"));
        Object exO = veh.get("ex_showroom_paise");
        if (exO == null) errors.add("EX_SHOWROOM_MISSING");
        else if (amountOk.test(exO, "EX_SHOWROOM") && asLong(exO) == 0) errors.add("EX_SHOWROOM_MISSING");
        Object exEvO = veh.get("ex_showroom_evidence");
        evidenceOk.test(exEvO, "EX_SHOWROOM");
        Object fuel = veh.get("fuel");
        if (!list(rs.get("fuels")).contains(fuel)) errors.add("UNKNOWN_FUEL");
        Object state = inp.get("state");
        Map<String, Object> states = map(rto.get("states"));
        if (!(state instanceof String) || !states.containsKey(state)) errors.add("UNKNOWN_STATE");

        Map<String, Object> disc = inp.get("discount") != null ? map(inp.get("discount"))
                : Map.of("amount_paise", 0L, "base", "EX_SHOWROOM", "evidence", "DEALER_CONFIRMED");
        Object dO = disc.get("amount_paise");
        if (!list(rs.get("discount_bases")).contains(disc.get("base"))) errors.add("DISCOUNT_BASE_UNSUPPORTED");
        amountOk.test(dO, "DISCOUNT");
        evidenceOk.test(disc.get("evidence"), "DISCOUNT");
        if (!errors.isEmpty()) return invalid(errors);
        long ex = asLong(exO), d = asLong(dO);
        long maxD = Math.floorDiv(Math.multiplyExact(ex, asLong(rs.get("max_discount_bp"))), 10000L);
        if (d > maxD) return invalid(List.of("DISCOUNT_EXCEEDS_LIMIT"));

        String exEv = (String) exEvO, discEv = (String) disc.get("evidence");
        long vehiclePrice = ex - d;
        String vpEv = weakest(exEv, discEv);
        lines.add(line("EX_SHOWROOM", ex, exEv));
        lines.add(line("DISCOUNT", -d, discEv));

        // Registration / RTO
        Map<String, Object> reg = inp.get("registration") == null ? null : map(inp.get("registration"));
        if (reg != null) {
            if (amountOk.test(reg.get("amount_paise"), "REGISTRATION") && evidenceOk.test(reg.get("evidence"), "REGISTRATION")) {
                lines.add(line("REGISTRATION", asLong(reg.get("amount_paise")), (String) reg.get("evidence")));
            }
        } else {
            Map<String, Object> st = map(states.get(state));
            Object fe = map(st.get("fuel_evidence")).getOrDefault(fuel, "UNVERIFIED");
            String ruleEv = RULE_EVIDENCE.get(fe);
            Map<String, Object> slabs = map(st.get("slabs"));
            if (ruleEv == null || !slabs.containsKey(fuel)) {
                errors.add("REGISTRATION_ESTIMATE_UNAVAILABLE");
            } else {
                String baseEv = weakest(ruleEv, vpEv, "CATALOGUE_ESTIMATE");
                long tax = pct(vehiclePrice, slab(list(slabs.get(fuel)), vehiclePrice));
                if (st.get("max_tax_paise") != null) tax = Math.min(tax, asLong(st.get("max_tax_paise")));
                lines.add(line("ROAD_TAX", tax, baseEv));
                if (st.get("cess_on_tax_bp") != null && asLong(st.get("cess_on_tax_bp")) != 0) {
                    long fixed = st.get("fixed_cess_paise") == null ? 0 : asLong(st.get("fixed_cess_paise"));
                    lines.add(line("ROAD_TAX_CESS", pct(tax, asLong(st.get("cess_on_tax_bp"))) + fixed, baseEv));
                }
                Map<String, Object> c = map(rto.get("common"));
                lines.add(line("REGISTRATION_FEE", asLong(c.get("registration_fee_paise")),
                        weakest(RULE_EVIDENCE.get(c.get("registration_fee_evidence")), "CATALOGUE_ESTIMATE")));
                String sc = RULE_EVIDENCE.get(c.get("smart_card_fee_evidence"));
                lines.add(line("SMART_CARD_FEE", asLong(c.get("smart_card_fee_paise")), sc == null ? "UNVERIFIED" : sc));
                warnings.add("REGISTRATION_ESTIMATED");
            }
        }

        // Insurance baseline (comprehensive yr-1 OD + 3-yr TP)
        Map<String, Object> pol = inp.get("insurance") == null ? null : map(inp.get("insurance"));
        if (pol != null && ranking && !rankableInsurance(pol, ins)) {
            // AMENDMENT-004: ranking uses the dealer's figure only for binding, exactly-baseline cover.
            warnings.add("DEALER_INSURANCE_NOT_RANKABLE");
            pol = null;
        }
        if (pol != null) {
            for (String[] kc : new String[][] {{"od_paise", "INSURANCE_OD"}, {"tp_paise", "INSURANCE_TP"}, {"gst_paise", "INSURANCE_GST"}}) {
                if (amountOk.test(pol.get(kc[0]), kc[1]) && evidenceOk.test(pol.get("evidence"), kc[1])) {
                    lines.add(line(kc[1], asLong(pol.get(kc[0])), (String) pol.get("evidence")));
                }
            }
        } else {
            Object cc = veh.get("engine_cc");
            if ("EV".equals(fuel)) errors.add("INSURANCE_ESTIMATE_UNAVAILABLE");
            else if (!isInt(cc) || asLong(cc) <= 0) errors.add("ENGINE_CC_MISSING");
            else {
                String ev = weakest("CATALOGUE_ESTIMATE", exEv);
                long idv = pct(ex, 10000 - asLong(ins.get("idv_depreciation_bp")));
                long od = pct(idv, asLong(ins.get("od_rate_bp")));
                long tp = -1;
                for (Object o : list(ins.get("tp_3yr_by_engine_cc"))) {
                    Map<String, Object> b = map(o);
                    if (b.get("max_cc") == null || asLong(cc) <= asLong(b.get("max_cc"))) { tp = asLong(b.get("premium_paise")); break; }
                }
                lines.add(line("INSURANCE_OD", od, ev));
                lines.add(line("INSURANCE_TP", tp, ev));
                lines.add(line("INSURANCE_GST", pct(od + tp, asLong(ins.get("gst_bp"))), ev));
                warnings.add("INSURANCE_ESTIMATED");
            }
        }

        for (Object o : list(inp.get("insurance_addons"))) {
            Map<String, Object> a = map(o);
            String code = String.valueOf(a.get("code"));
            if (amountOk.test(a.get("amount_paise"), code) && evidenceOk.test(a.get("evidence"), code)) {
                lines.add(line("INSURANCE_ADDON:" + code, asLong(a.get("amount_paise")), (String) a.get("evidence"),
                        Boolean.TRUE.equals(a.get("selected")) && !ranking, List.of("OPTIONAL")));
            }
        }
        for (Object o : list(inp.get("fees"))) {
            Map<String, Object> f = map(o);
            String code = String.valueOf(f.get("code"));
            if (amountOk.test(f.get("amount_paise"), code) && evidenceOk.test(f.get("evidence"), code)) {
                lines.add(line("FEE:" + code, asLong(f.get("amount_paise")), (String) f.get("evidence")));
            }
        }
        for (Object o : list(inp.get("accessories"))) {
            Map<String, Object> a = map(o);
            String code = String.valueOf(a.get("code"));
            if (amountOk.test(a.get("amount_paise"), code) && evidenceOk.test(a.get("evidence"), code)) {
                if (Boolean.TRUE.equals(a.get("required"))) {
                    lines.add(line("ACCESSORY:" + code, asLong(a.get("amount_paise")), (String) a.get("evidence"), true, List.of("DEALER_REQUIRED")));
                    warnings.add("REQUIRED_ACCESSORY:" + code);
                } else {
                    lines.add(line("ACCESSORY:" + code, asLong(a.get("amount_paise")), (String) a.get("evidence"),
                            Boolean.TRUE.equals(a.get("selected")) && !ranking, List.of("OPTIONAL")));
                }
            }
        }
        Map<String, Object> w = inp.get("extended_warranty") == null ? null : map(inp.get("extended_warranty"));
        if (w != null && amountOk.test(w.get("amount_paise"), "EXTENDED_WARRANTY") && evidenceOk.test(w.get("evidence"), "EXTENDED_WARRANTY")) {
            lines.add(line("EXTENDED_WARRANTY", asLong(w.get("amount_paise")), (String) w.get("evidence"),
                    Boolean.TRUE.equals(w.get("selected")) && !ranking, List.of("OPTIONAL")));
        }
        for (Object o : list(inp.get("other_charges"))) {
            Map<String, Object> c = map(o);
            String code = String.valueOf(c.get("code"));
            if (amountOk.test(c.get("amount_paise"), code) && evidenceOk.test(c.get("evidence"), code)) {
                boolean q = list(rs.get("questionable_charge_codes")).contains(code);
                lines.add(line("CHARGE:" + code, asLong(c.get("amount_paise")), (String) c.get("evidence"), true,
                        q ? List.of("QUESTIONABLE") : List.of()));
                if (q) warnings.add("QUESTIONABLE_CHARGE:" + code);
            }
        }

        if (!errors.isEmpty()) return invalid(errors);

        // TCS: 1 % of the full vehicle consideration when it EXCEEDS the threshold
        long tcsAmt = vehiclePrice > asLong(tcs.get("threshold_paise")) ? pct(vehiclePrice, asLong(tcs.get("rate_bp"))) : 0;
        lines.add(line("TCS", tcsAmt, vpEv, true, tcsAmt != 0 ? List.of("CREDITABLE") : List.of()));
        if (tcsAmt != 0) warnings.add("TCS_APPLIES");

        long cash = 0;
        for (Map<String, Object> l : lines) if (Boolean.TRUE.equals(l.get("in_cash_payable"))) cash = Math.addExact(cash, (Long) l.get("amount_paise"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "OK");
        out.put("vehicle_price_paise", vehiclePrice);
        out.put("lines", lines);
        out.put("cash_payable_paise", cash);
        out.put("tcs_paise", tcsAmt);
        out.put("estimated_net_cost_paise", cash - tcsAmt);
        out.put("warnings", new ArrayList<>(new TreeSet<>(warnings)));
        return out;
    }

    /** Stored calculation snapshot body: hash covers inputs + ruleset_version + output (not the timestamp). */
    public static Map<String, Object> snapshotBody(Map<String, Object> inp, Map<String, Object> rules) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inputs", inp);
        body.put("ruleset_version", inp.get("ruleset_version"));
        body.put("output", compute(inp, rules));
        return body;
    }
}
