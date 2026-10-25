package convoy.quote;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * ranking-rule.md section 2 (pure): per variant, ascending score (engine cash payable, mode RANKING, at the ceiling tier
 * with mandatory items and baseline insurance), ties broken by (1) earliest promised delivery date, no date last,
 * (2) earliest committed_at of the version ranked, (3) quote id (deterministic, never shown).
 */
final class Ranking {
    private Ranking() {}

    static final String RULE_VERSION = "ranking-rule.md#2 (2026-10, AMENDMENT-004/005)";

    record Entry(String quoteId, String dealershipId, String variantId, long scorePaise, LocalDate delivery, Instant committedAt) {}

    record Ranked(String quoteId, String dealershipId, String variantId, int rank, int ofN) {}

    static final Comparator<Entry> ORDER = Comparator.comparingLong(Entry::scorePaise)
            .thenComparing(Entry::delivery, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Entry::committedAt)
            .thenComparing(Entry::quoteId);

    static List<Ranked> rank(List<Entry> entries) {
        Map<String, List<Entry>> byVariant = new TreeMap<>();
        for (Entry e : entries) byVariant.computeIfAbsent(e.variantId(), k -> new ArrayList<>()).add(e);
        List<Ranked> out = new ArrayList<>();
        for (List<Entry> list : byVariant.values()) {
            list.sort(ORDER);
            for (int i = 0; i < list.size(); i++) {
                Entry e = list.get(i);
                out.add(new Ranked(e.quoteId(), e.dealershipId(), e.variantId(), i + 1, list.size()));
            }
        }
        return out;
    }
}
