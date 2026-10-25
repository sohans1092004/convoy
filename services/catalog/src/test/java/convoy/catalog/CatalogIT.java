package convoy.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import convoy.platform.testing.AuthzDenyChecks;
import convoy.platform.testing.ServiceTest;
import convoy.platform.testing.TestTokens;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Seeded verified catalogue, the stored on-road estimate snapshot (= vector P001), internal model lookup. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "convoy.messaging.enabled=false", "convoy.issuers.convoy-gateway=http://unused", "convoy.issuers.convoy-identity=http://unused"})
@Import(ServiceTest.Config.class)
class CatalogIT extends ServiceTest {
    static final ObjectMapper JSON = new ObjectMapper();
    @Autowired JdbcTemplate jdbc;

    @Test
    void onlyVerifiedP1ModelsAreSeededAndTheEstimateMatchesTheHandVerifiedVector() throws Exception {
        String anon = TestTokens.anonymous("catalog");
        JsonNode models = JSON.readTree(get("/catalog/models", anon).body()).path("models");
        assertThat(models).hasSize(7);
        assertThat(jdbc.queryForObject("select count(*) from variant_prices where evidence <> 'CATALOGUE_ESTIMATE'", Integer.class)).isZero();
        String swift = jdbc.queryForObject("select id::text from variants where code = 'swift-lxi-mt'", String.class);
        JsonNode est = JSON.readTree(get("/catalog/variants/" + swift + "/price-estimate?city=Pune", anon).body());
        assertThat(est.at("/snapshot/output/cash_payable_paise").asLong()).isEqualTo(67457500L); // P001, hand-verified in pricing-model.md
        assertThat(est.path("city_confirmed").asBoolean()).isFalse(); // manufacturer page names no city (Q-003)
        assertThat(jdbc.queryForObject("select count(*) from price_estimates where sha256 = ?", Integer.class, est.at("/snapshot/sha256").asText())).isEqualTo(1);
        // Creta's displacement is not published: no insurance estimate, so no on-road estimate (no invented data).
        String creta = jdbc.queryForObject("select id::text from variants where code = 'creta-e-mt'", String.class);
        Resp r = get("/catalog/variants/" + creta + "/price-estimate?city=Pune", anon);
        assertThat(r.status()).isEqualTo(422);
        assertThat(JSON.readTree(r.body()).path("errors").toString()).contains("ENGINE_CC_MISSING");
    }

    @Test
    void internalModelLookupNeedsTheScope() {
        assertThat(get("/internal/models/maruti-swift?city=Pune", TestTokens.service("catalog", "pool", List.of("models:read"))).status()).isEqualTo(200);
        assertThat(get("/internal/models/maruti-swift", TestTokens.service("catalog", "pool", List.of("other"))).status()).isEqualTo(403);
        assertThat(AuthzDenyChecks.run("catalog", this::send, (m, p) -> null)).isEmpty();
    }

    @Test
    void openApiMatchesTheCommittedWebClient() { assertOpenApiMatchesWebClient("catalog"); }
}
