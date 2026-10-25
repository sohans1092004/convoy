package convoy.platform.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/** Buffers the body of mutating requests that carry an Idempotency-Key, so it can be hashed before binding. */
public class IdempotencyFilter extends OncePerRequestFilter {
    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !MUTATING.contains(req.getMethod()) || req.getHeader(IdempotencyInterceptor.HEADER) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        chain.doFilter(new CachedBodyRequest(req), res);
    }

    /** Request whose body can be read more than once. */
    static final class CachedBodyRequest extends HttpServletRequestWrapper {
        final byte[] body;

        CachedBodyRequest(HttpServletRequest req) throws IOException {
            super(req);
            body = req.getInputStream().readAllBytes();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return in.read(); }
                @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
                @Override public boolean isFinished() { return in.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener l) { throw new UnsupportedOperationException(); }
            };
        }

        @Override
        public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
    }
}
