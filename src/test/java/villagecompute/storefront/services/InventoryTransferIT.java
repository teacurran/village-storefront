package villagecompute.storefront.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

import villagecompute.storefront.data.models.AdjustmentReason;
import villagecompute.storefront.data.models.InventoryAdjustment;
import villagecompute.storefront.data.models.InventoryLevel;
import villagecompute.storefront.data.models.InventoryLocation;
import villagecompute.storefront.data.models.InventoryTransfer;
import villagecompute.storefront.data.models.InventoryTransferLine;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.TransferStatus;
import villagecompute.storefront.data.repositories.InventoryAdjustmentRepository;
import villagecompute.storefront.data.repositories.InventoryLevelRepository;
import villagecompute.storefront.data.repositories.InventoryLocationRepository;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for {@link InventoryTransferService}.
 *
 * <p>
 * Tests multi-location inventory transfers, adjustments, and tenant isolation per Task I3.T2 acceptance criteria.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Multi-location inventory workflow</li>
 * <li>Acceptance Criteria: Transfers enforce validations, RLS protects tenant data, label job triggers, docs include
 * sequence</li>
 * </ul>
 */
@QuarkusTest
class InventoryTransferIT {

    @Inject
    InventoryTransferService transferService;

    @Inject
    InventoryService inventoryService;

    @Inject
    InventoryLocationRepository locationRepository;

    @Inject
    InventoryLevelRepository inventoryLevelRepository;

    @Inject
    InventoryAdjustmentRepository adjustmentRepository;

    @Inject
    EntityManager entityManager;

    private UUID tenant1Id;
    private UUID tenant2Id;
    private UUID variantId;
    private InventoryLocation warehouse1;
    private InventoryLocation warehouse2;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up existing data
        entityManager.createQuery("DELETE FROM InventoryAdjustment").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryTransferLine").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryTransfer").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryLevel").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryLocation").executeUpdate();
        entityManager.createQuery("DELETE FROM CartItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Cart").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutLineItem").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutBatch").executeUpdate();
        entityManager.createQuery("DELETE FROM ConsignmentItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Consignor").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductVariant").executeUpdate();
        entityManager.createQuery("DELETE FROM Product").executeUpdate();
        entityManager.createQuery("DELETE FROM User").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();

        // Create test tenants
        Tenant tenant1 = createTenant("transfertest1", "Transfer Test Tenant 1");
        tenant1Id = tenant1.id;

        Tenant tenant2 = createTenant("transfertest2", "Transfer Test Tenant 2");
        tenant2Id = tenant2.id;

        // Set current tenant context to tenant1
        TenantContext.setCurrentTenant(new TenantInfo(tenant1Id, tenant1.subdomain, tenant1.name, tenant1.status));

        // Create locations for tenant1
        warehouse1 = createLocation(tenant1, "warehouse-1", "Main Warehouse", "warehouse");
        warehouse2 = createLocation(tenant1, "warehouse-2", "Secondary Warehouse", "warehouse");

        // Create test product and variant
        Product product = new Product();
        product.tenant = tenant1;
        product.sku = "TEST-TRANSFER-001";
        product.name = "Test Transfer Product";
        product.slug = "test-transfer-product";
        product.type = "physical";
        product.status = "active";
        product.metadata = "{}";
        product.createdAt = OffsetDateTime.now();
        product.updatedAt = OffsetDateTime.now();
        entityManager.persist(product);

        ProductVariant variant = new ProductVariant();
        variant.tenant = tenant1;
        variant.product = product;
        variant.sku = "TEST-TRANSFER-VAR-001";
        variant.name = "Test Transfer Variant";
        variant.price = new BigDecimal("29.99");
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

        // Set initial inventory at warehouse1
        inventoryService.setInventoryLevel(variantId, warehouse1.code, 100);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Tenant createTenant(String subdomain, String name) {
        Tenant tenant = new Tenant();
        tenant.subdomain = subdomain;
        tenant.name = name;
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();
        return tenant;
    }

    private InventoryLocation createLocation(Tenant tenant, String code, String name, String type) {
        InventoryLocation location = new InventoryLocation();
        location.tenant = tenant;
        location.code = code;
        location.name = name;
        location.type = type;
        location.active = true;
        location.createdAt = OffsetDateTime.now();
        location.updatedAt = OffsetDateTime.now();
        entityManager.persist(location);
        entityManager.flush();
        return location;
    }

    @Test
    @Transactional
    void createTransfer_shouldReserveInventoryAtSource() {
        // Arrange
        InventoryTransfer transfer = new InventoryTransfer();
        transfer.sourceLocation = warehouse1;
        transfer.destinationLocation = warehouse2;
        transfer.initiatedBy = "test-user";

        InventoryTransferLine line = new InventoryTransferLine();
        line.variant = ProductVariant.findById(variantId);
        line.quantity = 30;
        transfer.addLine(line);

        // Act
        InventoryTransfer created = transferService.createTransfer(transfer);

        // Assert
        assertNotNull(created.id);
        assertEquals(TransferStatus.PENDING, created.status);
        assertNotNull(created.barcodeJobId, "Barcode job should be enqueued");

        // Verify inventory reserved at source
        InventoryLevel sourceLevel = inventoryLevelRepository.findByVariantAndLocation(variantId, warehouse1.code)
                .get();
        assertEquals(100, sourceLevel.quantity);
        assertEquals(30, sourceLevel.reserved);
        assertEquals(70, sourceLevel.getAvailableQuantity());
    }

    @Test
    @Transactional
    void createTransfer_shouldEnforceSourceDestinationValidation() {
        // Arrange - same source and destination
        InventoryTransfer transfer = new InventoryTransfer();
        transfer.sourceLocation = warehouse1;
        transfer.destinationLocation = warehouse1; // Same as source

        InventoryTransferLine line = new InventoryTransferLine();
        line.variant = ProductVariant.findById(variantId);
        line.quantity = 30;
        transfer.addLine(line);

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> transferService.createTransfer(transfer),
                "Should reject transfer with same source and destination");
    }

    @Test
    @Transactional
    void createTransfer_shouldRejectInsufficientStock() {
        // Arrange - request more than available
        InventoryTransfer transfer = new InventoryTransfer();
        transfer.sourceLocation = warehouse1;
        transfer.destinationLocation = warehouse2;

        InventoryTransferLine line = new InventoryTransferLine();
        line.variant = ProductVariant.findById(variantId);
        line.quantity = 150; // More than available (100)
        transfer.addLine(line);

        // Act & Assert
        assertThrows(InsufficientStockException.class, () -> transferService.createTransfer(transfer),
                "Should reject transfer exceeding available stock");
    }

    @Test
    @Transactional
    void createTransfer_shouldRejectInactiveLocation() {
        warehouse2.active = false;
        entityManager.merge(warehouse2);
        entityManager.flush();

        InventoryTransfer transfer = new InventoryTransfer();
        transfer.sourceLocation = warehouse1;
        transfer.destinationLocation = warehouse2;

        InventoryTransferLine line = new InventoryTransferLine();
        line.variant = ProductVariant.findById(variantId);
        line.quantity = 10;
        transfer.addLine(line);

        assertThrows(IllegalStateException.class, () -> transferService.createTransfer(transfer),
                "Inactive locations must be rejected");
    }

    @Test
    @Transactional
    void createTransfer_shouldRejectVariantFromOtherTenant() {
        // Create variant for tenant2
        TenantContext.clear();
        Tenant tenant2 = Tenant.findById(tenant2Id);
        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, tenant2.subdomain, tenant2.name, tenant2.status));

        Product otherProduct = new Product();
        otherProduct.tenant = tenant2;
        otherProduct.sku = "TENANT2-PROD";
        otherProduct.name = "Tenant 2 Product";
        otherProduct.slug = "tenant-2-product";
        otherProduct.type = "physical";
        otherProduct.status = "active";
        otherProduct.metadata = "{}";
        otherProduct.createdAt = OffsetDateTime.now();
        otherProduct.updatedAt = OffsetDateTime.now();
        entityManager.persist(otherProduct);

        ProductVariant tenant2Variant = new ProductVariant();
        tenant2Variant.tenant = tenant2;
        tenant2Variant.product = otherProduct;
        tenant2Variant.sku = "TENANT2-VAR";
        tenant2Variant.name = "Tenant 2 Variant";
        tenant2Variant.price = new BigDecimal("19.99");
        tenant2Variant.requiresShipping = true;
        tenant2Variant.taxable = true;
        tenant2Variant.position = 0;
        tenant2Variant.status = "active";
        tenant2Variant.attributes = "{}";
        tenant2Variant.createdAt = OffsetDateTime.now();
        tenant2Variant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant2Variant);
        entityManager.flush();

        // Switch context back to tenant1
        TenantContext.clear();
        TenantContext.setCurrentTenant(new TenantInfo(tenant1Id, "transfertest1", "Transfer Test Tenant 1", "active"));

        InventoryTransfer transfer = new InventoryTransfer();
        transfer.sourceLocation = warehouse1;
        transfer.destinationLocation = warehouse2;

        InventoryTransferLine line = new InventoryTransferLine();
        line.variant = tenant2Variant;
        line.quantity = 5;
        transfer.addLine(line);

        assertThrows(IllegalArgumentException.class, () -> transferService.createTransfer(transfer),
                "Cross-tenant variants must be rejected");
    }

    @Test
    @Transactional
    void receiveTransfer_shouldUpdateInventoryLevels() {
        // Arrange - create and complete transfer
        InventoryTransfer transfer = new InventoryTransfer();
        transfer.sourceLocation = warehouse1;
        transfer.destinationLocation = warehouse2;

        InventoryTransferLine line = new InventoryTransferLine();
        line.variant = ProductVariant.findById(variantId);
        line.quantity = 40;
        transfer.addLine(line);

        InventoryTransfer created = transferService.createTransfer(transfer);

        // Act - receive transfer
        InventoryTransfer received = transferService.receiveTransfer(created.id);

        // Assert
        assertEquals(TransferStatus.RECEIVED, received.status);

        // Verify source inventory reduced
        InventoryLevel sourceLevel = inventoryLevelRepository.findByVariantAndLocation(variantId, warehouse1.code)
                .get();
        assertEquals(60, sourceLevel.quantity, "Source should have 100 - 40 = 60");
        assertEquals(0, sourceLevel.reserved, "Reservation should be committed");

        // Verify destination inventory increased
        InventoryLevel destLevel = inventoryLevelRepository.findByVariantAndLocation(variantId, warehouse2.code).get();
        assertEquals(40, destLevel.quantity, "Destination should have 0 + 40 = 40");
    }

    @Test
    @Transactional
    void recordAdjustment_shouldCreateAuditLog() {
        // Act
        InventoryAdjustment adjustment = transferService.recordAdjustment(variantId, warehouse1.id, -10,
                AdjustmentReason.DAMAGE, "admin-user", "Water damage from storm");

        // Assert
        assertNotNull(adjustment.id);
        assertEquals(-10, adjustment.quantityChange);
        assertEquals(100, adjustment.quantityBefore);
        assertEquals(90, adjustment.quantityAfter);
        assertEquals(AdjustmentReason.DAMAGE, adjustment.reason);
        assertEquals("admin-user", adjustment.adjustedBy);
        assertEquals("Water damage from storm", adjustment.notes);

        // Verify adjustment persisted
        List<InventoryAdjustment> adjustments = adjustmentRepository.findByVariant(variantId);
        assertEquals(1, adjustments.size());
    }

    @Test
    @Transactional
    void tenantIsolation_shouldPreventCrossTenantTransfer() {
        // Arrange - create location for tenant2
        TenantContext.clear();
        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, "transfertest2", "Transfer Test Tenant 2", "active"));

        Tenant tenant2 = Tenant.findById(tenant2Id);
        InventoryLocation tenant2Location = createLocation(tenant2, "warehouse-tenant2", "Tenant 2 Warehouse",
                "warehouse");

        // Switch back to tenant1
        TenantContext.clear();
        TenantContext.setCurrentTenant(new TenantInfo(tenant1Id, "transfertest1", "Transfer Test Tenant 1", "active"));

        // Act & Assert - try to transfer to tenant2 location (should fail due to isolation)
        InventoryTransfer transfer = new InventoryTransfer();
        transfer.sourceLocation = warehouse1;
        transfer.destinationLocation = tenant2Location; // Different tenant!

        InventoryTransferLine line = new InventoryTransferLine();
        line.variant = ProductVariant.findById(variantId);
        line.quantity = 10;
        transfer.addLine(line);

        assertThrows(InvalidLocationException.class, () -> transferService.createTransfer(transfer),
                "Should reject cross-tenant transfer");
    }

    @Test
    @Transactional
    void recordAdjustment_shouldLogReasonCode() {
        // Test various reason codes with metrics logging
        transferService.recordAdjustment(variantId, warehouse1.id, 5, AdjustmentReason.CYCLE_COUNT, "admin",
                "Annual count");
        transferService.recordAdjustment(variantId, warehouse1.id, -2, AdjustmentReason.SHRINKAGE, "admin",
                "Missing items");
        transferService.recordAdjustment(variantId, warehouse1.id, 3, AdjustmentReason.RETURN, "admin",
                "Customer return");

        // Verify all adjustments logged
        List<InventoryAdjustment> adjustments = adjustmentRepository.findByLocation(warehouse1.id);
        assertEquals(3, adjustments.size());

        // Verify reason codes
        List<InventoryAdjustment> cycleCountAdjustments = adjustmentRepository
                .findByReason(AdjustmentReason.CYCLE_COUNT);
        assertEquals(1, cycleCountAdjustments.size());
    }
}
