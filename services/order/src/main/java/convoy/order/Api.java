package convoy.order;

import org.springframework.lang.Nullable;
import convoy.contracts.api.PriceOutput;
import java.util.List;
import java.util.Map;

/** Response records for every order endpoint. Times are ISO-8601 UTC strings. */
public final class Api {
    private Api() {}

    public record OrderRow(String id, String poolId, String quoteId, String state, String confirmationDeadline, long version) {}

    public record OrderList(List<OrderRow> orders) {}

    public record Snapshot(int tierIndex, PriceOutput output, String sha256, String computedAt) {}

    public record BookingRef(String id, String state, long version, @Nullable String promisedDeliveryDate, String dealerConfirmDeadline, String dealershipId, boolean voucherActive,
                             @Nullable String scheduledDate, @Nullable String deliverySlot, @Nullable String deliveredAt, boolean overdueFlag, @Nullable String cancelReason,
                             @Nullable String cancelledAt, @Nullable String contactReleasedAt) {}

    public record DisputeRef(String id, String category, String state, String openedBy, String createdAt) {}

    /** snapshots keyed by kind in lower case: ceiling, final. booking is null until issued. */
    public record Order(String id, String poolId, int roundSet, String buyerId, String quoteId, String variantId, String state, String confirmationDeadline,
                        @Nullable String confirmedAt, @Nullable String cancelledAt, @Nullable String declineReason, @Nullable String cancelReason, long version, String createdAt,
                        @Nullable String ceilingSnapshotId, @Nullable Long confirmedCeilingPaise,
                        Map<String, Snapshot> snapshots, @Nullable BookingRef booking, List<DisputeRef> disputes, String dealershipId, String serverTime) {}

    public record Voucher(String id, String state, String qrPayload, @Nullable String promisedDeliveryDate, String dealershipId, String verification) {}

    public record DealerBooking(String id, String orderId, String state, long version, @Nullable String promisedDeliveryDate, String dealerConfirmDeadline,
                                @Nullable String scheduledDate, @Nullable String deliverySlot, @Nullable String deliveredAt, boolean overdueFlag, @Nullable String cancelReason,
                                boolean contactReleased, String variantId, long finalCashPayablePaise) {}

    public record BuyerContact(String bookingId, String email, String consentVersion, String consentedAt, String releasedAt) {}

    public record DisputeEvent(String actorRole, String action, @Nullable String fromState, @Nullable String toState, @Nullable String note, String at) {}

    public record EvidenceFile(String id, String uploaderRole, String fileName, String contentType, long sizeBytes, String createdAt, String downloadUrl) {}

    public record Dispute(String id, String orderId, @Nullable String bookingId, String dealershipId, String buyerId, String category, String description, String state,
                          String openedBy, @Nullable String ownerId, @Nullable String awaitingParty, @Nullable String respondBy, @Nullable String outcome,
                          @Nullable String proposedOutcome, @Nullable String resolvedAt, @Nullable String appealUntil, int appealCount, long version, String createdAt,
                          List<DisputeEvent> history, List<EvidenceFile> evidence) {}

    public record DisputeRow(String id, String orderId, String dealershipId, String category, String state, String openedBy, @Nullable String ownerId,
                             @Nullable String proposedOutcome, @Nullable String proposedBy, String createdAt) {}

    public record DisputeQueue(List<DisputeRow> disputes) {}

    public record EvidenceUpload(String evidenceId, String uploadUrl, String method, Map<String, String> headers, long expiresInSeconds) {}

    public record DealerBookings(List<DealerBooking> bookings) {}
}
