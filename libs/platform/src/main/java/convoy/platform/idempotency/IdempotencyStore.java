package convoy.platform.idempotency;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * idempotency_keys(key, caller) in the service's own schema. A row is written only inside the business
 * transaction, together with the response it produced, so "changed but not recorded" cannot happen: after a
 * crash between commit and response, a retry with the same key replays the stored response.
 */
public class IdempotencyStore {
    public record Key(String key, String caller, String requestSha256) {}

    public record Stored(String requestSha256, int status, String contentType, byte[] body) {}

    private final JdbcTemplate jdbc;

    public IdempotencyStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Stored> find(String key, String caller) {
        return jdbc.query("select request_sha256, status, content_type, response from idempotency_keys where key = ? and caller = ?",
                (rs, i) -> new Stored(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getBytes(4)), key, caller).stream().findFirst();
    }

    /**
     * First statement of the business transaction. A concurrent request with the same key blocks here until the
     * first one commits (then fails with DuplicateKeyException and replays) or rolls back (then proceeds).
     */
    public void begin(Key k) {
        jdbc.update("insert into idempotency_keys(key, caller, request_sha256) values (?, ?, ?)", k.key(), k.caller(), k.requestSha256());
    }

    /** Last statement of the business transaction. */
    public void complete(Key k, int status, String contentType, byte[] body) {
        jdbc.update("update idempotency_keys set status = ?, content_type = ?, response = ?, completed_at = clock_timestamp() where key = ? and caller = ?",
                status, contentType, body, k.key(), k.caller());
    }

    /** Retention 24 h (deposit-contract.md §2). */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000)
    public void purge() {
        jdbc.update("delete from idempotency_keys where created_at < clock_timestamp() - interval '24 hours'");
    }
}
