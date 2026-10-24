package convoy.catalog;

import org.springframework.lang.Nullable;
import convoy.contracts.api.PriceOutput;
import java.util.List;
import java.util.Map;

/** Response records for every catalog endpoint. */
public final class Api {
    private Api() {}

    public record ModelSummary(String slug, String name, String brand, String bodyType, long variantCount, long fromExShowroomPaise,
                               boolean anyCityConfirmed, convoy.contracts.api.Evidence evidence) {}

    public record ModelList(List<ModelSummary> models, List<String> cities, String rulesetVersion) {}

    public record CityPrice(String city, String stateCode, long exShowroomPaise, convoy.contracts.api.Evidence evidence, boolean cityConfirmed, String sourceUrl, String accessedOn) {}

    public record Variant(String id, String code, String name, @Nullable String fuel, @Nullable String transmission, @Nullable Integer engineCc, @Nullable String note, List<CityPrice> prices) {}

    public record Model(String id, String slug, String name, String brand, String bodyType, String sourceUrl, String sourceTitle, String accessedOn,
                        String priceLabel, String cityBasis, List<Variant> variants) {}

    public record ComparedVariant(String id, String code, String name, @Nullable String fuel, @Nullable String transmission, @Nullable Integer engineCc, String modelSlug, String model,
                                  List<CityPrice> prices) {}

    public record Comparison(List<ComparedVariant> variants) {}

    public record Snapshot(Map<String, Object> inputs, String rulesetVersion, PriceOutput output, String sha256, String computedAt) {}

    public record VariantRef(String id, String code, String name) {}

    public record Source(String url, String accessedOn) {}

    public record Estimate(VariantRef variant, String city, boolean cityConfirmed, Source source, Snapshot snapshot) {}

    public record VariantFacts(String id, String code, String name, @Nullable String fuel, @Nullable String transmission, @Nullable Integer engineCc, @Nullable Long catalogueExShowroomPaise,
                               @Nullable String catalogueEvidence, @Nullable Boolean cityConfirmed) {}

    public record InternalModel(String id, String slug, String name, String brand, List<String> variantIds, List<VariantFacts> variants) {}
}
