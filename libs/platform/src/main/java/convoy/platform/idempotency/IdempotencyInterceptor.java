package convoy.platform.idempotency;

import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.security.Caller;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** Enforces {@link Idempotent}: 428 without a key, 422 on body mismatch, replay of the stored response. */
public class IdempotencyInterceptor implements HandlerInterceptor {
    public static final String HEADER = "Idempotency-Key";
    static final String CLAIM_ATTR = IdempotencyInterceptor.class.getName() + ".claim";
    private static final long WAIT_MS = 10_000;
    private final IdempotencyStore store;

    public IdempotencyInterceptor(IdempotencyStore store) { this.store = store; }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod hm) || !hm.hasMethodAnnotation(Idempotent.class)) return true;
        String key = req.getHeader(HEADER);
        if (key == null || key.isBlank() || key.length() > 200) {
            throw ApiProblem.of(ProblemType.IDEMPOTENCY_KEY_MISSING, "Send an Idempotency-Key header (a UUID per user action).");
        }
        if (!(req instanceof IdempotencyFilter.CachedBodyRequest) && !(unwrap(req) instanceof IdempotencyFilter.CachedBodyRequest)) {
            throw new IllegalStateException("IdempotencyFilter must wrap idempotent requests");
        }
        byte[] body = ((IdempotencyFilter.CachedBodyRequest) unwrap(req)).body;
        String hash = sha256(req.getMethod() + " " + req.getRequestURI() + "\n", body);
        IdempotencyStore.Claim claim = new IdempotencyStore.Claim(key, Caller.current().subject());
        long until = System.currentTimeMillis() + WAIT_MS;
        while (true) {
            if (store.claim(claim, hash)) {
                req.setAttribute(CLAIM_ATTR, claim);
                return true;
            }
            Optional<IdempotencyStore.Stored> s = store.find(claim);
            if (s.isEmpty()) continue; // released by a failed attempt: claim again
            IdempotencyStore.Stored st = s.get();
            if (!st.requestSha256().equals(hash)) {
                throw ApiProblem.of(ProblemType.IDEMPOTENCY_KEY_REUSED, "This Idempotency-Key was used for a different request.");
            }
            if (st.status() != null) {
                res.setStatus(st.status());
                if (st.contentType() != null) res.setContentType(st.contentType());
                res.setHeader("Idempotent-Replayed", "true");
                if (st.body() != null) res.getOutputStream().write(st.body());
                return false;
            }
            if (st.abandoned() && store.takeOver(claim, hash)) {
                req.setAttribute(CLAIM_ATTR, claim);
                return true;
            }
            if (System.currentTimeMillis() > until) {
                throw ApiProblem.of(ProblemType.DEPENDENCY_UNAVAILABLE, "The first request with this key is still running; retry with the same key.");
            }
            Thread.sleep(50); // the same action is in flight (double click): wait for its response
        }
    }

    private static HttpServletRequest unwrap(HttpServletRequest r) {
        while (r instanceof jakarta.servlet.http.HttpServletRequestWrapper w && !(r instanceof IdempotencyFilter.CachedBodyRequest)) {
            r = (HttpServletRequest) w.getRequest();
        }
        return r;
    }

    static String sha256(String prefix, byte[] body) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(prefix.getBytes(StandardCharsets.UTF_8));
        md.update(body);
        return HexFormat.of().formatHex(md.digest());
    }
}
