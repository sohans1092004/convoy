package convoy.identity;

import org.springframework.lang.Nullable;
import java.util.List;

/** Response records for every identity endpoint (snake_case on the wire). */
public final class Api {
    private Api() {}

    public record OtpStarted(String status, int expiresInSeconds) {}

    public record User(String id, List<String> roles) {}

    public record Session(String sessionId, String sessionExpiresAt, User user) {}

    public record Status(String status) {}

    public record Consent(String purpose, boolean granted, String noticeVersion, String at) {}

    public record Me(String id, String email, List<String> roles, @Nullable String dealershipId, List<Consent> consents) {}

    public record ConsentEntry(boolean granted, String noticeVersion, String at) {}

    public record ConsentHistory(String purpose, boolean granted, List<ConsentEntry> history) {}

    public record SessionValidation(String userId, List<String> roles, @Nullable String dealershipId, String sid, String sessionExpiresAt) {}

    public record ServiceToken(String accessToken, String tokenType, long expiresIn, List<String> scope) {}
}
