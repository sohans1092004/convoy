package convoy.dealer;

import org.springframework.lang.Nullable;
import java.util.List;

/** Response records for every dealer endpoint. */
public final class Api {
    private Api() {}

    public record Eligibility(String brand, String city, List<String> dealershipIds, int count) {}

    public record Registration(String id, String tradeName, String legalName, String gstin, String city, String stateCode, List<String> brands,
                               String status, @Nullable String rejectionReason, long version, String createdAt) {}

    public record Queue(List<Registration> dealerships) {}

    /** AMENDMENT-009: honoured / total FINAL commitments over window_days; score_percent is null when total is 0. */
    public record Reliability(long honoured, long total, @Nullable Long scorePercent, int windowDays) {}

    public record Dealership(String id, String tradeName, String city, String stateCode, List<String> brands, String status, boolean synthetic, @Nullable String verifiedAt,
                             Reliability reliability) {}

    public record Review(String id, String bookingId, String dealershipId, int rating, String body) {}

    public record PublicReview(String id, int rating, String body, String createdAt) {}

    public record Reviews(String dealershipId, long count, @Nullable Double averageRating, List<PublicReview> reviews) {}
}
