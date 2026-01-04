package villagecompute.storefront.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.ConsignmentItem;
import villagecompute.storefront.data.models.Consignor;
import villagecompute.storefront.data.models.PayoutBatch;
import villagecompute.storefront.data.models.PayoutLineItem;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.repositories.ConsignmentItemRepository;
import villagecompute.storefront.data.repositories.ConsignorRepository;
import villagecompute.storefront.data.repositories.PayoutBatchRepository;
import villagecompute.storefront.data.repositories.PayoutLineItemRepository;
import villagecompute.storefront.data.repositories.ProductRepository;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Service layer for consignment operations (consignors, items, payouts).
 *
 * <p>
 * Provides business logic for managing consignment vendors, intake items, commission schedules, and payout batches. All
 * operations are tenant-scoped and include structured logging for observability.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T1: Consignment domain implementation</li>
 * <li>ADR-001: Tenant-scoped services</li>
 * <li>ADR-003: Checkout saga and payment processing</li>
 * </ul>
 */
@ApplicationScoped
public class ConsignmentService {

    private static final Logger LOG = Logger.getLogger(ConsignmentService.class);
    private static final BigDecimal MAX_COMMISSION_RATE = new BigDecimal("100.00");

    @Inject
    ConsignorRepository consignorRepository;

    @Inject
    ConsignmentItemRepository consignmentItemRepository;

    @Inject
    PayoutBatchRepository payoutBatchRepository;

    @Inject
    PayoutLineItemRepository payoutLineItemRepository;

    @Inject
    ProductRepository productRepository;

    @Inject
    MeterRegistry meterRegistry;

    private final ConcurrentMap<UUID, AtomicReference<BigDecimal>> pendingPayoutGauges = new ConcurrentHashMap<>();

    // ========================================
    // Consignor Operations
    // ========================================

    /**
     * Create a new consignor.
     *
     * @param consignor
     *            consignor to create
     * @return created consignor with generated ID
     */
    @Transactional
    public Consignor createConsignor(Consignor consignor) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Creating consignor - tenantId=%s, name=%s", tenantId, consignor.name);

        consignorRepository.persist(consignor);

        LOG.infof("Consignor created successfully - tenantId=%s, consignorId=%s, name=%s", tenantId, consignor.id,
                consignor.name);
        meterRegistry.counter("consignment.consignor.created", "tenant_id", tenantId.toString()).increment();

        return consignor;
    }

    /**
     * Update an existing consignor.
     *
     * @param consignorId
     *            consignor UUID
     * @param updatedConsignor
     *            updated consignor data
     * @return updated consignor
     */
    @Transactional
    public Consignor updateConsignor(UUID consignorId, Consignor updatedConsignor) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Updating consignor - tenantId=%s, consignorId=%s", tenantId, consignorId);

        Consignor consignor = consignorRepository.findByIdAndTenant(consignorId)
                .orElseThrow(() -> new IllegalArgumentException("Consignor not found: " + consignorId));

        consignor.name = updatedConsignor.name;
        consignor.contactInfo = updatedConsignor.contactInfo;
        consignor.payoutSettings = updatedConsignor.payoutSettings;
        consignor.status = updatedConsignor.status;
        consignor.updatedAt = OffsetDateTime.now();

        consignorRepository.persist(consignor);

        LOG.infof("Consignor updated successfully - tenantId=%s, consignorId=%s", tenantId, consignorId);
        return consignor;
    }

    /**
     * Get consignor by ID.
     *
     * @param consignorId
     *            consignor UUID
     * @return consignor if found
     */
    public Optional<Consignor> getConsignor(UUID consignorId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Fetching consignor - tenantId=%s, consignorId=%s", tenantId, consignorId);

        return consignorRepository.findByIdAndTenant(consignorId);
    }

    /**
     * List active consignors with pagination.
     *
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return list of active consignors
     */
    public List<Consignor> listActiveConsignors(int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Listing active consignors - tenantId=%s, page=%d, size=%d", tenantId, page, size);

        int safePage = Math.max(page, 0);
        int safeSize = size > 0 ? size : 20;
        return consignorRepository.findActiveByCurrentTenant(safePage, safeSize);
    }

    /**
     * Search consignors by name.
     *
     * @param searchTerm
     *            search term
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return list of matching consignors
     */
    public List<Consignor> searchConsignors(String searchTerm, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Searching consignors - tenantId=%s, term=%s, page=%d, size=%d", tenantId, searchTerm, page, size);

        int safePage = Math.max(page, 0);
        int safeSize = size > 0 ? size : 20;
        return consignorRepository.searchByName(searchTerm, safePage, safeSize);
    }

    /**
     * Delete a consignor (soft delete by setting status to 'deleted').
     *
     * @param consignorId
     *            consignor UUID
     */
    @Transactional
    public void deleteConsignor(UUID consignorId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Deleting consignor - tenantId=%s, consignorId=%s", tenantId, consignorId);

        Consignor consignor = consignorRepository.findByIdAndTenant(consignorId)
                .orElseThrow(() -> new IllegalArgumentException("Consignor not found: " + consignorId));

        consignor.status = "deleted";
        consignor.updatedAt = OffsetDateTime.now();
        consignorRepository.persist(consignor);

        LOG.infof("Consignor deleted successfully - tenantId=%s, consignorId=%s", tenantId, consignorId);
    }

    // ========================================
    // Consignment Item Operations
    // ========================================

    /**
     * Create a new consignment item (intake).
     *
     * @param consignorId
     *            consignor UUID
     * @param productId
     *            product UUID
     * @param commissionRate
     *            commission rate percentage
     * @return created item with generated ID
     */
    @Transactional
    public ConsignmentItem createConsignmentItem(UUID consignorId, UUID productId, BigDecimal commissionRate) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        BigDecimal normalizedRate = normalizeCommissionRate(commissionRate);
        LOG.infof("Creating consignment item - tenantId=%s, productId=%s, consignorId=%s, commissionRate=%s", tenantId,
                productId, consignorId, normalizedRate);

        Consignor consignor = consignorRepository.findByIdAndTenant(consignorId)
                .orElseThrow(() -> new IllegalArgumentException("Consignor not found: " + consignorId));
        Product product = productRepository.findByIdAndTenant(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));

        ConsignmentItem item = new ConsignmentItem();
        item.consignor = consignor;
        item.product = product;
        item.commissionRate = normalizedRate;
        item.status = "active";

        consignmentItemRepository.persist(item);

        LOG.infof("Consignment item created successfully - tenantId=%s, itemId=%s", tenantId, item.id);
        meterRegistry.counter("consignment.item.created", "tenant_id", tenantId.toString()).increment();

        return item;
    }

    private BigDecimal normalizeCommissionRate(BigDecimal commissionRate) {
        if (commissionRate == null) {
            throw new IllegalArgumentException("Commission rate is required");
        }
        BigDecimal normalized = commissionRate.setScale(2, RoundingMode.HALF_UP);
        if (normalized.compareTo(BigDecimal.ZERO) < 0 || normalized.compareTo(MAX_COMMISSION_RATE) > 0) {
            throw new IllegalArgumentException("Commission rate must be between 0 and 100");
        }
        return normalized;
    }

    /**
     * Update consignment item.
     *
     * @param itemId
     *            item UUID
     * @param updatedItem
     *            updated item data
     * @return updated item
     */
    @Transactional
    public ConsignmentItem updateConsignmentItem(UUID itemId, ConsignmentItem updatedItem) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Updating consignment item - tenantId=%s, itemId=%s", tenantId, itemId);

        ConsignmentItem item = consignmentItemRepository.findByIdAndTenant(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Consignment item not found: " + itemId));

        item.commissionRate = updatedItem.commissionRate;
        item.status = updatedItem.status;
        item.updatedAt = OffsetDateTime.now();

        consignmentItemRepository.persistAndFlush(item);

        LOG.infof("Consignment item updated successfully - tenantId=%s, itemId=%s", tenantId, itemId);
        return item;
    }

    /**
     * Mark consignment item as sold.
     *
     * @param itemId
     *            item UUID
     */
    @Transactional
    public void markItemAsSold(UUID itemId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Marking consignment item as sold - tenantId=%s, itemId=%s", tenantId, itemId);

        ConsignmentItem item = consignmentItemRepository.findByIdAndTenant(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Consignment item not found: " + itemId));

        item.status = "sold";
        item.soldAt = OffsetDateTime.now();
        item.updatedAt = OffsetDateTime.now();

        consignmentItemRepository.persistAndFlush(item);

        LOG.infof("Consignment item marked as sold - tenantId=%s, itemId=%s", tenantId, itemId);
    }

    /**
     * Get consignment items for a consignor.
     *
     * @param consignorId
     *            consignor UUID
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return list of items
     */
    public List<ConsignmentItem> getConsignorItems(UUID consignorId, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Fetching consignment items - tenantId=%s, consignorId=%s, page=%d, size=%d", tenantId, consignorId,
                page, size);

        int safePage = Math.max(page, 0);
        int safeSize = size > 0 ? size : 20;
        return consignmentItemRepository.findByConsignor(consignorId, safePage, safeSize);
    }

    // ========================================
    // Payout Batch Operations
    // ========================================

    /**
     * Create a payout batch for a consignor and period.
     *
     * @param consignorId
     *            consignor UUID
     * @param periodStart
     *            period start date
     * @param periodEnd
     *            period end date
     * @return created payout batch
     */
    @Transactional
    public PayoutBatch createPayoutBatch(UUID consignorId, LocalDate periodStart, LocalDate periodEnd) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Creating payout batch - tenantId=%s, consignorId=%s, period=%s to %s", tenantId, consignorId,
                periodStart, periodEnd);

        // Verify consignor exists and belongs to tenant
        Consignor consignor = consignorRepository.findByIdAndTenant(consignorId)
                .orElseThrow(() -> new IllegalArgumentException("Consignor not found: " + consignorId));

        // Check if batch already exists for this period
        Optional<PayoutBatch> existingBatch = payoutBatchRepository.findByConsignorAndPeriod(consignorId, periodStart,
                periodEnd);
        if (existingBatch.isPresent()) {
            throw new IllegalArgumentException("Payout batch already exists for this period");
        }

        // Get sold items for this consignor
        List<ConsignmentItem> soldItems = consignmentItemRepository.findSoldByConsignor(consignorId);

        // Calculate payout amounts
        BigDecimal totalPayout = BigDecimal.ZERO;

        PayoutBatch batch = new PayoutBatch();
        batch.consignor = consignor;
        batch.periodStart = periodStart;
        batch.periodEnd = periodEnd;
        batch.totalAmount = totalPayout;
        batch.currency = "USD";
        batch.status = "pending";

        payoutBatchRepository.persist(batch);
        pendingPayoutGauge(tenantId).set(totalPayout);

        // Create line items (placeholder for now - would integrate with order data in production)
        // In a real implementation, we would query OrderLineItems that reference sold ConsignmentItems

        LOG.infof("Payout batch created successfully - tenantId=%s, batchId=%s, totalAmount=%s", tenantId, batch.id,
                batch.totalAmount);
        meterRegistry.counter("consignment.payout.batch.created", "tenant_id", tenantId.toString()).increment();
        meterRegistry.gauge("consignment.payout.pending.amount", batch.totalAmount.doubleValue());

        return batch;
    }

    /**
     * Calculate payout for a sold item based on commission rate.
     *
     * @param itemSubtotal
     *            item subtotal amount
     * @param commissionRate
     *            commission rate percentage (e.g., 15.00 for 15%)
     * @return payout calculation result
     */
    public PayoutCalculation calculatePayout(BigDecimal itemSubtotal, BigDecimal commissionRate) {
        // Commission amount = subtotal * (rate / 100)
        BigDecimal commissionAmount = itemSubtotal.multiply(commissionRate).divide(new BigDecimal("100"), 4,
                RoundingMode.HALF_UP);

        // Net payout = subtotal - commission
        BigDecimal netPayout = itemSubtotal.subtract(commissionAmount);

        return new PayoutCalculation(itemSubtotal, commissionAmount, netPayout);
    }

    /**
     * Get payout batches for a consignor.
     *
     * @param consignorId
     *            consignor UUID
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return list of batches
     */
    public List<PayoutBatch> getConsignorPayoutBatches(UUID consignorId, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Fetching payout batches - tenantId=%s, consignorId=%s, page=%d, size=%d", tenantId, consignorId,
                page, size);

        int safePage = Math.max(page, 0);
        int safeSize = size > 0 ? size : 20;
        return payoutBatchRepository.findByConsignor(consignorId, safePage, safeSize);
    }

    /**
     * Get payout batch by ID.
     *
     * @param batchId
     *            batch UUID
     * @return batch if found
     */
    public Optional<PayoutBatch> getPayoutBatch(UUID batchId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Fetching payout batch - tenantId=%s, batchId=%s", tenantId, batchId);

        return payoutBatchRepository.findByIdAndTenant(batchId);
    }

    /**
     * Get line items for a payout batch.
     *
     * @param batchId
     *            batch UUID
     * @return list of line items
     */
    public List<PayoutLineItem> getBatchLineItems(UUID batchId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Fetching batch line items - tenantId=%s, batchId=%s", tenantId, batchId);

        return payoutLineItemRepository.findByBatch(batchId);
    }

    /**
     * Mark payout batch as completed.
     *
     * @param batchId
     *            batch UUID
     * @param paymentReference
     *            external payment reference (e.g., Stripe payout ID)
     */
    @Transactional
    public void completePayoutBatch(UUID batchId, String paymentReference) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Completing payout batch - tenantId=%s, batchId=%s, paymentRef=%s", tenantId, batchId,
                paymentReference);

        PayoutBatch batch = payoutBatchRepository.findByIdAndTenant(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Payout batch not found: " + batchId));

        batch.status = "completed";
        batch.processedAt = OffsetDateTime.now();
        batch.paymentReference = paymentReference;
        batch.updatedAt = OffsetDateTime.now();

        payoutBatchRepository.persistAndFlush(batch);

        LOG.infof("Payout batch completed successfully - tenantId=%s, batchId=%s", tenantId, batchId);
        meterRegistry.counter("consignment.payout.batch.completed", "tenant_id", tenantId.toString()).increment();
    }

    private AtomicReference<BigDecimal> pendingPayoutGauge(UUID tenantId) {
        return pendingPayoutGauges.computeIfAbsent(tenantId, id -> {
            AtomicReference<BigDecimal> reference = new AtomicReference<>(BigDecimal.ZERO);
            Gauge.builder("consignment.payout.pending.amount", reference, value -> value.get().doubleValue())
                    .description("Pending consignment payout amount for a tenant").tag("tenant_id", id.toString())
                    .register(meterRegistry);
            return reference;
        });
    }

    /**
     * Value object encapsulating payout calculation results.
     */
    public record PayoutCalculation(BigDecimal itemSubtotal, BigDecimal commissionAmount, BigDecimal netPayout) {
    }
}
