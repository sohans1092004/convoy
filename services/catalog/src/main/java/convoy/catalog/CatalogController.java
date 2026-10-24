package convoy.catalog;

import convoy.platform.messaging.Uuid7;
import convoy.platform.problem.ApiProblem;
import convoy.platform.problem.ProblemType;
import convoy.price.CanonicalJson;
import convoy.price.PriceEngine;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** E020-E023: catalogue reads and the on-road estimate (engine + stored snapshot). Anonymous. */
@RestController
public class CatalogController {
    static final List<String> CITIES = List.of("Mumbai", "Pune", "Delhi", "Bengaluru");
    private final JdbcTemplate jdbc;
    private final CatalogApplication.Ruleset ruleset;

    public CatalogController(JdbcTemplate jdbc, CatalogApplication.Ruleset ruleset) {
        this.jdbc = jdbc;
        this.ruleset = ruleset;
    }

    static String city(String c) {
        if (c == null) return null;
        for (String k : CITIES) if (k.equalsIgnoreCase(c)) return k;
        throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "City must be one of " + CITIES + ".");
    }

    /** E020: models with the lowest seeded ex-showroom price (catalogue estimate) per model. */
    @GetMapping("/catalog/models")
    public Api.ModelList models(@RequestParam(required = false) String city) {
        String c = city(city);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select m.slug, m.name, b.name as brand, m.body_type, count(distinct v.id) as variant_count,
                       min(p.ex_showroom_paise) as from_ex_showroom_paise, bool_or(p.city_confirmed) as any_city_confirmed
                from models m join brands b on b.id = m.brand_id join variants v on v.model_id = m.id
                join variant_prices p on p.variant_id = v.id and (cast(? as text) is null or p.city = ?)
                group by m.slug, m.name, b.name, m.body_type order by b.name, m.name""", c, c);
        rows.forEach(r -> r.put("evidence", "CATALOGUE_ESTIMATE"));
        return convoy.platform.web.Dto.as(Map.of("models", rows, "cities", CITIES, "ruleset_version", ruleset.version()), Api.ModelList.class);
    }

    /** E021: one model with variants and per-city prices (each with source, access date and evidence). */
    @GetMapping("/catalog/models/{slug}")
    public Api.Model model(@PathVariable String slug) {
        List<Map<String, Object>> m = jdbc.queryForList("""
                select m.id, m.slug, m.name, b.name as brand, m.body_type, m.source_url, m.source_title, m.accessed_on::text as accessed_on,
                       m.price_label, m.city_basis from models m join brands b on b.id = m.brand_id where m.slug = ?""", slug);
        if (m.isEmpty()) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such model.");
        Map<String, Object> out = new LinkedHashMap<>(m.get(0));
        List<Map<String, Object>> variants = jdbc.queryForList(
                "select id, code, name, fuel, transmission, engine_cc, note from variants where model_id = ? order by name", out.get("id"));
        for (Map<String, Object> v : variants) {
            v.put("prices", jdbc.queryForList("select city, state_code, ex_showroom_paise, evidence, city_confirmed, source_url, accessed_on::text as accessed_on "
                    + "from variant_prices where variant_id = ? order by city", v.get("id")));
        }
        out.put("variants", variants);
        return convoy.platform.web.Dto.as(out, Api.Model.class);
    }

    /**
     * E029 internal (P1 addition, service token scope models:read): model brand and variant ids, so pool can
     * check dealer eligibility and the accepted variants of a new pool without trusting the client.
     */
    @GetMapping("/internal/models/{slug}")
    @convoy.platform.security.RequiresScope("models:read")
    public Api.InternalModel internalModel(@PathVariable String slug, @RequestParam(required = false) String city) {
        convoy.platform.security.Caller.current().requireScope("models:read");
        String c = city(city);
        List<Map<String, Object>> m = jdbc.queryForList("select m.id, m.slug, m.name, b.name as brand from models m join brands b on b.id = m.brand_id where m.slug = ?", slug);
        if (m.isEmpty()) throw ApiProblem.of(ProblemType.NOT_FOUND, "No such model.");
        Map<String, Object> out = new LinkedHashMap<>(m.get(0));
        out.put("variant_ids", jdbc.queryForList("select id from variants where model_id = ? order by name", UUID.class, out.get("id")));
        out.put("variants", jdbc.queryForList("""
                select v.id::text as id, v.code, v.name, v.fuel, v.transmission, v.engine_cc, p.ex_showroom_paise as catalogue_ex_showroom_paise,
                       p.evidence as catalogue_evidence, p.city_confirmed from variants v
                left join variant_prices p on p.variant_id = v.id and p.city = ? where v.model_id = ? order by v.name""", c, out.get("id")));
        return convoy.platform.web.Dto.as(out, Api.InternalModel.class);
    }

    /** E022: variants side by side. */
    @GetMapping("/catalog/variants/compare")
    public Api.Comparison compare(@RequestParam List<UUID> ids, @RequestParam(required = false) String city) {
        if (ids.isEmpty() || ids.size() > 4) throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "Compare 1 to 4 variants.");
        String c = city(city);
        List<Map<String, Object>> out = new ArrayList<>();
        for (UUID id : ids) {
            List<Map<String, Object>> v = jdbc.queryForList("""
                    select v.id, v.code, v.name, v.fuel, v.transmission, v.engine_cc, m.slug as model_slug, m.name as model
                    from variants v join models m on m.id = v.model_id where v.id = ?""", id);
            if (v.isEmpty()) throw ApiProblem.of(ProblemType.VALIDATION_FAILED, "Unknown variant " + id + ".");
            Map<String, Object> row = new LinkedHashMap<>(v.get(0));
            row.put("prices", jdbc.queryForList("select city, ex_showroom_paise, evidence, city_confirmed from variant_prices where variant_id = ? "
                    + "and (cast(? as text) is null or city = ?) order by city", id, c, c));
            out.add(row);
        }
        return convoy.platform.web.Dto.as(Map.of("variants", out), Api.Comparison.class);
    }

    /** E023: on-road estimate for a variant in a city. The engine output and its inputs are stored as a snapshot. */
    @GetMapping("/catalog/variants/{id}/price-estimate")
    public Api.Estimate estimate(@PathVariable UUID id, @RequestParam String city) {
        String c = city(city);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select v.id, v.code, v.name, v.fuel, v.engine_cc, p.state_code, p.ex_showroom_paise, p.evidence, p.city_confirmed, p.source_url,
                       p.accessed_on::text as accessed_on from variants v join variant_prices p on p.variant_id = v.id where v.id = ? and p.city = ?""", id, c);
        if (rows.isEmpty()) throw ApiProblem.of(ProblemType.NOT_FOUND, "No price for this variant in " + c + ".");
        Map<String, Object> r = rows.get(0);
        Map<String, Object> inputs = estimateInputs(r, ruleset.version());
        Map<String, Object> body = PriceEngine.snapshotBody(inputs, ruleset.rules());
        @SuppressWarnings("unchecked") Map<String, Object> output = (Map<String, Object>) body.get("output");
        if (!"OK".equals(output.get("status"))) {
            throw ApiProblem.of(ProblemType.QUOTE_INVALID, "An on-road estimate is not available for this variant: " + output.get("errors") + ".")
                    .with("errors", output.get("errors")).with("variant_id", id.toString()).with("city", c);
        }
        String sha = CanonicalJson.sha256(body);
        jdbc.update("insert into price_estimates(id, variant_id, city, ruleset_version, inputs, output, sha256) values (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?) "
                + "on conflict (variant_id, city, sha256) do nothing", Uuid7.next(), id, c, ruleset.version(), CanonicalJson.write(inputs), CanonicalJson.write(output), sha);
        Timestamp at = jdbc.queryForObject("select computed_at from price_estimates where variant_id = ? and city = ? and sha256 = ?", Timestamp.class, id, c, sha);
        Map<String, Object> snapshot = new LinkedHashMap<>(body);
        snapshot.put("sha256", sha);
        snapshot.put("computed_at", at.toInstant().toString());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("variant", Map.of("id", id.toString(), "code", r.get("code"), "name", r.get("name")));
        out.put("city", c);
        out.put("city_confirmed", r.get("city_confirmed"));
        out.put("source", Map.of("url", r.get("source_url"), "accessed_on", r.get("accessed_on")));
        out.put("snapshot", snapshot);
        return convoy.platform.web.Dto.as(out, Api.Estimate.class);
    }

    /** Buyer configuration with catalogue figures only: registration and insurance are estimated by the engine. */
    static Map<String, Object> estimateInputs(Map<String, Object> r, String rulesetVersion) {
        Map<String, Object> vehicle = new LinkedHashMap<>();
        vehicle.put("variant_id", r.get("code"));
        vehicle.put("fuel", r.get("fuel"));
        vehicle.put("engine_cc", r.get("engine_cc") == null ? null : ((Number) r.get("engine_cc")).longValue());
        vehicle.put("ex_showroom_paise", ((Number) r.get("ex_showroom_paise")).longValue());
        vehicle.put("ex_showroom_evidence", r.get("evidence"));
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("ruleset_version", rulesetVersion);
        in.put("mode", "BUYER_CONFIG");
        in.put("state", r.get("state_code"));
        in.put("vehicle", vehicle);
        in.put("discount", Map.of("amount_paise", 0L, "base", "EX_SHOWROOM", "evidence", "DEALER_CONFIRMED"));
        in.put("registration", null);
        in.put("insurance", null);
        in.put("insurance_addons", List.of());
        in.put("fees", List.of());
        in.put("accessories", List.of());
        in.put("extended_warranty", null);
        in.put("other_charges", List.of());
        return in;
    }
}
