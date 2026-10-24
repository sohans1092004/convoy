package convoy.platform.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verifies ES256 internal JWTs (auth-contract.md §3-5): trusted issuer, aud = this service, exp/nbf
 * with 30 s skew. JWKS are cached 5 min and refetched on an unknown kid (key rotation).
 */
public class JwtVerifier {
    public static final String GATEWAY = "convoy-gateway";
    public static final String IDENTITY = "convoy-identity";
    private final Map<String, DefaultJWTProcessor<SecurityContext>> processors = new HashMap<>();

    public JwtVerifier(String audience, Map<String, String> issuerJwks) {
        issuerJwks.forEach((iss, uri) -> {
            try {
                JWKSource<SecurityContext> src = JWKSourceBuilder.create(URI.create(uri).toURL())
                        .cache(5 * 60_000L, 15_000L).rateLimited(10_000L).retrying(true).build();
                DefaultJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
                p.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256, src));
                DefaultJWTClaimsVerifier<SecurityContext> v = new DefaultJWTClaimsVerifier<>(
                        Set.of(audience), new JWTClaimsSet.Builder().issuer(iss).build(), Set.of("sub", "exp", "iat"), null);
                v.setMaxClockSkew(30);
                p.setJWTClaimsSetVerifier(v);
                processors.put(iss, p);
            } catch (java.net.MalformedURLException e) {
                throw new IllegalArgumentException("bad JWKS uri " + uri, e);
            }
        });
    }

    /** Returns the caller, or throws if the token is not valid for this service. */
    public Caller verify(String token) throws Exception {
        String iss = SignedJWT.parse(token).getJWTClaimsSet().getIssuer();
        DefaultJWTProcessor<SecurityContext> p = processors.get(iss);
        if (p == null) throw new SecurityException("untrusted issuer " + iss);
        JWTClaimsSet c = p.process(token, null);
        List<String> roles = c.getStringListClaim("roles");
        List<String> scopes = c.getStringListClaim("scope");
        return new Caller(IDENTITY.equals(iss) ? Caller.Kind.SERVICE : Caller.Kind.USER, c.getSubject(),
                roles == null ? Set.of() : Set.copyOf(roles), c.getStringClaim("dealership_id"), c.getStringClaim("sid"),
                scopes == null ? List.of() : scopes);
    }
}
