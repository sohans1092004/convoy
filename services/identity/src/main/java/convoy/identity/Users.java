package convoy.identity;

import convoy.contracts.events.UserRegistered;
import convoy.platform.messaging.Outbox;
import convoy.platform.messaging.Uuid7;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Users, roles and dealership staff membership. */
@Component
public class Users {
    public record User(UUID id, String email, List<String> roles, UUID dealershipId) {}

    private final JdbcTemplate jdbc;
    private final Crypto crypto;
    private final Outbox outbox;

    public Users(JdbcTemplate jdbc, Crypto crypto, Outbox outbox) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.outbox = outbox;
    }

    public static String normalise(String email) { return email.trim().toLowerCase(java.util.Locale.ROOT); }

    public Optional<UUID> idByEmail(String email) {
        return jdbc.queryForList("select id from users where email_hash = ?", UUID.class, crypto.hash(normalise(email))).stream().findFirst();
    }

    /** Find or create; a new user is a buyer and emits UserRegistered. */
    @Transactional
    public UUID findOrRegister(String email) {
        Optional<UUID> existing = idByEmail(email);
        if (existing.isPresent()) return existing.get();
        UUID id = Uuid7.next();
        int n = jdbc.update("insert into users(id, email_hash, email_enc) values (?, ?, ?) on conflict (email_hash) do nothing",
                id, crypto.hash(normalise(email)), crypto.encrypt(normalise(email)));
        if (n == 0) return idByEmail(email).orElseThrow();
        jdbc.update("insert into user_roles(user_id, role) values (?, 'buyer')", id);
        outbox.append(id, 1, new UserRegistered(id.toString(), List.of("buyer"), "en"));
        return id;
    }

    public Optional<User> find(UUID id) {
        List<byte[]> enc = jdbc.queryForList("select email_enc from users where id = ? and status = 'ACTIVE'", byte[].class, id);
        if (enc.isEmpty()) return Optional.empty();
        List<String> roles = jdbc.queryForList("select role from user_roles where user_id = ? order by role", String.class, id);
        List<UUID> dealership = jdbc.queryForList(
                "select dealership_id from dealership_staff where user_id = ? and status = 'ACTIVE' and revoked_at is null", UUID.class, id);
        return Optional.of(new User(id, crypto.decrypt(enc.get(0)), roles, dealership.isEmpty() ? null : dealership.get(0)));
    }

    /** Local seeding: ensure a user with exactly these roles (and dealership membership for dealer roles). */
    @Transactional
    public UUID seed(String email, List<String> roles, UUID dealershipId) {
        UUID id = findOrRegister(email);
        jdbc.update("delete from user_roles where user_id = ?", id);
        for (String r : roles) jdbc.update("insert into user_roles(user_id, role) values (?, ?)", id, r);
        if (dealershipId != null) {
            String staffRole = roles.contains("dealer_admin") ? "dealer_admin" : "dealer_staff";
            jdbc.update("insert into dealership_staff(dealership_id, user_id, role) values (?, ?, ?) on conflict do nothing", dealershipId, id, staffRole);
        }
        return id;
    }
}
