package convoy.contracts.api;

import java.util.List;

/** A successful price-engine output (pricing-model.md §4). INVALID outputs never reach a response: they become problems. */
public record PriceOutput(String status, long vehiclePricePaise, List<Line> lines, long cashPayablePaise, long tcsPaise,
                          long estimatedNetCostPaise, List<String> warnings) {
    public record Line(String code, long amountPaise, Evidence evidence, boolean inCashPayable, List<String> flags) {}
}
