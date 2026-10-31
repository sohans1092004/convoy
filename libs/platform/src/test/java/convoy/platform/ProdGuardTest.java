package convoy.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProdGuardTest {
    static Map<String, String> good() {
        Map<String, String> p = new HashMap<>();
        p.put("convoy.aws.endpoint", "");
        p.put("convoy.aws.account-id", "123456789012");
        p.put("convoy.client-secret", "s3cret");
        p.put("spring.datasource.url", "jdbc:postgresql://postgres:5432/convoy");
        p.put("spring.datasource.password", "pw");
        p.put("spring.mail.host", "email-smtp.ap-south-1.amazonaws.com");
        p.put("spring.mail.password", "smtp-pw");
        p.put("spring.mail.properties.mail.smtp.auth", "true");
        p.put("spring.mail.properties.mail.smtp.starttls.required", "true");
        p.put("convoy.issuers.convoy-gateway", "http://gateway:8080/.well-known/jwks.json");
        p.put("identity.web-url", "https://convoy-demo.duckdns.org");
        p.put("identity.seed-users-file", "");
        return p;
    }

    @Test
    void validProdConfigPasses() {
        assertEquals(List.of(), ProdGuard.problems(good()));
    }

    @Test
    void everyLocalShortcutIsReported() {
        Map<String, String> p = good();
        p.put("convoy.aws.endpoint", "http://localstack:4566");
        p.put("convoy.aws.account-id", "000");
        p.put("convoy.client-secret", "");
        p.put("spring.mail.host", "mailpit");
        p.put("spring.mail.properties.mail.smtp.auth", "false");
        p.put("identity.web-url", "http://localhost:3000");
        p.put("identity.seed-users-file", "/seed/users.json");
        p.put("identity.mail-from", "no-reply@convoy.test");
        p.put("pool.services.quote-url", "quote:8080");
        String all = String.join("\n", ProdGuard.problems(p));
        for (String k : List.of("convoy.aws.endpoint: must be empty", "convoy.aws.account-id", "convoy.client-secret: required", "spring.mail.host: 'mailpit'",
                "smtp.auth: must be true", "identity.web-url: 'localhost'", "seed-users-file", "identity.mail-from: 'convoy.test'", "quote-url: not a URL"))
            assertTrue(all.contains(k), k + " missing from:\n" + all);
    }
}
