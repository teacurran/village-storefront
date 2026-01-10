package villagecompute.storefront.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.ConsignmentPayoutAggregate;
import villagecompute.storefront.data.models.Consignor;
import villagecompute.storefront.data.models.PayoutBatch;
import villagecompute.storefront.data.models.PayoutLedger;
import villagecompute.storefront.data.models.PayoutLedgerEntry;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.payment.MarketplaceProvider;
import villagecompute.storefront.payment.MarketplaceProvider.PayoutResult;
import villagecompute.storefront.payment.MarketplaceProvider.PayoutStatus;
import villagecompute.storefront.services.ConsignmentService;
import villagecompute.storefront.services.PayoutLedgerService;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for automated consignment payout processing.
 *
 * <p>
 * Tests cover:
 * <ul>
 * <li>Automated batch creation from aggregates</li>
 * <li>Auto-approval logic and threshold rules</li>
 * <li>Balance settlement (pending to available)</li>
 * <li>Payout processing with Stripe integration</li>
 * <li>Error handling and retry scenarios</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T6: Automated consignment payout implementation</li>
 * <li>ADR-004: Consignment payout state machine</li>
 * </ul>
 */
@QuarkusTest
class ConsignmentPayoutAutomationTest {

    @Inject
    ConsignmentService consignmentService;

    @Inject
    PayoutLedgerService payoutLedgerService;

    @Inject
    ConsignmentPayoutAutomationJob payoutAutomationJob;

    @Inject
    EntityManager entityManager;

    @InjectMock
    MarketplaceProvider marketplaceProvider;

    private UUID tenantId;
    private UUID consignorId;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up existing data
        entityManager.createQuery("DELETE FROM PayoutLedgerEntry").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutLedger").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutBatch").executeUpdate();
        entityManager.createQuery("DELETE FROM ConsignmentPayoutAggregate").executeUpdate();
        entityManager.createQuery("DELETE FROM Consignor").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();

        // Create test tenant
        Tenant tenant = new Tenant();
        tenant.subdomain = "payouttest";
        tenant.name = "Payout Test Tenant";
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();
        tenantId = tenant.id;

        // Set tenant context
        TenantContext.setCurrentTenantId(tenantId);

        // Create test consignor with verified Stripe account
        Consignor consignor = new Consignor();
        consignor.tenant = tenant;
        consignor.name = "Test Consignor";
        consignor.contactInfo = "{\"email\":\"consignor@example.com\"}";
        consignor.payoutSettings = "{\"stripe_account_id\":\"acct_test123\",\"stripe_charges_enabled\":true,\"stripe_payouts_enabled\":true}";
        consignor.status = "active";
        consignor.createdAt = OffsetDateTime.now().minusMonths(6); // 6 months tenure
        consignor.updatedAt = OffsetDateTime.now();
        entityManager.persist(consignor);
        entityManager.flush();
        consignorId = consignor.id;
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @Transactional
    void testCreatePayoutBatchesFromAggregates() {
        // Given: Aggregate data for previous month
        LocalDate previousMonth = LocalDate.now().minusMonths(1);
        LocalDate periodStart = previousMonth.withDayOfMonth(1);
        LocalDate periodEnd = previousMonth.withDayOfMonth(previousMonth.lengthOfMonth());

        ConsignmentPayoutAggregate aggregate = createAggregate(consignorId, periodStart, periodEnd,
                new BigDecimal("450.00"), 5);
        entityManager.persist(aggregate);
        entityManager.flush();

        // When: Create batches for period
        int batchCount = consignmentService.createPayoutBatchesForPeriod(periodStart, periodEnd);

        // Then: Batch created successfully
        assertEquals(1, batchCount);

        List<PayoutBatch> batches = PayoutBatch.find("consignor.id = ?1 and tenant.id = ?2", consignorId, tenantId)
                .list();
        assertEquals(1, batches.size());

        PayoutBatch batch = batches.get(0);
        assertEquals(periodStart, batch.periodStart);
        assertEquals(periodEnd, batch.periodEnd);
        assertEquals(new BigDecimal("450.00"), batch.totalAmount);
        assertEquals("USD", batch.currency);
        assertEquals("awaiting_approval", batch.status); // Auto-approved (amount < $500, verified account)
    }

    @Test
    @Transactional
    void testAutoApprovalThreshold() {
        // Given: Aggregate with amount > auto-approve limit ($500)
        LocalDate periodStart = LocalDate.now().minusMonths(1).withDayOfMonth(1);
        LocalDate periodEnd = periodStart.withDayOfMonth(periodStart.lengthOfMonth());

        ConsignmentPayoutAggregate aggregate = createAggregate(consignorId, periodStart, periodEnd,
                new BigDecimal("750.00"), 10);
        entityManager.persist(aggregate);
        entityManager.flush();

        // When: Create batches
        consignmentService.createPayoutBatchesForPeriod(periodStart, periodEnd);

        // Then: Batch requires manual approval
        PayoutBatch batch = PayoutBatch.find("consignor.id = ?1", consignorId).firstResult();
        assertNotNull(batch);
        assertEquals("pending", batch.status); // Requires manual approval (amount > $500)
    }

    @Test
    @Transactional
    void testAutoApprovalNewConsignor() {
        // Given: New consignor (< 3 months tenure)
        Consignor newConsignor = new Consignor();
        newConsignor.tenant = Tenant.findById(tenantId);
        newConsignor.name = "New Consignor";
        newConsignor.contactInfo = "{\"email\":\"new@example.com\"}";
        newConsignor.payoutSettings = "{\"stripe_account_id\":\"acct_new123\",\"stripe_charges_enabled\":true,\"stripe_payouts_enabled\":true}";
        newConsignor.status = "active";
        newConsignor.createdAt = OffsetDateTime.now().minusMonths(1); // Only 1 month tenure
        newConsignor.updatedAt = OffsetDateTime.now();
        entityManager.persist(newConsignor);
        entityManager.flush();

        LocalDate periodStart = LocalDate.now().minusMonths(1).withDayOfMonth(1);
        LocalDate periodEnd = periodStart.withDayOfMonth(periodStart.lengthOfMonth());

        ConsignmentPayoutAggregate aggregate = createAggregate(newConsignor.id, periodStart, periodEnd,
                new BigDecimal("100.00"), 2);
        entityManager.persist(aggregate);
        entityManager.flush();

        // When: Create batches
        consignmentService.createPayoutBatchesForPeriod(periodStart, periodEnd);

        // Then: Batch requires manual approval (new consignor)
        PayoutBatch batch = PayoutBatch.find("consignor.id = ?1", newConsignor.id).firstResult();
        assertNotNull(batch);
        assertEquals("pending", batch.status);
    }

    @Test
    @Transactional
    void testBalanceSettlement() throws Exception {
        // Given: Pending sales in ledger (older than hold period)
        PayoutLedger ledger = payoutLedgerService.getOrCreateLedger(consignorId);
        assertNotNull(ledger);

        // Record sales from 10 days ago
        OffsetDateTime saleDate = OffsetDateTime.now().minusDays(10);
        PayoutLedgerEntry sale = createSaleEntry(ledger, new BigDecimal("100.00"), saleDate);
        entityManager.persist(sale);
        entityManager.flush();

        // Update ledger balance
        ledger.pendingBalance = new BigDecimal("100.00");
        entityManager.merge(ledger);
        entityManager.flush();

        // When: Settle pending balances (hold period = 7 days)
        OffsetDateTime cutoffDate = OffsetDateTime.now().minusDays(7);
        int settledCount = payoutLedgerService.settlePendingSales(cutoffDate);

        // Then: Pending balance moved to available
        assertEquals(1, settledCount);

        ledger = payoutLedgerService.getLedgerByConsignor(consignorId).orElseThrow();
        assertEquals(new BigDecimal("0.00"), ledger.pendingBalance);
        assertEquals(new BigDecimal("100.00"), ledger.availableBalance);
    }

    @Test
    @Transactional
    void testPayoutProcessingWithStripeIntegration() throws Exception {
        // Given: Mock Stripe payout success
        when(marketplaceProvider.createPayout(any())).thenReturn(new PayoutResult("po_test123",
                new BigDecimal("200.00"), PayoutStatus.PENDING, Instant.now().plusSeconds(259200)));

        // Given: Approved payout batch with available balance
        PayoutBatch batch = createBatch(consignorId, LocalDate.now().minusMonths(1).withDayOfMonth(1),
                LocalDate.now().minusMonths(1).withDayOfMonth(28), new BigDecimal("200.00"), "awaiting_approval");
        entityManager.persist(batch);
        entityManager.flush();

        // Ensure available balance
        PayoutLedger ledger = payoutLedgerService.getOrCreateLedger(consignorId);
        ledger.availableBalance = new BigDecimal("200.00");
        entityManager.merge(ledger);
        entityManager.flush();

        // When: Process payout batch
        consignmentService.processPayoutBatch(batch.id);

        // Then: Batch status updated and ledger deducted
        PayoutBatch processedBatch = PayoutBatch.findById(batch.id);
        assertNotNull(processedBatch);
        assertEquals("processing", processedBatch.status);
        assertNotNull(processedBatch.paymentReference);
        assertEquals("po_test123", processedBatch.paymentReference);

        ledger = payoutLedgerService.getLedgerByConsignor(consignorId).orElseThrow();
        assertEquals(new BigDecimal("0.00"), ledger.availableBalance);
    }

    @Test
    @Transactional
    void testPayoutProcessingInsufficientBalance() {
        // Given: Payout batch without sufficient available balance
        PayoutBatch batch = createBatch(consignorId, LocalDate.now().minusMonths(1).withDayOfMonth(1),
                LocalDate.now().minusMonths(1).withDayOfMonth(28), new BigDecimal("500.00"), "awaiting_approval");
        entityManager.persist(batch);
        entityManager.flush();

        // Ledger has only $100 available
        PayoutLedger ledger = payoutLedgerService.getOrCreateLedger(consignorId);
        ledger.availableBalance = new BigDecimal("100.00");
        entityManager.merge(ledger);
        entityManager.flush();

        // When/Then: Processing fails with insufficient balance error
        assertThrows(RuntimeException.class, () -> consignmentService.processPayoutBatch(batch.id));

        // Batch status should be failed
        PayoutBatch failedBatch = PayoutBatch.findById(batch.id);
        assertEquals("failed", failedBatch.status);
    }

    @Test
    @Transactional
    void testDuplicateBatchPrevention() {
        // Given: Existing batch for period
        LocalDate periodStart = LocalDate.now().minusMonths(1).withDayOfMonth(1);
        LocalDate periodEnd = periodStart.withDayOfMonth(periodStart.lengthOfMonth());

        PayoutBatch existingBatch = createBatch(consignorId, periodStart, periodEnd, new BigDecimal("100.00"),
                "pending");
        entityManager.persist(existingBatch);
        entityManager.flush();

        // Create aggregate for same period
        ConsignmentPayoutAggregate aggregate = createAggregate(consignorId, periodStart, periodEnd,
                new BigDecimal("100.00"), 2);
        entityManager.persist(aggregate);
        entityManager.flush();

        // When: Attempt to create batches again
        int batchCount = consignmentService.createPayoutBatchesForPeriod(periodStart, periodEnd);

        // Then: No new batch created
        assertEquals(0, batchCount);
        assertEquals(1, PayoutBatch.count("consignor.id = ?1 and tenant.id = ?2", consignorId, tenantId));
    }

    @Test
    @Transactional
    void testStaleAggregateSkipped() {
        // Given: Stale aggregate (data freshness > 30 minutes)
        LocalDate periodStart = LocalDate.now().minusMonths(1).withDayOfMonth(1);
        LocalDate periodEnd = periodStart.withDayOfMonth(periodStart.lengthOfMonth());

        ConsignmentPayoutAggregate staleAggregate = createAggregate(consignorId, periodStart, periodEnd,
                new BigDecimal("200.00"), 3);
        staleAggregate.dataFreshnessTimestamp = OffsetDateTime.now().minusHours(1); // 1 hour old
        entityManager.persist(staleAggregate);
        entityManager.flush();

        // When: Create batches
        int batchCount = consignmentService.createPayoutBatchesForPeriod(periodStart, periodEnd);

        // Then: Stale aggregate skipped
        assertEquals(0, batchCount);
    }

    @Test
    @Transactional
    void testRequiresManualApprovalUnverifiedStripeAccount() {
        // Given: Consignor with unverified Stripe account
        Consignor unverifiedConsignor = new Consignor();
        unverifiedConsignor.tenant = Tenant.findById(tenantId);
        unverifiedConsignor.name = "Unverified Consignor";
        unverifiedConsignor.contactInfo = "{\"email\":\"unverified@example.com\"}";
        unverifiedConsignor.payoutSettings = "{\"stripe_account_id\":\"acct_unverified\",\"stripe_charges_enabled\":false,\"stripe_payouts_enabled\":false}";
        unverifiedConsignor.status = "active";
        unverifiedConsignor.createdAt = OffsetDateTime.now().minusMonths(6);
        unverifiedConsignor.updatedAt = OffsetDateTime.now();
        entityManager.persist(unverifiedConsignor);
        entityManager.flush();

        // Create batch
        PayoutBatch batch = createBatch(unverifiedConsignor.id, LocalDate.now().minusMonths(1).withDayOfMonth(1),
                LocalDate.now().minusMonths(1).withDayOfMonth(28), new BigDecimal("100.00"), "pending");
        batch.consignor = unverifiedConsignor;

        // When: Check approval requirement
        boolean requiresApproval = consignmentService.requiresManualApproval(batch);

        // Then: Manual approval required
        assertTrue(requiresApproval);
    }

    // Helper methods

    private ConsignmentPayoutAggregate createAggregate(UUID consignorId, LocalDate periodStart, LocalDate periodEnd,
            BigDecimal totalOwed, int itemsSold) {
        ConsignmentPayoutAggregate aggregate = new ConsignmentPayoutAggregate();
        aggregate.tenant = Tenant.findById(tenantId);
        aggregate.consignor = Consignor.findById(consignorId);
        aggregate.periodStart = periodStart;
        aggregate.periodEnd = periodEnd;
        aggregate.totalOwed = totalOwed;
        aggregate.itemCount = itemsSold;
        aggregate.itemsSold = itemsSold;
        aggregate.dataFreshnessTimestamp = OffsetDateTime.now(); // Fresh data
        aggregate.jobName = "ConsignmentAggregator";
        aggregate.createdAt = OffsetDateTime.now();
        aggregate.updatedAt = OffsetDateTime.now();
        return aggregate;
    }

    private PayoutBatch createBatch(UUID consignorId, LocalDate periodStart, LocalDate periodEnd,
            BigDecimal totalAmount, String status) {
        PayoutBatch batch = new PayoutBatch();
        batch.tenant = Tenant.findById(tenantId);
        batch.consignor = Consignor.findById(consignorId);
        batch.periodStart = periodStart;
        batch.periodEnd = periodEnd;
        batch.totalAmount = totalAmount;
        batch.currency = "USD";
        batch.status = status;
        return batch;
    }

    private PayoutLedgerEntry createSaleEntry(PayoutLedger ledger, BigDecimal amount, OffsetDateTime createdAt) {
        PayoutLedgerEntry entry = new PayoutLedgerEntry();
        entry.tenant = Tenant.findById(tenantId);
        entry.ledger = ledger;
        entry.entryType = "SALE";
        entry.amount = amount;
        entry.currency = "USD";
        entry.description = "Test sale";
        entry.referenceId = UUID.randomUUID();
        entry.referenceType = "ORDER";
        entry.pendingBalanceAfter = amount;
        entry.availableBalanceAfter = BigDecimal.ZERO;
        entry.createdAt = createdAt;
        return entry;
    }
}
