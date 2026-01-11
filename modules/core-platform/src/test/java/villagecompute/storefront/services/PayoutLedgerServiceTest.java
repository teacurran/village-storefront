package villagecompute.storefront.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;
import villagecompute.storefront.testsupport.TestDataCleaner;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for {@link PayoutLedgerService}.
 *
 * <p>
 * Tests cover ledger creation, balance operations (sale, refund, settlement, payout, adjustment), entry queries, and
 * tenant isolation.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T5: Payout ledger service tests</li>
 * </ul>
 */
@QuarkusTest
class PayoutLedgerServiceTest {

    @Inject
    PayoutLedgerService ledgerService;

    @Inject
    EntityManager entityManager;

    private UUID tenantId;
    private UUID tenant2Id;

    @BeforeEach
    @Transactional
    void setUp() {
        TestDataCleaner.clearTenantData(entityManager);

        // Create test tenant
        Tenant tenant = new Tenant();
        tenant.subdomain = "ledgertest";
        tenant.name = "Ledger Test Tenant";
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();
        tenantId = tenant.id;

        // Create second tenant for isolation tests
        Tenant tenant2 = new Tenant();
        tenant2.subdomain = "ledgertest2";
        tenant2.name = "Ledger Test Tenant 2";
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

    @Test
    @Transactional
    void testGetOrCreateLedger_createsNewLedger() {
        // Given
        Consignor consignor = createTestConsignor("Test Consignor");

        // When
        PayoutLedger ledger = ledgerService.getOrCreateLedger(consignor.id);

        // Then
        assertNotNull(ledger);
        assertNotNull(ledger.id);
        assertEquals(consignor.id, ledger.consignor.id);
        assertEquals(BigDecimal.ZERO.setScale(4), ledger.pendingBalance.setScale(4));
        assertEquals(BigDecimal.ZERO.setScale(4), ledger.availableBalance.setScale(4));
        assertEquals("USD", ledger.currency);
    }

    @Test
    @Transactional
    void testGetOrCreateLedger_returnsExistingLedger() {
        // Given
        Consignor consignor = createTestConsignor("Test Consignor");
        PayoutLedger firstLedger = ledgerService.getOrCreateLedger(consignor.id);

        // When
        PayoutLedger secondLedger = ledgerService.getOrCreateLedger(consignor.id);

        // Then
        assertEquals(firstLedger.id, secondLedger.id);
    }

    @Test
    @Transactional
    void testRecordSale_addsToPendingBalance() {
        // Given
        Consignor consignor = createTestConsignor("Test Consignor");
        BigDecimal saleAmount = new BigDecimal("150.00");
        UUID orderId = UUID.randomUUID();

        // When
        PayoutLedgerEntry entry = ledgerService.recordSale(consignor.id, saleAmount, orderId, "Sale of item #123");

        // Then
        assertNotNull(entry);
        assertNotNull(entry.id);
        assertEquals("SALE", entry.entryType);
        assertEquals(saleAmount.setScale(4), entry.amount.setScale(4));
        assertEquals(saleAmount.setScale(4), entry.pendingBalanceAfter.setScale(4));
        assertEquals(BigDecimal.ZERO.setScale(4), entry.availableBalanceAfter.setScale(4));

        // Verify ledger updated
        PayoutLedger ledger = ledgerService.getLedgerByConsignor(consignor.id).orElseThrow();
        assertEquals(saleAmount.setScale(4), ledger.pendingBalance.setScale(4));
        assertEquals(BigDecimal.ZERO.setScale(4), ledger.availableBalance.setScale(4));
    }

    @Test
    @Transactional
    void testRecordSale_rejectsNegativeAmount() {
        // Given
        Consignor consignor = createTestConsignor("Test Consignor");
        BigDecimal negativeAmount = new BigDecimal("-50.00");

        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            ledgerService.recordSale(consignor.id, negativeAmount, UUID.randomUUID(), "Invalid sale");
        });
    }

    @Test
    @Transactional
    void testRecordRefund_deductsFromAvailableFirst() {
        // Given
        Consignor consignor = createTestConsignor("Test Consignor");
        ledgerService.recordSale(consignor.id, new BigDecimal("200.00"), UUID.randomUUID(), "Sale");
        ledgerService.settlePendingToAvailable(consignor.id, new BigDecimal("100.00"), "Settlement");

        // When
        PayoutLedgerEntry entry = ledgerService.recordRefund(consignor.id, new BigDecimal("75.00"), UUID.randomUUID(),
                "Refund for order #456");

        // Then
        assertNotNull(entry);
        assertEquals("REFUND", entry.entryType);
        assertEquals(new BigDecimal("-75.00").setScale(4), entry.amount.setScale(4));
        assertEquals(new BigDecimal("100.00").setScale(4), entry.pendingBalanceAfter.setScale(4));
        assertEquals(new BigDecimal("25.00").setScale(4), entry.availableBalanceAfter.setScale(4));
    }

    @Test
    @Transactional
    void testRecordRefund_deductsFromPendingWhenInsufficientAvailable() {
        // Given
        Consignor consignor = createTestConsignor("Test Consignor");
        ledgerService.recordSale(consignor.id, new BigDecimal("200.00"), UUID.randomUUID(), "Sale");
        ledgerService.settlePendingToAvailable(consignor.id, new BigDecimal("50.00"), "Settlement");

        // When
        PayoutLedgerEntry entry = ledgerService.recordRefund(consignor.id, new BigDecimal("100.00"), UUID.randomUUID(),
                "Refund for order #456");

        // Then
        assertNotNull(entry);
        assertEquals(new BigDecimal("100.00").setScale(4), entry.pendingBalanceAfter.setScale(4));
        assertEquals(BigDecimal.ZERO.setScale(4), entry.availableBalanceAfter.setScale(4));
    }

    @Test
    @Transactional
    void testRecordRefund_failsWhenInsufficientBalance() {
        // Given
        Consignor consignor = createTestConsignor("Test Consignor");
        ledgerService.recordSale(consignor.id, new BigDecimal("50.00"), UUID.randomUUID(), "Sale");

        // When/Then
        assertThrows(IllegalStateException.class, () -> {
            ledgerService.recordRefund(consignor.id, new BigDecimal("100.00"), UUID.randomUUID(),
                    "Refund exceeds balance");
        });
    }

    @Test
    @Transactional
    void testSettlePendingToAvailable_movesBalances() {
        // Given
        Consignor consignor = createTestConsignor("Test Consignor");
        ledgerService.recordSale(consignor.id, new BigDecimal("300.00"), UUID.randomUUID(), "Sale");

        // When
        PayoutLedgerEntry entry = ledgerService.settlePendingToAvailable(consignor.id, new BigDecimal("300.00"),
                "Settlement after hold period");

        // Then
        assertNotNull(entry);
        assertEquals("SETTLEMENT", entry.entryType);
        assertEquals(new BigDecimal("300.00").setScale(4), entry.amount.setScale(4));
        assertEquals(BigDecimal.ZERO.setScale(4), entry.pendingBalanceAfter.setScale(4));
        assertEquals(new BigDecimal("300.00").setScale(4), entry.availableBalanceAfter.setScale(4));
    }

    @Test
    @Transactional
    void testSettlePendingToAvailable_failsWhenInsufficientPending() {
        // Given
        Consignor consignor = createTestConsignor("Test Consignor");
        ledgerService.recordSale(consignor.id, new BigDecimal("100.00"), UUID.randomUUID(), "Sale");

        // When/Then
        assertThrows(IllegalStateException.class, () -> {
            ledgerService.settlePendingToAvailable(consignor.id, new BigDecimal("200.00"),
                    "Settlement exceeds pending");
        });
    }

    @Test
    @Transactional
    void testRecordPayout_deductsFromAvailableBalance() {
        // Given
        Consignor consignor = createTestConsignor("Test Consignor");
        ledgerService.recordSale(consignor.id, new BigDecimal("500.00"), UUID.randomUUID(), "Sale");
        ledgerService.settlePendingToAvailable(consignor.id, new BigDecimal("500.00"), "Settlement");
        UUID payoutBatchId = UUID.randomUUID();

        // When
        PayoutLedgerEntry entry = ledgerService.recordPayout(consignor.id, new BigDecimal("250.00"), payoutBatchId,
                "Payout batch #789");

        // Then
        assertNotNull(entry);
        assertEquals("PAYOUT", entry.entryType);
        assertEquals(new BigDecimal("-250.00").setScale(4), entry.amount.setScale(4));
        assertEquals(BigDecimal.ZERO.setScale(4), entry.pendingBalanceAfter.setScale(4));
        assertEquals(new BigDecimal("250.00").setScale(4), entry.availableBalanceAfter.setScale(4));
    }

    @Test
    @Transactional
    void testRecordPayout_failsWhenInsufficientAvailable() {
        // Given
        Consignor consignor = createTestConsignor("Test Consignor");
        ledgerService.recordSale(consignor.id, new BigDecimal("100.00"), UUID.randomUUID(), "Sale");

        // When/Then - pending balance should not be used for payout
        assertThrows(IllegalStateException.class, () -> {
            ledgerService.recordPayout(consignor.id, new BigDecimal("50.00"), UUID.randomUUID(),
                    "Payout exceeds available");
        });
    }

    @Test
    @Transactional
    void testRecordAdjustment_adjustsBothBalances() {
        // Given
        Consignor consignor = createTestConsignor("Test Consignor");
        ledgerService.recordSale(consignor.id, new BigDecimal("100.00"), UUID.randomUUID(), "Sale");

        // When - add 50 to pending, subtract 25 from available
        PayoutLedgerEntry entry = ledgerService.recordAdjustment(consignor.id, new BigDecimal("50.00"),
                new BigDecimal("-25.00"), "Admin correction");

        // Then
        assertNotNull(entry);
        assertEquals("ADJUSTMENT", entry.entryType);
        assertEquals(new BigDecimal("25.00").setScale(4), entry.amount.setScale(4)); // 50 + (-25)
        assertEquals(new BigDecimal("150.00").setScale(4), entry.pendingBalanceAfter.setScale(4));
        assertEquals(new BigDecimal("-25.00").setScale(4), entry.availableBalanceAfter.setScale(4));
    }

    @Test
    @Transactional
    void testGetLedgerEntries_returnsDescendingOrder() {
        // Given
        Consignor consignor = createTestConsignor("Test Consignor");
        ledgerService.recordSale(consignor.id, new BigDecimal("100.00"), UUID.randomUUID(), "Sale 1");
        ledgerService.recordSale(consignor.id, new BigDecimal("200.00"), UUID.randomUUID(), "Sale 2");
        ledgerService.settlePendingToAvailable(consignor.id, new BigDecimal("100.00"), "Settlement");

        // When
        List<PayoutLedgerEntry> entries = ledgerService.getLedgerEntries(consignor.id, 0, 10);

        // Then
        assertEquals(3, entries.size());
        assertEquals("SETTLEMENT", entries.get(0).entryType); // Most recent first
        assertEquals("Sale 2", entries.get(1).description);
        assertEquals("Sale 1", entries.get(2).description);
    }

    @Test
    @Transactional
    void testGetLedgerEntriesByType_filtersCorrectly() {
        // Given
        Consignor consignor = createTestConsignor("Test Consignor");
        ledgerService.recordSale(consignor.id, new BigDecimal("100.00"), UUID.randomUUID(), "Sale 1");
        ledgerService.recordSale(consignor.id, new BigDecimal("200.00"), UUID.randomUUID(), "Sale 2");
        ledgerService.settlePendingToAvailable(consignor.id, new BigDecimal("100.00"), "Settlement");

        // When
        List<PayoutLedgerEntry> saleEntries = ledgerService.getLedgerEntriesByType(consignor.id, "SALE", 0, 10);

        // Then
        assertEquals(2, saleEntries.size());
        assertTrue(saleEntries.stream().allMatch(e -> "SALE".equals(e.entryType)));
    }

    @Test
    @Transactional
    void testTenantIsolation_cannotAccessOtherTenantLedger() {
        // Given - create consignor in tenant 1
        Consignor consignor1 = createTestConsignor("Consignor 1");
        ledgerService.recordSale(consignor1.id, new BigDecimal("100.00"), UUID.randomUUID(), "Sale");

        // When - switch to tenant 2 and try to access
        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, "ledgertest2", "Ledger Test Tenant 2", "active"));

        // Then - should not find the ledger
        Optional<PayoutLedger> ledger = ledgerService.getLedgerByConsignor(consignor1.id);
        assertFalse(ledger.isPresent());
    }

    @Test
    @Transactional
    void testComplexWorkflow_saleSettlementRefundPayout() {
        // Given
        Consignor consignor = createTestConsignor("Test Consignor");

        // Record multiple sales
        ledgerService.recordSale(consignor.id, new BigDecimal("100.00"), UUID.randomUUID(), "Sale 1");
        ledgerService.recordSale(consignor.id, new BigDecimal("150.00"), UUID.randomUUID(), "Sale 2");
        ledgerService.recordSale(consignor.id, new BigDecimal("75.00"), UUID.randomUUID(), "Sale 3");

        // Verify pending balance
        PayoutLedger ledger = ledgerService.getLedgerByConsignor(consignor.id).orElseThrow();
        assertEquals(new BigDecimal("325.00").setScale(4), ledger.pendingBalance.setScale(4));

        // Settle part of pending to available
        ledgerService.settlePendingToAvailable(consignor.id, new BigDecimal("200.00"), "Settlement");

        ledger = ledgerService.getLedgerByConsignor(consignor.id).orElseThrow();
        assertEquals(new BigDecimal("125.00").setScale(4), ledger.pendingBalance.setScale(4));
        assertEquals(new BigDecimal("200.00").setScale(4), ledger.availableBalance.setScale(4));

        // Process a refund
        ledgerService.recordRefund(consignor.id, new BigDecimal("50.00"), UUID.randomUUID(), "Refund");

        ledger = ledgerService.getLedgerByConsignor(consignor.id).orElseThrow();
        assertEquals(new BigDecimal("125.00").setScale(4), ledger.pendingBalance.setScale(4));
        assertEquals(new BigDecimal("150.00").setScale(4), ledger.availableBalance.setScale(4));

        // Process payout
        ledgerService.recordPayout(consignor.id, new BigDecimal("100.00"), UUID.randomUUID(), "Payout");

        ledger = ledgerService.getLedgerByConsignor(consignor.id).orElseThrow();
        assertEquals(new BigDecimal("125.00").setScale(4), ledger.pendingBalance.setScale(4));
        assertEquals(new BigDecimal("50.00").setScale(4), ledger.availableBalance.setScale(4));

        // Verify all entries recorded
        List<PayoutLedgerEntry> entries = ledgerService.getLedgerEntries(consignor.id, 0, 20);
        assertEquals(6, entries.size()); // 3 sales + 1 settlement + 1 refund + 1 payout
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
