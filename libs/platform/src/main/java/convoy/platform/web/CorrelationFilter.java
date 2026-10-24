package convoy.platform.web;

import convoy.platform.messaging.EventContext;
import convoy.platform.messaging.Uuid7;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** Starts the request's event correlation (X-Correlation-Id or a new id) and clears it afterwards. */
public class CorrelationFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        UUID id;
        try {
            id = req.getHeader(HEADER) == null ? Uuid7.next() : UUID.fromString(req.getHeader(HEADER));
        } catch (IllegalArgumentException e) {
            id = Uuid7.next();
        }
        EventContext.set(id, null);
        MDC.put("correlation_id", id.toString());
        res.setHeader(HEADER, id.toString());
        try {
            chain.doFilter(req, res);
        } finally {
            EventContext.clear();
            MDC.remove("correlation_id");
        }
    }
}
