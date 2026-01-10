package villagecompute.storefront.services;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.Consignor;
import villagecompute.storefront.data.models.PayoutLedger;
import villagecompute.storefront.data.models.PayoutLedgerEntry;
import villagecompute.storefront.data.repositories.ConsignorRepository;
import villagecompute.storefront.data.repositories.PayoutLedgerEntryRepository;
import villagecompute.storefront.data.repositories.PayoutLedgerRepository;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Service layer for payout ledger operations.
 *
 * <p>
 * Manages consignor balances through a double-entry ledger system tracking pending and available balances. Pending
 * balances represent unsettled sales awaiting the hold period threshold, while available balances can be withdrawn
 * immediately.
 *
 * <p>
 * Ledger Entry Types:
 * <ul>
 * <li>SALE: Adds to pending balance when consignment item sold</li>
 * <li>REFUND: Deducts from pending/available balance on order refund</li>
 * <li>SETTLEMENT: Moves pending to available after hold period</li>
 * <li>PAYOUT: Deducts from available balance when payout processed</li>
 * <li>ADJUSTMENT: Manual balance correction (admin action)</li>
 * </ul>
 *
 * <p>
 * All operations are tenant-scoped and include structured logging for observability.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T5: Consignment payout ledger implementation</li>
 * <li>ADR-001: Tenant-scoped services</li>
 * <li>ADR-003: Checkout saga and payment processing</li>
 * </ul>
 */
@ApplicationScoped
public class PayoutLedgerService {

    private static final Logger LOG = Logger.getLogger(PayoutLedgerService.class);

    @Inject
    PayoutLedgerRepository ledgerRepository;

    @Inject
    PayoutLedgerEntryRepository entryRepository;

    @Inject
    ConsignorRepository consignorRepository;

    @Inject
    MeterRegistry meterRegistry;

    // ========================================
    // Ledger Management
    // ========================================

    /**
     * Get or create ledger for a consignor.
     *
     * @param consignorId
     *            consignor UUID
     * @return ledger
     */
    @Transactional
    public PayoutLedger getOrCreateLedger(UUID consignorId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Getting or creating ledger - tenantId=%s, consignorId=%s", tenantId, consignorId);

        Optional<PayoutLedger> existingLedger = ledgerRepository.findByConsignor(consignorId);
        if (existingLedger.isPresent()) {
            return existingLedger.get();
        }

        Consignor consignor = consignorRepository.findByIdAndTenant(consignorId)
                .orElseThrow(() -> new IllegalArgumentException("Consignor not found: " + consignorId));

        PayoutLedger ledger = new PayoutLedger();
        ledger.consignor = consignor;
        ledger.pendingBalance = BigDecimal.ZERO;
        ledger.availableBalance = BigDecimal.ZERO;
        ledger.currency = "USD";

        ledgerRepository.persist(ledger);

        LOG.infof("Ledger created - tenantId=%s, consignorId=%s, ledgerId=%s", tenantId, consignorId, ledger.id);
        meterRegistry.counter("consignment.ledger.created", "tenant_id", tenantId.toString()).increment();

        return ledger;
    }

    /**
     * Get ledger by consignor.
     *
     * @param consignorId
     *            consignor UUID
     * @return ledger if found
     */
    public Optional<PayoutLedger> getLedgerByConsignor(UUID consignorId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Fetching ledger - tenantId=%s, consignorId=%s", tenantId, consignorId);

        return ledgerRepository.findByConsignor(consignorId);
    }

    // ========================================
    // Balance Operations
    // ========================================

    /**
     * Record a sale transaction adding to pending balance.
     *
     * @param consignorId
     *            consignor UUID
     * @param amount
     *            sale amount (net after commission)
     * @param referenceId
     *            reference to order or line item
     * @param description
     *            description of the sale
     * @return ledger entry
     */
    @Transactional
    public PayoutLedgerEntry recordSale(UUID consignorId, BigDecimal amount, UUID referenceId, String description) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Recording sale - tenantId=%s, consignorId=%s, amount=%s, refId=%s", tenantId, consignorId, amount,
                referenceId);

        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Sale amount must be non-negative");
        }

        PayoutLedger ledger = getOrCreateLedger(consignorId);

        ledger.pendingBalance = ledger.pendingBalance.add(amount);

        PayoutLedgerEntry entry = createEntry(ledger, "SALE", amount, description, referenceId, "ORDER");

        ledgerRepository.persist(ledger);
        entryRepository.persist(entry);

        LOG.infof("Sale recorded - tenantId=%s, consignorId=%s, entryId=%s, pendingBalance=%s", tenantId, consignorId,
                entry.id, ledger.pendingBalance);
        meterRegistry.counter("consignment.ledger.sale", "tenant_id", tenantId.toString()).increment();

        return entry;
    }

    /**
     * Record a refund transaction deducting from pending or available balance.
     *
     * @param consignorId
     *            consignor UUID
     * @param amount
     *            refund amount (positive value)
     * @param referenceId
     *            reference to order or line item
     * @param description
     *            description of the refund
     * @return ledger entry
     */
    @Transactional
    public PayoutLedgerEntry recordRefund(UUID consignorId, BigDecimal amount, UUID referenceId, String description) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Recording refund - tenantId=%s, consignorId=%s, amount=%s, refId=%s", tenantId, consignorId, amount,
                referenceId);

        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Refund amount must be non-negative");
        }

        PayoutLedger ledger = ledgerRepository.findByConsignor(consignorId)
                .orElseThrow(() -> new IllegalArgumentException("Ledger not found for consignor: " + consignorId));

        // Deduct from available first, then pending
        BigDecimal remainingRefund = amount;
        if (ledger.availableBalance.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal deductFromAvailable = ledger.availableBalance.min(remainingRefund);
            ledger.availableBalance = ledger.availableBalance.subtract(deductFromAvailable);
            remainingRefund = remainingRefund.subtract(deductFromAvailable);
        }

        if (remainingRefund.compareTo(BigDecimal.ZERO) > 0) {
            if (ledger.pendingBalance.compareTo(remainingRefund) < 0) {
                throw new IllegalStateException("Insufficient balance for refund. Pending: " + ledger.pendingBalance
                        + ", Required: " + remainingRefund);
            }
            ledger.pendingBalance = ledger.pendingBalance.subtract(remainingRefund);
        }

        // Store as negative amount to indicate deduction
        PayoutLedgerEntry entry = createEntry(ledger, "REFUND", amount.negate(), description, referenceId, "ORDER");

        ledgerRepository.persist(ledger);
        entryRepository.persist(entry);

        LOG.infof("Refund recorded - tenantId=%s, consignorId=%s, entryId=%s, pendingBalance=%s, availableBalance=%s",
                tenantId, consignorId, entry.id, ledger.pendingBalance, ledger.availableBalance);
        meterRegistry.counter("consignment.ledger.refund", "tenant_id", tenantId.toString()).increment();

        return entry;
    }

    /**
     * Settle pending balance to available (after hold period).
     *
     * @param consignorId
     *            consignor UUID
     * @param amount
     *            amount to settle
     * @param description
     *            description of the settlement
     * @return ledger entry
     */
    @Transactional
    public PayoutLedgerEntry settlePendingToAvailable(UUID consignorId, BigDecimal amount, String description) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Settling pending to available - tenantId=%s, consignorId=%s, amount=%s", tenantId, consignorId,
                amount);

        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Settlement amount must be non-negative");
        }

        PayoutLedger ledger = ledgerRepository.findByConsignor(consignorId)
                .orElseThrow(() -> new IllegalArgumentException("Ledger not found for consignor: " + consignorId));

        if (ledger.pendingBalance.compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient pending balance for settlement. Pending: "
                    + ledger.pendingBalance + ", Required: " + amount);
        }

        ledger.pendingBalance = ledger.pendingBalance.subtract(amount);
        ledger.availableBalance = ledger.availableBalance.add(amount);

        PayoutLedgerEntry entry = createEntry(ledger, "SETTLEMENT", amount, description, null, null);

        ledgerRepository.persist(ledger);
        entryRepository.persist(entry);

        LOG.infof(
                "Settlement completed - tenantId=%s, consignorId=%s, entryId=%s, pendingBalance=%s, availableBalance=%s",
                tenantId, consignorId, entry.id, ledger.pendingBalance, ledger.availableBalance);
        meterRegistry.counter("consignment.ledger.settlement", "tenant_id", tenantId.toString()).increment();

        return entry;
    }

    /**
     * Record a payout transaction deducting from available balance.
     *
     * @param consignorId
     *            consignor UUID
     * @param amount
     *            payout amount (positive value)
     * @param payoutBatchId
     *            reference to payout batch
     * @param description
     *            description of the payout
     * @return ledger entry
     */
    @Transactional
    public PayoutLedgerEntry recordPayout(UUID consignorId, BigDecimal amount, UUID payoutBatchId, String description) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Recording payout - tenantId=%s, consignorId=%s, amount=%s, batchId=%s", tenantId, consignorId,
                amount, payoutBatchId);

        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Payout amount must be non-negative");
        }

        PayoutLedger ledger = ledgerRepository.findByConsignor(consignorId)
                .orElseThrow(() -> new IllegalArgumentException("Ledger not found for consignor: " + consignorId));

        if (ledger.availableBalance.compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient available balance for payout. Available: "
                    + ledger.availableBalance + ", Required: " + amount);
        }

        ledger.availableBalance = ledger.availableBalance.subtract(amount);

        // Store as negative amount to indicate deduction
        PayoutLedgerEntry entry = createEntry(ledger, "PAYOUT", amount.negate(), description, payoutBatchId,
                "PAYOUT_BATCH");

        ledgerRepository.persist(ledger);
        entryRepository.persist(entry);

        LOG.infof("Payout recorded - tenantId=%s, consignorId=%s, entryId=%s, availableBalance=%s", tenantId,
                consignorId, entry.id, ledger.availableBalance);
        meterRegistry.counter("consignment.ledger.payout", "tenant_id", tenantId.toString()).increment();

        return entry;
    }

    /**
     * Record a manual adjustment (admin action).
     *
     * @param consignorId
     *            consignor UUID
     * @param pendingDelta
     *            change to pending balance (can be negative)
     * @param availableDelta
     *            change to available balance (can be negative)
     * @param description
     *            description of the adjustment
     * @return ledger entry
     */
    @Transactional
    public PayoutLedgerEntry recordAdjustment(UUID consignorId, BigDecimal pendingDelta, BigDecimal availableDelta,
            String description) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Recording adjustment - tenantId=%s, consignorId=%s, pendingDelta=%s, availableDelta=%s", tenantId,
                consignorId, pendingDelta, availableDelta);

        PayoutLedger ledger = ledgerRepository.findByConsignor(consignorId)
                .orElseThrow(() -> new IllegalArgumentException("Ledger not found for consignor: " + consignorId));

        ledger.pendingBalance = ledger.pendingBalance.add(pendingDelta);
        ledger.availableBalance = ledger.availableBalance.add(availableDelta);

        BigDecimal totalDelta = pendingDelta.add(availableDelta);
        PayoutLedgerEntry entry = createEntry(ledger, "ADJUSTMENT", totalDelta, description, null, null);

        ledgerRepository.persist(ledger);
        entryRepository.persist(entry);

        LOG.infof(
                "Adjustment recorded - tenantId=%s, consignorId=%s, entryId=%s, pendingBalance=%s, availableBalance=%s",
                tenantId, consignorId, entry.id, ledger.pendingBalance, ledger.availableBalance);
        meterRegistry.counter("consignment.ledger.adjustment", "tenant_id", tenantId.toString()).increment();

        return entry;
    }

    // ========================================
    // Query Operations
    // ========================================

    /**
     * Get ledger entries for a consignor.
     *
     * @param consignorId
     *            consignor UUID
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return list of entries ordered by created_at descending
     */
    public List<PayoutLedgerEntry> getLedgerEntries(UUID consignorId, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Fetching ledger entries - tenantId=%s, consignorId=%s, page=%d, size=%d", tenantId, consignorId,
                page, size);

        PayoutLedger ledger = ledgerRepository.findByConsignor(consignorId)
                .orElseThrow(() -> new IllegalArgumentException("Ledger not found for consignor: " + consignorId));

        int safePage = Math.max(page, 0);
        int safeSize = size > 0 ? size : 20;
        return entryRepository.findByLedger(ledger.id, safePage, safeSize);
    }

    /**
     * Get ledger entries by type for a consignor.
     *
     * @param consignorId
     *            consignor UUID
     * @param entryType
     *            entry type (SALE, REFUND, SETTLEMENT, PAYOUT, ADJUSTMENT)
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return list of entries
     */
    public List<PayoutLedgerEntry> getLedgerEntriesByType(UUID consignorId, String entryType, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Fetching ledger entries by type - tenantId=%s, consignorId=%s, type=%s, page=%d, size=%d", tenantId,
                consignorId, entryType, page, size);

        PayoutLedger ledger = ledgerRepository.findByConsignor(consignorId)
                .orElseThrow(() -> new IllegalArgumentException("Ledger not found for consignor: " + consignorId));

        int safePage = Math.max(page, 0);
        int safeSize = size > 0 ? size : 20;
        return entryRepository.findByLedgerAndType(ledger.id, entryType, safePage, safeSize);
    }

    /**
     * Get ledger entries within date range.
     *
     * @param consignorId
     *            consignor UUID
     * @param startDate
     *            start of date range (inclusive)
     * @param endDate
     *            end of date range (inclusive)
     * @return list of entries
     */
    public List<PayoutLedgerEntry> getLedgerEntriesByDateRange(UUID consignorId, OffsetDateTime startDate,
            OffsetDateTime endDate) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Fetching ledger entries by date range - tenantId=%s, consignorId=%s, startDate=%s, endDate=%s",
                tenantId, consignorId, startDate, endDate);

        PayoutLedger ledger = ledgerRepository.findByConsignor(consignorId)
                .orElseThrow(() -> new IllegalArgumentException("Ledger not found for consignor: " + consignorId));

        return entryRepository.findByLedgerAndDateRange(ledger.id, startDate, endDate);
    }

    // ========================================
    // Automated Settlement Operations (Task I5.T6)
    // ========================================

    /**
     * Settle pending sales to available balance for all consignors (batch operation).
     *
     * <p>
     * Queries all SALE-type ledger entries older than the cutoff date and moves their amounts from pending to available
     * balance. Called by {@link villagecompute.storefront.jobs.ConsignmentPayoutAutomationJob} on scheduled basis.
     *
     * <p>
     * This method processes settlements in batches to avoid long-running transactions. Each consignor's settlement is
     * recorded as a SETTLEMENT entry in the ledger.
     *
     * @param cutoffDate
     *            settlement cutoff timestamp (entries created before this date are eligible)
     * @return number of consignors settled
     */
    @Transactional
    public int settlePendingSales(OffsetDateTime cutoffDate) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Settling pending sales to available - tenantId=%s, cutoffDate=%s", tenantId, cutoffDate);

        // Query all SALE entries older than cutoff that haven't been settled yet
        List<PayoutLedgerEntry> pendingSales = entryRepository
                .find("entryType = 'SALE' and createdAt <= ?1 and tenant.id = ?2 order by ledger.id, createdAt",
                        cutoffDate, tenantId)
                .list();

        if (pendingSales.isEmpty()) {
            LOG.debugf("No pending sales to settle - tenantId=%s, cutoffDate=%s", tenantId, cutoffDate);
            return 0;
        }

        // Group by ledger and sum amounts
        java.util.Map<UUID, BigDecimal> settlementsByLedger = new java.util.HashMap<>();
        for (PayoutLedgerEntry sale : pendingSales) {
            settlementsByLedger.merge(sale.ledger.id, sale.amount, BigDecimal::add);
        }

        int settledCount = 0;
        for (java.util.Map.Entry<UUID, BigDecimal> settlement : settlementsByLedger.entrySet()) {
            UUID ledgerId = settlement.getKey();
            BigDecimal amountToSettle = settlement.getValue();

            if (amountToSettle.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            PayoutLedger ledger = ledgerRepository.findById(ledgerId);
            if (ledger == null) {
                LOG.warnf("Ledger not found during settlement - tenantId=%s, ledgerId=%s", tenantId, ledgerId);
                continue;
            }

            try {
                settlePendingToAvailable(ledger.consignor.id, amountToSettle,
                        String.format("Settlement of pending sales (cutoff: %s)", cutoffDate));
                settledCount++;
                LOG.infof("Settled pending balance - tenantId=%s, consignorId=%s, amount=%s", tenantId,
                        ledger.consignor.id, amountToSettle);
            } catch (Exception e) {
                LOG.errorf(e, "Failed to settle ledger - tenantId=%s, ledgerId=%s, consignorId=%s", tenantId, ledgerId,
                        ledger.consignor.id);
                meterRegistry.counter("consignment.ledger.settlement.failed", "tenant", tenantId.toString(), "reason",
                        "exception").increment();
            }
        }

        LOG.infof("Pending sales settlement completed - tenantId=%s, consignorsSettled=%d, totalEntries=%d", tenantId,
                settledCount, pendingSales.size());
        meterRegistry.counter("consignment.ledger.bulk_settlement.completed", "tenant", tenantId.toString())
                .increment();

        return settledCount;
    }

    // ========================================
    // Helper Methods
    // ========================================

    private PayoutLedgerEntry createEntry(PayoutLedger ledger, String entryType, BigDecimal amount, String description,
            UUID referenceId, String referenceType) {
        PayoutLedgerEntry entry = new PayoutLedgerEntry();
        entry.ledger = ledger;
        entry.entryType = entryType;
        entry.amount = amount;
        entry.currency = ledger.currency;
        entry.description = description;
        entry.referenceId = referenceId;
        entry.referenceType = referenceType;
        entry.pendingBalanceAfter = ledger.pendingBalance;
        entry.availableBalanceAfter = ledger.availableBalance;
        return entry;
    }
}
