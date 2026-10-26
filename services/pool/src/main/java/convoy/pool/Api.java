package convoy.pool;

import org.springframework.lang.Nullable;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/** Response records for every pool endpoint. Times are ISO-8601 UTC strings. */
public final class Api {
    private Api() {}

    public record Membership(String id, String poolId, String state, @Nullable String currentPaymentId, @Nullable String termsVersion, @Nullable String acceptedQuoteId,
                             @Nullable String acceptedVariantId, @Nullable String releaseReason, @Nullable String choiceDeadline, long version) {}

    public record Round(String id, int roundSet, int roundNo, String kind, String state, String deadline, @Nullable String closedAt) {}

    /** my_membership only for buyers (absent for dealers: AZ-E033-X); acceptance_counts (quote id -> count) only in DECISION_OPEN / CONFIRMATION. */
    public record Pool(String id, String modelSlug, String brand, String city, String stateCode, List<String> acceptedVariantIds, int minMembers,
                       String state, @Nullable String endReason, int roundSet, String purchaseMonth, @Nullable String formingDeadline, @Nullable String decisionDeadline,
                       @Nullable String confirmationDeadline, @Nullable String purchaseWindowEnd, long interestedCount, long committedCount, long version, String serverTime,
                       List<Round> rounds, long depositAmountPaise, String termsVersion, @JsonInclude(JsonInclude.Include.NON_NULL) Membership myMembership,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Long> acceptanceCounts) {}

    public record PoolCreated(boolean created, Pool pool) {}

    public record Deposit(String paymentId, String state, long amountPaise, String currency, @Nullable String razorpayOrderId, String razorpayKeyId, boolean testMode) {}

    public record Commitment(Membership membership, Deposit deposit) {}

    /** E031 body and response. Empty lists mean "any"; colours are matched against dealer stock later, not pools. */
    public record Preferences(@Nullable String city, @Nullable String purchaseMonth, List<String> variantIds, List<String> colours, List<String> fuels,
                              List<String> transmissions) {}

    /** E030: one result per requested saved model. */
    public record Suggestions(List<Matching.Result> results, String serverTime) {}

    public record StreamAccess(boolean allowed) {}

    public record PoolVersion(long version) {}
}
