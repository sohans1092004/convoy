package convoy.catalog;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.price.CanonicalJson;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootApplication
public class CatalogApplication {
    public static void main(String[] args) { SpringApplication.run(CatalogApplication.class, args); }

    /** The active ruleset (rules.json, generated from docs/data/rules), loaded once and recorded in rulesets. */
    public record Ruleset(String version, String sha256, Map<String, Object> rules) {}

    @Bean
    @SuppressWarnings("unchecked")
    Ruleset ruleset(JdbcTemplate jdbc) throws IOException {
        ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.USE_LONG_FOR_INTS);
        try (InputStream in = CatalogApplication.class.getResourceAsStream("/convoy/rules.json")) {
            Map<String, Object> rules = json.readValue(in, Map.class);
            String version = (String) ((Map<String, Object>) rules.get("ruleset")).get("ruleset_version");
            String sha = CanonicalJson.sha256(rules);
            jdbc.update("insert into rulesets(version, sha256, content) values (?, ?, ?::jsonb) on conflict (version) do nothing",
                    version, sha, CanonicalJson.write(rules));
            String stored = jdbc.queryForObject("select sha256 from rulesets where version = ?", String.class, version);
            if (!sha.equals(stored)) throw new IllegalStateException("ruleset " + version + " changed without a new version");
            return new Ruleset(version, sha, rules);
        }
    }
}
