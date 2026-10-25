package convoy.dealer;

import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Demo seed (P4.0): the synthetic dealerships live in a seed script, never in a Flyway migration, so a real environment
 * never receives fake dealers. Runs only with dealer.demo=true (env DEMO; the local stack and CI set it, a lite deploy only
 * with DEMO=true) and a mounted seed file. The script is idempotent (on conflict do nothing).
 */
@Component
class DemoSeed implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoSeed.class);
    private final JdbcTemplate jdbc;
    private final boolean demo;
    private final String file;

    DemoSeed(JdbcTemplate jdbc, @Value("${dealer.demo:false}") boolean demo, @Value("${dealer.demo-seed-file:}") String file) {
        this.jdbc = jdbc;
        this.demo = demo;
        this.file = file;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!demo) return;
        if (file == null || file.isBlank() || !Files.exists(Path.of(file))) {
            log.warn("DEMO=true but no dealership seed file at '{}': no demo dealerships", file);
            return;
        }
        jdbc.execute(Files.readString(Path.of(file)));
        log.info("demo seed applied from {}", file);
    }
}
