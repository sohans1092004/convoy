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
        assertThat(models).hasSize(6); // Windsor EV is seeded but not listed: the engine does not estimate EV insurance
        assertThat(models.findValues("slug").stream().map(JsonNode::asText)).doesNotContain("mg-windsor-ev");
        assertThat(jdbc.queryForObject("select count(*) from variant_prices where evidence <> 'CATALOGUE_ESTIMATE'", Integer.class)).isZero();
        String swift = jdbc.queryForObject("select id::text from variants where code = 'swift-lxi-mt'", String.class);
        JsonNode est = JSON.readTree(get("/catalog/variants/" + swift + "/price-estimate?city=Pune", anon).body());
        assertThat(est.at("/snapshot/output/cash_payable_paise").asLong()).isEqualTo(67457500L); // P001, hand-verified in pricing-model.md
        assertThat(est.path("city_confirmed").asBoolean()).isFalse(); // manufacturer page names no city (Q-003)
        assertThat(jdbc.queryForObject("select count(*) from price_estimates where sha256 = ?", Integer.class, est.at("/snapshot/sha256").asText())).isEqualTo(1);
        // U.1: Creta's displacement now comes from Hyundai's specification data, so it has an estimate.
        String creta = jdbc.queryForObject("select id::text from variants where code = 'creta-e-mt'", String.class);
        assertThat(get("/catalog/variants/" + creta + "/price-estimate?city=Pune", anon).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select spec_source_url from variants where code = 'creta-e-mt'", String.class)).contains("hyundai.com");
        // No EV insurance estimate exists, so no on-road estimate; the code stays machine-readable, the sentence is plain.
        String windsor = jdbc.queryForObject("select id::text from variants where code = 'windsor-excite'", String.class);
        Resp r = get("/catalog/variants/" + windsor + "/price-estimate?city=Pune", anon);
        assertThat(r.status()).isEqualTo(422);
        JsonNode problem = JSON.readTree(r.body());
        assertThat(problem.path("errors").toString()).contains("INSURANCE_ESTIMATE_UNAVAILABLE");
        assertThat(problem.path("detail").asText()).doesNotContain("[").doesNotContain("INSURANCE");
        // A hybrid has no verified road tax in Maharashtra: the model page steers to the variant that can be estimated.
        for (JsonNode v : JSON.readTree(get("/catalog/models/maruti-ertiga", anon).body()).path("variants")) {
            assertThat(v.path("estimate_cities").toString().contains("Pune")).isEqualTo("CNG".equals(v.path("fuel").asText()));
        }
    }

    @Test
    void internalVariantLookupNamesTheCarAndNeedsTheScope() throws Exception {
        String swift = jdbc.queryForObject("select id::text from variants where code = 'swift-lxi-mt'", String.class);
        JsonNode car = JSON.readTree(get("/internal/variants/" + swift, TestTokens.service("catalog", "order", List.of("models:read"))).body());
        assertThat(car.path("brand").asText()).isEqualTo("Maruti Suzuki");
        assertThat(car.path("model").asText()).isEqualTo("Swift");
        assertThat(car.path("variant").asText()).isEqualTo("LXi MT");
        assertThat(get("/internal/variants/" + swift, TestTokens.service("catalog", "order", List.of("other"))).status()).isEqualTo(403);
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
