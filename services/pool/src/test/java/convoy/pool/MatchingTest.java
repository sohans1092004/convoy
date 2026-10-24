package convoy.pool;

import static org.assertj.core.api.Assertions.assertThat;

import convoy.pool.Matching.Pool;
import convoy.pool.Matching.Preferences;
import convoy.pool.Matching.Variant;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MatchingTest {
    static final Instant NOW = Instant.parse("2026-10-25T06:00:00Z");
    static final Variant LXI = new Variant("v-lxi", "PETROL", "MT"), ZXI_AMT = new Variant("v-zxi-amt", "PETROL", "AMT"), CNG = new Variant("v-cng", "CNG", "MT");
    static final Preferences PUNE_NOV = new Preferences("Pune", "2026-11", Set.of(), Set.of(), Set.of());

    static Pool pool(String id, String state, int committed, int min, Duration closesIn, Variant... variants) {
        return new Pool(id, "maruti-swift", "Pune", "2026-11", state, List.of(variants), min, committed, committed, closesIn == null ? null : NOW.plus(closesIn));
    }

    @Test
    void incompletePreferencesAskForCityAndMonthInsteadOfGuessing() {
        Matching.Result r = Matching.suggest("maruti-swift", new Preferences(null, "2026-11", Set.of(), Set.of(), Set.of()), List.of(pool("p", "FORMING", 1, 5, Duration.ofDays(3), LXI)), NOW);
        assertThat(r.pools()).isEmpty();
        assertThat(r.start()).isNull();
        assertThat(r.note()).contains("city and purchase month");
    }

    @Test
    void onlyJoinablePoolsForTheSameModelCityAndMonthBeforeTheirDeadlineMatch() {
        List<Pool> pools = List.of(
                pool("forming", "FORMING", 2, 5, Duration.ofDays(3), LXI),
                pool("quoting", "QUOTE_OPEN", 5, 5, Duration.ofHours(5), LXI),
                pool("deciding", "DECISION_OPEN", 5, 5, null, LXI),
                pool("expired", "EXPIRED", 1, 5, null, LXI),
                pool("past-deadline", "FORMING", 1, 5, Duration.ofMinutes(-1), LXI),
                new Pool("other-city", "maruti-swift", "Mumbai", "2026-11", "FORMING", List.of(LXI), 5, 1, 1, NOW.plus(Duration.ofDays(3))),
                new Pool("other-month", "maruti-swift", "Pune", "2026-12", "FORMING", List.of(LXI), 5, 1, 1, NOW.plus(Duration.ofDays(3))),
                new Pool("other-model", "tata-nexon", "Pune", "2026-11", "FORMING", List.of(LXI), 5, 1, 1, NOW.plus(Duration.ofDays(3))));
        Matching.Result r = Matching.suggest("maruti-swift", PUNE_NOV, pools, NOW);
        assertThat(r.pools()).extracting(Matching.Suggestion::poolId).containsExactly("quoting", "forming");
        assertThat(r.start()).isNull();
    }

    @Test
    void variantFuelAndTransmissionPreferencesMustOverlapThePoolsVariants() {
        List<Pool> pools = List.of(pool("mt-only", "FORMING", 1, 5, Duration.ofDays(3), LXI, CNG), pool("amt", "FORMING", 1, 5, Duration.ofDays(3), ZXI_AMT));
        Matching.Result amt = Matching.suggest("maruti-swift", new Preferences("Pune", "2026-11", Set.of(), Set.of(), Set.of("AMT")), pools, NOW);
        assertThat(amt.pools()).extracting(Matching.Suggestion::poolId).containsExactly("amt");
        Matching.Result cng = Matching.suggest("maruti-swift", new Preferences("Pune", "2026-11", Set.of(), Set.of("CNG"), Set.of()), pools, NOW);
        assertThat(cng.pools()).singleElement().satisfies(s -> assertThat(s.matchingVariantIds()).containsExactly("v-cng"));
        Matching.Result named = Matching.suggest("maruti-swift", new Preferences("Pune", "2026-11", Set.of("v-zxi-amt"), Set.of("PETROL"), Set.of()), pools, NOW);
        assertThat(named.pools()).extracting(Matching.Suggestion::poolId).containsExactly("amt");
    }

    @Test
    void noMatchOffersToStartAPoolWithTheBuyersCityAndMonth() {
        Matching.Result none = Matching.suggest("maruti-swift", new Preferences("Pune", "2026-11", Set.of(), Set.of("DIESEL"), Set.of()),
                List.of(pool("cng-only", "FORMING", 1, 5, Duration.ofDays(3), CNG)), NOW);
        assertThat(none.pools()).isEmpty();
        assertThat(none.start()).isEqualTo(new Matching.Start("maruti-swift", "Pune", "2026-11"));
        assertThat(none.note()).contains("Start one");
    }

    @Test
    void statusSaysWhatIsTrueNow() {
        assertThat(Matching.status(pool("p", "FORMING", 2, 5, Duration.ofDays(2).plusHours(3)), NOW))
                .isEqualTo("Accepting members: 2 of 5 committed, needs 3 more; closes in 2 days");
        assertThat(Matching.status(pool("p", "FORMING", 5, 5, Duration.ofHours(30)), NOW)).isEqualTo("Accepting members: 5 of 5 committed; closes in 1 day");
        assertThat(Matching.status(pool("p", "READY_FOR_QUOTES", 5, 5, null), NOW)).isEqualTo("Enough buyers; inviting dealers. You can still join");
        assertThat(Matching.status(pool("p", "QUOTE_OPEN", 6, 5, Duration.ofMinutes(90)), NOW)).isEqualTo("Dealers are quoting (sealed). You can still join; closes in 1 hour");
        assertThat(Matching.human(Duration.ofSeconds(20))).isEqualTo("1 min");
    }

    @Test
    void poolsClosestToGoingAheadComeFirstThenTheEarliestDeadline() {
        List<Pool> pools = List.of(pool("far", "FORMING", 1, 5, Duration.ofDays(1), LXI), pool("near-late", "FORMING", 4, 5, Duration.ofDays(5), LXI),
                pool("near-soon", "FORMING", 4, 5, Duration.ofDays(2), LXI));
        assertThat(Matching.suggest("maruti-swift", PUNE_NOV, pools, NOW).pools()).extracting(Matching.Suggestion::poolId).containsExactly("near-soon", "near-late", "far");
    }
}
