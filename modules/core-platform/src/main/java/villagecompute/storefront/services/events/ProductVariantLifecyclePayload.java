package villagecompute.storefront.services.events;

import java.util.UUID;

/**
 * Payload for PRODUCT_VARIANT_* domain events.
 *
 * <p>
 * Includes identifiers and the most relevant descriptive fields (SKU + status) so consumers can update inventory/search
 * documents.
 */
public record ProductVariantLifecyclePayload(UUID variantId, UUID productId, String sku, String status, String action) {
}
