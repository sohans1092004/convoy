package convoy.platform.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Rows of docs/architecture/api-outline.md, so authorisation tests are attached to every P1 endpoint by id. */
public final class ApiOutline {
    public record Endpoint(String id, String service, String method, String path, Set<String> roles, boolean idempotent, boolean ifMatch, String phase, List<String> authzTests) {
        /** Path with {params} replaced by the given value. */
        public String concrete(String value) { return path.replaceAll("\\{[^}]+}", value); }
        public boolean internal() { return path.startsWith("/internal/"); }
    }

    private ApiOutline() {}

    /** Phases whose endpoints are built (and so must deny as specified). */
    static final java.util.Set<String> BUILT = java.util.Set.of("P1", "P2");
    /** Later-phase endpoints already built (P3 grows this list as each subtask lands; then P3 joins BUILT). */
    static final java.util.Set<String> BUILT_IDS = java.util.Set.of("E010", "E011", "E050", "E052", "E053", "E153", "E064", "E065", "E066", "E068", "E121", "E122",
            // P4
            "E056", "E057", "E083", "E084", "E086", "E089", "E090", "E091", "E092", "E093", "E094", "E095", "E096", "E097", "E098", "E099", "E100",
            "E116", "E117", "E118", "E154", "E155", "E156", "E157");

    public static List<Endpoint> p1(String service) {
        Path doc = Path.of(System.getProperty("convoy.repoRoot", "../.."), "docs", "architecture", "api-outline.md");
        try {
            return Files.readAllLines(doc).stream().filter(l -> l.startsWith("| E")).map(l -> Arrays.stream(l.split("\\|")).map(String::trim).toList())
                    .map(c -> new Endpoint(c.get(1), c.get(2), c.get(3), c.get(4),
                            Arrays.stream(c.get(5).split(",")).map(String::trim).collect(Collectors.toSet()), c.get(6).equals("yes"), c.get(7).equals("yes"),
                            c.get(9), Arrays.stream(c.get(10).split(",")).map(String::trim).toList()))
                    .filter(e -> e.service().equals(service) && (BUILT.contains(e.phase()) || BUILT_IDS.contains(e.id()))).toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
