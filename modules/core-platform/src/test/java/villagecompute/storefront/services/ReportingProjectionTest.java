package villagecompute.storefront.services;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.*;
import villagecompute.storefront.data.repositories.*;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Unit tests for ReportingProjectionService.
 *
 * <p>
 * Verifies aggregate computation logic, tenant isolation, and SLA freshness tracking.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I3.T3 - Reporting Projection Service</li>
 * <li>Testing Guidance: Simulate event streams, ensure tenant isolation</li>
 * </ul>
 */
@QuarkusTest
public class ReportingProjectionTest {

    @Inject
    ReportingProjectionService projectionService;

    @Inject
    SalesByPeriodAggregateRepository salesAggregateRepo;

    @Inject
    ConsignmentPayoutAggregateRepository payoutAggregateRepo;

    @Inject
    InventoryAgingAggregateRepository agingAggregateRepo;

    @Inject
    CartRepository cartRepository;

    @Inject
    CartItemRepository cartItemRepository;

    @Inject
    ConsignorRepository consignorRepository;

    @Inject
    ConsignmentItemRepository consignmentItemRepository;

    @Inject
    ProductRepository productRepository;

    @Inject
    ProductVariantRepository productVariantRepository;

    @Inject
    InventoryLocationRepository inventoryLocationRepository;

    @Inject
    InventoryLevelRepository inventoryLevelRepository;

    private Tenant testTenant;
    private Product testProduct;
    private ProductVariant testVariant;

    @BeforeEach
    @Transactional
    public void setup() {
        // Create test tenant
        testTenant = new Tenant();
        testTenant.subdomain = "test-reporting-" + UUID.randomUUID().toString().substring(0, 8);
        testTenant.name = "Test Reporting Tenant";
        testTenant.status = "active";
        testTenant.createdAt = OffsetDateTime.now();
        testTenant.updatedAt = OffsetDateTime.now();
        testTenant.persist();

        TenantContext.setCurrentTenantId(testTenant.id);

        // Create test product and variant
        testProduct = new Product();
        testProduct.tenant = testTenant;
        testProduct.sku = "TEST-PROD-" + UUID.randomUUID().toString().substring(0, 8);
        testProduct.slug = "test-product-" + UUID.randomUUID().toString().substring(0, 8);
        testProduct.name = "Test Product";
        testProduct.status = "active";
        testProduct.createdAt = OffsetDateTime.now();
        testProduct.updatedAt = OffsetDateTime.now();
        testProduct.persist();

        testVariant = new ProductVariant();
        testVariant.tenant = testTenant;
        testVariant.product = testProduct;
        testVariant.sku = "TEST-SKU-" + UUID.randomUUID().toString().substring(0, 8);
        testVariant.name = "Test Variant";
        testVariant.price = new BigDecimal("99.99");
        testVariant.createdAt = OffsetDateTime.now();
        testVariant.updatedAt = OffsetDateTime.now();
        testVariant.persist();
    }

    @AfterEach
    public void cleanup() {
        TenantContext.clear();
    }

    @Test
    @Transactional
    public void testRefreshSalesAggregates_EmptyData() {
        LocalDate today = LocalDate.now();

        projectionService.refreshSalesAggregates(today, today);

        Optional<SalesByPeriodAggregate> aggregateOpt = salesAggregateRepo.findByExactPeriod(today, today);
        assertTrue(aggregateOpt.isPresent(), "Aggregate should be created even with no data");

        SalesByPeriodAggregate aggregate = aggregateOpt.get();
        assertEquals(BigDecimal.ZERO, aggregate.totalAmount);
        assertEquals(0, aggregate.itemCount);
        assertEquals(0, aggregate.orderCount);
        assertNotNull(aggregate.dataFreshnessTimestamp);
        assertNotNull(aggregate.jobName);
    }

    @Test
    @Transactional
    public void testRefreshSalesAggregates_WithCartData() {
        LocalDate today = LocalDate.now();

        // Create test cart
        Cart cart = new Cart();
        cart.tenant = testTenant;
        cart.sessionId = "test-session-" + UUID.randomUUID();
        cart.expiresAt = OffsetDateTime.now().plusDays(1);
        cart.createdAt = OffsetDateTime.now();
        cart.updatedAt = OffsetDateTime.now();
        cart.persist();

        // Add cart items
        CartItem item1 = new CartItem();
        item1.tenant = testTenant;
        item1.cart = cart;
        item1.variant = testVariant;
        item1.quantity = 2;
        item1.unitPrice = new BigDecimal("99.99");
        item1.createdAt = OffsetDateTime.now();
        item1.updatedAt = OffsetDateTime.now();
        item1.persist();

        CartItem item2 = new CartItem();
        item2.tenant = testTenant;
        item2.cart = cart;
        item2.variant = testVariant;
        item2.quantity = 1;
        item2.unitPrice = new BigDecimal("99.99");
        item2.createdAt = OffsetDateTime.now();
        item2.updatedAt = OffsetDateTime.now();
        item2.persist();

        projectionService.refreshSalesAggregates(today, today);

        Optional<SalesByPeriodAggregate> aggregateOpt = salesAggregateRepo.findByExactPeriod(today, today);
        assertTrue(aggregateOpt.isPresent());

        SalesByPeriodAggregate aggregate = aggregateOpt.get();
        assertEquals(new BigDecimal("299.97"), aggregate.totalAmount); // 2*99.99 + 1*99.99
        assertEquals(2, aggregate.itemCount);
        assertEquals(1, aggregate.orderCount);
    }

    @Test
    @Transactional
    public void testRefreshConsignmentPayoutAggregates_WithConsignmentData() {
        LocalDate today = LocalDate.now();

        // Create consignor
        Consignor consignor = new Consignor();
        consignor.tenant = testTenant;
        consignor.name = "Test Consignor";
        consignor.createdAt = OffsetDateTime.now();
        consignor.updatedAt = OffsetDateTime.now();
        consignor.persist();

        // Create sold consignment item
        ConsignmentItem item = new ConsignmentItem();
        item.tenant = testTenant;
        item.product = testProduct;
        item.consignor = consignor;
        item.commissionRate = new BigDecimal("30.00");
        item.status = "sold";
        item.soldAt = OffsetDateTime.now();
        item.createdAt = OffsetDateTime.now();
        item.updatedAt = OffsetDateTime.now();
        item.persist();

        projectionService.refreshConsignmentPayoutAggregates(today, today);

        Optional<ConsignmentPayoutAggregate> aggregateOpt = payoutAggregateRepo.findExact(consignor.id, today, today);
        assertTrue(aggregateOpt.isPresent());

        ConsignmentPayoutAggregate aggregate = aggregateOpt.get();
        // Payout = salePrice * (1 - commissionRate/100) = 100 * 0.70 = 70.00
        assertEquals(0, aggregate.totalOwed.compareTo(new BigDecimal("70.00")));
        assertEquals(1, aggregate.itemCount);
        assertEquals(1, aggregate.itemsSold);
    }

    @Test
    @Transactional
    public void testRefreshInventoryAgingAggregates_WithInventoryData() {
        // Create inventory location
        InventoryLocation location = new InventoryLocation();
        location.tenant = testTenant;
        location.code = "LOC-" + UUID.randomUUID().toString().substring(0, 8);
        location.name = "Test Location";
        location.type = "warehouse";
        location.active = true;
        location.createdAt = OffsetDateTime.now();
        location.updatedAt = OffsetDateTime.now();
        location.persist();

        // Create inventory level
        InventoryLevel level = new InventoryLevel();
        level.tenant = testTenant;
        level.variant = testVariant;
        level.location = location.code;
        level.quantity = 10;
        level.createdAt = OffsetDateTime.now().minusDays(35); // >30 days old
        level.updatedAt = OffsetDateTime.now();
        level.persist();

        projectionService.refreshInventoryAgingAggregates();

        Optional<InventoryAgingAggregate> aggregateOpt = agingAggregateRepo.findExact(testVariant.id, location.id);
        assertTrue(aggregateOpt.isPresent());

        InventoryAgingAggregate aggregate = aggregateOpt.get();
        long expectedDays = ChronoUnit.DAYS.between(level.createdAt, aggregate.dataFreshnessTimestamp);
        assertEquals(expectedDays, aggregate.daysInStock);
        assertEquals(10, aggregate.quantity);
        assertNotNull(aggregate.firstReceivedAt);
    }

    @Test
    @Transactional
    public void testTenantIsolation() {
        // Create second tenant
        Tenant tenant2 = new Tenant();
        tenant2.subdomain = "test-reporting-2-" + UUID.randomUUID().toString().substring(0, 8);
        tenant2.name = "Test Reporting Tenant 2";
        tenant2.status = "active";
        tenant2.createdAt = OffsetDateTime.now();
        tenant2.updatedAt = OffsetDateTime.now();
        tenant2.persist();

        LocalDate today = LocalDate.now();

        // Refresh for tenant 1
        TenantContext.setCurrentTenantId(testTenant.id);
        projectionService.refreshSalesAggregates(today, today);

        // Switch to tenant 2 and verify no cross-tenant data
        TenantContext.setCurrentTenantId(tenant2.id);
        Optional<SalesByPeriodAggregate> aggregateOpt = salesAggregateRepo.findByExactPeriod(today, today);
        assertFalse(aggregateOpt.isPresent(), "Tenant 2 should not see tenant 1's aggregates");
    }

    @Test
    @Transactional
    public void testAggregateUpdate_PreservesId() {
        LocalDate today = LocalDate.now();

        // First refresh
        projectionService.refreshSalesAggregates(today, today);
        Optional<SalesByPeriodAggregate> firstAggregateOpt = salesAggregateRepo.findByExactPeriod(today, today);
        assertTrue(firstAggregateOpt.isPresent());
        UUID firstId = firstAggregateOpt.get().id;

        // Second refresh (update)
        projectionService.refreshSalesAggregates(today, today);
        Optional<SalesByPeriodAggregate> secondAggregateOpt = salesAggregateRepo.findByExactPeriod(today, today);
        assertTrue(secondAggregateOpt.isPresent());
        UUID secondId = secondAggregateOpt.get().id;

        assertEquals(firstId, secondId, "Aggregate ID should be preserved on update");
    }
}
