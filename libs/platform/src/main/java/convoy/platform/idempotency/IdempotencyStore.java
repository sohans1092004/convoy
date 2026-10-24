package convoy.platform.idempotency;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * idempotency_keys(key, caller) UNIQUE in the service's own schema. A request claims the key with an
 * INSERT; the first completed (non-5xx) response is stored and replayed for later requests with that key.
 *
 * ponytail: the response is stored after the business transaction commits, not inside it. A crash in
 * between leaves the claim IN_PROGRESS; claims older than 60 s are treated as abandoned and re-run, and
 * business uniqueness (e.g. one membership per pool and buyer) keeps the re-run safe. Store the response
 * in the business transaction if that window ever matters.
 */
public class IdempotencyStore {
    public record Claim(String key, String caller) {}

    public record Stored(String requestSha256, Integer status, String contentType, byte[] body, boolean abandoned) {}

    private final JdbcTemplate jdbc;

    public IdempotencyStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** True if this request now owns the key. */
    public boolean claim(Claim c, String requestSha256) {
        return jdbc.update("insert into idempotency_keys(key, caller, request_sha256) values (?, ?, ?) on conflict do nothing",
                c.key(), c.caller(), requestSha256) == 1;
    }

    public Optional<Stored> find(Claim c) {
        return jdbc.query("select request_sha256, status, content_type, response, created_at < clock_timestamp() - interval '60 seconds' as abandoned "
                        + "from idempotency_keys where key = ? and caller = ?",
                (rs, i) -> new Stored(rs.getString(1), (Integer) rs.getObject(2), rs.getString(3), rs.getBytes(4), rs.getBoolean(5)),
                c.key(), c.caller()).stream().findFirst();
    }

    /** Abandoned in-progress claim: take it over (only one taker wins). */
    public boolean takeOver(Claim c, String requestSha256) {
        return jdbc.update("update idempotency_keys set created_at = clock_timestamp() where key = ? and caller = ? and status is null "
                + "and request_sha256 = ? and created_at < clock_timestamp() - interval '60 seconds'", c.key(), c.caller(), requestSha256) == 1;
    }

    public void complete(Claim c, int status, String contentType, byte[] body) {
        if (status >= 500) {
            // Server failure: release the key so the client can retry with it.
            jdbc.update("delete from idempotency_keys where key = ? and caller = ? and status is null", c.key(), c.caller());
        } else {
            jdbc.update("update idempotency_keys set status = ?, content_type = ?, response = ?, completed_at = clock_timestamp() "
                    + "where key = ? and caller = ?", status, contentType, body, c.key(), c.caller());
        }
    }

    /** Retention 24 h (deposit-contract.md §2). */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000)
    public void purge() {
        jdbc.update("delete from idempotency_keys where created_at < clock_timestamp() - interval '24 hours'");
    }
}
