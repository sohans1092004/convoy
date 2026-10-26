package convoy.order;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Signs booking vouchers (booking.yaml: QR encodes an ES256 token {booking_id, voucher_code, exp} with kid). */
@Component
public class Vouchers {
    private final ECKey key;

    public Vouchers(JdbcTemplate jdbc) throws JOSEException, ParseException {
        List<String> rows = jdbc.queryForList("select private_jwk from voucher_keys order by created_at desc limit 1", String.class);
        if (rows.isEmpty()) {
            ECKey k = new ECKeyGenerator(Curve.P_256).algorithm(JWSAlgorithm.ES256).keyIDFromThumbprint(true).generate();
            jdbc.update("insert into voucher_keys(kid, private_jwk) values (?, ?) on conflict do nothing", k.getKeyID(), k.toJSONString());
            rows = jdbc.queryForList("select private_jwk from voucher_keys order by created_at desc limit 1", String.class);
        }
        this.key = ECKey.parse(rows.get(0));
    }

    /** BKG-T05 guard: our signature, not expired, and issued for this booking. */
    public boolean valid(String token, UUID bookingId) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            return jwt.verify(new com.nimbusds.jose.crypto.ECDSAVerifier(key.toECPublicKey())) && bookingId.toString().equals(jwt.getJWTClaimsSet().getSubject())
                    && jwt.getJWTClaimsSet().getExpirationTime().after(new Date());
        } catch (ParseException | JOSEException | RuntimeException e) {
            return false;
        }
    }

    public String sign(UUID bookingId, String voucherCode, Instant expires) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).build(), new JWTClaimsSet.Builder()
                    .issuer("convoy-order").subject(bookingId.toString()).claim("voucher_code", voucherCode)
                    .issueTime(new Date()).expirationTime(Date.from(expires)).build());
            jwt.sign(new ECDSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
