package convoy.platform.problem;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An RFC 9457 problem (docs/architecture/problem-types.yaml). Throw it anywhere in a request;
 * {@link ProblemHandler} renders application/problem+json with type, title, status, code and extras.
 */
public class ApiProblem extends RuntimeException {
    private final ProblemType type;
    private final Map<String, Object> extras = new LinkedHashMap<>();

    public ApiProblem(ProblemType type, String detail) {
        super(detail);
        this.type = type;
    }

    public static ApiProblem of(ProblemType type, String detail) { return new ApiProblem(type, detail); }

    public ApiProblem with(String key, Object value) {
        extras.put(key, value);
        return this;
    }

    public ProblemType type() { return type; }
    public Map<String, Object> extras() { return extras; }
}
