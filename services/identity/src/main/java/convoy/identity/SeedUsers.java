package convoy.identity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Local seed: test buyers, dealer staff and an admin from identity.seed-users-file (deploy/local/seed/users.json). */
@Component
public class SeedUsers implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SeedUsers.class);
    private final IdentityApplication.IdentitySettings settings;
    private final Users users;
    private final ObjectMapper json;

    public SeedUsers(IdentityApplication.IdentitySettings settings, Users users, ObjectMapper json) {
        this.settings = settings;
        this.users = users;
        this.json = json;
    }

    record SeedUser(String email, List<String> roles, @JsonProperty("dealership_id") UUID dealershipId) {}

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String path = settings.seedUsersFile();
        if (path == null || path.isBlank() || !new File(path).exists()) return;
        SeedUser[] all = json.readValue(new File(path), SeedUser[].class);
        for (SeedUser u : all) users.seed(u.email(), u.roles(), u.dealershipId());
        log.info("seeded {} local users from {}", all.length, path);
    }
}
