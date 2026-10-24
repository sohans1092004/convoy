package convoy.contracts.api;

import java.util.List;

/** RFC 7517 JWK set with public EC keys only. */
public record Jwks(List<Key> keys) {
    public record Key(String kty, String crv, String x, String y, String kid, String use, String alg, Long nbf) {}
}
