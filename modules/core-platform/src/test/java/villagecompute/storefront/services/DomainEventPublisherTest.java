package villagecompute.storefront.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import villagecompute.storefront.data.models.AdjustmentReason;
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
import villagecompute.storefront.data.repositories.DomainEventRepository;
import villagecompute.storefront.services.events.InventoryAdjustedPayload;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for domain event publishing and tenant isolation.
 *
 * <p>
 * Verifies that inventory operations emit correct events, events are tenant-scoped, and RLS policies enforce isolation.
 */
@QuarkusTest
class DomainEventPublisherTest {

    @Inject
    DomainEventPublisher eventPublisher;

    @Inject
    DomainEventRepository eventRepository;

    @Inject
    InventoryTransferService transferService;

    @Inject
    EntityManager entityManager;

    @Inject
    ObjectMapper objectMapper;

    private UUID tenant1Id;
    private UUID tenant2Id;
    private UUID variantId;
    private UUID locationId;
    private String tenant1Subdomain;
    private String tenant2Subdomain;

    @BeforeEach
    @Transactional
    void setUp() {
        clearDatabase();
        // Create tenant 1
        Tenant tenant1 = new Tenant();
        tenant1Subdomain = "eventtenant1-" + UUID.randomUUID().toString().substring(0, 8);
        tenant1.subdomain = tenant1Subdomain;
        tenant1.name = "Event Tenant 1";
        tenant1.status = "active";
        tenant1.settings = "{}";
        tenant1.createdAt = OffsetDateTime.now();
        tenant1.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant1);
        entityManager.flush();
        tenant1Id = tenant1.id;

        // Create tenant 2
        Tenant tenant2 = new Tenant();
        tenant2Subdomain = "eventtenant2-" + UUID.randomUUID().toString().substring(0, 8);
        tenant2.subdomain = tenant2Subdomain;
        tenant2.name = "Event Tenant 2";
        tenant2.status = "active";
        tenant2.settings = "{}";
        tenant2.createdAt = OffsetDateTime.now();
        tenant2.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant2);
        entityManager.flush();
        tenant2Id = tenant2.id;

        // Set tenant context to tenant1
        TenantContext.setCurrentTenant(new TenantInfo(tenant1Id, tenant1Subdomain, "Event Tenant 1", "active"));

        // Create product and variant for tenant1
        Product product = new Product();
        product.tenant = tenant1;
        product.sku = "EVENT-TEST-001";
        product.name = "Event Test Product";
        product.slug = "event-test-product";
        product.type = "physical";
        product.status = "active";
        product.metadata = "{}";
        product.createdAt = OffsetDateTime.now();
        product.updatedAt = OffsetDateTime.now();
        entityManager.persist(product);

        ProductVariant variant = new ProductVariant();
        variant.tenant = tenant1;
        variant.product = product;
        variant.sku = "EVENT-VAR-001";
        variant.name = "Event Test Variant";
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

        // Create inventory location
        InventoryLocation location = new InventoryLocation();
        location.tenant = tenant1;
        location.code = "warehouse-test";
        location.name = "Test Warehouse";
        location.type = "warehouse";
        location.active = true;
        location.createdAt = OffsetDateTime.now();
        location.updatedAt = OffsetDateTime.now();
        entityManager.persist(location);
        entityManager.flush();
        locationId = location.id;

        // Create initial inventory level
        InventoryLevel level = new InventoryLevel();
        level.tenant = tenant1;
        level.variant = variant;
        level.location = "warehouse-test";
        level.quantity = 100;
        level.reserved = 0;
        level.createdAt = OffsetDateTime.now();
        level.updatedAt = OffsetDateTime.now();
        entityManager.persist(level);
        entityManager.flush();
    }

    @AfterEach
    @Transactional
    void tearDown() {
        TenantContext.clear();
        clearDatabase();
    }

    private void clearDatabase() {
        entityManager.createQuery("DELETE FROM DomainEvent").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryAdjustment").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryTransferLine").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryTransfer").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryLevel").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryLocation").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductVariant").executeUpdate();
        entityManager.createQuery("DELETE FROM Product").executeUpdate();
    }

    @Test
    @Transactional
    void testPublishInventoryAdjustedEvent() throws Exception {
        // Given
        TenantContext.setCurrentTenant(new TenantInfo(tenant1Id, tenant1Subdomain, "Event Tenant 1", "active"));
        UUID levelId = UUID.randomUUID();

        InventoryAdjustedPayload payload = new InventoryAdjustedPayload(variantId, "warehouse-test", 100, 110, 10,
                AdjustmentReason.FOUND, "admin@test.com", "Found during cycle count", UUID.randomUUID());

        // When
        DomainEvent event = eventPublisher.publish("INVENTORY_LEVEL", levelId, "INVENTORY_ADJUSTED", payload);

        // Then
        assertNotNull(event);
        assertNotNull(event.id);
        assertEquals("INVENTORY_LEVEL", event.aggregateType);
        assertEquals(levelId, event.aggregateId);
        assertEquals("INVENTORY_ADJUSTED", event.eventType);
        assertNotNull(event.payload);
        assertNotNull(event.metadata);
        assertNotNull(event.occurredAt);

        // Verify payload deserialization
        JsonNode payloadNode = objectMapper.readTree(event.payload);
        assertEquals(variantId.toString(), payloadNode.get("variantId").asText());
        assertEquals("warehouse-test", payloadNode.get("locationCode").asText());
        assertEquals(100, payloadNode.get("quantityBefore").asInt());
        assertEquals(110, payloadNode.get("quantityAfter").asInt());
        assertEquals(10, payloadNode.get("quantityChange").asInt());
        assertEquals("FOUND", payloadNode.get("reason").asText());
    }

    @Test
    @Transactional
    void testRecordAdjustmentEmitsEvent() {
        // Given
        TenantContext.setCurrentTenant(new TenantInfo(tenant1Id, tenant1Subdomain, "Event Tenant 1", "active"));

        // When
        InventoryAdjustment adjustment = transferService.recordAdjustment(variantId, locationId, 15,
                AdjustmentReason.CYCLE_COUNT, "admin@test.com", "Cycle count correction");

        entityManager.flush();

        // Then
        assertNotNull(adjustment);
        assertNotNull(adjustment.id);

        // Verify event was published
        List<DomainEvent> events = eventRepository.findByEventType("INVENTORY_ADJUSTED");
        assertEquals(1, events.size());

        DomainEvent event = events.get(0);
        assertEquals("INVENTORY_LEVEL", event.aggregateType);
        assertEquals("INVENTORY_ADJUSTED", event.eventType);
        assertNotNull(event.payload);

        // Verify tenant association
        assertEquals(tenant1Id, event.tenant.id);
    }

    @Test
    @Transactional
    void testTransferInitiatedEmitsEvent() throws Exception {
        // Given
        TenantContext.setCurrentTenant(new TenantInfo(tenant1Id, tenant1Subdomain, "Event Tenant 1", "active"));

        // Create second location
        InventoryLocation destLocation = new InventoryLocation();
        destLocation.tenant = Tenant.findById(tenant1Id);
        destLocation.code = "store-001";
        destLocation.name = "Store 001";
        destLocation.type = "store";
        destLocation.active = true;
        destLocation.createdAt = OffsetDateTime.now();
        destLocation.updatedAt = OffsetDateTime.now();
        entityManager.persist(destLocation);
        entityManager.flush();

        // Create transfer
        InventoryTransfer transfer = new InventoryTransfer();
        transfer.sourceLocation = InventoryLocation.findById(locationId);
        transfer.destinationLocation = destLocation;
        transfer.initiatedBy = "admin@test.com";
        transfer.status = TransferStatus.PENDING;

        InventoryTransferLine line = new InventoryTransferLine();
        line.variant = ProductVariant.findById(variantId);
        line.quantity = 10;
        transfer.addLine(line);

        // When
        InventoryTransfer created = transferService.createTransfer(transfer);
        entityManager.flush();

        // Then
        assertNotNull(created);
        assertNotNull(created.id);

        // Verify event was published
        List<DomainEvent> events = eventRepository.findByEventType("TRANSFER_INITIATED");
        assertEquals(1, events.size());

        DomainEvent event = events.get(0);
        assertEquals("INVENTORY_TRANSFER", event.aggregateType);
        assertEquals(created.id, event.aggregateId);
        assertEquals("TRANSFER_INITIATED", event.eventType);

        // Verify payload structure
        JsonNode payloadNode = objectMapper.readTree(event.payload);
        assertEquals(created.id.toString(), payloadNode.get("transferId").asText());
        assertEquals("warehouse-test", payloadNode.get("sourceLocationCode").asText());
        assertEquals("store-001", payloadNode.get("destinationLocationCode").asText());
        assertEquals("PENDING", payloadNode.get("status").asText());
        assertTrue(payloadNode.has("lineItems"));
        assertEquals(1, payloadNode.get("lineItems").size());
    }

    @Test
    @Transactional
    void testTenantIsolationForEvents() {
        // Given: Publish event for tenant1
        TenantContext.setCurrentTenant(new TenantInfo(tenant1Id, tenant1Subdomain, "Event Tenant 1", "active"));
        UUID aggregateId = UUID.randomUUID();
        InventoryAdjustedPayload payload1 = new InventoryAdjustedPayload(variantId, "warehouse-test", 100, 105, 5,
                AdjustmentReason.FOUND, "admin1", null, UUID.randomUUID());
        eventPublisher.publish("INVENTORY_LEVEL", aggregateId, "INVENTORY_ADJUSTED", payload1);
        entityManager.flush();

        // When: Query from tenant2 context
        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, tenant2Subdomain, "Event Tenant 2", "active"));
        List<DomainEvent> tenant2Events = eventRepository.findByEventType("INVENTORY_ADJUSTED");

        // Then: Tenant2 should not see tenant1's events
        assertEquals(0, tenant2Events.size(), "Tenant 2 should not see Tenant 1's events");

        // When: Query from tenant1 context
        TenantContext.setCurrentTenant(new TenantInfo(tenant1Id, tenant1Subdomain, "Event Tenant 1", "active"));
        List<DomainEvent> tenant1Events = eventRepository.findByEventType("INVENTORY_ADJUSTED");

        // Then: Tenant1 should see their own events
        assertEquals(1, tenant1Events.size(), "Tenant 1 should see their own events");
        assertEquals(tenant1Id, tenant1Events.get(0).tenant.id);
    }

    @Test
    @Transactional
    void testEventMetadataIncludesTenantContext() throws Exception {
        // Given
        TenantContext.setCurrentTenant(new TenantInfo(tenant1Id, tenant1Subdomain, "Event Tenant 1", "active"));
        UUID aggregateId = UUID.randomUUID();

        InventoryAdjustedPayload payload = new InventoryAdjustedPayload(variantId, "warehouse-test", 100, 95, -5,
                AdjustmentReason.DAMAGE, "admin", "Damaged in transit", UUID.randomUUID());

        // When
        DomainEvent event = eventPublisher.publish("INVENTORY_LEVEL", aggregateId, "INVENTORY_ADJUSTED", payload);

        // Then
        assertNotNull(event.metadata);
        JsonNode metadataNode = objectMapper.readTree(event.metadata);
        assertTrue(metadataNode.has("tenantId"));
        assertEquals(tenant1Id.toString(), metadataNode.get("tenantId").asText());
        assertTrue(metadataNode.has("publishedAt"));
    }

    @Test
    @Transactional
    void testFindEventsByAggregate() {
        // Given
        TenantContext.setCurrentTenant(new TenantInfo(tenant1Id, tenant1Subdomain, "Event Tenant 1", "active"));
        UUID aggregateId = UUID.randomUUID();

        // Publish multiple events for same aggregate
        InventoryAdjustedPayload payload1 = new InventoryAdjustedPayload(variantId, "warehouse-test", 100, 105, 5,
                AdjustmentReason.FOUND, "admin", null, UUID.randomUUID());
        InventoryAdjustedPayload payload2 = new InventoryAdjustedPayload(variantId, "warehouse-test", 105, 110, 5,
                AdjustmentReason.CYCLE_COUNT, "admin", null, UUID.randomUUID());

        eventPublisher.publish("INVENTORY_LEVEL", aggregateId, "INVENTORY_ADJUSTED", payload1);
        eventPublisher.publish("INVENTORY_LEVEL", aggregateId, "INVENTORY_ADJUSTED", payload2);
        entityManager.flush();

        // When
        List<DomainEvent> events = eventRepository.findByAggregate("INVENTORY_LEVEL", aggregateId);

        // Then
        assertEquals(2, events.size());
        assertEquals(aggregateId, events.get(0).aggregateId);
        assertEquals(aggregateId, events.get(1).aggregateId);
    }
}
