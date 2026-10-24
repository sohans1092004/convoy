package convoy.price;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** ADR-011: the Java engine reproduces every pricing and tier vector byte for byte (canonical JSON). */
class VectorParityTest {
    static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_LONG_FOR_INTS);
    static final Path VECTORS = Path.of(System.getProperty("convoy.repoRoot", "../.."), "spec-tools", "vectors");
    /** Extra T23 fields describe downstream order behaviour, not engine output (gen_vectors.py). */
    static final List<String> T23_EXTRAS = List.of("post_deadline_cancellations", "price_after_cancellations_paise", "per_booking_refund_reason");

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(String name) throws Exception {
        return JSON.readValue(VECTORS.resolve(name).toFile(), Map.class);
    }

    @SuppressWarnings("unchecked")
    @TestFactory
    Stream<DynamicTest> pricingVectors() throws Exception {
        Map<String, Object> rules = read("rules.json");
        List<Map<String, Object>> cases = (List<Map<String, Object>>) read("pricing.json").get("cases");
        assertEquals(54, cases.size());
        return cases.stream().map(c -> DynamicTest.dynamicTest((String) c.get("id"), () -> {
            Map<String, Object> inp = (Map<String, Object>) c.get("input");
            Map<String, Object> body = PriceEngine.snapshotBody(inp, rules);
            assertEquals(CanonicalJson.write(c.get("expected")), CanonicalJson.write(body.get("output")));
            assertEquals(c.get("snapshot_sha256"), CanonicalJson.sha256(body));
        }));
    }

    @SuppressWarnings("unchecked")
    @TestFactory
    Stream<DynamicTest> tierVectors() throws Exception {
        List<Map<String, Object>> cases = (List<Map<String, Object>>) read("tiers.json").get("cases");
        assertEquals(30, cases.size());
        return cases.stream().map(c -> DynamicTest.dynamicTest((String) c.get("id"), () -> {
            Map<String, Object> in = (Map<String, Object>) c.get("input");
            List<Tiers.Tier> tiers = ((List<Object>) in.get("tiers")).stream().map(Tiers.Tier::of).toList();
            Long maxUnits = in.get("max_units") == null ? null : ((Number) in.get("max_units")).longValue();
            long n;
            if (in.containsKey("confirmations")) {
                List<Tiers.Confirmation> cs = new ArrayList<>();
                for (Object o : (List<Object>) in.get("confirmations")) {
                    Map<String, Object> m = (Map<String, Object>) o;
                    cs.add(new Tiers.Confirmation((String) m.get("confirmed_at"), (String) m.get("deposit_captured_at"), (String) m.get("cancelled_at")));
                }
                n = Tiers.countConfirmed(cs, (String) in.get("confirmation_deadline"));
            } else {
                n = ((Number) in.get("confirmed_count")).longValue();
            }
            Map<String, Object> expected = new LinkedHashMap<>((Map<String, Object>) c.get("expected"));
            if ("T23".equals(c.get("id"))) {
                assertEquals(expected.get("resolved_price_paise"), expected.get("price_after_cancellations_paise"));
                T23_EXTRAS.forEach(expected::remove);
            }
            Map<String, Object> got = Tiers.resolve(tiers, ((Number) in.get("min_fulfilment")).longValue(), n, maxUnits);
            assertEquals(CanonicalJson.write(expected), CanonicalJson.write(got));
        }));
    }
}
