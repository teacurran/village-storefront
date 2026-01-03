package villagecompute.storefront.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.InventoryLevel;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for {@link InventoryService}.
 *
 * <p>
 * Tests cover inventory level management, reservations, adjustments, and multi-location tracking.
 */
@QuarkusTest
class InventoryServiceTest {

    @Inject
    InventoryService inventoryService;

    @Inject
    EntityManager entityManager;

    private UUID tenantId;
    private UUID variantId;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up existing data
        entityManager.createQuery("DELETE FROM CartItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Cart").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryLevel").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutLineItem").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutBatch").executeUpdate();
        entityManager.createQuery("DELETE FROM ConsignmentItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Consignor").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductVariant").executeUpdate();
        entityManager.createQuery("DELETE FROM Product").executeUpdate();
        entityManager.createQuery("DELETE FROM User").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();

        // Create test tenant
        Tenant tenant = new Tenant();
        tenant.subdomain = "inventorytest";
        tenant.name = "Inventory Test Tenant";
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();
        tenantId = tenant.id;

        // Create test product and variant
        Product product = new Product();
        product.tenant = tenant;
        product.sku = "TEST-PROD-001";
        product.name = "Test Product";
        product.slug = "test-product";
        product.type = "physical";
        product.status = "active";
        product.metadata = "{}";
        product.createdAt = OffsetDateTime.now();
        product.updatedAt = OffsetDateTime.now();
        entityManager.persist(product);

        ProductVariant variant = new ProductVariant();
        variant.tenant = tenant;
        variant.product = product;
        variant.sku = "TEST-VAR-001";
        variant.name = "Test Variant";
        variant.price = new BigDecimal("19.99");
        variant.requiresShipping = true;
        variant.taxable = true;
        variant.position = 0;
        variant.status = "active";
        variant.attributes = "{}";
        variant.createdAt = OffsetDateTime.now();
        variant.updatedAt = OffsetDateTime.now();
        entityManager.persist(variant);
        entityManager.flush();
        variantId = variant.id;

        // Set current tenant context
        TenantContext.setCurrentTenant(new TenantInfo(tenantId, tenant.subdomain, tenant.name, tenant.status));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @Transactional
    void setInventoryLevel_shouldCreateNewLevel() {
        InventoryLevel level = inventoryService.setInventoryLevel(variantId, "warehouse-1", 100);

        assertEquals(100, level.quantity);
        assertEquals("warehouse-1", level.location);
        assertEquals(variantId, level.variant.id);
    }

    @Test
    @Transactional
    void setInventoryLevel_shouldUpdateExistingLevel() {
        inventoryService.setInventoryLevel(variantId, "warehouse-1", 100);
        InventoryLevel updated = inventoryService.setInventoryLevel(variantId, "warehouse-1", 150);

        assertEquals(150, updated.quantity);
    }

    @Test
    @Transactional
    void adjustInventory_shouldAddToQuantity() {
        inventoryService.setInventoryLevel(variantId, "warehouse-1", 100);
        InventoryLevel adjusted = inventoryService.adjustInventory(variantId, "warehouse-1", 50);

        assertEquals(150, adjusted.quantity);
    }

    @Test
    @Transactional
    void adjustInventory_shouldSubtractFromQuantity() {
        inventoryService.setInventoryLevel(variantId, "warehouse-1", 100);
        InventoryLevel adjusted = inventoryService.adjustInventory(variantId, "warehouse-1", -30);

        assertEquals(70, adjusted.quantity);
    }

    @Test
    @Transactional
    void reserveInventory_shouldIncreaseReservedCount() {
        inventoryService.setInventoryLevel(variantId, "warehouse-1", 100);
        InventoryLevel reserved = inventoryService.reserveInventory(variantId, "warehouse-1", 10);

        assertEquals(100, reserved.quantity);
        assertEquals(10, reserved.reserved);
        assertEquals(90, reserved.getAvailableQuantity());
    }

    @Test
    @Transactional
    void reserveInventory_shouldFailWhenInsufficientStock() {
        inventoryService.setInventoryLevel(variantId, "warehouse-1", 10);

        assertThrows(IllegalStateException.class,
                () -> inventoryService.reserveInventory(variantId, "warehouse-1", 20));
    }

    @Test
    @Transactional
    void releaseReservation_shouldDecreaseReservedCount() {
        inventoryService.setInventoryLevel(variantId, "warehouse-1", 100);
        inventoryService.reserveInventory(variantId, "warehouse-1", 10);
        InventoryLevel released = inventoryService.releaseReservation(variantId, "warehouse-1", 5);

        assertEquals(5, released.reserved);
        assertEquals(95, released.getAvailableQuantity());
    }

    @Test
    @Transactional
    void releaseReservation_shouldNotGoNegative() {
        inventoryService.setInventoryLevel(variantId, "warehouse-1", 50);
        inventoryService.reserveInventory(variantId, "warehouse-1", 5);

        InventoryLevel released = inventoryService.releaseReservation(variantId, "warehouse-1", 20);

        assertEquals(0, released.reserved);
        assertEquals(50, released.quantity);
    }

    @Test
    @Transactional
    void commitReservation_shouldReduceQuantityAndReserved() {
        inventoryService.setInventoryLevel(variantId, "warehouse-1", 100);
        inventoryService.reserveInventory(variantId, "warehouse-1", 10);
        InventoryLevel committed = inventoryService.commitReservation(variantId, "warehouse-1", 10);

        assertEquals(90, committed.quantity);
        assertEquals(0, committed.reserved);
        assertEquals(90, committed.getAvailableQuantity());
    }

    @Test
    @Transactional
    void getInventoryLevels_shouldReturnAllLocations() {
        inventoryService.setInventoryLevel(variantId, "warehouse-1", 50);
        inventoryService.setInventoryLevel(variantId, "warehouse-2", 30);
        inventoryService.setInventoryLevel(variantId, "store-main", 20);

        List<InventoryLevel> levels = inventoryService.getInventoryLevels(variantId);

        assertEquals(3, levels.size());
    }

    @Test
    @Transactional
    void getTotalAvailableQuantity_shouldSumAcrossLocations() {
        inventoryService.setInventoryLevel(variantId, "warehouse-1", 50);
        inventoryService.setInventoryLevel(variantId, "warehouse-2", 30);
        inventoryService.reserveInventory(variantId, "warehouse-1", 10);

        int total = inventoryService.getTotalAvailableQuantity(variantId);

        assertEquals(70, total); // (50-10) + 30
    }

    @Test
    @Transactional
    void isInStock_shouldReturnTrueWhenInventoryAvailable() {
        inventoryService.setInventoryLevel(variantId, "warehouse-1", 10);

        assertTrue(inventoryService.isInStock(variantId));
    }

    @Test
    @Transactional
    void isInStock_shouldReturnFalseWhenNoInventory() {
        assertFalse(inventoryService.isInStock(variantId));
    }
}
