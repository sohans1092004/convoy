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

    public record Dealership(String id, String tradeName, String city, String stateCode, List<String> brands, String status, boolean synthetic, @Nullable String verifiedAt) {}
}
