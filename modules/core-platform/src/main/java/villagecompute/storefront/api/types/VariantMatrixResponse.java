package villagecompute.storefront.api.types;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Variant matrix response combining option axes and variant entries.
 */
public class VariantMatrixResponse {

    public UUID productId;
    public List<VariantOptionAxis> optionAxes = new ArrayList<>();
    public List<VariantMatrixEntry> variants = new ArrayList<>();

    public VariantMatrixResponse() {
    }
}
