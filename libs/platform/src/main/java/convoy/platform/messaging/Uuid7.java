package convoy.platform.messaging;

import java.security.SecureRandom;
import java.util.UUID;

/** UUIDv7 (RFC 9562): 48-bit Unix ms timestamp + random. Time-ordered ids for every aggregate and event. */
public final class Uuid7 {
    private static final SecureRandom RANDOM = new SecureRandom();

    private Uuid7() {}

    public static UUID next() {
        long ms = System.currentTimeMillis();
        long hi = (ms << 16) | 0x7000L | (RANDOM.nextInt() & 0x0FFF);
        long lo = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(hi, lo);
    }
}
