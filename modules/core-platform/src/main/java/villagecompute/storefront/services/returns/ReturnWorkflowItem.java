package villagecompute.storefront.services.returns;

import java.util.Objects;
import java.util.UUID;

/**
 * Value object describing a single line item included in a return or restock workflow.
 *
 * <p>
 * Captures immutable line item reference plus operational metadata (quantities, reason codes, notes) so downstream
 * queue processors have enough context to adjust inventory and notify consignors.
 * </p>
 */
public record ReturnWorkflowItem(UUID lineItemId, int quantity, String reasonCode, String notes) {

    public ReturnWorkflowItem {
        Objects.requireNonNull(lineItemId, "lineItemId is required");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than 0");
        }
    }

    /**
     * Create a copy with sanitized defaults for missing optional fields.
     *
     * @param defaultReason
     *            fallback reason code when {@code reasonCode} is blank
     * @return normalized workflow item
     */
    public ReturnWorkflowItem withDefaults(String defaultReason) {
        String normalizedReason = (reasonCode == null || reasonCode.isBlank()) ? defaultReason : reasonCode;
        return new ReturnWorkflowItem(lineItemId, quantity, normalizedReason, notes);
    }
}
