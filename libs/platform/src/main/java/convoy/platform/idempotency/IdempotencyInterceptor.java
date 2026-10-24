package convoy.platform.idempotency;

import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.platform.security.Caller;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link Idempotent} before binding: 428 without a key, 422 when the key was used for a different
 * request, replay of a stored response. New keys continue to {@link IdempotentHandlerAdapter}.
 */
public class IdempotencyInterceptor implements HandlerInterceptor {
    public static final String HEADER = "Idempotency-Key";
    static final String KEY_ATTR = IdempotencyInterceptor.class.getName() + ".key";
    private final IdempotencyStore store;

    public IdempotencyInterceptor(IdempotencyStore store) { this.store = store; }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod hm) || !hm.hasMethodAnnotation(Idempotent.class)) return true;
        String key = req.getHeader(HEADER);
        if (key == null || key.isBlank() || key.length() > 200) {
            throw ApiProblem.of(ProblemType.IDEMPOTENCY_KEY_MISSING, "Send an Idempotency-Key header (a UUID per user action).");
        }
        if (!(unwrap(req) instanceof IdempotencyFilter.CachedBodyRequest cached)) {
            throw new IllegalStateException("IdempotencyFilter must wrap idempotent requests");
        }
        IdempotencyStore.Key k = new IdempotencyStore.Key(key, Caller.current().subject(),
                sha256(req.getMethod() + " " + req.getRequestURI() + "\n", cached.body));
        Optional<IdempotencyStore.Stored> stored = store.find(k.key(), k.caller());
        if (stored.isPresent()) {
            replay(k, stored.get(), res);
            return false;
        }
        req.setAttribute(KEY_ATTR, k);
        return true;
    }

    /** Writes the stored response, or 422 if the key belongs to a different request. */
    static void replay(IdempotencyStore.Key k, IdempotencyStore.Stored st, HttpServletResponse res) throws IOException {
        if (!st.requestSha256().equals(k.requestSha256())) {
            throw ApiProblem.of(ProblemType.IDEMPOTENCY_KEY_REUSED, "This Idempotency-Key was used for a different request.");
        }
        res.setStatus(st.status());
        if (st.contentType() != null) res.setContentType(st.contentType());
        res.setHeader("Idempotent-Replayed", "true");
        if (st.body() != null) res.getOutputStream().write(st.body());
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
