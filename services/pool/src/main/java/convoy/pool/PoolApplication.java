package convoy.pool;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.contracts.events.BookingsIssued;
import convoy.contracts.events.PaymentCaptured;
import convoy.contracts.events.PaymentFailed;
import convoy.platform.messaging.Subscriptions;
import convoy.platform.security.ServiceTokens;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@SpringBootApplication
@EnableConfigurationProperties(PoolApplication.PoolSettings.class)
public class PoolApplication {
    public static void main(String[] args) { SpringApplication.run(PoolApplication.class, args); }

    /**
     * @param minMembers      committed members needed before quoting (demo profile 1; production value is Q-042)
     * @param sweeperInterval deadline sweeper period (the scheduler wake-up of PLAN is a P6 concern)
     * @param durations       Q-022 durations; the demo profile shortens them through env
     * @param termsVersion    current pre-commit terms version (cancellation-rules.md); commits must echo it
     * @param services        callee base URLs for service-token calls
     */
    @ConfigurationProperties(prefix = "pool")
    public record PoolSettings(int minMembers, Duration sweeperInterval, Durations durations, String termsVersion, Map<String, String> services) {
        public record Durations(Duration forming, Duration sealedRound, Duration finalRound, Duration decision, Duration confirmation, Duration dealerWait) {}
    }

    /** Deposit amount from the ruleset (ruleset.yaml deposit_amount_paise). */
    @Bean
    @SuppressWarnings("unchecked")
    Long depositAmountPaise() throws Exception {
        try (InputStream in = PoolApplication.class.getResourceAsStream("/convoy/rules.json")) {
            Map<String, Object> rules = new ObjectMapper().enable(DeserializationFeature.USE_LONG_FOR_INTS).readValue(in, Map.class);
            return (Long) ((Map<String, Object>) rules.get("ruleset")).get("deposit_amount_paise");
        }
    }

    /** Service-token clients for pool's synchronous calls (auth-contract.md §5). */
    public record Clients(RestClient payment, RestClient quote, RestClient dealer, RestClient catalog) {}

    @Bean
    Clients clients(ServiceTokens tokens, RestClient.Builder builder, PoolSettings s) {
        return new Clients(tokens.client(builder, "payment", s.services().get("payment")), tokens.client(builder, "quote", s.services().get("quote")),
                tokens.client(builder, "dealer", s.services().get("dealer")), tokens.client(builder, "catalog", s.services().get("catalog")));
    }

    @Bean
    Subscriptions subscriptions(Pools pools) {
        return new Subscriptions()
                .on(PaymentCaptured.class, (e, p) -> pools.paymentCaptured(e, p))
                .on(PaymentFailed.class, (e, p) -> pools.paymentFailed(p))
                .on(BookingsIssued.class, (e, p) -> pools.bookingsIssued(e, p));
    }
}
