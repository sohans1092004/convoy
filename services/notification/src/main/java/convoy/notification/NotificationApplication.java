package convoy.notification;

import convoy.contracts.events.*;
import convoy.platform.messaging.Subscriptions;
import convoy.platform.security.ServiceTokens;
import java.time.Duration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * Notification (wave 2, P5.A): turns user-facing events into one in-app message and one email per user per event.
 * Subscriptions are EVENTS kind (AMENDMENT-010): inbox dedupe only, so an unordered queue or a backlog loses nothing.
 * Nothing upstream waits for it: when it is down, events wait in its SQS queues.
 */
@SpringBootApplication
@EnableConfigurationProperties(NotificationApplication.Settings.class)
public class NotificationApplication {
    public static void main(String[] args) { SpringApplication.run(NotificationApplication.class, args); }

    /** Email retries: attempt n waits base * 2^n (capped at maxBackoff); after maxAttempts the delivery is DEAD. */
    @ConfigurationProperties(prefix = "notification")
    public record Settings(String mailFrom, int maxAttempts, Duration baseBackoff, Duration maxBackoff, Duration pollEvery) {}

    @Bean
    Subscriptions subscriptions(Inbox in) {
        return new Subscriptions()
                // recipient graph
                .onUnordered(MemberCommitted.class, (e, p) -> in.member(e, p.poolId(), p.buyerId(), true))
                .onUnordered(MemberLeft.class, (e, p) -> in.member(e, p.poolId(), p.buyerId(), false))
                .onUnordered(MemberReleased.class, (e, p) -> in.member(e, p.poolId(), p.buyerId(), false))
                .on(BookingIssued.class, (e, p) -> in.link("booking:" + p.bookingId(), "order:" + p.orderId()))
                // messages (some also add links)
                .onUnordered(PoolReady.class, (e, p) -> in.event(e, "POOL_READY", "pool:" + p.poolId()))
                .onUnordered(QuoteRoundOpened.class, (e, p) -> in.event(e, "ROUND_OPENED", "pool:" + p.poolId()))
                .onUnordered(FinalRoundOpened.class, (e, p) -> in.event(e, "FINAL_ROUND_OPENED", "pool:" + p.poolId()))
                .onUnordered(RoundClosed.class, (e, p) -> in.event(e, "ROUND_CLOSED", "pool:" + p.poolId()))
                .onUnordered(DecisionOpened.class, (e, p) -> in.event(e, "COMPARISON_READY", "pool:" + p.poolId()))
                .on(OrderCreated.class, (e, p) -> {
                    in.link("order:" + p.orderId(), "user:" + p.buyerId());
                    in.link("quote:" + p.quoteId(), "order:" + p.orderId());
                    in.event(e, "CONFIRMATION_NEEDED", "order:" + p.orderId());
                })
                .on(TierResolved.class, (e, p) -> in.event(e, "TIER_RESOLVED", "quote:" + p.quoteId()))
                .on(PaymentCaptured.class, (e, p) -> in.event(e, "DEPOSIT_RECEIVED", "user:" + p.buyerId()))
                .on(RefundCompleted.class, (e, p) -> in.event(e, "REFUND_DONE", "user:" + p.buyerId()))
                .on(BookingConfirmedByDealer.class, (e, p) -> in.event(e, "BOOKING_CONFIRMED", "booking:" + p.bookingId()))
                .on(DeliveryScheduled.class, (e, p) -> in.event(e, "DELIVERY_SCHEDULED", "booking:" + p.bookingId()))
                .on(DisputeOpened.class, (e, p) -> {
                    in.link("dispute:" + p.disputeId(), "order:" + p.orderId());
                    in.event(e, "DISPUTE_OPENED", "dispute:" + p.disputeId());
                })
                .on(DisputeStatusChanged.class, (e, p) -> in.event(e, "DISPUTE_UPDATED", "dispute:" + p.disputeId()))
                .on(DisputeResolved.class, (e, p) -> in.event(e, "DISPUTE_RESOLVED", "dispute:" + p.disputeId()));
    }

    /** E157 with scope contacts:read: the recipient's email address, read at send time (never stored here). */
    @Bean
    RestClient identityClient(ServiceTokens tokens, RestClient.Builder builder, convoy.platform.ConvoyProperties p) {
        return tokens.client(builder, "identity", p.identityUrl());
    }
}
