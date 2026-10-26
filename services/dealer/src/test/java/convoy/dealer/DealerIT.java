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
        "convoy.messaging.enabled=false", "convoy.issuers.convoy-gateway=http://unused", "convoy.issuers.convoy-identity=http://unused",
        // The demo seed (P4.0), from the same file the local stack mounts: the migrations alone hold no dealership.
        "dealer.demo=true", "dealer.demo-seed-file=../../deploy/local/seed/dealerships.sql"})
@Import(ServiceTest.Config.class)
class DealerIT extends ServiceTest {
    @Test
    void eligibilityFindsTheSyntheticDealershipForItsBrandAndCityOnly() {
        String pool = TestTokens.service("dealer", "pool", List.of("eligibility:read"));
        // The P1 synthetic dealership plus the three simulator dealerships (P3.F, dealer V5), all verified, Maruti Suzuki, Pune.
        String pune = get("/internal/eligibility?brand=Maruti%20Suzuki&city=Pune", pool).body();
        assertThat(pune).contains("\"count\":4");
        for (String d : List.of("d001", "d002", "d003", "d004")) assertThat(pune).contains("0190f3a0-0000-7000-8000-00000000" + d);
        assertThat(get("/internal/eligibility?brand=Hyundai&city=Pune", pool).body()).contains("\"count\":0");
        assertThat(get("/internal/eligibility?brand=Maruti%20Suzuki&city=Delhi", pool).body()).contains("\"count\":0");
        assertThat(get("/dealerships/0190f3a0-0000-7000-8000-00000000d001", TestTokens.anonymous("dealer")).body()).contains("\"synthetic\":true");
        assertThat(AuthzDenyChecks.run("dealer", this::send, (m, p) -> null)).isEmpty();
    }

    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    static String gstin(String first14) { return first14 + Gstin.checkChar(first14); }

    @Test
    void gstinShapeStateCodeAndCheckCharacter() {
        assertThat(Gstin.problem("27AAPFU0939F1ZV")).isNull(); // a published example GSTIN
        assertThat(Gstin.problem("27AAPFU0939F1ZA")).contains("check character");
        assertThat(Gstin.problem("99AAPFU0939F1Z" + Gstin.checkChar("99AAPFU0939F1Z"))).contains("state code");
        assertThat(Gstin.problem("27AAPFU0939F1Y5")).contains("15 characters");
        assertThat(Gstin.problem(null)).isNotNull();
    }

    @Test
    void registerQueueRejectNeedsAReasonApproveIsAuditedAndEmitsDealershipVerified() throws Exception {
        java.util.UUID owner = java.util.UUID.randomUUID();
        String buyer = TestTokens.buyer("dealer", owner);
        String admin = TestTokens.admin("dealer", java.util.UUID.randomUUID());
        String g = gstin("29ABCDE" + String.format("%04d", (int) (Math.random() * 10000)) + "F1Z");
        String body = "{\"trade_name\":\"Test Motors\",\"legal_name\":\"Test Motors Pvt Ltd\",\"gstin\":\"" + g + "\",\"city\":\"Bengaluru\",\"brands\":[\"Maruti Suzuki\"]}";
        Resp created = send("POST", "/dealerships", buyer, body, java.util.Map.of("Idempotency-Key", java.util.UUID.randomUUID().toString()));
        assertThat(created.status()).isEqualTo(201);
        var d = JSON.readTree(created.body());
        assertThat(d.path("status").asText()).isEqualTo("PENDING_VERIFICATION");
        assertThat(d.path("state_code").asText()).isEqualTo("29");
        String id = d.path("id").asText();
        // Bad GSTIN and duplicate GSTIN are validation errors.
        assertThat(send("POST", "/dealerships", buyer, body.replace(g, "29ABCDE1234F1ZZ"), java.util.Map.of("Idempotency-Key", java.util.UUID.randomUUID().toString())).status()).isEqualTo(400);
        assertThat(send("POST", "/dealerships", buyer, body, java.util.Map.of("Idempotency-Key", java.util.UUID.randomUUID().toString())).status()).isEqualTo(400);
        // Not public while pending; listed in the admin queue.
        assertThat(get("/dealerships/" + id, TestTokens.anonymous("dealer")).status()).isEqualTo(404);
        assertThat(get("/admin/dealerships", admin).body()).contains(id);
        // Reject without a reason: 400. Stale If-Match: 409. Approve: VERIFIED, public, audited, event emitted.
        java.util.function.BiFunction<String, String, Resp> decide = (json, v) -> send("POST", "/admin/dealerships/" + id + "/verify", admin, json,
                java.util.Map.of("Idempotency-Key", java.util.UUID.randomUUID().toString(), "If-Match", "\"" + v + "\""));
        assertThat(decide.apply("{\"decision\":\"REJECT\"}", "1").status()).isEqualTo(400);
        assertThat(decide.apply("{\"decision\":\"APPROVE\"}", "7").status()).isEqualTo(409);
        Resp ok = decide.apply("{\"decision\":\"APPROVE\"}", "1");
        assertThat(ok.status()).isEqualTo(200);
        assertThat(JSON.readTree(ok.body()).path("status").asText()).isEqualTo("VERIFIED");
        assertThat(get("/dealerships/" + id, TestTokens.anonymous("dealer")).status()).isEqualTo(200);
        assertThat(decide.apply("{\"decision\":\"REJECT\",\"reason\":\"x\"}", "2").status()).isEqualTo(409); // already decided
        assertThat(jdbc.queryForList("select action from dealership_audit where dealership_id = ?::uuid order by at", String.class, id)).containsExactly("REGISTERED", "APPROVED");
        assertThat(jdbc.queryForObject("select payload->>'registered_by' from outbox where event_type = 'DealershipVerified' and aggregate_id = ?::uuid", String.class, id))
                .isEqualTo(owner.toString());
    }

    @Test
    void aRejectionKeepsTheReasonAndIsAudited() throws Exception {
        String admin = TestTokens.admin("dealer", java.util.UUID.randomUUID());
        String g = gstin("07PQRST" + String.format("%04d", (int) (Math.random() * 10000)) + "K1Z");
        String body = "{\"trade_name\":\"Rejected Motors\",\"legal_name\":\"Rejected Motors LLP\",\"gstin\":\"" + g + "\",\"city\":\"Delhi\",\"brands\":[\"Hyundai\"]}";
        String id = JSON.readTree(send("POST", "/dealerships", TestTokens.buyer("dealer", java.util.UUID.randomUUID()), body,
                java.util.Map.of("Idempotency-Key", java.util.UUID.randomUUID().toString())).body()).path("id").asText();
        Resp r = send("POST", "/admin/dealerships/" + id + "/verify", admin, "{\"decision\":\"REJECT\",\"reason\":\"GST certificate does not match the legal name\"}",
                java.util.Map.of("Idempotency-Key", java.util.UUID.randomUUID().toString(), "If-Match", "\"1\""));
        assertThat(JSON.readTree(r.body()).path("status").asText()).isEqualTo("REJECTED");
        assertThat(JSON.readTree(r.body()).path("rejection_reason").asText()).contains("legal name");
        assertThat(jdbc.queryForObject("select reason from dealership_audit where dealership_id = ?::uuid and action = 'REJECTED'", String.class, id)).contains("legal name");
        assertThat(jdbc.queryForObject("select count(*) from outbox where aggregate_id = ?::uuid", Integer.class, id)).isZero();
    }

    @Test
    void noMigrationCarriesDemoData() throws Exception {
        // P4.0: a real environment must never receive fake dealers; demo dealerships come only from the DEMO seed.
        try (var files = java.nio.file.Files.list(java.nio.file.Path.of("src/main/resources/db/migration"))) {
            for (var f : files.toList()) assertThat(java.nio.file.Files.readString(f).toLowerCase()).as(f.toString()).doesNotContain("insert into dealerships");
        }
    }

    @Test
    void openApiMatchesTheCommittedWebClient() { assertOpenApiMatchesWebClient("dealer"); }
}
