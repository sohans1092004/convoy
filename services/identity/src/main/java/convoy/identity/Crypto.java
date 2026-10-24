package convoy.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Data-key crypto for personal data at rest: AES-256-GCM for ciphertext, HMAC-SHA256 for lookup hashes.
 * The 32-byte key comes from IDENTITY_DATA_KEY (base64). ponytail: one env key locally; KMS envelope
 * encryption in the full profile (P6).
 */
public final class Crypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec aes;
    private final SecretKeySpec hmac;

    public Crypto(String base64Key) {
        byte[] k = Base64.getDecoder().decode(base64Key);
        if (k.length != 32) throw new IllegalArgumentException("IDENTITY_DATA_KEY must be 32 bytes (base64)");
        aes = new SecretKeySpec(k, "AES");
        hmac = new SecretKeySpec(k, "HmacSHA256");
    }

    public byte[] encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, aes, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(12 + ct.length).put(iv).put(ct).array();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String decrypt(byte[] blob) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, aes, new GCMParameterSpec(128, blob, 0, 12));
            return new String(c.doFinal(blob, 12, blob.length - 12), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Keyed hash for lookups (emails, OTP codes); never reversible without the key. */
    public String hash(String value) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(hmac);
            return HexFormat.of().formatHex(m.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 256-bit random, base64url without padding (session ids). */
    public static String randomToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String otpCode() { return String.format("%06d", RANDOM.nextInt(1_000_000)); }

    public static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
