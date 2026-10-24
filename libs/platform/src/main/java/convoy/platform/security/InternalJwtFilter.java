package convoy.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.platform.problem.ProblemHandler;
import convoy.platform.problem.ProblemType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * auth-contract.md §4: accept only a valid internal JWT; reject caller-supplied identity headers (400 +
 * security log, AZ-INT-03); /internal/** needs a service token, every other path a user token.
 */
public class InternalJwtFilter extends OncePerRequestFilter {
    private static final Logger SECURITY = LoggerFactory.getLogger("convoy.security");
    static final List<String> IDENTITY_HEADER_PREFIXES = List.of("x-user-", "x-convoy-session", "x-forwarded-user", "x-roles");
    private final JwtVerifier verifier;
    private final List<String> publicPaths;
    private final ObjectMapper json;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public InternalJwtFilter(JwtVerifier verifier, List<String> publicPaths, ObjectMapper json) {
        this.verifier = verifier;
        this.publicPaths = publicPaths;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String path = req.getRequestURI();
        for (String h : Collections.list(req.getHeaderNames())) {
            String n = h.toLowerCase();
            if (IDENTITY_HEADER_PREFIXES.stream().anyMatch(n::startsWith)) {
                SECURITY.warn("rejected client-supplied identity header {} on {} {}", h, req.getMethod(), path);
                fail(res, ProblemType.VALIDATION_FAILED, "Identity headers are not accepted.", path);
                return;
            }
        }
        if (publicPaths.stream().anyMatch(p -> matcher.match(p, path))) {
            chain.doFilter(req, res);
            return;
        }
        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            fail(res, ProblemType.UNAUTHENTICATED, "Missing internal token.", path);
            return;
        }
        Caller caller;
        try {
            caller = verifier.verify(auth.substring(7));
        } catch (Exception e) {
            SECURITY.warn("rejected token on {} {}: {}", req.getMethod(), path, e.getMessage());
            fail(res, ProblemType.UNAUTHENTICATED, "Invalid internal token.", path);
            return;
        }
        boolean internal = path.startsWith("/internal/");
        if (internal != (caller.kind() == Caller.Kind.SERVICE)) {
            SECURITY.warn("token kind {} not accepted on {}", caller.kind(), path);
            fail(res, ProblemType.FORBIDDEN, internal ? "Service token required." : "User token required.", path);
            return;
        }
        req.setAttribute(Caller.ATTR, caller);
        chain.doFilter(req, res);
    }

    private void fail(HttpServletResponse res, ProblemType t, String detail, String path) throws IOException {
        res.setStatus(t.status);
        res.setContentType(ProblemHandler.PROBLEM_JSON.toString());
        json.writeValue(res.getOutputStream(), ProblemHandler.body(t, detail, path, Map.of(), Instant.now().toString()));
    }
}
