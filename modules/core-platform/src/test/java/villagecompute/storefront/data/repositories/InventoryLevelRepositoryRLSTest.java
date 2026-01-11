package villagecompute.storefront.data.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import villagecompute.storefront.data.models.InventoryLocation;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.services.InventoryService;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;
import villagecompute.storefront.tenant.TenantPostgresRlsProfile;
import villagecompute.storefront.testsupport.PostgresTenantTestResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * RLS (Row-Level Security) tenant isolation tests for {@link InventoryLevelRepository}.
 *
 * <p>
 * Verifies that tenant context properly filters inventory levels at the repository query level, ensuring complete data
 * isolation between tenants per Task I2.T4 acceptance criteria.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T4: Multi-location inventory with tenant isolation</li>
 * <li>Acceptance Criteria: Tests run against dev Postgres with RLS enforcement</li>
 * <li>Architecture: Multi-tenant data isolation via tenant_id filtering</li>
 * </ul>
 */
@QuarkusTest
@TestProfile(TenantPostgresRlsProfile.class)
@QuarkusTestResource(
        value = PostgresTenantTestResource.class,
        restrictToAnnotatedClass = true)
class InventoryLevelRepositoryRLSTest {

    @Inject
    InventoryLevelRepository inventoryLevelRepository;

    @Inject
    InventoryLocationRepository locationRepository;

    @Inject
    InventoryService inventoryService;

    @Inject
    EntityManager entityManager;

    private UUID tenantAId;
    private UUID tenantBId;
    private UUID variantATenantA;
    private UUID variantBTenantB;
    private String locationCodeA;
    private String locationCodeB;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up existing data
        entityManager.createQuery("DELETE FROM InventoryLevel").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryLocation").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductVariant").executeUpdate();
        entityManager.createQuery("DELETE FROM Product").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();

        // Create tenant A
        Tenant tenantA = createTenant("rls-tenant-a", "RLS Test Tenant A");
        tenantAId = tenantA.id;

        // Create tenant B
        Tenant tenantB = createTenant("rls-tenant-b", "RLS Test Tenant B");
        tenantBId = tenantB.id;

        // Setup data for tenant A
        TenantContext.setCurrentTenant(new TenantInfo(tenantAId, "rls-tenant-a", "RLS Test Tenant A", "active"));
        InventoryLocation locationA = createLocation(tenantA, "warehouse-a", "Tenant A Warehouse", "warehouse");
        locationCodeA = locationA.code;

        Product productA = createProduct(tenantA, "PROD-A", "Tenant A Product");
        variantATenantA = createVariant(tenantA, productA, "VAR-A", "Tenant A Variant").id;

        inventoryService.setInventoryLevel(variantATenantA, locationCodeA, 100);
        TenantContext.clear();

        // Setup data for tenant B
        TenantContext.setCurrentTenant(new TenantInfo(tenantBId, "rls-tenant-b", "RLS Test Tenant B", "active"));
        InventoryLocation locationB = createLocation(tenantB, "warehouse-b", "Tenant B Warehouse", "warehouse");
        locationCodeB = locationB.code;

        Product productB = createProduct(tenantB, "PROD-B", "Tenant B Product");
        variantBTenantB = createVariant(tenantB, productB, "VAR-B", "Tenant B Variant").id;

        inventoryService.setInventoryLevel(variantBTenantB, locationCodeB, 200);
        TenantContext.clear();
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

    private Product createProduct(Tenant tenant, String sku, String name) {
        Product product = new Product();
        product.tenant = tenant;
        product.sku = sku;
        product.name = name;
        product.title = name + " Title";
        product.slug = sku.toLowerCase();
        product.type = "physical";
        product.status = "active";
        product.metadata = "{}";
        product.createdAt = OffsetDateTime.now();
        product.updatedAt = OffsetDateTime.now();
        entityManager.persist(product);
        entityManager.flush();
        return product;
    }

    private ProductVariant createVariant(Tenant tenant, Product product, String sku, String name) {
        ProductVariant variant = new ProductVariant();
        variant.tenant = tenant;
        variant.product = product;
        variant.sku = sku;
        variant.name = name;
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
        return variant;
    }

    @Test
    @Transactional
    void findByVariant_shouldRespectTenantIsolation() {
        // Act as tenant A - should see only tenant A's inventory
        TenantContext.setCurrentTenant(new TenantInfo(tenantAId, "rls-tenant-a", "RLS Test Tenant A", "active"));
        List<InventoryLevel> levelsA = inventoryLevelRepository.findByVariant(variantATenantA);
        assertEquals(1, levelsA.size(), "Tenant A should see its own inventory");
        assertEquals(100, levelsA.get(0).quantity);

        // Try to query tenant B's variant (should return empty)
        List<InventoryLevel> crossTenantQuery = inventoryLevelRepository.findByVariant(variantBTenantB);
        assertTrue(crossTenantQuery.isEmpty(), "Tenant A should not see tenant B's inventory");
        TenantContext.clear();

        // Act as tenant B - should see only tenant B's inventory
        TenantContext.setCurrentTenant(new TenantInfo(tenantBId, "rls-tenant-b", "RLS Test Tenant B", "active"));
        List<InventoryLevel> levelsB = inventoryLevelRepository.findByVariant(variantBTenantB);
        assertEquals(1, levelsB.size(), "Tenant B should see its own inventory");
        assertEquals(200, levelsB.get(0).quantity);

        // Try to query tenant A's variant (should return empty)
        List<InventoryLevel> crossTenantQueryB = inventoryLevelRepository.findByVariant(variantATenantA);
        assertTrue(crossTenantQueryB.isEmpty(), "Tenant B should not see tenant A's inventory");
        TenantContext.clear();
    }

    @Test
    @Transactional
    void findByVariantAndLocation_shouldRespectTenantIsolation() {
        // Act as tenant A
        TenantContext.setCurrentTenant(new TenantInfo(tenantAId, "rls-tenant-a", "RLS Test Tenant A", "active"));
        var levelA = inventoryLevelRepository.findByVariantAndLocation(variantATenantA, locationCodeA);
        assertTrue(levelA.isPresent(), "Tenant A should find its own inventory");
        assertEquals(100, levelA.get().quantity);

        // Try to query tenant B's location (should return empty)
        var crossTenantLevel = inventoryLevelRepository.findByVariantAndLocation(variantBTenantB, locationCodeB);
        assertTrue(crossTenantLevel.isEmpty(), "Tenant A should not see tenant B's inventory");
        TenantContext.clear();

        // Act as tenant B
        TenantContext.setCurrentTenant(new TenantInfo(tenantBId, "rls-tenant-b", "RLS Test Tenant B", "active"));
        var levelB = inventoryLevelRepository.findByVariantAndLocation(variantBTenantB, locationCodeB);
        assertTrue(levelB.isPresent(), "Tenant B should find its own inventory");
        assertEquals(200, levelB.get().quantity);

        // Try to query tenant A's location (should return empty)
        var crossTenantLevelB = inventoryLevelRepository.findByVariantAndLocation(variantATenantA, locationCodeA);
        assertTrue(crossTenantLevelB.isEmpty(), "Tenant B should not see tenant A's inventory");
        TenantContext.clear();
    }

    @Test
    @Transactional
    void findByLocation_shouldRespectTenantIsolation() {
        // Act as tenant A
        TenantContext.setCurrentTenant(new TenantInfo(tenantAId, "rls-tenant-a", "RLS Test Tenant A", "active"));

        List<InventoryLevel> levelsAtLocationA = inventoryLevelRepository.findByLocation(locationCodeA);
        assertEquals(1, levelsAtLocationA.size(), "Tenant A should see inventory at its location");
        assertEquals(100, levelsAtLocationA.get(0).quantity);
        TenantContext.clear();

        // Act as tenant B
        TenantContext.setCurrentTenant(new TenantInfo(tenantBId, "rls-tenant-b", "RLS Test Tenant B", "active"));

        List<InventoryLevel> levelsAtLocationB = inventoryLevelRepository.findByLocation(locationCodeB);
        assertEquals(1, levelsAtLocationB.size(), "Tenant B should see inventory at its location");
        assertEquals(200, levelsAtLocationB.get(0).quantity);
        TenantContext.clear();

        // Verify tenant A cannot query tenant B's location
        TenantContext.setCurrentTenant(new TenantInfo(tenantAId, "rls-tenant-a", "RLS Test Tenant A", "active"));
        List<InventoryLevel> crossTenantQuery = inventoryLevelRepository.findByLocation(locationCodeB);
        assertTrue(crossTenantQuery.isEmpty(), "Tenant A should not see inventory at tenant B's location");
        TenantContext.clear();
    }

    @Test
    @Transactional
    void getTotalAvailableQuantity_shouldRespectTenantIsolation() {
        // Act as tenant A
        TenantContext.setCurrentTenant(new TenantInfo(tenantAId, "rls-tenant-a", "RLS Test Tenant A", "active"));
        int totalA = inventoryLevelRepository.getTotalAvailableQuantity(variantATenantA);
        assertEquals(100, totalA, "Tenant A should see total of 100");

        // Try to query tenant B's variant (should return 0)
        int crossTenantTotal = inventoryLevelRepository.getTotalAvailableQuantity(variantBTenantB);
        assertEquals(0, crossTenantTotal, "Tenant A should not see tenant B's inventory total");
        TenantContext.clear();

        // Act as tenant B
        TenantContext.setCurrentTenant(new TenantInfo(tenantBId, "rls-tenant-b", "RLS Test Tenant B", "active"));
        int totalB = inventoryLevelRepository.getTotalAvailableQuantity(variantBTenantB);
        assertEquals(200, totalB, "Tenant B should see total of 200");

        // Try to query tenant A's variant (should return 0)
        int crossTenantTotalB = inventoryLevelRepository.getTotalAvailableQuantity(variantATenantA);
        assertEquals(0, crossTenantTotalB, "Tenant B should not see tenant A's inventory total");
        TenantContext.clear();
    }

    @Test
    @Transactional
    void multipleLocations_shouldAggregateTotalCorrectly() {
        // Setup: Create second location for tenant A with additional inventory
        TenantContext.setCurrentTenant(new TenantInfo(tenantAId, "rls-tenant-a", "RLS Test Tenant A", "active"));
        Tenant tenantA = Tenant.findById(tenantAId);
        InventoryLocation locationA2 = createLocation(tenantA, "warehouse-a2", "Tenant A Warehouse 2", "warehouse");
        inventoryService.setInventoryLevel(variantATenantA, locationA2.code, 50);

        // Verify total aggregates across both locations
        int total = inventoryLevelRepository.getTotalAvailableQuantity(variantATenantA);
        assertEquals(150, total, "Total should aggregate across multiple locations (100 + 50)");
        TenantContext.clear();

        // Verify tenant B still sees only its own data
        TenantContext.setCurrentTenant(new TenantInfo(tenantBId, "rls-tenant-b", "RLS Test Tenant B", "active"));
        int totalB = inventoryLevelRepository.getTotalAvailableQuantity(variantBTenantB);
        assertEquals(200, totalB, "Tenant B should still see only its own total");
        TenantContext.clear();
    }
}
