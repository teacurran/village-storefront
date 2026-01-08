package villagecompute.storefront.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.ConsignmentItem;
import villagecompute.storefront.data.models.Consignor;
import villagecompute.storefront.data.models.PayoutBatch;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.services.ConsignmentService.PayoutCalculation;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for {@link ConsignmentService}.
 *
 * <p>
 * Tests cover consignor CRUD, consignment item intake, payout calculations, and tenant isolation.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T1: Consignment service tests</li>
 * </ul>
 */
@QuarkusTest
class ConsignmentServiceTest {

    @Inject
    ConsignmentService consignmentService;

    @Inject
    EntityManager entityManager;

    private UUID tenantId;
    private UUID tenant2Id;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up existing data
        entityManager.createQuery("DELETE FROM PayoutLineItem").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutBatch").executeUpdate();
        entityManager.createQuery("DELETE FROM ConsignmentItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Consignor").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductVariant").executeUpdate();
        entityManager.createQuery("DELETE FROM Product").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();

        // Create test tenant
        Tenant tenant = new Tenant();
        tenant.subdomain = "consigntest";
        tenant.name = "Consignment Test Tenant";
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();
        tenantId = tenant.id;

        // Create second tenant for isolation tests
        Tenant tenant2 = new Tenant();
        tenant2.subdomain = "consigntest2";
        tenant2.name = "Consignment Test Tenant 2";
        tenant2.status = "active";
        tenant2.settings = "{}";
        tenant2.createdAt = OffsetDateTime.now();
        tenant2.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant2);
        entityManager.flush();
        tenant2Id = tenant2.id;

        // Set current tenant context
        TenantContext.setCurrentTenant(new TenantInfo(tenantId, tenant.subdomain, tenant.name, tenant.status));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ========================================
    // Consignor CRUD Tests
    // ========================================

    @Test
    @Transactional
    void testCreateConsignor() {
        Consignor consignor = new Consignor();
        consignor.name = "John Doe Collectibles";
        consignor.contactInfo = "{\"email\":\"john@example.com\"}";
        consignor.payoutSettings = "{\"default_commission_rate\":15.0}";
        consignor.status = "active";

        Consignor created = consignmentService.createConsignor(consignor);

        assertNotNull(created.id);
        assertEquals("John Doe Collectibles", created.name);
        assertEquals("active", created.status);
        assertNotNull(created.createdAt);
        assertNotNull(created.updatedAt);
    }

    @Test
    @Transactional
    void testGetConsignor() {
        Consignor consignor = createTestConsignor("Test Vendor");

        Optional<Consignor> retrieved = consignmentService.getConsignor(consignor.id);

        assertTrue(retrieved.isPresent());
        assertEquals(consignor.id, retrieved.get().id);
        assertEquals("Test Vendor", retrieved.get().name);
    }

    @Test
    @Transactional
    void testUpdateConsignor() {
        Consignor consignor = createTestConsignor("Old Name");

        Consignor updates = new Consignor();
        updates.name = "New Name";
        updates.contactInfo = "{\"email\":\"updated@example.com\"}";
        updates.payoutSettings = consignor.payoutSettings;
        updates.status = "active";

        Consignor updated = consignmentService.updateConsignor(consignor.id, updates);

        assertEquals("New Name", updated.name);
        assertEquals("{\"email\":\"updated@example.com\"}", updated.contactInfo);
    }

    @Test
    @Transactional
    void testDeleteConsignor() {
        Consignor consignor = createTestConsignor("To Delete");

        consignmentService.deleteConsignor(consignor.id);

        Optional<Consignor> retrieved = consignmentService.getConsignor(consignor.id);
        assertTrue(retrieved.isPresent());
        assertEquals("deleted", retrieved.get().status);
    }

    @Test
    @Transactional
    void testListActiveConsignors() {
        createTestConsignor("Active 1");
        createTestConsignor("Active 2");

        Consignor deleted = createTestConsignor("Deleted");
        consignmentService.deleteConsignor(deleted.id);

        List<Consignor> active = consignmentService.listActiveConsignors(0, 10);

        assertEquals(2, active.size());
    }

    // ========================================
    // Consignment Item Tests
    // ========================================

    @Test
    @Transactional
    void testCreateConsignmentItem() {
        Consignor consignor = createTestConsignor("Vendor");
        Product product = createTestProduct("Vintage Watch");

        ConsignmentItem created = consignmentService.createConsignmentItem(consignor.id, product.id,
                new BigDecimal("15.00"));

        assertNotNull(created.id);
        assertEquals(product.id, created.product.id);
        assertEquals(consignor.id, created.consignor.id);
        assertEquals(new BigDecimal("15.00"), created.commissionRate);
        assertEquals("active", created.status);
    }

    @Test
    @Transactional
    void testMarkItemAsSold() {
        ConsignmentItem item = createTestConsignmentItem();

        consignmentService.markItemAsSold(item.id);

        entityManager.refresh(item);
        assertEquals("sold", item.status);
        assertNotNull(item.soldAt);
    }

    @Test
    @Transactional
    void testGetConsignorItems() {
        Consignor consignor = createTestConsignor("Vendor");
        Product product1 = createTestProduct("Item 1");
        Product product2 = createTestProduct("Item 2");

        createConsignmentItem(consignor, product1);
        createConsignmentItem(consignor, product2);

        List<ConsignmentItem> items = consignmentService.getConsignorItems(consignor.id, 0, 10);

        assertEquals(2, items.size());
    }

    // ========================================
    // Payout Batch Tests
    // ========================================

    @Test
    @Transactional
    void testCreatePayoutBatch() {
        Consignor consignor = createTestConsignor("Vendor");
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 31);

        PayoutBatch batch = consignmentService.createPayoutBatch(consignor.id, start, end);

        assertNotNull(batch.id);
        assertEquals(consignor.id, batch.consignor.id);
        assertEquals(start, batch.periodStart);
        assertEquals(end, batch.periodEnd);
        assertEquals("pending", batch.status);
        assertNotNull(batch.totalAmount);
    }

    @Test
    @Transactional
    void testCreatePayoutBatchDuplicatePeriod() {
        Consignor consignor = createTestConsignor("Vendor");
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 31);

        consignmentService.createPayoutBatch(consignor.id, start, end);

        assertThrows(IllegalArgumentException.class, () -> {
            consignmentService.createPayoutBatch(consignor.id, start, end);
        });
    }

    @Test
    @Transactional
    void testGetPayoutBatch() {
        Consignor consignor = createTestConsignor("Vendor");
        PayoutBatch batch = consignmentService.createPayoutBatch(consignor.id, LocalDate.now().minusDays(30),
                LocalDate.now());

        Optional<PayoutBatch> retrieved = consignmentService.getPayoutBatch(batch.id);

        assertTrue(retrieved.isPresent());
        assertEquals(batch.id, retrieved.get().id);
    }

    @Test
    @Transactional
    void testCompletePayoutBatch() {
        Consignor consignor = createTestConsignor("Vendor");
        PayoutBatch batch = consignmentService.createPayoutBatch(consignor.id, LocalDate.now().minusDays(30),
                LocalDate.now());

        consignmentService.completePayoutBatch(batch.id, "stripe_payout_123");

        entityManager.refresh(batch);
        assertEquals("completed", batch.status);
        assertEquals("stripe_payout_123", batch.paymentReference);
        assertNotNull(batch.processedAt);
    }

    // ========================================
    // Payout Calculation Tests
    // ========================================

    @Test
    void testCalculatePayout() {
        BigDecimal itemSubtotal = new BigDecimal("100.00");
        BigDecimal commissionRate = new BigDecimal("15.00");

        PayoutCalculation calc = consignmentService.calculatePayout(itemSubtotal, commissionRate);

        assertEquals(new BigDecimal("100.00"), calc.itemSubtotal());
        assertEquals(new BigDecimal("15.0000"), calc.commissionAmount());
        assertEquals(new BigDecimal("85.0000"), calc.netPayout());
    }

    @Test
    void testCalculatePayoutZeroCommission() {
        BigDecimal itemSubtotal = new BigDecimal("100.00");
        BigDecimal commissionRate = new BigDecimal("0.00");

        PayoutCalculation calc = consignmentService.calculatePayout(itemSubtotal, commissionRate);

        assertEquals(new BigDecimal("100.00"), calc.itemSubtotal());
        assertEquals(new BigDecimal("0.0000"), calc.commissionAmount());
        assertEquals(new BigDecimal("100.0000"), calc.netPayout());
    }

    // ========================================
    // Tenant Isolation Tests
    // ========================================

    @Test
    @Transactional
    void testConsignorTenantIsolation() {
        Consignor tenant1Consignor = createTestConsignor("Tenant 1 Vendor");

        // Switch to tenant 2
        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, "consigntest2", "Tenant 2", "active"));
        createTestConsignor("Tenant 2 Vendor");

        List<Consignor> tenant2Consignors = consignmentService.listActiveConsignors(0, 10);
        assertEquals(1, tenant2Consignors.size());

        // Switch back to tenant 1
        TenantContext.setCurrentTenant(new TenantInfo(tenantId, "consigntest", "Tenant 1", "active"));
        Optional<Consignor> shouldNotFind = consignmentService.getConsignor(tenant2Consignors.get(0).id);
        assertFalse(shouldNotFind.isPresent());
    }

    // ========================================
    // Helper Methods
    // ========================================

    private Consignor createTestConsignor(String name) {
        Consignor consignor = new Consignor();
        consignor.name = name;
        consignor.contactInfo = "{\"email\":\"test@example.com\"}";
        consignor.payoutSettings = "{\"default_commission_rate\":15.0}";
        consignor.status = "active";
        return consignmentService.createConsignor(consignor);
    }

    private Product createTestProduct(String name) {
        Product product = new Product();
        product.tenant = Tenant.findById(tenantId);
        product.sku = "SKU-" + UUID.randomUUID().toString().substring(0, 8);
        product.name = name;
        product.type = "physical";
        product.status = "active";
        product.createdAt = OffsetDateTime.now();
        product.updatedAt = OffsetDateTime.now();
        entityManager.persist(product);
        entityManager.flush();
        return product;
    }

    private ConsignmentItem createConsignmentItem(Consignor consignor, Product product) {
        return consignmentService.createConsignmentItem(consignor.id, product.id, new BigDecimal("15.00"));
    }

    private ConsignmentItem createTestConsignmentItem() {
        Consignor consignor = createTestConsignor("Test Vendor");
        Product product = createTestProduct("Test Product");
        return createConsignmentItem(consignor, product);
    }
}
