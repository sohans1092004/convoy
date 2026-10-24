package convoy.price;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tier validation and resolution (docs/rules/tier-resolution.md); port of price_ref.resolve_tiers. */
public final class Tiers {
    private Tiers() {}

    /** One tier: inclusive bounds, {@code max == null} means unbounded. */
    public record Tier(long min, Long max, long pricePaise) {
        @SuppressWarnings("unchecked")
        public static Tier of(Object o) {
            Map<String, Object> m = (Map<String, Object>) o;
            return new Tier(((Number) m.get("min")).longValue(), m.get("max") == null ? null : ((Number) m.get("max")).longValue(),
                    ((Number) m.get("price_paise")).longValue());
        }
    }

    /** A confirmation as counted for the tier; timestamps are UTC ISO-8601 strings of identical format. */
    public record Confirmation(String confirmedAt, String depositCapturedAt, String cancelledAt) {}

    public static List<String> validate(List<Tier> tiers, long minFulfilment, Long maxUnits) {
        List<String> errs = new ArrayList<>();
        if (minFulfilment < 1) errs.add("MIN_FULFILMENT_INVALID");
        if (tiers.isEmpty()) { errs.add("TIERS_EMPTY"); return errs; }
        for (int i = 0; i < tiers.size(); i++) {
            Tier t = tiers.get(i);
            Tier p = i > 0 ? tiers.get(i - 1) : null;
            if (t.min() < 1 || (t.max() != null && t.max() < t.min())) errs.add("TIER_BOUNDS_INVALID:" + i);
            if (p != null && p.max() == null) errs.add("TIER_AFTER_UNBOUNDED");
            if (p != null && p.max() != null && t.min() > p.max() + 1) errs.add("TIER_GAP:" + i);
            if (p != null && p.max() != null && t.min() <= p.max()) errs.add("TIER_OVERLAP:" + i);
            if (p != null && t.pricePaise() > p.pricePaise()) errs.add("TIER_PRICE_INCREASES:" + i);
        }
        if (tiers.get(tiers.size() - 1).max() != null) errs.add("LAST_TIER_MUST_BE_UNBOUNDED");
        if (minFulfilment >= 1 && errs.isEmpty() && minFulfilment < tiers.get(0).min()) errs.add("MIN_FULFILMENT_BELOW_FIRST_TIER");
        if (maxUnits != null && maxUnits < minFulfilment) errs.add("MAX_UNITS_BELOW_MIN_FULFILMENT");
        return errs;
    }

    public static Integer tierFor(List<Tier> tiers, long n) {
        for (int i = 0; i < tiers.size(); i++) {
            Tier t = tiers.get(i);
            if (t.min() <= n && (t.max() == null || n <= t.max())) return i;
        }
        return null;
    }

    /** Tier count (PLAN §2.2.4): confirmed and captured strictly before the deadline, not cancelled before it. */
    public static long countConfirmed(List<Confirmation> cs, String deadline) {
        return cs.stream().filter(c -> c.confirmedAt().compareTo(deadline) < 0
                && c.depositCapturedAt() != null && c.depositCapturedAt().compareTo(deadline) < 0
                && (c.cancelledAt() == null || c.cancelledAt().compareTo(deadline) >= 0)).count();
    }

    public static Map<String, Object> resolve(List<Tier> tiers, long minFulfilment, long confirmedCount, Long maxUnits) {
        List<String> errs = validate(tiers, minFulfilment, maxUnits);
        Map<String, Object> out = new LinkedHashMap<>();
        if (!errs.isEmpty()) {
            out.put("status", "INVALID");
            out.put("errors", errs);
            return out;
        }
        int ci = tierFor(tiers, minFulfilment);
        out.put("status", "OK");
        out.put("ceiling_tier", (long) ci);
        out.put("ceiling_price_paise", tiers.get(ci).pricePaise());
        out.put("confirmed_count", confirmedCount);
        if (confirmedCount < minFulfilment) {
            out.put("outcome", "VOID");
            out.put("resolved_tier", null);
            out.put("resolved_price_paise", null);
            out.put("refund_reason", "QUOTE_VOID");
        } else {
            int ri = tierFor(tiers, confirmedCount);
            long price = tiers.get(ri).pricePaise();
            if (price > tiers.get(ci).pricePaise()) throw new IllegalStateException("price rose above ceiling");
            out.put("outcome", "RESOLVED");
            out.put("resolved_tier", (long) ri);
            out.put("resolved_price_paise", price);
            out.put("refund_reason", null);
        }
        return out;
    }
}
