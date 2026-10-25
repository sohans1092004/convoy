package convoy.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.platform.ConvoyProperties;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootApplication
@EnableConfigurationProperties(IdentityApplication.IdentitySettings.class)
public class IdentityApplication {
    public static void main(String[] args) { SpringApplication.run(IdentityApplication.class, args); }

    /**
     * @param dataKey       base64 32-byte key for email ciphertext and lookup hashes (env IDENTITY_DATA_KEY)
     * @param mailFrom      sender address for OTP mails
     * @param otpMinutes    OTP lifetime (auth-contract.md: 10 min)
     * @param sessionDays   session lifetime (30 days, sliding)
     * @param clients       service clients: id -> {secret, scopes: {audience: [scope]}}
     * @param seedUsersFile optional JSON file of local test users (local profile only)
     * @param passkeys      WebAuthn relying party: id (the web app's host), the origins the browser reports, challenge lifetime
     */
    @ConfigurationProperties(prefix = "identity")
    public record IdentitySettings(String dataKey, String mailFrom, int otpMinutes, int sessionDays,
                                   Map<String, Client> clients, String seedUsersFile, Passkeys passkeys) {
        public record Passkeys(String rpId, String rpName, List<String> origins, int challengeMinutes) {}
        public record Client(String secret, Map<String, List<String>> scopes) {}
    }

    @Bean Crypto crypto(IdentitySettings s) { return new Crypto(s.dataKey()); }

    @Bean SigningKeys signingKeys(JdbcTemplate jdbc, Crypto crypto) { return new SigningKeys(jdbc, crypto); }

    /** Upsert service clients from config at startup (secrets from env; only hashes are stored). */
    @Bean
    Object serviceClientSync(JdbcTemplate jdbc, IdentitySettings s, ObjectMapper json, ConvoyProperties p) throws Exception {
        if (s.clients() != null) {
            for (var e : s.clients().entrySet()) {
                jdbc.update("insert into service_clients(client_id, secret_hash, scopes) values (?, ?, ?::jsonb) "
                                + "on conflict (client_id) do update set secret_hash = excluded.secret_hash, scopes = excluded.scopes",
                        e.getKey(), Crypto.sha256(e.getValue().secret()), json.writeValueAsString(e.getValue().scopes()));
            }
        }
        return new Object();
    }
}
