package villagecompute.storefront.services;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.InventoryLevel;
import villagecompute.storefront.data.repositories.InventoryLevelRepository;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Service layer for inventory management operations.
 *
 * <p>
 * Manages inventory levels across multiple locations, including quantity adjustments, reservations, and stock
 * transfers. All operations are tenant-scoped and include structured logging.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T1: Inventory domain implementation</li>
 * <li>ADR-001: Tenant-scoped inventory tracking</li>
 * </ul>
 */
@ApplicationScoped
public class InventoryService {

    private static final Logger LOG = Logger.getLogger(InventoryService.class);

    @Inject
    InventoryLevelRepository inventoryRepository;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Create or update inventory level for a variant at a location.
     *
     * @param variantId
     *            variant UUID
     * @param location
     *            location identifier
     * @param quantity
     *            new quantity
     * @return updated inventory level
     */
    @Transactional
    public InventoryLevel setInventoryLevel(UUID variantId, String location, int quantity) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Setting inventory level - tenantId=%s, variantId=%s, location=%s, quantity=%d", tenantId, variantId,
                location, quantity);

        Optional<InventoryLevel> existing = inventoryRepository.findByVariantAndLocation(variantId, location);

        InventoryLevel inventoryLevel;
        if (existing.isPresent()) {
            inventoryLevel = existing.get();
            int oldQuantity = inventoryLevel.quantity;
            inventoryLevel.quantity = quantity;
            LOG.infof("Updated inventory level - tenantId=%s, variantId=%s, location=%s, oldQty=%d, newQty=%d",
                    tenantId, variantId, location, oldQuantity, quantity);
        } else {
            inventoryLevel = new InventoryLevel();
            inventoryLevel.variant = villagecompute.storefront.data.models.ProductVariant.findById(variantId);
            inventoryLevel.location = location;
            inventoryLevel.quantity = quantity;
            inventoryLevel.reserved = 0;
            LOG.infof("Created new inventory level - tenantId=%s, variantId=%s, location=%s, quantity=%d", tenantId,
                    variantId, location, quantity);
        }

        inventoryRepository.persist(inventoryLevel);
        meterRegistry.counter("inventory.level.updated", "tenant_id", tenantId.toString(), "location", location)
                .increment();

        return inventoryLevel;
    }

    /**
     * Adjust inventory quantity (add or subtract).
     *
     * @param variantId
     *            variant UUID
     * @param location
     *            location identifier
     * @param adjustment
     *            quantity adjustment (positive or negative)
     * @return updated inventory level
     */
    @Transactional
    public InventoryLevel adjustInventory(UUID variantId, String location, int adjustment) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Adjusting inventory - tenantId=%s, variantId=%s, location=%s, adjustment=%d", tenantId, variantId,
                location, adjustment);

        InventoryLevel inventoryLevel = inventoryRepository.findByVariantAndLocation(variantId, location)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Inventory level not found for variant " + variantId + " at location " + location));

        int oldQuantity = inventoryLevel.quantity;
        inventoryLevel.quantity += adjustment;

        if (inventoryLevel.quantity < 0) {
            LOG.warnf(
                    "Inventory adjustment resulted in negative quantity - tenantId=%s, variantId=%s, location=%s, newQty=%d",
                    tenantId, variantId, location, inventoryLevel.quantity);
        }

        inventoryRepository.persist(inventoryLevel);

        LOG.infof("Inventory adjusted - tenantId=%s, variantId=%s, location=%s, oldQty=%d, newQty=%d, adjustment=%d",
                tenantId, variantId, location, oldQuantity, inventoryLevel.quantity, adjustment);

        return inventoryLevel;
    }

    /**
     * Reserve inventory for an order (increase reserved count).
     *
     * @param variantId
     *            variant UUID
     * @param location
     *            location identifier
     * @param quantity
     *            quantity to reserve
     * @return updated inventory level
     */
    @Transactional
    public InventoryLevel reserveInventory(UUID variantId, String location, int quantity) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Reserving inventory - tenantId=%s, variantId=%s, location=%s, quantity=%d", tenantId, variantId,
                location, quantity);

        InventoryLevel inventoryLevel = inventoryRepository.findByVariantAndLocation(variantId, location)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Inventory level not found for variant " + variantId + " at location " + location));

        if (inventoryLevel.getAvailableQuantity() < quantity) {
            throw new IllegalStateException("Insufficient inventory to reserve - available: "
                    + inventoryLevel.getAvailableQuantity() + ", requested: " + quantity);
        }

        inventoryLevel.reserved += quantity;
        inventoryRepository.persist(inventoryLevel);

        LOG.infof("Inventory reserved - tenantId=%s, variantId=%s, location=%s, reserved=%d", tenantId, variantId,
                location, quantity);
        meterRegistry.counter("inventory.reserved", "tenant_id", tenantId.toString(), "location", location)
                .increment(quantity);

        return inventoryLevel;
    }

    /**
     * Release reserved inventory (decrease reserved count).
     *
     * @param variantId
     *            variant UUID
     * @param location
     *            location identifier
     * @param quantity
     *            quantity to release
     * @return updated inventory level
     */
    @Transactional
    public InventoryLevel releaseReservation(UUID variantId, String location, int quantity) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Releasing inventory reservation - tenantId=%s, variantId=%s, location=%s, quantity=%d", tenantId,
                variantId, location, quantity);

        InventoryLevel inventoryLevel = inventoryRepository.findByVariantAndLocation(variantId, location)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Inventory level not found for variant " + variantId + " at location " + location));

        inventoryLevel.reserved = Math.max(0, inventoryLevel.reserved - quantity);
        inventoryRepository.persist(inventoryLevel);

        LOG.infof("Inventory reservation released - tenantId=%s, variantId=%s, location=%s, released=%d", tenantId,
                variantId, location, quantity);

        return inventoryLevel;
    }

    /**
     * Commit reserved inventory (decrease both reserved and quantity).
     *
     * @param variantId
     *            variant UUID
     * @param location
     *            location identifier
     * @param quantity
     *            quantity to commit
     * @return updated inventory level
     */
    @Transactional
    public InventoryLevel commitReservation(UUID variantId, String location, int quantity) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Committing inventory reservation - tenantId=%s, variantId=%s, location=%s, quantity=%d", tenantId,
                variantId, location, quantity);

        InventoryLevel inventoryLevel = inventoryRepository.findByVariantAndLocation(variantId, location)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Inventory level not found for variant " + variantId + " at location " + location));

        inventoryLevel.quantity -= quantity;
        inventoryLevel.reserved = Math.max(0, inventoryLevel.reserved - quantity);
        inventoryRepository.persist(inventoryLevel);

        LOG.infof("Inventory reservation committed - tenantId=%s, variantId=%s, location=%s, committed=%d", tenantId,
                variantId, location, quantity);
        meterRegistry.counter("inventory.committed", "tenant_id", tenantId.toString(), "location", location)
                .increment(quantity);

        return inventoryLevel;
    }

    /**
     * Get inventory levels for a variant across all locations.
     *
     * @param variantId
     *            variant UUID
     * @return list of inventory levels
     */
    public List<InventoryLevel> getInventoryLevels(UUID variantId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Fetching inventory levels - tenantId=%s, variantId=%s", tenantId, variantId);

        return inventoryRepository.findByVariant(variantId);
    }

    /**
     * Get total available quantity for a variant across all locations.
     *
     * @param variantId
     *            variant UUID
     * @return total available quantity
     */
    public int getTotalAvailableQuantity(UUID variantId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Calculating total available quantity - tenantId=%s, variantId=%s", tenantId, variantId);

        return inventoryRepository.getTotalAvailableQuantity(variantId);
    }

    /**
     * Check if variant is in stock (has available inventory).
     *
     * @param variantId
     *            variant UUID
     * @return true if in stock
     */
    public boolean isInStock(UUID variantId) {
        return getTotalAvailableQuantity(variantId) > 0;
    }
}
