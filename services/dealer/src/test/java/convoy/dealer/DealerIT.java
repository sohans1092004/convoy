package convoy.dealer;

import static org.assertj.core.api.Assertions.assertThat;

import convoy.platform.testing.AuthzDenyChecks;
import convoy.platform.testing.ServiceTest;
import convoy.platform.testing.TestTokens;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "convoy.messaging.enabled=false", "convoy.issuers.convoy-gateway=http://unused", "convoy.issuers.convoy-identity=http://unused"})
@Import(ServiceTest.Config.class)
class DealerIT extends ServiceTest {
    @Test
    void eligibilityFindsTheSyntheticDealershipForItsBrandAndCityOnly() {
        String pool = TestTokens.service("dealer", "pool", List.of("eligibility:read"));
        assertThat(get("/internal/eligibility?brand=Maruti%20Suzuki&city=Pune", pool).body()).contains("\"count\":1");
        assertThat(get("/internal/eligibility?brand=Hyundai&city=Pune", pool).body()).contains("\"count\":0");
        assertThat(get("/internal/eligibility?brand=Maruti%20Suzuki&city=Delhi", pool).body()).contains("\"count\":0");
        assertThat(get("/dealerships/0190f3a0-0000-7000-8000-00000000d001", TestTokens.anonymous("dealer")).body()).contains("\"synthetic\":true");
        assertThat(AuthzDenyChecks.run("dealer", this::send, (m, p) -> null)).isEmpty();
    }

    @Test
    void openApiMatchesTheCommittedWebClient() { assertOpenApiMatchesWebClient("dealer"); }
}
