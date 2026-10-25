package convoy.quote;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** ranking-rule.md: order, tie-breaks, single dealer, and the insurance-binding rule through the real engine (AMENDMENT-004). */
class RankingTest {
    static final Instant T = Instant.parse("2026-10-25T10:00:00Z");

    static Ranking.Entry e(String quote, String variant, long score, String delivery, long secondsAfter) {
        return new Ranking.Entry(quote, "d-" + quote, variant, score, delivery == null ? null : LocalDate.parse(delivery), T.plusSeconds(secondsAfter));
    }

    static int rankOf(List<Ranking.Ranked> r, String quote, String variant) {
        return r.stream().filter(x -> x.quoteId().equals(quote) && x.variantId().equals(variant)).findFirst().orElseThrow().rank();
    }

    @Test
    void lowestScoreRanksFirstPerVariantWithOfN() {
        List<Ranking.Ranked> r = Ranking.rank(List.of(e("a", "v1", 300, null, 0), e("b", "v1", 100, null, 0), e("c", "v1", 200, null, 0), e("a", "v2", 50, null, 0)));
        assertThat(rankOf(r, "b", "v1")).isEqualTo(1);
        assertThat(rankOf(r, "c", "v1")).isEqualTo(2);
        assertThat(rankOf(r, "a", "v1")).isEqualTo(3);
        assertThat(r.stream().filter(x -> x.variantId().equals("v1")).allMatch(x -> x.ofN() == 3)).isTrue();
        assertThat(rankOf(r, "a", "v2")).isEqualTo(1); // variants are ranked independently
    }

    @Test
    void tiesBreakByDeliveryDateThenCommittedAtThenQuoteId() {
        // Same score: the earlier promised delivery wins; no date sorts last.
        List<Ranking.Ranked> byDate = Ranking.rank(List.of(e("a", "v", 100, null, 0), e("b", "v", 100, "2026-11-20", 5), e("c", "v", 100, "2026-11-10", 9)));
        assertThat(List.of(rankOf(byDate, "c", "v"), rankOf(byDate, "b", "v"), rankOf(byDate, "a", "v"))).containsExactly(1, 2, 3);
        // Same score and date: the earlier committed version wins.
        List<Ranking.Ranked> byTime = Ranking.rank(List.of(e("a", "v", 100, "2026-11-10", 30), e("b", "v", 100, "2026-11-10", 10)));
        assertThat(rankOf(byTime, "b", "v")).isEqualTo(1);
        // Everything equal: quote id decides, deterministically.
        List<Ranking.Ranked> byId = Ranking.rank(List.of(e("q2", "v", 100, null, 0), e("q1", "v", 100, null, 0)));
        assertThat(rankOf(byId, "q1", "v")).isEqualTo(1);
    }

    @Test
    void aSingleDealerIsFirstOfOne() {
        List<Ranking.Ranked> r = Ranking.rank(List.of(e("only", "v", 123, null, 0)));
        assertThat(r).singleElement().satisfies(x -> {
            assertThat(x.rank()).isEqualTo(1);
            assertThat(x.ofN()).isEqualTo(1);
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void theDealersInsuranceFigureCountsOnlyIfBindingAndBaseline() throws Exception {
        Map<String, Object> rules = QuoteApplication.rules().rules();
        String swift = "0190f3a0-0000-7000-8000-0000000000a1";
        Map<String, Map<String, Object>> facts = Map.of(swift, Map.of("id", swift, "code", "swift-lxi-mt", "name", "LXi MT", "fuel", "PETROL", "engine_cc", 1197));
        java.util.function.Function<String, Map<String, Object>> draft = insurance -> (Map<String, Object>) (Map<?, ?>) Map.of(
                "variants", List.of(Map.of("variant_id", swift, "ex_showroom_paise", 58390000L, "insurance", insurance == null ? "" : insurance,
                        "accessories", List.of(), "fees", List.of(), "other_charges", List.of(), "stock", Map.of("units", 3L, "colours", List.of("White")))),
                "tiers", List.of(Map.of("min", 1L, "discount_paise", 1000000L)), "min_fulfilment", 1L);
        Map<String, Object> cheap = Map.of("od_paise", 1500000L, "tp_paise", 1000000L, "gst_paise", 450000L);
        Map<String, Object> baseline = Map.of("idv_bp", 9500L, "addons", List.of());
        long estimate = score(QuotePricing.rankingScores(withoutInsurance(draft.apply(null)), facts, "MH", rules), swift);
        long binding = score(QuotePricing.rankingScores(withInsurance(draft, cheap, true, baseline), facts, "MH", rules), swift);
        long notBinding = score(QuotePricing.rankingScores(withInsurance(draft, cheap, false, baseline), facts, "MH", rules), swift);
        assertThat(notBinding).as("a non-binding figure is replaced by the catalogue estimate").isEqualTo(estimate);
        assertThat(binding).as("a binding baseline figure is used for the rank").isNotEqualTo(estimate);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> withoutInsurance(Map<String, Object> d) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(d);
        Map<String, Object> line = new java.util.LinkedHashMap<>((Map<String, Object>) ((List<Object>) d.get("variants")).get(0));
        line.remove("insurance");
        copy.put("variants", List.of(line));
        return copy;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> withInsurance(java.util.function.Function<String, Map<String, Object>> draft, Map<String, Object> figures, boolean binding, Map<String, Object> cover) {
        Map<String, Object> d = withoutInsurance(draft.apply(null));
        Map<String, Object> line = new java.util.LinkedHashMap<>((Map<String, Object>) ((List<Object>) d.get("variants")).get(0));
        Map<String, Object> ins = new java.util.LinkedHashMap<>(figures);
        ins.put("binding", binding);
        ins.put("declared_cover", cover);
        line.put("insurance", ins);
        d.put("variants", List.of(line));
        return d;
    }

    @SuppressWarnings("unchecked")
    static long score(Map<String, Object> scores, String variant) {
        Object s = ((Map<String, Object>) scores.get(variant)).get("score_paise");
        return ((Number) s).longValue();
    }
}
