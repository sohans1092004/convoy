package convoy.platform.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.contracts.events.ErasureRequested;
import convoy.contracts.events.UserExportRequested;
import convoy.platform.messaging.Subscriptions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.web.client.RestClient;

/**
 * DPDP export, erasure and retention for one service (P5.D), driven by its `convoy.privacy` settings
 * (docs/privacy/retention.md lists every table, its category and period).
 * <ul>
 * <li>export: every row of every listed table that belongs to the user, sent to identity as this service's part (E165);</li>
 * <li>erase: DELETE rows, or PSEUDONYMISE kept categories (the user id becomes a random id unique to this service and user,
 *     `rewrite` columns get the id replaced inside them, `clear` columns become null); then confirm to identity (E017);</li>
 * <li>retention: each rule deletes what is past its period (`:cutoff`), daily.</li>
 * </ul>
 * Runs in the inbox transaction: if identity cannot be told, the erasure rolls back and the event is retried (idempotent).
 */
public class Privacy {
    private static final Logger log = LoggerFactory.getLogger(Privacy.class);
    private static final Pattern NAME = Pattern.compile("[a-z_][a-z0-9_]*");

    public enum Action { DELETE, PSEUDONYMISE }

    /** `where` (one `?` = the user id) replaces `column = ?` for rows reached through another table; PSEUDONYMISE still needs `column`. */
    public record Table(String table, String column, @Nullable String where, Action action, String category, @Nullable List<String> rewrite, @Nullable List<String> clear) {
        String condition() { return where != null ? where : column + " = ?"; }
    }

    public record Rule(String category, int days, String sql) {}

    @ConfigurationProperties(prefix = "convoy.privacy")
    public record Settings(@Nullable List<Table> tables, @Nullable List<Rule> retention) {
        public Settings {
            tables = tables == null ? List.of() : tables;
            retention = retention == null ? List.of() : retention;
            for (Table t : tables) {
                for (String n : concat(List.of(t.table(), t.column()), t.rewrite(), t.clear())) {
                    if (!NAME.matcher(n).matches()) throw new IllegalArgumentException("convoy.privacy: bad identifier " + n);
                }
            }
        }
    }

    private final String service;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RestClient identity;
    private final Settings s;

    public Privacy(String service, JdbcTemplate jdbc, ObjectMapper json, RestClient identity, Settings s) {
        this.service = service;
        this.jdbc = jdbc;
        this.json = json;
        this.identity = identity;
        this.s = s;
    }

    public void subscribe(Subscriptions subs) {
        subs.on(UserExportRequested.class, (e, p) -> {
            identity.post().uri("/internal/exports/{id}/parts", p.exportId()).body(Map.of("service", service, "data", export(UUID.fromString(p.userId()))))
                    .retrieve().toBodilessEntity();
        });
        subs.on(ErasureRequested.class, (e, p) -> {
            erase(UUID.fromString(p.userId()));
            identity.post().uri("/internal/erasure/{id}/confirm", p.erasureId()).body(Map.of("service", service)).retrieve().toBodilessEntity();
        });
    }

    /** table -> the user's rows (JSON). */
    public Map<String, JsonNode> export(UUID user) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (Table t : s.tables()) {
            String rows = jdbc.queryForObject("select coalesce(json_agg(x), '[]')::text from " + t.table() + " x where " + t.condition(), String.class, user);
            try {
                out.put(t.table(), json.readTree(rows));
            } catch (java.io.IOException ex) {
                throw new IllegalStateException(ex);
            }
        }
        return out;
    }

    /** Deletes or pseudonymises in the configured order (children before parents). Idempotent. */
    public void erase(UUID user) {
        UUID pseudonym = UUID.randomUUID();
        for (Table t : s.tables()) {
            int n;
            if (t.action() == Action.DELETE) {
                n = jdbc.update("delete from " + t.table() + " where " + t.condition(), user);
            } else {
                StringBuilder set = new StringBuilder(t.column() + " = ?");
                for (String c : nn(t.rewrite())) set.append(", ").append(c).append(" = replace(").append(c).append(", '").append(user).append("', '").append(pseudonym).append("')");
                for (String c : nn(t.clear())) set.append(", ").append(c).append(" = null");
                n = jdbc.update("update " + t.table() + " set " + set + " where " + t.condition(), pseudonym, user);
            }
            if (n > 0) log.info("erasure: {} {} row(s) in {} ({})", t.action(), n, t.table(), t.category());
        }
        // Published events keep their rows (audit reconciles against the count) but no longer name the person.
        jdbc.update("update outbox set payload = replace(payload::text, ?, ?)::jsonb where payload::text like '%' || ? || '%'", user.toString(), pseudonym.toString(), user.toString());
    }

    /** Applies every retention rule; returns rows removed. */
    public int enforceRetention() {
        int total = 0;
        for (Rule r : s.retention()) {
            int n = jdbc.update(r.sql().replace(":cutoff", "(clock_timestamp() - make_interval(days => ?))"), r.days());
            if (n > 0) log.info("retention: {} removed {} row(s) past {} days", r.category(), n, r.days());
            total += n;
        }
        return total;
    }

    private static List<String> nn(@Nullable List<String> l) { return l == null ? List.of() : l; }

    @SafeVarargs
    private static List<String> concat(List<String>... ls) {
        return java.util.Arrays.stream(ls).filter(java.util.Objects::nonNull).flatMap(List::stream).toList();
    }
}
