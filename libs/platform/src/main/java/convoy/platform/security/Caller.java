package convoy.platform.security;

import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/** The authenticated caller of the current request, from a verified internal JWT. */
public record Caller(Kind kind, String subject, Set<String> roles, String dealershipId, String sessionHash, List<String> scopes) {
    public enum Kind { USER, SERVICE }

    static final String ATTR = Caller.class.getName();

    public static Caller current() {
        RequestAttributes a = RequestContextHolder.getRequestAttributes();
        Object c = a == null ? null : a.getAttribute(ATTR, RequestAttributes.SCOPE_REQUEST);
        if (c == null) throw ApiProblem.of(ProblemType.UNAUTHENTICATED, "No verified caller.");
        return (Caller) c;
    }

    public boolean has(String role) { return roles.contains(role); }

    /** Require one of the roles, else 403. */
    public Caller require(String... anyOf) {
        for (String r : anyOf) if (has(r)) return this;
        throw ApiProblem.of(ProblemType.FORBIDDEN, "This action needs role " + String.join(" or ", anyOf) + ".");
    }

    /** A service token must carry the scope (AZ-SVC-01..03), else 403. */
    public Caller requireScope(String scope) {
        if (kind != Kind.SERVICE || !scopes.contains(scope)) throw ApiProblem.of(ProblemType.FORBIDDEN, "Missing scope " + scope + ".");
        return this;
    }

    public UUID userId() { return UUID.fromString(subject); }
}
