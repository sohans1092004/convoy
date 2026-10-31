package convoy.platform;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Profiles;

/**
 * P6.A: the {@code prod} profile takes all config from the environment and refuses to start on a missing or
 * local-only value, listing every problem at once, before any bean (or database connection) is created.
 * Local and CI never activate it.
 */
public class ProdGuard implements EnvironmentPostProcessor {
    private static final List<String> LOCAL_ONLY = List.of("localhost", "127.0.0.1", "localstack", "mailpit", "convoy.test");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        if (!env.acceptsProfiles(Profiles.of("prod"))) return;
        Map<String, String> resolved = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        // Only the service's own config files: every key the service reads, after ${ENV} substitution.
        env.getPropertySources().stream()
                .filter(s -> s instanceof EnumerablePropertySource<?> && s.getName().contains("application"))
                .flatMap(s -> List.of(((EnumerablePropertySource<?>) s).getPropertyNames()).stream())
                .forEach(k -> {
                    try {
                        resolved.putIfAbsent(k, env.getProperty(k, ""));
                    } catch (IllegalArgumentException e) {
                        problems.add(k + ": no value (" + e.getMessage() + ")");
                    }
                });
        problems.addAll(problems(resolved));
        if (!problems.isEmpty()) throw new IllegalStateException("prod config invalid:\n  " + String.join("\n  ", problems));
    }

    static List<String> problems(Map<String, String> p) {
        List<String> out = new ArrayList<>();
        if (!p.getOrDefault("convoy.aws.endpoint", "").isBlank()) out.add("convoy.aws.endpoint: must be empty (real AWS endpoints, no LocalStack)");
        if (!p.getOrDefault("convoy.aws.account-id", "").matches("\\d{12}")) out.add("convoy.aws.account-id: a 12-digit AWS account id (CONVOY_AWS_ACCOUNT_ID)");
        if (!p.getOrDefault("identity.seed-users-file", "").isBlank()) out.add("identity.seed-users-file: test users with known logins are local-only");
        if (!p.getOrDefault("spring.mail.host", "").isBlank()) {
            for (String k : List.of("spring.mail.properties.mail.smtp.auth", "spring.mail.properties.mail.smtp.starttls.required"))
                if (!"true".equals(p.get(k))) out.add(k + ": must be true (SES SMTP)");
        }
        p.forEach((k, v) -> {
            String key = k.toLowerCase(Locale.ROOT);
            if ((key.endsWith("password") || key.endsWith("secret")) && v.isBlank()) out.add(k + ": required");
            String lower = v.toLowerCase(Locale.ROOT);
            LOCAL_ONLY.stream().filter(lower::contains).findFirst().ifPresent(bad -> out.add(k + ": '" + bad + "' is local-only"));
            if ((key.endsWith("-url") || key.endsWith(".url") || key.startsWith("convoy.issuers.")) && !v.isBlank() && !validUrl(v)) out.add(k + ": not a URL: " + v);
        });
        return out;
    }

    private static boolean validUrl(String v) {
        if (v.startsWith("jdbc:postgresql://")) return true;
        try {
            URI u = URI.create(v);
            return ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) && u.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
