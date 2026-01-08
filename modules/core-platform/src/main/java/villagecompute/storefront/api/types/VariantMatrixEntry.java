package villagecompute.storefront.api.types;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a single variant row in the variant matrix response.
 */
public class VariantMatrixEntry {

    public UUID variantId;
    public String sku;
    public Money price;
    public Boolean available;
    public Map<String, String> options = new LinkedHashMap<>();

    public VariantMatrixEntry() {
    }
}
