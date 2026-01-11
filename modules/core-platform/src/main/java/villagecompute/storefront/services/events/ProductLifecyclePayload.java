package villagecompute.storefront.services.events;

import java.util.UUID;

/**
 * Payload for PRODUCT_* domain events.
 *
 * <p>
 * Captures essential product details so downstream processors (reporting, search indexing) can react to catalog
 * mutations without reaching into transactional tables.
 */
public record ProductLifecyclePayload(UUID productId, String slug, String status, String action,
        String visibilityWindow) {
}
