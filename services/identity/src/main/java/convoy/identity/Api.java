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

    /** E003: what navigator.credentials.create needs (binary values base64url). */
    public record PasskeyRegistrationOptions(String challengeId, String challenge, String rpId, String rpName, String userHandle,
                                             String userName, long timeoutMs, List<String> excludeCredentials) {}

    /** E005 step 1: what navigator.credentials.get needs (discoverable credential: no allow-list). */
    public record PasskeyAuthenticationOptions(String challengeId, String challenge, String rpId, long timeoutMs) {}

    public record PasskeyRegistered(String credentialId, String createdAt) {}

    public record StaffInvite(String inviteId, String role, String expiresAt) {}

    public record StaffMembership(String dealershipId, String role) {}

    public record SessionCheck(List<String> invalid) {}

    public record ServiceToken(String accessToken, String tokenType, long expiresIn, List<String> scope) {}
}
