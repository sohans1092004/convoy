package convoy.order;

import convoy.platform.security.ServiceTokens;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The car and dealership names on a buyer's order (U.1). Names are display data owned by catalog and dealer, so a failed
 * lookup returns null and the order still loads: money and state never depend on these services being up.
 */
@Component
public class OrderNames {
    private static final Logger log = LoggerFactory.getLogger(OrderNames.class);
    private final RestClient catalog;
    private final RestClient dealer;
    // ponytail: unbounded, process-lifetime memo; a few hundred entries at most on the lite host. A renamed dealership
    // shows after the next restart. Add a TTL if names start changing while orders are open.
    private final Map<UUID, Api.CarRef> cars = new ConcurrentHashMap<>();
    private final Map<UUID, String> dealerships = new ConcurrentHashMap<>();

    public OrderNames(ServiceTokens tokens, RestClient.Builder builder,
                      @Value("${order.catalog-url:http://catalog:8080}") String catalogUrl,
                      @Value("${order.dealer-url:http://dealer:8080}") String dealerUrl) {
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(1));
        timeouts.setReadTimeout(Duration.ofSeconds(2));
        this.catalog = tokens.client(builder.clone().requestFactory(timeouts), "catalog", catalogUrl);
        this.dealer = tokens.client(builder.clone().requestFactory(timeouts), "dealer", dealerUrl);
    }

    public Api.CarRef car(UUID variantId) {
        Api.CarRef known = cars.get(variantId);
        if (known != null) return known;
        try {
            Api.CarRef found = catalog.get().uri("/internal/variants/{id}", variantId).retrieve().body(Api.CarRef.class);
            if (found != null) cars.put(variantId, found);
            return found;
        } catch (RuntimeException e) {
            log.warn("car name lookup failed for variant {}: {}", variantId, e.toString());
            return null;
        }
    }

    public String dealership(UUID id) {
        String known = dealerships.get(id);
        if (known != null) return known;
        try {
            Map<?, ?> found = dealer.get().uri("/internal/dealerships/{id}/name", id).retrieve().body(Map.class);
            String name = found == null ? null : (String) found.get("trade_name");
            if (name != null) dealerships.put(id, name);
            return name;
        } catch (RuntimeException e) {
            log.warn("dealership name lookup failed for {}: {}", id, e.toString());
            return null;
        }
    }
}
