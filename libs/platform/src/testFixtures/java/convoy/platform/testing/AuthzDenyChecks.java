package convoy.platform.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * The AZ-&lt;id&gt;-D half of the authorisation matrix, run for every P1 endpoint of a service
 * (api-outline.md conventions): no token, forged token, expired token -> 401; caller-supplied identity
 * headers -> 400 (AZ-INT-03); a token kind or role not allowed for the endpoint -> 401/403/404.
 */
public final class AuthzDenyChecks {
    private AuthzDenyChecks() {}

    public interface Sender {
        ServiceTest.Resp send(String method, String path, String token, String body, Map<String, String> headers);
    }

    static final List<String> HUMAN_ROLES = List.of("buyer", "dealer_staff", "dealer_admin", "platform_admin");

    /** Returns failures as "AZ-E0xx-D: ..." strings (empty = all denied as specified). */
    public static List<String> run(String service, Sender s, BiFunction<String, String, String> body) {
        List<String> failures = new ArrayList<>();
        String any = UUID.randomUUID().toString();
        for (ApiOutline.Endpoint e : ApiOutline.p1(service)) {
            String path = e.concrete(any);
            String b = body.apply(e.method(), e.path());
            Map<String, String> h = Map.of("Idempotency-Key", UUID.randomUUID().toString(), "If-Match", "\"1\"");
            String tag = e.id() + "-D";
            boolean anonymousAllowed = e.roles().contains("anonymous");
            boolean webhook = e.roles().contains("razorpay");
            if (webhook) continue; // HMAC-authenticated, covered by AZ-INT-06
            if (!anonymousAllowed && !e.path().equals("/internal/oauth/token")) {
                expect(failures, tag + " no token", s.send(e.method(), path, null, b, h), Set.of(401));
                expect(failures, tag + " forged token", s.send(e.method(), path, TestTokens.forged(service, UUID.randomUUID()), b, h), Set.of(401));
                expect(failures, tag + " expired token", s.send(e.method(), path, TestTokens.expired(service, UUID.randomUUID()), b, h), Set.of(401));
                expect(failures, tag + " token for another audience", s.send(e.method(), path, TestTokens.buyer("not-" + service, UUID.randomUUID()), b, h), Set.of(401));
            }
            String allowedToken = e.internal() ? TestTokens.service(service, "pool", List.of("*")) : TestTokens.buyer(service, UUID.randomUUID());
            Map<String, String> spoof = new java.util.HashMap<>(h);
            spoof.put("X-User-Id", UUID.randomUUID().toString());
            expect(failures, "AZ-INT-03 " + tag + " identity header", s.send(e.method(), path, allowedToken, b, spoof), Set.of(400));
            if (e.internal()) {
                expect(failures, tag + " human token on internal", s.send(e.method(), path, TestTokens.admin(service, UUID.randomUUID()), b, h), Set.of(403));
                expect(failures, tag + " service token without scope", s.send(e.method(), path, TestTokens.service(service, "intruder", List.of()), b, h), Set.of(403));
            } else if (!anonymousAllowed && !e.roles().contains("authenticated")) {
                expect(failures, tag + " service token on human endpoint", s.send(e.method(), path, TestTokens.service(service, "pool", List.of("*")), b, h), Set.of(403));
                for (String role : HUMAN_ROLES) {
                    if (e.roles().contains(role)) continue;
                    String t = TestTokens.user(service, UUID.randomUUID(), List.of(role), role.startsWith("dealer") ? UUID.randomUUID() : null);
                    expect(failures, tag + " role " + role, s.send(e.method(), path, t, b, h), Set.of(401, 403, 404));
                }
            }
        }
        return failures;
    }

    private static void expect(List<String> failures, String what, ServiceTest.Resp r, Set<Integer> codes) {
        if (!codes.contains(r.status())) failures.add(what + ": got " + r.status() + " " + r.body().substring(0, Math.min(160, r.body().length())));
    }
}
