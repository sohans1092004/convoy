package convoy.platform.problem;

import convoy.platform.web.DbClock;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Renders every error as RFC 9457 application/problem+json with the Convoy fields. */
@RestControllerAdvice
public class ProblemHandler {
    private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);
    public static final MediaType PROBLEM_JSON = MediaType.parseMediaType("application/problem+json");
    private final DbClock clock;

    public ProblemHandler(DbClock clock) { this.clock = clock; }

    public static Map<String, Object> body(ProblemType t, String detail, String instance, Map<String, Object> extras, String serverTime) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", t.uri());
        b.put("title", t.title);
        b.put("status", t.status);
        b.put("code", t.code());
        if (detail != null) b.put("detail", detail);
        if (instance != null) b.put("instance", instance);
        b.put("server_time", serverTime);
        b.putAll(extras);
        return b;
    }

    ResponseEntity<Map<String, Object>> render(ProblemType t, String detail, HttpServletRequest req, Map<String, Object> extras) {
        return ResponseEntity.status(t.status).contentType(PROBLEM_JSON)
                .body(body(t, detail, req.getRequestURI(), extras, clock.now().toString()));
    }

    @ExceptionHandler(ApiProblem.class)
    ResponseEntity<Map<String, Object>> problem(ApiProblem p, HttpServletRequest req) {
        if (p.type().status >= 500) log.warn("problem {} on {}: {}", p.type().slug, req.getRequestURI(), p.getMessage());
        return render(p.type(), p.getMessage(), req, p.extras());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e, HttpServletRequest req) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage()))).toList();
        return render(ProblemType.VALIDATION_FAILED, "Some fields are invalid.", req, Map.of("errors", errors));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class, MissingRequestHeaderException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<Map<String, Object>> unreadable(Exception e, HttpServletRequest req) {
        return render(ProblemType.VALIDATION_FAILED, e instanceof MissingRequestHeaderException || e instanceof org.springframework.web.bind.MissingServletRequestParameterException
                ? e.getMessage() : "Malformed request.", req, Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException e, HttpServletRequest req) {
        return render(ProblemType.NOT_FOUND, null, req, Map.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, Object>> method(HttpRequestMethodNotSupportedException e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).contentType(PROBLEM_JSON)
                .body(Map.of("title", "Method not allowed", "status", 405, "instance", req.getRequestURI()));
    }
}
