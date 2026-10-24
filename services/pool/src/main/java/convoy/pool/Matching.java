package convoy.pool;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.lang.Nullable;

/**
 * Pool suggestions for a buyer's saved cars (PLAN §3 item 3, E030). Pure: no I/O, no clock of its own; the caller
 * passes the candidate pools and the database time, so every rule here is unit-tested (MatchingTest).
 */
public final class Matching {
    private Matching() {}

    /** The buyer's preferences (E031). Empty sets mean "any". Colours are kept for dealer stock, not used to match pools. */
    public record Preferences(String city, String purchaseMonth, Set<String> variantIds, Set<String> fuels, Set<String> transmissions) {}

    public record Variant(String id, String fuel, String transmission) {}

    /** A candidate pool as the caller read it (same model, any state). */
    public record Pool(String id, String modelSlug, String city, String purchaseMonth, String state, List<Variant> variants, int minMembers,
                       int committedCount, int interestedCount, Instant joinDeadline) {}

    /** One open pool to join, with the honest status line the buyer sees. */
    public record Suggestion(String poolId, String status, List<String> matchingVariantIds, int committedCount, int minMembers,
                             @Nullable Instant joinDeadline) {}

    /** Per saved model: open pools that fit, or an offer to start one (start == null when the preferences are incomplete). */
    public record Result(String modelSlug, List<Suggestion> pools, @Nullable Start start, @Nullable String note) {}

    public record Start(String modelSlug, String city, String purchaseMonth) {}

    static final Set<String> JOINABLE = Set.of("FORMING", "READY_FOR_QUOTES", "QUOTE_OPEN");

    public static Result suggest(String modelSlug, Preferences prefs, List<Pool> candidates, Instant now) {
        if (prefs == null || prefs.city() == null || prefs.purchaseMonth() == null) {
            return new Result(modelSlug, List.of(), null, "Set your city and purchase month to see pools for this car.");
        }
        List<Suggestion> out = new ArrayList<>();
        for (Pool p : candidates) {
            if (!p.modelSlug().equals(modelSlug) || !p.city().equals(prefs.city()) || !p.purchaseMonth().equals(prefs.purchaseMonth())) continue;
            if (!JOINABLE.contains(p.state()) || (p.joinDeadline() != null && !now.isBefore(p.joinDeadline()))) continue;
            List<String> fit = p.variants().stream().filter(v -> fits(v, prefs)).map(Variant::id).toList();
            if (fit.isEmpty()) continue;
            out.add(new Suggestion(p.id(), status(p, now), fit, p.committedCount(), p.minMembers(), p.joinDeadline()));
        }
        // Closest to going ahead first: furthest past (or nearest to) the minimum, then the earliest deadline.
        out.sort(Comparator.comparingInt((Suggestion s) -> s.minMembers() - s.committedCount())
                .thenComparing(Suggestion::joinDeadline, Comparator.nullsLast(Comparator.naturalOrder())));
        Start start = out.isEmpty() ? new Start(modelSlug, prefs.city(), prefs.purchaseMonth()) : null;
        String note = out.isEmpty() ? "No open pool matches yet. Start one: others who save this car can join it." : null;
        return new Result(modelSlug, out, start, note);
    }

    static boolean fits(Variant v, Preferences p) {
        return (p.variantIds().isEmpty() || p.variantIds().contains(v.id()))
                && (p.fuels().isEmpty() || (v.fuel() != null && p.fuels().contains(v.fuel())))
                && (p.transmissions().isEmpty() || (v.transmission() != null && p.transmissions().contains(v.transmission())));
    }

    /** "accepting members", "awaiting quotes", "closes in 2 days": what is true now, never a promise. */
    static String status(Pool p, Instant now) {
        String phase = switch (p.state()) {
            case "FORMING" -> {
                int need = p.minMembers() - p.committedCount();
                yield "Accepting members: " + p.committedCount() + " of " + p.minMembers() + " committed"
                        + (need > 0 ? ", needs " + need + " more" : "");
            }
            case "READY_FOR_QUOTES" -> "Enough buyers; inviting dealers. You can still join";
            default -> "Dealers are quoting (sealed). You can still join";
        };
        return p.joinDeadline() == null ? phase : phase + "; closes in " + human(Duration.between(now, p.joinDeadline()));
    }

    static String human(Duration d) {
        long minutes = Math.max(0, d.toMinutes());
        if (minutes >= 2 * 24 * 60) return (minutes / (24 * 60)) + " days";
        if (minutes >= 24 * 60) return "1 day";
        if (minutes >= 120) return (minutes / 60) + " hours";
        if (minutes >= 60) return "1 hour";
        return Math.max(1, minutes) + " min";
    }
}
