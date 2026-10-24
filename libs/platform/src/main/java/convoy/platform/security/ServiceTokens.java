package convoy.platform.security;

import convoy.platform.ConvoyProperties;
import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Service-to-service tokens (auth-contract.md §5): client credentials at identity
 * POST /internal/oauth/token, cached per audience until 30 s before expiry.
 */
public class ServiceTokens {
    private record Token(String value, Instant expires) {}

    private final ConvoyProperties props;
    private final RestClient identity;
    private final Map<String, Token> cache = new ConcurrentHashMap<>();

    public ServiceTokens(ConvoyProperties props, RestClient.Builder builder) {
        this.props = props;
        this.identity = builder.clone().baseUrl(String.valueOf(props.identityUrl())).build();
    }

    @SuppressWarnings("unchecked")
    public String tokenFor(String audience) {
        Token t = cache.get(audience);
        if (t != null && Instant.now().isBefore(t.expires().minusSeconds(30))) return t.value();
        try {
            Map<String, Object> r = identity.post().uri("/internal/oauth/token")
                    .body(Map.of("grant_type", "client_credentials", "client_id", props.clientId(),
                            "client_secret", props.clientSecret(), "audience", audience))
                    .retrieve().body(Map.class);
            Token fresh = new Token((String) r.get("access_token"), Instant.now().plusSeconds(((Number) r.get("expires_in")).longValue()));
            cache.put(audience, fresh);
            return fresh.value();
        } catch (RestClientException e) {
            throw ApiProblem.of(ProblemType.DEPENDENCY_UNAVAILABLE, "Could not obtain a service token for " + audience + ".");
        }
    }

    /** A RestClient for calling {@code audience} at {@code baseUrl} with a fresh service token on every request. */
    public RestClient client(RestClient.Builder builder, String audience, String baseUrl) {
        ClientHttpRequestInterceptor auth = (req, body, exec) -> {
            req.getHeaders().setBearerAuth(tokenFor(audience));
            return exec.execute(req, body);
        };
        return builder.clone().baseUrl(baseUrl).requestInterceptor(auth).build();
    }
}
