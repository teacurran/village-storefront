package villagecompute.storefront.api.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.CommissionSchedule;
import villagecompute.storefront.data.models.Consignor;
import villagecompute.storefront.data.models.IntakeBatch;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.repositories.ConsignmentRetentionRepository;
import villagecompute.storefront.data.repositories.InventoryLevelRepository;
import villagecompute.storefront.data.repositories.ProductVariantRepository;
import villagecompute.storefront.services.BatchIntakeService;
import villagecompute.storefront.services.ConsignmentService;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.util.PgCryptoUtil;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for Consignment REST endpoints.
 *
 * <p>
 * Tests batch intake flow, commission schedule management, tenant isolation, and encryption helpers.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T1: Consignment domain implementation</li>
 * <li>Resource: {@link ConsignmentResource}</li>
 * <li>Acceptance Criteria: Intake flow, commission validation, encryption tests, payout math</li>
 * </ul>
 */
@QuarkusTest
class ConsignorResourceIT {

    @Inject
    ConsignmentService consignmentService;

    @Inject
    BatchIntakeService batchIntakeService;

    @Inject
    PgCryptoUtil pgCryptoUtil;

    @Inject
    EntityManager entityManager;

    @Inject
    ProductVariantRepository productVariantRepository;

    @Inject
    InventoryLevelRepository inventoryLevelRepository;

    @Inject
    ConsignmentRetentionRepository retentionRepository;

    private Tenant testTenant;
    private Consignor testConsignor;
    private Product testProduct;

    @BeforeEach
    @Transactional
    void setUp() {
        // Create test tenant
        testTenant = new Tenant();
        testTenant.subdomain = "consignment-test";
        testTenant.name = "Consignment Test Tenant";
        testTenant.status = "active";
        OffsetDateTime now = OffsetDateTime.now();
        testTenant.createdAt = now;
        testTenant.updatedAt = now;
        testTenant.persist();

        // Create test consignor
        testConsignor = new Consignor();
        testConsignor.tenant = testTenant;
        testConsignor.name = "Test Consignor";
        testConsignor.contactInfo = "{\"email\":\"consignor@example.com\",\"phone\":\"+1234567890\"}";
        testConsignor.payoutSettings = "{\"default_commission_rate\":15.00,\"stripe_account_id\":\"acct_test123\"}";
        testConsignor.status = "active";
        testConsignor.persist();

        // Create test product
        testProduct = new Product();
        testProduct.tenant = testTenant;
        testProduct.name = "Test Product";
        testProduct.description = "Test description";
        testProduct.title = "Test Product";
        testProduct.slug = "test-product-" + UUID.randomUUID().toString().substring(0, 6);
        testProduct.sku = "TEST-SKU-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        testProduct.type = "consignment";
        testProduct.status = "active";
        testProduct.persist();

        TenantContext.setCurrentTenantId(testTenant.id);
    }

    @AfterEach
    @Transactional
    void tearDown() {
        if (testTenant != null) {
            entityManager.createQuery("DELETE FROM ConsignmentItem WHERE tenant = :tenant")
                    .setParameter("tenant", testTenant).executeUpdate();
            entityManager.createQuery("DELETE FROM IntakeBatch WHERE tenant = :tenant")
                    .setParameter("tenant", testTenant).executeUpdate();
            entityManager.createQuery("DELETE FROM ConsignmentRetentionRecord WHERE tenant = :tenant")
                    .setParameter("tenant", testTenant).executeUpdate();
            entityManager.createQuery("DELETE FROM CommissionSchedule WHERE tenant = :tenant")
                    .setParameter("tenant", testTenant).executeUpdate();
            entityManager.createQuery("DELETE FROM Consignor WHERE tenant = :tenant").setParameter("tenant", testTenant)
                    .executeUpdate();
            entityManager.createQuery("DELETE FROM Product WHERE tenant = :tenant").setParameter("tenant", testTenant)
                    .executeUpdate();
            entityManager.createNativeQuery("DELETE FROM tenants WHERE id = ?").setParameter(1, testTenant.id)
                    .executeUpdate();
        }
        TenantContext.clear();
    }

    // ========================================
    // Batch Intake Flow Tests
    // ========================================

    @Test
    void testCreateIntakeBatch() {
        IntakeBatch batch = batchIntakeService.createIntakeBatch(testConsignor.id, "Test Batch #1",
                "Initial intake batch");

        assertNotNull(batch.id);
        assertEquals("Test Batch #1", batch.batchName);
        assertEquals("pending", batch.status);
        assertEquals(0, batch.totalItems);
        assertEquals(0, batch.processedItems);
    }

    @Test
    void testAddItemsToBatchWithAutoSKU() {
        IntakeBatch batch = batchIntakeService.createIntakeBatch(testConsignor.id, "SKU Auto Batch", null);

        List<BatchIntakeService.IntakeItemRequest> items = new ArrayList<>();
        items.add(new BatchIntakeService.IntakeItemRequest("Widget A", "Auto SKU widget", null, // no SKU
                "123456789", new BigDecimal("29.99"), new BigDecimal("39.99"), new BigDecimal("15.00"),
                new BigDecimal("20.00"), 5, "Blue", new HashMap<>()));

        List<villagecompute.storefront.data.models.ConsignmentItem> created = batchIntakeService
                .addItemsToBatch(batch.id, items);

        assertEquals(1, created.size());
        assertNotNull(created.get(0).id);
        assertEquals(testConsignor.id, created.get(0).consignor.id);
        assertEquals(new BigDecimal("20.00"), created.get(0).commissionRate);

        UUID variantId = (UUID) entityManager
                .createQuery("SELECT ci.variant.id FROM ConsignmentItem ci WHERE ci.id = :id")
                .setParameter("id", created.get(0).id).getSingleResult();
        assertNotNull(variantId);
        var variant = productVariantRepository.findByIdForTenant(variantId).orElseThrow();
        assertTrue(variant.sku.startsWith("CONSIGN-"));

        inventoryLevelRepository.findByVariantAndLocation(variantId, "default").ifPresentOrElse(level -> {
            assertEquals(5, level.quantity.intValue());
        }, () -> fail("Inventory level should exist for variant"));
    }

    @Test
    void testCompleteBatch() {
        IntakeBatch batch = batchIntakeService.createIntakeBatch(testConsignor.id, "Complete Batch", null);

        List<BatchIntakeService.IntakeItemRequest> items = new ArrayList<>();
        items.add(new BatchIntakeService.IntakeItemRequest("Item 1", "Test item", "SKU-001", null,
                new BigDecimal("19.99"), null, new BigDecimal("10.00"), new BigDecimal("15.00"), 2, null,
                new HashMap<>()));

        batchIntakeService.addItemsToBatch(batch.id, items);

        IntakeBatch completed = batchIntakeService.completeBatch(batch.id);

        assertEquals("completed", completed.status);
        assertNotNull(completed.completedAt);
        assertEquals(1, completed.totalItems);
        assertEquals(1, completed.processedItems);
    }

    @Test
    void testDataRetentionHook() {
        IntakeBatch batch = batchIntakeService.createIntakeBatch(testConsignor.id, "Retention Batch", null);

        List<BatchIntakeService.IntakeItemRequest> items = new ArrayList<>();
        items.add(new BatchIntakeService.IntakeItemRequest("Retention Item", "For retention check", null, null,
                new BigDecimal("10.00"), null, new BigDecimal("5.00"), new BigDecimal("15.00"), 1, null,
                new HashMap<>()));
        batchIntakeService.addItemsToBatch(batch.id, items);

        IntakeBatch completed = batchIntakeService.completeBatch(batch.id);

        var record = retentionRepository.findByResource("INTAKE_BATCH", batch.id);
        assertTrue(record.isPresent());
        assertNotNull(record.get().retainUntil);
        assertTrue(record.get().retainUntil.isAfter(completed.completedAt));
    }

    // ========================================
    // Commission Schedule Tests
    // ========================================

    @Test
    void testCreateCommissionSchedule() {
        CommissionSchedule schedule = new CommissionSchedule();
        schedule.commissionRate = new BigDecimal("18.50");
        schedule.effectiveFrom = LocalDate.now();
        schedule.effectiveUntil = LocalDate.now().plusMonths(6);
        schedule.priority = 1;
        schedule.notes = "Promotional rate";
        schedule.createdBy = "admin@example.com";

        CommissionSchedule created = consignmentService.createCommissionSchedule(testConsignor.id, schedule);

        assertNotNull(created.id);
        assertEquals(new BigDecimal("18.50"), created.commissionRate);
        assertEquals("active", created.status);
        assertEquals(testConsignor.id, created.consignor.id);
    }

    @Test
    void testCommissionScheduleValidation() {
        CommissionSchedule schedule = new CommissionSchedule();
        schedule.commissionRate = new BigDecimal("150.00"); // Invalid: over 100%
        schedule.effectiveFrom = LocalDate.now();

        try {
            consignmentService.createCommissionSchedule(testConsignor.id, schedule);
            assertTrue(false, "Should have thrown validation error");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("between 0 and 100"));
        }
    }

    @Test
    void testGetActiveCommissionSchedules() {
        // Create multiple schedules with different priorities
        CommissionSchedule schedule1 = new CommissionSchedule();
        schedule1.commissionRate = new BigDecimal("15.00");
        schedule1.effectiveFrom = LocalDate.now().minusMonths(1);
        schedule1.priority = 0;
        schedule1.createdBy = "system";
        consignmentService.createCommissionSchedule(testConsignor.id, schedule1);

        CommissionSchedule schedule2 = new CommissionSchedule();
        schedule2.commissionRate = new BigDecimal("20.00");
        schedule2.effectiveFrom = LocalDate.now().minusDays(1);
        schedule2.priority = 10;
        schedule2.createdBy = "admin";
        consignmentService.createCommissionSchedule(testConsignor.id, schedule2);

        List<CommissionSchedule> active = consignmentService.getActiveCommissionSchedules(testConsignor.id);

        assertTrue(active.size() >= 2);
        // Highest priority should be first
        assertEquals(new BigDecimal("20.00"), active.get(0).commissionRate);
    }

    // ========================================
    // Encryption Tests
    // ========================================

    @Test
    void testEncryptDecryptTaxId() {
        String originalTaxId = "123-45-6789";

        byte[] encrypted = pgCryptoUtil.encrypt(originalTaxId, testTenant.id);
        assertNotNull(encrypted);
        assertTrue(encrypted.length > 0);

        String decrypted = pgCryptoUtil.decrypt(encrypted, testTenant.id);
        assertEquals(originalTaxId, decrypted);
    }

    @Test
    void testKeyRotation() {
        String originalData = "sensitive-data-2026";
        String oldVersion = "v1";
        String newVersion = "v2";

        byte[] encryptedV1 = pgCryptoUtil.encrypt(originalData, testTenant.id, oldVersion);
        byte[] rotated = pgCryptoUtil.rotateKey(encryptedV1, testTenant.id, oldVersion, newVersion);

        String decryptedV2 = pgCryptoUtil.decrypt(rotated, testTenant.id, newVersion);
        assertEquals(originalData, decryptedV2);
    }

    @Test
    void testEncryptionConfiguration() {
        boolean isValid = pgCryptoUtil.verifyConfiguration();
        assertTrue(isValid, "Encryption configuration should be valid");
    }

    // ========================================
    // Payout Math Tests
    // ========================================

    @Test
    void testPayoutCalculation() {
        BigDecimal itemSubtotal = new BigDecimal("100.00");
        BigDecimal commissionRate = new BigDecimal("15.00");

        ConsignmentService.PayoutCalculation result = consignmentService.calculatePayout(itemSubtotal, commissionRate);

        assertEquals(new BigDecimal("100.00"), result.itemSubtotal());
        assertEquals(new BigDecimal("15.0000"), result.commissionAmount());
        assertEquals(new BigDecimal("85.0000"), result.netPayout());
    }

    @Test
    void testPayoutCalculationEdgeCases() {
        // Zero commission
        ConsignmentService.PayoutCalculation zero = consignmentService.calculatePayout(new BigDecimal("50.00"),
                BigDecimal.ZERO);
        assertEquals(new BigDecimal("50.00"), zero.netPayout());

        // 100% commission
        ConsignmentService.PayoutCalculation full = consignmentService.calculatePayout(new BigDecimal("50.00"),
                new BigDecimal("100.00"));
        assertEquals(new BigDecimal("0.0000"), full.netPayout());

        // Fractional amounts
        ConsignmentService.PayoutCalculation fractional = consignmentService.calculatePayout(new BigDecimal("33.33"),
                new BigDecimal("12.50"));
        assertTrue(fractional.netPayout().compareTo(new BigDecimal("29.00")) > 0);
    }

    // ========================================
    // Tenant Isolation Tests
    // ========================================

    @Test
    @Transactional
    void testTenantIsolation() {
        // Create second tenant
        Tenant tenant2 = new Tenant();
        tenant2.subdomain = "consignment-test-2";
        tenant2.name = "Test Tenant 2";
        tenant2.status = "active";
        tenant2.persist();

        // Create consignor in second tenant
        TenantContext.setCurrentTenantId(tenant2.id);
        Consignor consignor2 = new Consignor();
        consignor2.tenant = tenant2;
        consignor2.name = "Consignor 2";
        consignor2.status = "active";
        consignor2.persist();

        // Switch back to first tenant
        TenantContext.setCurrentTenantId(testTenant.id);

        // Attempt to access second tenant's consignor should fail
        try {
            consignmentService.getConsignor(consignor2.id);
            assertTrue(false, "Should not be able to access cross-tenant data");
        } catch (Exception e) {
            // Expected - tenant isolation working
        }

        // Cleanup
        TenantContext.setCurrentTenantId(tenant2.id);
        consignor2.delete();
        tenant2.delete();
        TenantContext.setCurrentTenantId(testTenant.id);
    }
}
