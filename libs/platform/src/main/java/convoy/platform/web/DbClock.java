package convoy.platform.web;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Server time for Server-Time headers and problem bodies, derived from the DB clock (deadline-rule.md §3).
 * Deadline decisions never use this: they run clock_timestamp() inside the guarded SQL statement.
 */
public class DbClock {
    private final JdbcTemplate jdbc;
    private final AtomicLong offsetMillis = new AtomicLong();

    public DbClock(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** DB minus JVM clock, refreshed every 60 s (one cheap query). */
    @Scheduled(fixedDelay = 60_000, initialDelay = 0)
    public void sync() {
        try {
            long t0 = System.currentTimeMillis();
            Instant db = jdbc.queryForObject("select clock_timestamp()", java.sql.Timestamp.class).toInstant();
            long t1 = System.currentTimeMillis();
            offsetMillis.set(db.toEpochMilli() - (t0 + t1) / 2);
        } catch (RuntimeException e) {
            // DB briefly unavailable: keep the last offset.
        }
    }

    public Instant now() { return Instant.now().plus(Duration.ofMillis(offsetMillis.get())); }

    /** Exact DB clock (one query) when a response must quote it, e.g. a deadline-passed body. */
    public Instant dbNow() { return jdbc.queryForObject("select clock_timestamp()", java.sql.Timestamp.class).toInstant(); }
}
