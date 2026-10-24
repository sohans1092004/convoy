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

    public record BookingRef(String id, String state, @Nullable String promisedDeliveryDate, String dealerConfirmDeadline, String dealershipId) {}

    /** snapshots keyed by kind in lower case: ceiling, final. booking is null until issued. */
    public record Order(String id, String poolId, int roundSet, String buyerId, String quoteId, String variantId, String state, String confirmationDeadline,
                        @Nullable String confirmedAt, @Nullable String cancelledAt, @Nullable String declineReason, @Nullable String cancelReason, long version, String createdAt,
                        Map<String, Snapshot> snapshots, @Nullable BookingRef booking, String serverTime) {}

    public record Voucher(String id, String state, String qrPayload, @Nullable String promisedDeliveryDate, String dealershipId, String verification) {}

    public record DealerBooking(String id, String orderId, String state, long version, @Nullable String promisedDeliveryDate, String dealerConfirmDeadline,
                                String variantId, long finalCashPayablePaise) {}

    public record DealerBookings(List<DealerBooking> bookings) {}
}
