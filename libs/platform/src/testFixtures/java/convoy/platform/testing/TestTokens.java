package convoy.platform.testing;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import convoy.platform.security.JwtVerifier;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Mints internal JWTs for service tests: gateway user tokens and identity service tokens, plus bad ones. */
public final class TestTokens {
    public static final ECKey GATEWAY_KEY = key(), IDENTITY_KEY = key(), ROGUE_KEY = key();

    private TestTokens() {}

    private static ECKey key() {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID(UUID.randomUUID().toString()).algorithm(JWSAlgorithm.ES256).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The verifier a service under test uses: trusts the two test keys only. */
    public static JwtVerifier verifier(String audience) {
        JWKSource<SecurityContext> gw = new ImmutableJWKSet<>(new JWKSet(GATEWAY_KEY.toPublicJWK()));
        JWKSource<SecurityContext> id = new ImmutableJWKSet<>(new JWKSet(IDENTITY_KEY.toPublicJWK()));
        return new JwtVerifier(audience, Map.of(JwtVerifier.GATEWAY, gw, JwtVerifier.IDENTITY, id), true);
    }

    public static String sign(ECKey key, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new ECDSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String user(String audience, UUID userId, List<String> roles, UUID dealershipId) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder c = new JWTClaimsSet.Builder().issuer(JwtVerifier.GATEWAY).audience(audience).subject(userId.toString())
                .claim("roles", roles).claim("sid", "sid-" + userId).issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(120)))
                .jwtID(UUID.randomUUID().toString());
        if (dealershipId != null) c.claim("dealership_id", dealershipId.toString());
        return sign(GATEWAY_KEY, c.build());
    }

    public static String buyer(String audience, UUID userId) { return user(audience, userId, List.of("buyer"), null); }

    public static String dealer(String audience, UUID userId, UUID dealershipId) { return user(audience, userId, List.of("dealer_staff"), dealershipId); }

    public static String admin(String audience, UUID userId) { return user(audience, userId, List.of("platform_admin"), null); }

    public static String anonymous(String audience) { return user(audience, UUID.randomUUID(), List.of(), null); }

    public static String service(String audience, String client, List<String> scopes) {
        Instant now = Instant.now();
        return sign(IDENTITY_KEY, new JWTClaimsSet.Builder().issuer(JwtVerifier.IDENTITY).audience(audience).subject(client).claim("scope", scopes)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300))).build());
    }

    public static String expired(String audience, UUID userId) {
        Instant then = Instant.now().minusSeconds(600);
        return sign(GATEWAY_KEY, new JWTClaimsSet.Builder().issuer(JwtVerifier.GATEWAY).audience(audience).subject(userId.toString())
                .claim("roles", List.of("buyer")).issueTime(Date.from(then)).expirationTime(Date.from(then.plusSeconds(120))).build());
    }

    public static String forged(String audience, UUID userId) {
        Instant now = Instant.now();
        return sign(ROGUE_KEY, new JWTClaimsSet.Builder().issuer(JwtVerifier.GATEWAY).audience(audience).subject(userId.toString())
                .claim("roles", List.of("buyer", "platform_admin")).issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(120))).build());
    }
}
