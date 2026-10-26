package convoy.quote;

import org.springframework.lang.Nullable;
import com.fasterxml.jackson.annotation.JsonInclude;
import convoy.contracts.api.PriceOutput;
import java.util.List;
import java.util.Map;

/** Request and response records for every quote endpoint. */
public final class Api {
    private Api() {}

    // ---- the dealer's draft (E062 body; echoed back in DealerQuote.draft)

    /** One price-engine line item (pricing-model.md §3); which fields apply depends on the list it is in. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String code, String label, Long amountPaise, String basis, Boolean required, Boolean selected, String evidence) {}

    public record DeclaredCover(@Nullable Integer idvBp, @Nullable List<String> addons) {}

    public record Insurance(@Nullable Long odPaise, @Nullable Long tpPaise, @Nullable Long gstPaise, @Nullable Boolean binding, @Nullable DeclaredCover declaredCover) {}

    /** A stock claim; evidence is DEALER_CONFIRMED on submitted versions (the dealer's own statement). */
    public record Stock(Long units, @Nullable List<String> colours, @Nullable String deliveryDate,
                        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String evidence) {}

    public record DraftLine(@Nullable String variantId, @Nullable Long exShowroomPaise, @Nullable Long registrationPaise, @Nullable Insurance insurance, @Nullable List<Item> insuranceAddons,
                            @Nullable List<Item> fees, @Nullable List<Item> accessories, @Nullable Item extendedWarranty, @Nullable List<Item> otherCharges, @Nullable Stock stock) {}

    public record DraftTier(@Nullable Long min, @Nullable Long max, @Nullable Long discountPaise) {}

    public record QuoteDraft(@Nullable List<DraftLine> variants, @Nullable List<DraftTier> tiers, @Nullable Long minFulfilment, @Nullable Long maxUnits, @Nullable String validUntil) {}

    // ---- dealer views

    /** A price snapshot for one variant at one tier (one entry per tier in DealerQuote.snapshots[variant_id]). */
    public record TierSnapshot(long tierIndex, long discountPaise, long cashPayablePaise, String sha256, PriceOutput output) {}

    /** snapshots only once submitted (variant id -> one snapshot per tier). */
    public record DealerQuote(String id, String poolId, String state, long version, QuoteDraft draft, int currentVersion, @Nullable String submittedAt,
                              @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, List<TierSnapshot>> snapshots, List<Clarification> clarifications) {}

    // ---- clarifications (E071, E072)

    /** question is null once the asking buyer erased their account; answer is null while open. */
    public record Clarification(String id, String quoteId, String questionType, @Nullable String question, @Nullable String answer, String askedAt, @Nullable String answeredAt) {}

    public record Ask(@Nullable String questionType, @Nullable String text) {}

    public record Answer(@Nullable String text) {}

    public record RoundVariant(String id, String code, String name, @Nullable String fuel, @Nullable String transmission, @Nullable Integer engineCc, @Nullable Long catalogueExShowroomPaise,
                               @Nullable String catalogueEvidence, @Nullable Boolean cityConfirmed) {}

    public record Request(String roundId, String poolId, int roundSet, int roundNo, String kind, String deadline, String state, String stateCode,
                          List<RoundVariant> variants, long committedCount, @Nullable String quoteId, @Nullable String quoteState, @Nullable Long quoteVersion, String serverTime,
                          String rulesetVersion) {}

    public record Inbox(List<Request> requests) {}

    /** E068: exactly these fields (AZ-RNK-01..03: nothing about competitors). */
    public record Rank(String variantId, int rank, int ofN, String ruleVersion, String computedAt) {}

    public record Ranks(String quoteId, List<Rank> ranks) {}

    // ---- internal (E073)

    public record SummaryQuote(String quoteId, String dealershipId, String state, List<String> variantIds) {}

    public record RoundSummary(String roundId, String kind, long validCount, long finalCount, List<SummaryQuote> quotes) {}

    // ---- buyer views (E069, E070)

    public record Ceiling(int tierIndex, long cashPayablePaise, PriceOutput output, String snapshotSha256) {}

    public record Projection(String label, long count, boolean guaranteed, @Nullable Long cashPayablePaise, String note) {}

    public record TierView(long min, @Nullable Long max, long cashPayablePaise, boolean isCeiling) {}

    public record OfferVariant(String variantId, String variantCode, Ceiling ceiling, Projection projection, List<TierView> tiers, Stock stock) {}

    public record Offer(String quoteId, String dealershipId, String state, int versionNo, String committedAt, long minFulfilment, @Nullable Long maxUnits,
                        String snapshotSha256, List<OfferVariant> variants, List<Clarification> clarifications) {}

    public record Offers(String poolId, List<Offer> quotes, String rankingRule) {}
}
