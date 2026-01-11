package villagecompute.storefront.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import villagecompute.storefront.data.models.AdjustmentReason;
import villagecompute.storefront.data.models.AuditLogEntry;
import villagecompute.storefront.data.models.DomainEvent;
import villagecompute.storefront.data.models.InventoryAdjustment;
import villagecompute.storefront.data.models.InventoryLevel;
import villagecompute.storefront.data.models.InventoryLocation;
import villagecompute.storefront.data.models.InventoryTransfer;
import villagecompute.storefront.data.models.InventoryTransferLine;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.TransferStatus;
import villagecompute.storefront.data.repositories.AuditLogRepository;
import villagecompute.storefront.data.repositories.InventoryAdjustmentRepository;
import villagecompute.storefront.data.repositories.InventoryLevelRepository;
import villagecompute.storefront.data.repositories.InventoryLocationRepository;
import villagecompute.storefront.notifications.InventoryNotificationPayload;
import villagecompute.storefront.notifications.InventoryNotificationQueue;
import villagecompute.storefront.notifications.InventoryNotificationType;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.narayana.jta.QuarkusTransaction;
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

    @Inject
    villagecompute.storefront.services.jobs.LowStockAlertScheduler lowStockAlertScheduler;

    @Inject
    AuditLogRepository auditLogRepository;

    @Inject
    InventoryNotificationQueue inventoryNotificationQueue;

    private UUID tenant1Id;
    private UUID tenant2Id;
    private UUID variantId;
    private InventoryLocation warehouse1;
    private InventoryLocation warehouse2;

    @BeforeEach
    @Transactional
    void setUp() {
        inventoryNotificationQueue.clear();

        // Clean up existing data
        clearAuditLogsIfPresent();
        entityManager.createQuery("DELETE FROM DomainEvent").executeUpdate();
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

    private void clearAuditLogsIfPresent() {
        Number tableCount = (Number) entityManager
                .createNativeQuery(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE lower(table_name) = 'audit_log_entries'")
                .getSingleResult();
        if (tableCount != null && tableCount.intValue() > 0) {
            entityManager.createQuery("DELETE FROM AuditLogEntry").executeUpdate();
        }
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

        assertEquals(1,
                auditLogRepository.findByEntityAndAction(created.id, "inventory_transfer_created").size(),
                "Transfer creation should record audit entry");

        InventoryNotificationPayload payload = inventoryNotificationQueue.poll();
        assertNotNull(payload, "Notification payload should be enqueued");
        assertEquals(InventoryNotificationType.TRANSFER_INITIATED, payload.getType());
        assertEquals(created.id, payload.getTransferId());
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
        inventoryNotificationQueue.poll(); // Drain creation notification

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

        assertEquals(1,
                auditLogRepository.findByEntityAndAction(created.id, "inventory_transfer_received").size(),
                "Receiving transfer should record audit entry");
        InventoryNotificationPayload receivePayload = inventoryNotificationQueue.poll();
        assertNotNull(receivePayload);
        assertEquals(InventoryNotificationType.TRANSFER_RECEIVED, receivePayload.getType());
        assertEquals(created.id, receivePayload.getTransferId());
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

        assertEquals(1,
                auditLogRepository.findByEntityAndAction(adjustment.id, "inventory_adjustment_recorded").size(),
                "Adjustment should produce audit entry");
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

        assertEquals(3, auditLogRepository.findByAction("inventory_adjustment_recorded").size(),
                "Every adjustment should produce audit entry");
    }

    @Test
    @Transactional
    void concurrentAdjustments_shouldHandleOptimisticLocking() throws InterruptedException, ExecutionException {
        // Setup: Create inventory level with quantity 100
        inventoryService.setInventoryLevel(variantId, warehouse1.code, 100);

        // Execute: Two threads adjust simultaneously
        CompletableFuture<Void> future1 = CompletableFuture.runAsync(() -> {
            try {
                TenantContext.setCurrentTenant(
                        new TenantInfo(tenant1Id, "transfertest1", "Transfer Test Tenant 1", "active"));
                inventoryService.adjustInventory(variantId, warehouse1.code, 10);
            } finally {
                TenantContext.clear();
            }
        });

        CompletableFuture<Void> future2 = CompletableFuture.runAsync(() -> {
            try {
                TenantContext.setCurrentTenant(
                        new TenantInfo(tenant1Id, "transfertest1", "Transfer Test Tenant 1", "active"));
                inventoryService.adjustInventory(variantId, warehouse1.code, 20);
            } finally {
                TenantContext.clear();
            }
        });

        // Wait for both to complete
        CompletableFuture.allOf(future1, future2).join();

        // Verify: Final quantity should be 130 (100 + 10 + 20)
        entityManager.clear();
        InventoryLevel level = inventoryLevelRepository.findByVariantAndLocation(variantId, warehouse1.code)
                .orElseThrow();
        assertEquals(130, level.quantity, "Both adjustments should be applied");
    }

    @Test
    @Transactional
    void recordAdjustment_shouldAllowNegativeQuantity() {
        // Setup: Create inventory level with quantity 10
        inventoryService.setInventoryLevel(variantId, warehouse1.code, 10);

        // Adjust by -20 (should result in negative quantity)
        InventoryAdjustment adjustment = transferService.recordAdjustment(variantId, warehouse1.id, -20,
                AdjustmentReason.DAMAGE, "test-user", "Excessive damage adjustment");

        // Verify adjustment succeeded
        assertNotNull(adjustment.id);
        assertEquals(-20, adjustment.quantityChange);
        assertEquals(10, adjustment.quantityBefore);
        assertEquals(-10, adjustment.quantityAfter);

        // Verify final quantity is negative
        InventoryLevel level = inventoryLevelRepository.findByVariantAndLocation(variantId, warehouse1.code)
                .orElseThrow();
        assertEquals(-10, level.quantity, "Negative quantities should be allowed");
    }

    @Test
    @Transactional
    void receiveTransfer_shouldRejectAlreadyReceived() {
        // Arrange - create and receive transfer
        InventoryTransfer transfer = new InventoryTransfer();
        transfer.sourceLocation = warehouse1;
        transfer.destinationLocation = warehouse2;

        InventoryTransferLine line = new InventoryTransferLine();
        line.variant = ProductVariant.findById(variantId);
        line.quantity = 20;
        transfer.addLine(line);

        InventoryTransfer created = transferService.createTransfer(transfer);
        transferService.receiveTransfer(created.id);

        // Act & Assert - attempt to receive again should fail
        assertThrows(IllegalStateException.class, () -> transferService.receiveTransfer(created.id),
                "Should reject receiving already-received transfer");
    }

    @Test
    @Transactional
    void recordAdjustment_shouldPublishDomainEvent() throws Exception {
        // Act - record adjustment
        InventoryAdjustment adjustment = transferService.recordAdjustment(variantId, warehouse1.id, -5,
                AdjustmentReason.DAMAGE, "test-user", "Test adjustment for event validation");

        entityManager.flush();

        // Query domain events
        List<DomainEvent> events = entityManager.createQuery(
                "SELECT e FROM DomainEvent e WHERE e.aggregateType = :aggregateType AND e.eventType = :eventType",
                DomainEvent.class).setParameter("aggregateType", "INVENTORY_LEVEL")
                .setParameter("eventType", "INVENTORY_ADJUSTED").getResultList();

        // Verify event exists
        assertTrue(!events.isEmpty(), "Event should be published");
        DomainEvent event = events.get(0);

        // Verify event fields
        assertEquals("INVENTORY_LEVEL", event.aggregateType);
        assertEquals("INVENTORY_ADJUSTED", event.eventType);
        assertNotNull(event.payload, "Payload should not be null");

        // Parse and verify payload
        ObjectMapper mapper = new ObjectMapper();
        JsonNode payload = mapper.readTree(event.payload);
        assertEquals(variantId.toString(), payload.get("variantId").asText());
        assertEquals(-5, payload.get("quantityChange").asInt());
        assertEquals("DAMAGE", payload.get("reason").asText());
        assertEquals("test-user", payload.get("adjustedBy").asText());
        assertEquals(adjustment.id.toString(), payload.get("adjustmentId").asText());
    }

    @Test
    @Transactional
    void createTransfer_shouldPublishTransferInitiatedEvent() throws Exception {
        // Act - create transfer
        InventoryTransfer transfer = new InventoryTransfer();
        transfer.sourceLocation = warehouse1;
        transfer.destinationLocation = warehouse2;

        InventoryTransferLine line = new InventoryTransferLine();
        line.variant = ProductVariant.findById(variantId);
        line.quantity = 25;
        transfer.addLine(line);

        InventoryTransfer created = transferService.createTransfer(transfer);
        entityManager.flush();

        // Query domain events
        List<DomainEvent> events = entityManager.createQuery(
                "SELECT e FROM DomainEvent e WHERE e.aggregateType = :aggregateType AND e.eventType = :eventType",
                DomainEvent.class).setParameter("aggregateType", "INVENTORY_TRANSFER")
                .setParameter("eventType", "TRANSFER_INITIATED").getResultList();

        // Verify event exists
        assertTrue(!events.isEmpty(), "TRANSFER_INITIATED event should be published");
        DomainEvent event = events.get(0);

        // Verify event fields
        assertEquals("INVENTORY_TRANSFER", event.aggregateType);
        assertEquals(created.id, event.aggregateId);
        assertEquals("TRANSFER_INITIATED", event.eventType);

        // Parse and verify payload
        ObjectMapper mapper = new ObjectMapper();
        JsonNode payload = mapper.readTree(event.payload);
        assertEquals(created.id.toString(), payload.get("transferId").asText());
        assertEquals(warehouse1.code, payload.get("sourceLocation").asText());
        assertEquals(warehouse2.code, payload.get("destinationLocation").asText());
    }

    @Test
    @Transactional
    void receiveTransfer_shouldPublishTransferReceivedEvent() throws Exception {
        // Arrange - create transfer
        InventoryTransfer transfer = new InventoryTransfer();
        transfer.sourceLocation = warehouse1;
        transfer.destinationLocation = warehouse2;

        InventoryTransferLine line = new InventoryTransferLine();
        line.variant = ProductVariant.findById(variantId);
        line.quantity = 15;
        transfer.addLine(line);

        InventoryTransfer created = transferService.createTransfer(transfer);

        // Act - receive transfer
        transferService.receiveTransfer(created.id);
        entityManager.flush();

        // Query domain events
        List<DomainEvent> events = entityManager.createQuery(
                "SELECT e FROM DomainEvent e WHERE e.aggregateType = :aggregateType AND e.eventType = :eventType",
                DomainEvent.class).setParameter("aggregateType", "INVENTORY_TRANSFER")
                .setParameter("eventType", "TRANSFER_RECEIVED").getResultList();

        // Verify event exists
        assertTrue(!events.isEmpty(), "TRANSFER_RECEIVED event should be published");
        DomainEvent event = events.get(0);

        // Verify event fields
        assertEquals("INVENTORY_TRANSFER", event.aggregateType);
        assertEquals(created.id, event.aggregateId);
        assertEquals("TRANSFER_RECEIVED", event.eventType);
    }

    @Test
    @Transactional
    void recordAdjustment_shouldWorkWithAllReasonCodes() {
        // Test all adjustment reason codes
        transferService.recordAdjustment(variantId, warehouse1.id, 10, AdjustmentReason.CYCLE_COUNT, "admin",
                "Cycle count");
        transferService.recordAdjustment(variantId, warehouse1.id, -5, AdjustmentReason.DAMAGE, "admin",
                "Damaged goods");
        transferService.recordAdjustment(variantId, warehouse1.id, 3, AdjustmentReason.RETURN, "admin",
                "Customer return");
        transferService.recordAdjustment(variantId, warehouse1.id, -2, AdjustmentReason.SHRINKAGE, "admin",
                "Shrinkage");
        transferService.recordAdjustment(variantId, warehouse1.id, 7, AdjustmentReason.FOUND, "admin",
                "Found inventory");
        transferService.recordAdjustment(variantId, warehouse1.id, 1, AdjustmentReason.OTHER, "admin", "Other reason");

        // Verify all adjustments persisted
        List<InventoryAdjustment> adjustments = adjustmentRepository.findByLocation(warehouse1.id);
        assertEquals(6, adjustments.size(), "All reason codes should be accepted");

        // Verify each reason code was used
        assertEquals(1, adjustmentRepository.findByReason(AdjustmentReason.CYCLE_COUNT).size());
        assertEquals(1, adjustmentRepository.findByReason(AdjustmentReason.DAMAGE).size());
        assertEquals(1, adjustmentRepository.findByReason(AdjustmentReason.RETURN).size());
        assertEquals(1, adjustmentRepository.findByReason(AdjustmentReason.SHRINKAGE).size());
        assertEquals(1, adjustmentRepository.findByReason(AdjustmentReason.FOUND).size());
        assertEquals(1, adjustmentRepository.findByReason(AdjustmentReason.OTHER).size());
    }

    @Test
    @Transactional
    void inventoryLevel_shouldSupportSafetyStockAndLowStockThreshold() {
        // Arrange
        InventoryLevel level = inventoryLevelRepository.findByVariantAndLocation(variantId, warehouse1.code).get();

        // Act - set safety stock and threshold
        level.safetyStock = 20;
        level.lowStockThreshold = 30;
        inventoryLevelRepository.persist(level);
        entityManager.flush();

        // Refresh and verify
        InventoryLevel refreshed = inventoryLevelRepository.findByVariantAndLocation(variantId, warehouse1.code).get();
        assertEquals(20, refreshed.safetyStock);
        assertEquals(30, refreshed.lowStockThreshold);
    }

    @Test
    @Transactional
    void inventoryLevel_isLowStock_shouldDetectLowStock() {
        // Arrange
        InventoryLevel level = inventoryLevelRepository.findByVariantAndLocation(variantId, warehouse1.code).get();
        level.quantity = 5;
        level.lowStockThreshold = 10;
        inventoryLevelRepository.persist(level);
        entityManager.flush();

        // Act
        InventoryLevel refreshed = inventoryLevelRepository.findByVariantAndLocation(variantId, warehouse1.code).get();

        // Assert
        assertTrue(refreshed.isLowStock(), "Should be low stock when quantity (5) <= threshold (10)");
    }

    @Test
    @Transactional
    void inventoryLevelRepository_findLowStockItems_shouldReturnLowStockItems() {
        // Arrange - set inventory below threshold
        InventoryLevel level = inventoryLevelRepository.findByVariantAndLocation(variantId, warehouse1.code).get();
        level.quantity = 8;
        level.lowStockThreshold = 10;
        inventoryLevelRepository.persist(level);
        entityManager.flush();

        // Act
        List<InventoryLevel> lowStockItems = inventoryLevelRepository.findLowStockItems();

        // Assert
        assertEquals(1, lowStockItems.size());
        assertEquals(variantId, lowStockItems.get(0).variant.id);
        assertEquals(8, lowStockItems.get(0).quantity);
    }

    @Test
    @Transactional
    void inventoryLevelRepository_findLowStockItems_shouldExcludeItemsAboveThreshold() {
        // Arrange - set inventory above threshold
        InventoryLevel level = inventoryLevelRepository.findByVariantAndLocation(variantId, warehouse1.code).get();
        level.quantity = 50;
        level.lowStockThreshold = 10;
        inventoryLevelRepository.persist(level);
        entityManager.flush();

        // Act
        List<InventoryLevel> lowStockItems = inventoryLevelRepository.findLowStockItems();

        // Assert
        assertTrue(lowStockItems.isEmpty(), "Should not return items above threshold");
    }

    @Test
    @Transactional
    void inventoryLevelRepository_findLowStockItems_shouldExcludeItemsWithoutThreshold() {
        // Arrange - set quantity low but no threshold configured
        InventoryLevel level = inventoryLevelRepository.findByVariantAndLocation(variantId, warehouse1.code).get();
        level.quantity = 5;
        level.lowStockThreshold = 0; // Threshold not configured
        inventoryLevelRepository.persist(level);
        entityManager.flush();

        // Act
        List<InventoryLevel> lowStockItems = inventoryLevelRepository.findLowStockItems();

        // Assert
        assertTrue(lowStockItems.isEmpty(), "Should not return items without configured threshold");
    }

    @Test
    @Transactional
    void inventoryLevelRepository_findLowStockItems_shouldConsiderReservations() {
        InventoryLevel level = inventoryLevelRepository.findByVariantAndLocation(variantId, warehouse1.code).get();
        level.quantity = 50;
        level.reserved = 45;
        level.lowStockThreshold = 10;
        inventoryLevelRepository.persist(level);
        entityManager.flush();

        List<InventoryLevel> lowStockItems = inventoryLevelRepository.findLowStockItems();
        assertEquals(1, lowStockItems.size(), "Reserved quantity should reduce availability for low stock detection");
        assertEquals(variantId, lowStockItems.get(0).variant.id);
    }

    @Test
    void lowStockAlertScheduler_shouldRespectFeatureFlag() {
        // This test validates that the scheduler can be disabled via feature flag
        // Integration test verifies the scheduler respects lowStockAlertsEnabled property

        // Arrange
        lowStockAlertScheduler.setLowStockAlertsEnabled(false);
        lowStockAlertScheduler.setLowStockAlertEmailsEnabled(true); // even if emails enabled, overall flag wins

        // Act - scheduler should skip processing when disabled
        lowStockAlertScheduler.scanForLowStockAlerts();

        // Assert - no exceptions thrown, scheduler respects flag
        // (Log output would show "Low stock alerts disabled via feature flag")
        assertEquals(0, inventoryNotificationQueue.getQueueDepth(),
                "Notifications should not be enqueued when scheduler disabled");
    }

    @Test
    void lowStockAlertScheduler_shouldProcessLowStockAlerts() {
        // Arrange - create low stock situation
        QuarkusTransaction.run(() -> {
            InventoryLevel level = inventoryLevelRepository.findByVariantAndLocation(variantId, warehouse1.code).get();
            level.quantity = 5;
            level.lowStockThreshold = 10;
            inventoryLevelRepository.persist(level);
            entityManager.flush();

            InventoryLevel refreshed = inventoryLevelRepository.findByVariantAndLocation(variantId, warehouse1.code)
                    .get();
            assertEquals(10, refreshed.lowStockThreshold);
            assertTrue(refreshed.isLowStock());
            assertEquals(1, inventoryLevelRepository.findLowStockItems().size());

            // Simulate scheduler tenant switching
            Tenant tenant1 = Tenant.findById(tenant1Id);
            Tenant tenant2 = Tenant.findById(tenant2Id);
            TenantContext.clear();
            TenantContext.setCurrentTenant(new TenantInfo(tenant2.id, tenant2.subdomain, tenant2.name, tenant2.status));
            assertTrue(inventoryLevelRepository.findLowStockItems().isEmpty());
            TenantContext.setCurrentTenant(new TenantInfo(tenant1.id, tenant1.subdomain, tenant1.name, tenant1.status));
            assertEquals(1, inventoryLevelRepository.findLowStockItems().size());
        });

        // Enable scheduler
        lowStockAlertScheduler.setLowStockAlertsEnabled(true);
        lowStockAlertScheduler.setLowStockAlertEmailsEnabled(true);

        // Act
        lowStockAlertScheduler.scanForLowStockAlerts();

        // Assert - scheduler runs without exceptions and processes alert
        // (Logs should show "LOW STOCK ALERT" warning for this item)
        // Metrics would be incremented via MeterRegistry
        InventoryNotificationPayload alertPayload = inventoryNotificationQueue.poll();
        assertNotNull(alertPayload);
        assertEquals(InventoryNotificationType.LOW_STOCK_ALERT, alertPayload.getType());
        assertEquals(variantId, alertPayload.getVariantId());
    }
}
