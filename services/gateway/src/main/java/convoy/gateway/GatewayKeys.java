package convoy.gateway;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * ES256 keys for internal user JWTs (auth-contract.md §3), one JWK file per key in a directory
 * (lite profile: files with 0400 permissions generated at first boot; KMS in full). Every key file is
 * published in the JWKS; the newest key whose not-before has passed signs. Rotation = drop a new key
 * file with a future not-before, wait 24 h, then remove the old file after its last use + 24 h.
 */
public class GatewayKeys {
    private final Path dir;
    private volatile List<ECKey> keys = List.of();

    public GatewayKeys(Path dir) {
        this.dir = dir;
        reload();
    }

    public synchronized void reload() {
        try {
            Files.createDirectories(dir);
            List<ECKey> found = new ArrayList<>();
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".jwk.json")).toList()) found.add(ECKey.parse(Files.readString(f)));
            }
            if (found.isEmpty()) found.add(generate(Instant.now()));
            keys = List.copyOf(found);
        } catch (IOException | ParseException | JOSEException e) {
            throw new IllegalStateException("cannot load gateway signing keys from " + dir, e);
        }
    }

    /** Create a key file; nbf in the future means "published now, signs later". */
    public ECKey generate(Instant notBefore) throws JOSEException, IOException {
        ECKey k = new ECKeyGenerator(Curve.P_256).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.ES256)
                .keyIDFromThumbprint(true).notBeforeTime(Date.from(notBefore)).generate();
        Path f = dir.resolve(k.getKeyID() + ".jwk.json");
        Files.writeString(f, k.toJSONString());
        try {
            Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("r--------"));
        } catch (UnsupportedOperationException e) {
            // non-POSIX filesystem (Windows dev): permissions not enforceable
        }
        return k;
    }

    ECKey signingKey() {
        Instant now = Instant.now();
        return keys.stream().filter(k -> k.getNotBeforeTime() == null || !k.getNotBeforeTime().toInstant().isAfter(now))
                .max(Comparator.comparing(k -> k.getNotBeforeTime() == null ? Instant.EPOCH : k.getNotBeforeTime().toInstant()))
                .orElseThrow(() -> new IllegalStateException("no active signing key"));
    }

    public Map<String, Object> jwks() {
        return new JWKSet(keys.stream().map(k -> (JWK) k.toPublicJWK()).toList()).toJSONObject(true);
    }

    public String sign(JWTClaimsSet claims) {
        ECKey k = signingKey();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(k.getKeyID()).build(), claims);
            jwt.sign(new ECDSASigner(k));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
