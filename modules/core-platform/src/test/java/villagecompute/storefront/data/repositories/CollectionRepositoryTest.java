package villagecompute.storefront.data.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import villagecompute.storefront.data.models.Collection;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for {@link CollectionRepository}.
 *
 * <p>
 * Tests cover tenant isolation, query filtering, pagination, and search functionality.
 */
@QuarkusTest
class CollectionRepositoryTest {

    @Inject
    CollectionRepository collectionRepository;

    @Inject
    EntityManager entityManager;

    private UUID tenantId;
    private UUID tenant2Id;

    @BeforeEach
    @Transactional
    void setUp() {
        purgeCatalogTables();

        // Create test tenant 1
        Tenant tenant = new Tenant();
        tenant.subdomain = "collectiontest1";
        tenant.name = "Collection Test Tenant 1";
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();
        tenantId = tenant.id;

        // Create test tenant 2
        Tenant tenant2 = new Tenant();
        tenant2.subdomain = "collectiontest2";
        tenant2.name = "Collection Test Tenant 2";
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
    @Transactional
    void tearDown() {
        TenantContext.clear();
        purgeCatalogTables();
    }

    @Test
    @Transactional
    void findByCurrentTenant_shouldReturnOnlyCurrentTenantCollections() {
        // Create collections for tenant 1
        createCollection(tenantId, "COLL-001", "Collection 1", "draft");
        createCollection(tenantId, "COLL-002", "Collection 2", "active");

        // Create collection for tenant 2
        createCollection(tenant2Id, "COLL-003", "Collection 3", "active");

        List<Collection> results = collectionRepository.findByCurrentTenant();

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(c -> c.tenant.id.equals(tenantId)));
    }

    @Test
    @Transactional
    void findByStatus_shouldFilterByStatus() {
        createCollection(tenantId, "COLL-001", "Draft Collection", "draft");
        createCollection(tenantId, "COLL-002", "Active Collection", "active");
        createCollection(tenantId, "COLL-003", "Archived Collection", "archived");

        List<Collection> activeCollections = collectionRepository.findByStatus("active", 0, 10);
        List<Collection> draftCollections = collectionRepository.findByStatus("draft", 0, 10);

        assertEquals(1, activeCollections.size());
        assertEquals("COLL-002", activeCollections.get(0).code);

        assertEquals(1, draftCollections.size());
        assertEquals("COLL-001", draftCollections.get(0).code);
    }

    @Test
    @Transactional
    void findByCode_shouldReturnCollectionForCurrentTenant() {
        createCollection(tenantId, "SUMMER-SALE", "Summer Sale", "active");
        createCollection(tenant2Id, "SUMMER-SALE", "Summer Sale T2", "active"); // Same code, different tenant

        Optional<Collection> result = collectionRepository.findByCode("SUMMER-SALE");

        assertTrue(result.isPresent());
        assertEquals(tenantId, result.get().tenant.id);
        assertEquals("Summer Sale", result.get().name);
    }

    @Test
    @Transactional
    void findBySlug_shouldReturnCollectionForCurrentTenant() {
        createCollection(tenantId, "COLL-001", "Collection", "active").slug = "summer-collection";
        createCollection(tenant2Id, "COLL-002", "Collection T2", "active").slug = "summer-collection";

        Optional<Collection> result = collectionRepository.findBySlug("summer-collection");

        assertTrue(result.isPresent());
        assertEquals(tenantId, result.get().tenant.id);
    }

    @Test
    @Transactional
    void findPublished_shouldReturnOnlyPublishedActiveCollections() {
        Collection draft = createCollection(tenantId, "COLL-001", "Draft", "draft");
        draft.published = false;

        Collection active = createCollection(tenantId, "COLL-002", "Active", "active");
        active.published = true;

        Collection archived = createCollection(tenantId, "COLL-003", "Archived", "archived");
        archived.published = true;

        Collection unpublished = createCollection(tenantId, "COLL-004", "Unpublished", "active");
        unpublished.published = false;

        List<Collection> results = collectionRepository.findPublished();

        assertEquals(1, results.size());
        assertEquals("COLL-002", results.get(0).code);
    }

    @Test
    @Transactional
    void searchCollections_shouldMatchName() {
        createCollection(tenantId, "COLL-001", "Summer Sale", "active");
        createCollection(tenantId, "COLL-002", "Winter Sale", "active");
        createCollection(tenantId, "COLL-003", "Summer Collection", "active");

        List<Collection> results = collectionRepository.searchCollections("summer", "active", 0, 10);

        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(c -> c.code.equals("COLL-001")));
        assertTrue(results.stream().anyMatch(c -> c.code.equals("COLL-003")));
    }

    @Test
    @Transactional
    void searchCollections_shouldBeCaseInsensitive() {
        createCollection(tenantId, "COLL-001", "Summer Sale", "active");

        List<Collection> results = collectionRepository.searchCollections("SUMMER", "active", 0, 10);

        assertEquals(1, results.size());
        assertEquals("COLL-001", results.get(0).code);
    }

    @Test
    @Transactional
    void countByStatus_shouldReturnCorrectCount() {
        createCollection(tenantId, "COLL-001", "Collection 1", "active");
        createCollection(tenantId, "COLL-002", "Collection 2", "active");
        createCollection(tenantId, "COLL-003", "Collection 3", "draft");

        long activeCount = collectionRepository.countByStatus("active");
        long draftCount = collectionRepository.countByStatus("draft");

        assertEquals(2, activeCount);
        assertEquals(1, draftCount);
    }

    @Test
    @Transactional
    void countByCurrentTenant_shouldReturnTotalCount() {
        createCollection(tenantId, "COLL-001", "Collection 1", "active");
        createCollection(tenantId, "COLL-002", "Collection 2", "draft");
        createCollection(tenantId, "COLL-003", "Collection 3", "archived");
        createCollection(tenant2Id, "COLL-004", "Other Tenant", "active");

        long count = collectionRepository.countByCurrentTenant();

        assertEquals(3, count);
    }

    @Test
    @Transactional
    void findByIdAndTenant_shouldReturnCollectionIfOwnedByCurrentTenant() {
        Collection collection = createCollection(tenantId, "COLL-001", "Test Collection", "active");

        Optional<Collection> result = collectionRepository.findByIdAndTenant(collection.id);

        assertTrue(result.isPresent());
        assertEquals(collection.id, result.get().id);
    }

    @Test
    @Transactional
    void findByIdAndTenant_shouldReturnEmptyIfNotOwnedByCurrentTenant() {
        Collection otherTenantCollection = createCollection(tenant2Id, "COLL-001", "Other Collection", "active");

        Optional<Collection> result = collectionRepository.findByIdAndTenant(otherTenantCollection.id);

        assertFalse(result.isPresent());
    }

    @Test
    @Transactional
    void findByStatus_shouldSupportPagination() {
        // Create 15 active collections
        for (int i = 1; i <= 15; i++) {
            createCollection(tenantId, String.format("COLL-%03d", i), "Collection " + i, "active");
        }

        List<Collection> page1 = collectionRepository.findByStatus("active", 0, 10);
        List<Collection> page2 = collectionRepository.findByStatus("active", 1, 10);

        assertEquals(10, page1.size());
        assertEquals(5, page2.size());
    }

    // Helper method to create collection
    private Collection createCollection(UUID tenantId, String code, String name, String status) {
        Collection collection = new Collection();
        collection.tenant = entityManager.find(Tenant.class, tenantId);
        collection.code = code;
        collection.name = name;
        collection.slug = code.toLowerCase();
        collection.status = status;
        collection.collectionType = "manual";
        collection.published = false;
        collection.createdAt = OffsetDateTime.now();
        collection.updatedAt = OffsetDateTime.now();
        entityManager.persist(collection);
        entityManager.flush();
        return collection;
    }

    private void purgeCatalogTables() {
        entityManager.createQuery("DELETE FROM Collection").executeUpdate();
        entityManager.createQuery("DELETE FROM CartItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Cart").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutLineItem").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutBatch").executeUpdate();
        entityManager.createQuery("DELETE FROM ConsignmentItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Consignor").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryAdjustment").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryTransferLine").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryTransfer").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryAgingAggregate").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryLevel").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryLocation").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductImage").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductCategory").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductVariant").executeUpdate();
        entityManager.createQuery("DELETE FROM Product").executeUpdate();
        entityManager.createQuery("DELETE FROM Category").executeUpdate();
        entityManager.createQuery("DELETE FROM User").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();
    }
}
