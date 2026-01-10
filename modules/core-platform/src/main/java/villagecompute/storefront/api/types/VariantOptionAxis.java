package villagecompute.storefront.api.types;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single variant option axis (e.g., color, size) in the variant matrix response.
 */
public class VariantOptionAxis {

    public String name;
    public List<String> values = new ArrayList<>();

    public VariantOptionAxis() {
    }
}
