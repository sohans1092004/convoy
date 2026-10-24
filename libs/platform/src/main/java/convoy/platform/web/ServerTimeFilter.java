package convoy.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.temporal.ChronoUnit;
import org.springframework.web.filter.OncePerRequestFilter;

/** Every response carries Server-Time (ms precision, DB clock) for client countdown offsets. */
public class ServerTimeFilter extends OncePerRequestFilter {
    private final DbClock clock;

    public ServerTimeFilter(DbClock clock) { this.clock = clock; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        res.setHeader("Server-Time", clock.now().truncatedTo(ChronoUnit.MILLIS).toString());
        chain.doFilter(req, res);
    }
}
