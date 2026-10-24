package convoy.testing;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** Tiny HTTP stub for a callee service in integration tests (JDK HttpServer, no extra dependency). */
public final class StubServer implements AutoCloseable {
    public record Req(String method, String path, String query, String body, Map<String, java.util.List<String>> headers) {}

    public record Reply(int status, String body, long delayMillis) {
        public static Reply json(String body) { return new Reply(200, body, 0); }
    }

    private final HttpServer server;
    private final Map<String, Function<Req, Reply>> routes = new ConcurrentHashMap<>();

    public StubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Req req = new Req(ex.getRequestMethod(), ex.getRequestURI().getPath(), ex.getRequestURI().getQuery(), body, ex.getRequestHeaders());
            Function<Req, Reply> h = routes.entrySet().stream().filter(e -> req.path().matches(e.getKey())).map(Map.Entry::getValue).findFirst()
                    .orElse(r -> new Reply(404, "{}", 0));
            Reply reply = h.apply(req);
            if (reply.delayMillis() > 0) {
                try {
                    Thread.sleep(reply.delayMillis());
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(reply.status(), out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    /** Route by regex on the path. */
    public StubServer on(String pathRegex, Function<Req, Reply> handler) {
        routes.put(pathRegex, handler);
        return this;
    }

    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    @Override
    public void close() { server.stop(0); }
}
