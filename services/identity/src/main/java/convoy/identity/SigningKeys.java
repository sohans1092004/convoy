package convoy.identity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Identity's ES256 key for service tokens. Generated at first boot and stored encrypted in
 * signing_keys; every non-retired public key is published at /.well-known/jwks.json, so a new key
 * can be added (rotation) while tokens signed by the old one are still valid.
 */
public class SigningKeys {
    private final JdbcTemplate jdbc;
    private final Crypto crypto;
    private volatile ECKey active;

    public SigningKeys(JdbcTemplate jdbc, Crypto crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        load();
    }

    private void load() {
        List<String> rows = jdbc.query("select private_enc from signing_keys where status = 'ACTIVE' order by created_at desc limit 1",
                (rs, i) -> crypto.decrypt(rs.getBytes(1)));
        try {
            if (rows.isEmpty()) {
                ECKey k = new ECKeyGenerator(Curve.P_256).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.ES256)
                        .keyIDFromThumbprint(true).generate();
                jdbc.update("insert into signing_keys(kid, public_jwk, private_enc, status) values (?, ?, ?, 'ACTIVE') on conflict do nothing",
                        k.getKeyID(), k.toPublicJWK().toJSONString(), crypto.encrypt(k.toJSONString()));
                active = k;
            } else {
                active = ECKey.parse(rows.get(0));
            }
        } catch (JOSEException | ParseException e) {
            throw new IllegalStateException(e);
        }
    }

    public Map<String, Object> jwks() {
        List<com.nimbusds.jose.jwk.JWK> keys = jdbc.query("select public_jwk from signing_keys where status = 'ACTIVE'", (rs, i) -> {
            try {
                return ECKey.parse(rs.getString(1));
            } catch (ParseException e) {
                throw new IllegalStateException(e);
            }
        });
        return new JWKSet(keys).toJSONObject(true);
    }

    public String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(active.getKeyID()).build(), claims);
            jwt.sign(new ECDSASigner(active));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
