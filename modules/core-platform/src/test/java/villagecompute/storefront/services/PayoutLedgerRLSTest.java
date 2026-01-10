package villagecompute.storefront.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
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

import villagecompute.storefront.data.models.Consignor;
import villagecompute.storefront.data.models.PayoutLedger;
import villagecompute.storefront.data.models.PayoutLedgerEntry;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.repositories.PayoutLedgerEntryRepository;
import villagecompute.storefront.data.repositories.PayoutLedgerRepository;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests verifying Row Level Security (RLS) for payout ledger tables.
 *
 * <p>
 * Tests ensure that:
 * <ul>
 * <li>Ledgers are isolated by tenant_id</li>
 * <li>Repositories enforce tenant scoping</li>
 * <li>Cross-tenant access is prevented</li>
 * <li>Service operations respect tenant boundaries</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T5: Payout ledger RLS verification</li>
 * <li>ADR-001: Multi-tenant data isolation</li>
 * </ul>
 */
@QuarkusTest
class PayoutLedgerRLSTest {

    @Inject
    PayoutLedgerService ledgerService;

    @Inject
    PayoutLedgerRepository ledgerRepository;

    @Inject
    PayoutLedgerEntryRepository entryRepository;

    @Inject
    EntityManager entityManager;

    private UUID tenant1Id;
    private UUID tenant2Id;
    private Tenant tenant1;
    private Tenant tenant2;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up existing data
        entityManager.createQuery("DELETE FROM PayoutLedgerEntry").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutLedger").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutLineItem").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutBatch").executeUpdate();
        entityManager.createQuery("DELETE FROM ConsignmentItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Consignor").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();

        // Create tenant 1
        tenant1 = new Tenant();
        tenant1.subdomain = "rlstest1";
        tenant1.name = "RLS Test Tenant 1";
        tenant1.status = "active";
        tenant1.settings = "{}";
        tenant1.createdAt = OffsetDateTime.now();
        tenant1.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant1);
        entityManager.flush();
        tenant1Id = tenant1.id;

        // Create tenant 2
        tenant2 = new Tenant();
        tenant2.subdomain = "rlstest2";
        tenant2.name = "RLS Test Tenant 2";
        tenant2.status = "active";
        tenant2.settings = "{}";
        tenant2.createdAt = OffsetDateTime.now();
        tenant2.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant2);
        entityManager.flush();
        tenant2Id = tenant2.id;
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @Transactional
    void testRepositoryFindByConsignor_isolatesByTenant() {
        // Given - create consignors and ledgers in both tenants
        TenantContext.setCurrentTenant(new TenantInfo(tenant1Id, tenant1.subdomain, tenant1.name, tenant1.status));
        Consignor consignor1 = createTestConsignor("Consignor 1");
        PayoutLedger ledger1 = ledgerService.getOrCreateLedger(consignor1.id);

        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, tenant2.subdomain, tenant2.name, tenant2.status));
        Consignor consignor2 = createTestConsignor("Consignor 2");
        PayoutLedger ledger2 = ledgerService.getOrCreateLedger(consignor2.id);

        // When/Then - tenant 1 can only see its own ledger
        TenantContext.setCurrentTenant(new TenantInfo(tenant1Id, tenant1.subdomain, tenant1.name, tenant1.status));
        Optional<PayoutLedger> found1 = ledgerRepository.findByConsignor(consignor1.id);
        assertTrue(found1.isPresent());
        assertEquals(ledger1.id, found1.get().id);

        Optional<PayoutLedger> notFound = ledgerRepository.findByConsignor(consignor2.id);
        assertFalse(notFound.isPresent(), "Should not find tenant 2's consignor from tenant 1 context");
    }

    @Test
    @Transactional
    void testRepositoryFindByIdAndTenant_enforcesTenantOwnership() {
        // Given - create ledger in tenant 1
        TenantContext.setCurrentTenant(new TenantInfo(tenant1Id, tenant1.subdomain, tenant1.name, tenant1.status));
        Consignor consignor1 = createTestConsignor("Consignor 1");
        PayoutLedger ledger1 = ledgerService.getOrCreateLedger(consignor1.id);

        // When/Then - tenant 1 can find its ledger by ID
        Optional<PayoutLedger> found1 = ledgerRepository.findByIdAndTenant(ledger1.id);
        assertTrue(found1.isPresent());
        assertEquals(ledger1.id, found1.get().id);

        // When/Then - tenant 2 cannot find tenant 1's ledger by ID
        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, tenant2.subdomain, tenant2.name, tenant2.status));
        Optional<PayoutLedger> notFound = ledgerRepository.findByIdAndTenant(ledger1.id);
        assertFalse(notFound.isPresent(), "Tenant 2 should not access tenant 1's ledger by ID");
    }

    @Test
    @Transactional
    void testRepositoryFindByCurrentTenant_returnsOnlyOwnLedgers() {
        // Given - create multiple ledgers in tenant 1
        TenantContext.setCurrentTenant(new TenantInfo(tenant1Id, tenant1.subdomain, tenant1.name, tenant1.status));
        Consignor consignor1A = createTestConsignor("Consignor 1A");
        Consignor consignor1B = createTestConsignor("Consignor 1B");
        ledgerService.getOrCreateLedger(consignor1A.id);
        ledgerService.getOrCreateLedger(consignor1B.id);

        // Given - create ledger in tenant 2
        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, tenant2.subdomain, tenant2.name, tenant2.status));
        Consignor consignor2 = createTestConsignor("Consignor 2");
        ledgerService.getOrCreateLedger(consignor2.id);

        // When/Then - tenant 1 sees only its 2 ledgers
        TenantContext.setCurrentTenant(new TenantInfo(tenant1Id, tenant1.subdomain, tenant1.name, tenant1.status));
        List<PayoutLedger> tenant1Ledgers = ledgerRepository.findByCurrentTenant();
        assertEquals(2, tenant1Ledgers.size());

        // When/Then - tenant 2 sees only its 1 ledger
        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, tenant2.subdomain, tenant2.name, tenant2.status));
        List<PayoutLedger> tenant2Ledgers = ledgerRepository.findByCurrentTenant();
        assertEquals(1, tenant2Ledgers.size());
    }

    @Test
    @Transactional
    void testEntryRepositoryFindByLedger_isolatesByTenant() {
        // Given - create entries in tenant 1
        TenantContext.setCurrentTenant(new TenantInfo(tenant1Id, tenant1.subdomain, tenant1.name, tenant1.status));
        Consignor consignor1 = createTestConsignor("Consignor 1");
        ledgerService.recordSale(consignor1.id, new BigDecimal("100.00"), UUID.randomUUID(), "Sale 1");
        ledgerService.recordSale(consignor1.id, new BigDecimal("200.00"), UUID.randomUUID(), "Sale 2");
        PayoutLedger ledger1 = ledgerService.getLedgerByConsignor(consignor1.id).orElseThrow();

        // Given - create entries in tenant 2
        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, tenant2.subdomain, tenant2.name, tenant2.status));
        Consignor consignor2 = createTestConsignor("Consignor 2");
        ledgerService.recordSale(consignor2.id, new BigDecimal("150.00"), UUID.randomUUID(), "Sale 3");

        // When/Then - tenant 1 sees only its entries
        TenantContext.setCurrentTenant(new TenantInfo(tenant1Id, tenant1.subdomain, tenant1.name, tenant1.status));
        List<PayoutLedgerEntry> entries1 = entryRepository.findByLedger(ledger1.id, 0, 20);
        assertEquals(2, entries1.size());
        assertTrue(entries1.stream().allMatch(e -> e.description.startsWith("Sale")));

        // When/Then - tenant 2 cannot access tenant 1's entries via ledger ID
        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, tenant2.subdomain, tenant2.name, tenant2.status));
        List<PayoutLedgerEntry> entries2 = entryRepository.findByLedger(ledger1.id, 0, 20);
        assertEquals(0, entries2.size(), "Tenant 2 should not access tenant 1's ledger entries");
    }

    @Test
    @Transactional
    void testServiceRecordSale_isolatesByTenant() {
        // Given - create consignors in both tenants
        TenantContext.setCurrentTenant(new TenantInfo(tenant1Id, tenant1.subdomain, tenant1.name, tenant1.status));
        Consignor consignor1 = createTestConsignor("Consignor 1");
        ledgerService.recordSale(consignor1.id, new BigDecimal("100.00"), UUID.randomUUID(), "Sale in tenant 1");

        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, tenant2.subdomain, tenant2.name, tenant2.status));
        Consignor consignor2 = createTestConsignor("Consignor 2");
        ledgerService.recordSale(consignor2.id, new BigDecimal("200.00"), UUID.randomUUID(), "Sale in tenant 2");

        // When/Then - verify tenant 1's balance
        TenantContext.setCurrentTenant(new TenantInfo(tenant1Id, tenant1.subdomain, tenant1.name, tenant1.status));
        PayoutLedger ledger1 = ledgerService.getLedgerByConsignor(consignor1.id).orElseThrow();
        assertEquals(new BigDecimal("100.00").setScale(4), ledger1.pendingBalance.setScale(4));

        // When/Then - verify tenant 2's balance
        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, tenant2.subdomain, tenant2.name, tenant2.status));
        PayoutLedger ledger2 = ledgerService.getLedgerByConsignor(consignor2.id).orElseThrow();
        assertEquals(new BigDecimal("200.00").setScale(4), ledger2.pendingBalance.setScale(4));

        // When/Then - tenant 1 cannot access tenant 2's ledger
        TenantContext.setCurrentTenant(new TenantInfo(tenant1Id, tenant1.subdomain, tenant1.name, tenant1.status));
        Optional<PayoutLedger> notFound = ledgerService.getLedgerByConsignor(consignor2.id);
        assertFalse(notFound.isPresent());
    }

    @Test
    @Transactional
    void testServiceGetLedgerEntries_isolatesByTenant() {
        // Given - create entries in both tenants
        TenantContext.setCurrentTenant(new TenantInfo(tenant1Id, tenant1.subdomain, tenant1.name, tenant1.status));
        Consignor consignor1 = createTestConsignor("Consignor 1");
        ledgerService.recordSale(consignor1.id, new BigDecimal("100.00"), UUID.randomUUID(), "Tenant 1 Sale");

        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, tenant2.subdomain, tenant2.name, tenant2.status));
        Consignor consignor2 = createTestConsignor("Consignor 2");
        ledgerService.recordSale(consignor2.id, new BigDecimal("200.00"), UUID.randomUUID(), "Tenant 2 Sale");
        ledgerService.recordSale(consignor2.id, new BigDecimal("300.00"), UUID.randomUUID(), "Tenant 2 Sale 2");

        // When/Then - tenant 1 sees only 1 entry
        TenantContext.setCurrentTenant(new TenantInfo(tenant1Id, tenant1.subdomain, tenant1.name, tenant1.status));
        List<PayoutLedgerEntry> entries1 = ledgerService.getLedgerEntries(consignor1.id, 0, 20);
        assertEquals(1, entries1.size());
        assertTrue(entries1.get(0).description.contains("Tenant 1"));

        // When/Then - tenant 2 sees only its 2 entries
        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, tenant2.subdomain, tenant2.name, tenant2.status));
        List<PayoutLedgerEntry> entries2 = ledgerService.getLedgerEntries(consignor2.id, 0, 20);
        assertEquals(2, entries2.size());
        assertTrue(entries2.stream().allMatch(e -> e.description.contains("Tenant 2")));
    }

    @Test
    @Transactional
    void testConcurrentTenantOperations_maintainIsolation() {
        // Given - create consignors in both tenants
        TenantContext.setCurrentTenant(new TenantInfo(tenant1Id, tenant1.subdomain, tenant1.name, tenant1.status));
        Consignor consignor1 = createTestConsignor("Consignor 1");

        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, tenant2.subdomain, tenant2.name, tenant2.status));
        Consignor consignor2 = createTestConsignor("Consignor 2");

        // When - interleave operations across tenants
        TenantContext.setCurrentTenant(new TenantInfo(tenant1Id, tenant1.subdomain, tenant1.name, tenant1.status));
        ledgerService.recordSale(consignor1.id, new BigDecimal("100.00"), UUID.randomUUID(), "T1 Sale 1");

        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, tenant2.subdomain, tenant2.name, tenant2.status));
        ledgerService.recordSale(consignor2.id, new BigDecimal("200.00"), UUID.randomUUID(), "T2 Sale 1");

        TenantContext.setCurrentTenant(new TenantInfo(tenant1Id, tenant1.subdomain, tenant1.name, tenant1.status));
        ledgerService.settlePendingToAvailable(consignor1.id, new BigDecimal("50.00"), "T1 Settlement");

        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, tenant2.subdomain, tenant2.name, tenant2.status));
        ledgerService.recordSale(consignor2.id, new BigDecimal("300.00"), UUID.randomUUID(), "T2 Sale 2");

        // Then - verify each tenant's final state is correct
        TenantContext.setCurrentTenant(new TenantInfo(tenant1Id, tenant1.subdomain, tenant1.name, tenant1.status));
        PayoutLedger ledger1 = ledgerService.getLedgerByConsignor(consignor1.id).orElseThrow();
        assertEquals(new BigDecimal("50.00").setScale(4), ledger1.pendingBalance.setScale(4));
        assertEquals(new BigDecimal("50.00").setScale(4), ledger1.availableBalance.setScale(4));
        List<PayoutLedgerEntry> entries1 = ledgerService.getLedgerEntries(consignor1.id, 0, 20);
        assertEquals(2, entries1.size()); // 1 sale + 1 settlement

        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, tenant2.subdomain, tenant2.name, tenant2.status));
        PayoutLedger ledger2 = ledgerService.getLedgerByConsignor(consignor2.id).orElseThrow();
        assertEquals(new BigDecimal("500.00").setScale(4), ledger2.pendingBalance.setScale(4));
        assertEquals(BigDecimal.ZERO.setScale(4), ledger2.availableBalance.setScale(4));
        List<PayoutLedgerEntry> entries2 = ledgerService.getLedgerEntries(consignor2.id, 0, 20);
        assertEquals(2, entries2.size()); // 2 sales
    }

    // ========================================
    // Helper Methods
    // ========================================

    private Consignor createTestConsignor(String name) {
        Consignor consignor = new Consignor();
        consignor.name = name;
        consignor.contactInfo = "{\"email\":\"test@example.com\"}";
        consignor.payoutSettings = "{\"default_commission_rate\":15.00}";
        consignor.status = "active";
        entityManager.persist(consignor);
        entityManager.flush();
        return consignor;
    }
}
