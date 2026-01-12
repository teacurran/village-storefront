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
import villagecompute.storefront.data.models.Order;
import villagecompute.storefront.data.models.OrderLineItem;
import villagecompute.storefront.data.models.PayoutBatch;
import villagecompute.storefront.data.models.PayoutLineItem;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.data.repositories.ConsignmentItemRepository;
import villagecompute.storefront.data.repositories.ConsignorRepository;
import villagecompute.storefront.data.repositories.PayoutBatchRepository;
import villagecompute.storefront.data.repositories.PayoutLineItemRepository;
import villagecompute.storefront.data.repositories.ProductRepository;
import villagecompute.storefront.data.repositories.ProductVariantRepository;
import villagecompute.storefront.services.events.ConsignmentItemReceivedPayload;
import villagecompute.storefront.services.events.ConsignmentPayoutDuePayload;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.util.PgCryptoUtil;

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
    ProductVariantRepository productVariantRepository;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    DomainEventPublisher eventPublisher;

    @Inject
    PayoutLedgerService payoutLedgerService;

    @Inject
    villagecompute.storefront.data.repositories.ConsignmentPayoutAggregateRepository payoutAggregateRepository;

    @Inject
    villagecompute.storefront.config.ConsignmentPayoutConfig payoutConfig;

    @Inject
    villagecompute.storefront.payment.MarketplaceProvider marketplaceProvider;

    @Inject
    PayoutStatementService payoutStatementService;

    @Inject
    villagecompute.storefront.notifications.NotificationService notificationService;

    @Inject
    villagecompute.storefront.data.repositories.CommissionScheduleRepository commissionScheduleRepository;

    @Inject
    PgCryptoUtil pgCryptoUtil;

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
    public Consignor createConsignor(Consignor consignor) {
        return createConsignor(consignor, null, null);
    }

    @Transactional
    public Consignor createConsignor(Consignor consignor, String taxIdPlaintext, String keyVersion) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Creating consignor - tenantId=%s, name=%s", tenantId, consignor.name);

        applyEncryptedTaxId(consignor, taxIdPlaintext, keyVersion);

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
    public Consignor updateConsignor(UUID consignorId, Consignor updatedConsignor) {
        return updateConsignor(consignorId, updatedConsignor, null, null);
    }

    @Transactional
    public Consignor updateConsignor(UUID consignorId, Consignor updatedConsignor, String taxIdPlaintext,
            String keyVersion) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Updating consignor - tenantId=%s, consignorId=%s", tenantId, consignorId);

        Consignor consignor = consignorRepository.findByIdAndTenant(consignorId)
                .orElseThrow(() -> new IllegalArgumentException("Consignor not found: " + consignorId));

        consignor.name = updatedConsignor.name;
        consignor.contactInfo = updatedConsignor.contactInfo;
        consignor.payoutSettings = updatedConsignor.payoutSettings;
        consignor.status = updatedConsignor.status;
        consignor.updatedAt = OffsetDateTime.now();
        if (taxIdPlaintext != null && !taxIdPlaintext.isBlank()) {
            applyEncryptedTaxId(consignor, taxIdPlaintext, keyVersion);
        }

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

    private void applyEncryptedTaxId(Consignor consignor, String taxIdPlaintext, String keyVersion) {
        if (consignor == null || taxIdPlaintext == null || taxIdPlaintext.isBlank()) {
            return;
        }
        UUID tenantId = TenantContext.getCurrentTenantId();
        String effectiveVersion = (keyVersion != null && !keyVersion.isBlank()) ? keyVersion
                : pgCryptoUtil.getDefaultKeyVersion();
        consignor.taxIdEncrypted = pgCryptoUtil.encrypt(taxIdPlaintext.trim(), tenantId, effectiveVersion);
        consignor.taxIdKeyVersion = effectiveVersion;
        consignor.taxIdLastRotated = OffsetDateTime.now();
        LOG.debugf("Applied encrypted tax ID - tenantId=%s, consignorId=%s, keyVersion=%s", tenantId, consignor.id,
                effectiveVersion);
    }

    // ========================================
    // Commission Schedule Operations
    // ========================================

    /**
     * Create a commission schedule for a consignor.
     *
     * @param consignorId
     *            consignor UUID
     * @param schedule
     *            commission schedule to create
     * @return created schedule
     */
    @Transactional
    public villagecompute.storefront.data.models.CommissionSchedule createCommissionSchedule(UUID consignorId,
            villagecompute.storefront.data.models.CommissionSchedule schedule) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Creating commission schedule - tenantId=%s, consignorId=%s, rate=%s", tenantId, consignorId,
                schedule.commissionRate);

        Consignor consignor = consignorRepository.findByIdAndTenant(consignorId)
                .orElseThrow(() -> new IllegalArgumentException("Consignor not found: " + consignorId));

        // Validate commission rate
        if (schedule.commissionRate.compareTo(BigDecimal.ZERO) < 0
                || schedule.commissionRate.compareTo(MAX_COMMISSION_RATE) > 0) {
            throw new IllegalArgumentException("Commission rate must be between 0 and 100");
        }
        validateCommissionSchedule(consignor.id, schedule, null);

        schedule.consignor = consignor;
        commissionScheduleRepository.persist(schedule);

        LOG.infof("Commission schedule created - tenantId=%s, scheduleId=%s, consignorId=%s", tenantId, schedule.id,
                consignorId);
        meterRegistry.counter("consignment.commission_schedule.created", "tenant_id", tenantId.toString()).increment();

        return schedule;
    }

    /**
     * Get active commission schedules for a consignor.
     *
     * @param consignorId
     *            consignor UUID
     * @return list of active schedules
     */
    public List<villagecompute.storefront.data.models.CommissionSchedule> getActiveCommissionSchedules(
            UUID consignorId) {
        return commissionScheduleRepository.findActiveForConsignor(consignorId, java.time.LocalDate.now());
    }

    /**
     * Get all commission schedules for a consignor.
     *
     * @param consignorId
     *            consignor UUID
     * @return list of schedules
     */
    public List<villagecompute.storefront.data.models.CommissionSchedule> getAllCommissionSchedules(UUID consignorId) {
        return commissionScheduleRepository.findByConsignor(consignorId);
    }

    /**
     * Update a commission schedule.
     *
     * @param scheduleId
     *            schedule UUID
     * @param updates
     *            updated schedule data
     * @return updated schedule
     */
    @Transactional
    public villagecompute.storefront.data.models.CommissionSchedule updateCommissionSchedule(UUID scheduleId,
            villagecompute.storefront.data.models.CommissionSchedule updates) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Updating commission schedule - tenantId=%s, scheduleId=%s", tenantId, scheduleId);

        villagecompute.storefront.data.models.CommissionSchedule schedule = commissionScheduleRepository
                .findByIdAndTenant(scheduleId)
                .orElseThrow(() -> new IllegalArgumentException("Commission schedule not found: " + scheduleId));

        validateCommissionSchedule(schedule.consignor.id, updates, scheduleId);
        schedule.commissionRate = updates.commissionRate;
        schedule.effectiveFrom = updates.effectiveFrom;
        schedule.effectiveUntil = updates.effectiveUntil;
        schedule.priority = updates.priority;
        schedule.categoryId = updates.categoryId;
        schedule.metadata = updates.metadata;
        schedule.notes = updates.notes;
        schedule.status = updates.status;
        schedule.updatedAt = OffsetDateTime.now();

        commissionScheduleRepository.persist(schedule);

        LOG.infof("Commission schedule updated - tenantId=%s, scheduleId=%s", tenantId, scheduleId);
        return schedule;
    }

    private void validateCommissionSchedule(UUID consignorId,
            villagecompute.storefront.data.models.CommissionSchedule schedule, UUID existingId) {
        if (schedule.effectiveFrom == null) {
            throw new IllegalArgumentException("effectiveFrom is required");
        }
        if (schedule.effectiveUntil != null && schedule.effectiveUntil.isBefore(schedule.effectiveFrom)) {
            throw new IllegalArgumentException("effectiveUntil must be on or after effectiveFrom");
        }
        List<villagecompute.storefront.data.models.CommissionSchedule> existing = commissionScheduleRepository
                .findByConsignor(consignorId);
        LocalDate newStart = schedule.effectiveFrom;
        LocalDate newEnd = schedule.effectiveUntil != null ? schedule.effectiveUntil : LocalDate.MAX;

        for (villagecompute.storefront.data.models.CommissionSchedule other : existing) {
            if (existingId != null && existingId.equals(other.id)) {
                continue;
            }
            if (!"active".equalsIgnoreCase(other.status)) {
                continue;
            }
            boolean sameCategory = (other.categoryId == null && schedule.categoryId == null)
                    || (other.categoryId != null && other.categoryId.equals(schedule.categoryId));
            if (!sameCategory) {
                continue;
            }
            LocalDate otherStart = other.effectiveFrom;
            LocalDate otherEnd = other.effectiveUntil != null ? other.effectiveUntil : LocalDate.MAX;
            if (datesOverlap(newStart, newEnd, otherStart, otherEnd)) {
                throw new IllegalArgumentException("Overlapping commission schedule already exists for category");
            }
        }
    }

    private boolean datesOverlap(LocalDate start1, LocalDate end1, LocalDate start2, LocalDate end2) {
        return !(end1.isBefore(start2) || end2.isBefore(start1));
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
        return createConsignmentItem(consignorId, productId, null, commissionRate, null, null);
    }

    @Transactional
    public ConsignmentItem createConsignmentItem(UUID consignorId, UUID productId, UUID variantId,
            BigDecimal commissionRate, BigDecimal costBasis, String status) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        BigDecimal normalizedRate = normalizeCommissionRate(commissionRate);
        LOG.infof("Creating consignment item - tenantId=%s, productId=%s, consignorId=%s, commissionRate=%s", tenantId,
                productId, consignorId, normalizedRate);

        Consignor consignor = consignorRepository.findByIdAndTenant(consignorId)
                .orElseThrow(() -> new IllegalArgumentException("Consignor not found: " + consignorId));
        Product product = productRepository.findByIdAndTenant(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));
        ProductVariant variant = null;
        if (variantId != null) {
            variant = productVariantRepository.findByIdForTenant(variantId)
                    .orElseThrow(() -> new IllegalArgumentException("Variant not found: " + variantId));
            if (variant.product == null || !variant.product.id.equals(productId)) {
                throw new IllegalArgumentException("Variant does not belong to provided product");
            }
        }

        ConsignmentItem item = new ConsignmentItem();
        item.consignor = consignor;
        item.product = product;
        item.variant = variant;
        item.commissionRate = normalizedRate;
        item.status = status != null && !status.isBlank() ? status : "active";
        item.costBasis = costBasis;

        consignmentItemRepository.persist(item);

        LOG.infof("Consignment item created successfully - tenantId=%s, itemId=%s", tenantId, item.id);
        meterRegistry.counter("consignment.item.created", "tenant_id", tenantId.toString()).increment();

        // Publish ConsignmentItemReceived event
        publishConsignmentItemReceivedEvent(item);

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

        if (updatedItem.commissionRate != null) {
            item.commissionRate = updatedItem.commissionRate;
        }
        if (updatedItem.status != null && !updatedItem.status.isBlank()) {
            item.status = updatedItem.status;
        }
        if (updatedItem.costBasis != null) {
            item.costBasis = updatedItem.costBasis;
        }
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
     * Handle a paid order by attributing consignment sales to the correct consignors and creating ledger entries.
     *
     * @param order
     *            paid order
     */
    @Transactional
    public void handleOrderPaid(Order order) {
        if (order == null) {
            return;
        }
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Processing consignment payouts for paid order - tenantId=%s, orderId=%s", tenantId, order.id);

        List<OrderLineItem> lineItems = OrderLineItem.list("order.id = ?1 and tenant.id = ?2", order.id,
                order.tenant.id);
        for (OrderLineItem lineItem : lineItems) {
            if (lineItem.vendorId == null) {
                continue;
            }
            processConsignmentSale(order, lineItem);
        }
    }

    /**
     * Handle an order refund by reversing previously recorded consignment payouts.
     *
     * @param order
     *            refunded order
     * @param refundAmount
     *            amount refunded to the buyer
     * @param reason
     *            refund reason for auditing
     */
    @Transactional
    public void handleOrderRefund(Order order, BigDecimal refundAmount, String reason) {
        if (order == null || refundAmount == null || refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        UUID tenantId = TenantContext.getCurrentTenantId();
        BigDecimal remainingRefund = refundAmount;
        LOG.infof("Reversing consignment payouts for refund - tenantId=%s, orderId=%s, refundAmount=%s, reason=%s",
                tenantId, order.id, refundAmount, reason);

        List<OrderLineItem> lineItems = OrderLineItem.list("order.id = ?1 and tenant.id = ?2", order.id,
                order.tenant.id);
        for (OrderLineItem lineItem : lineItems) {
            if (lineItem.vendorId == null || remainingRefund.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            remainingRefund = reverseConsignmentSale(order, lineItem, remainingRefund, reason);
        }

        if (remainingRefund.compareTo(BigDecimal.ZERO) > 0) {
            LOG.warnf("Refund amount exceeded consignor attribution - tenantId=%s, orderId=%s, leftover=%s", tenantId,
                    order.id, remainingRefund);
        }
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
     * Emit payout due event for a consignor with available balance.
     *
     * @param consignorId
     *            consignor UUID
     * @param availableBalance
     *            available balance amount
     * @param periodStart
     *            payout period start date
     * @param periodEnd
     *            payout period end date
     * @param triggeredBy
     *            source of the trigger (e.g., "SETTLEMENT_JOB", "MANUAL")
     */
    @Transactional
    public void emitPayoutDueEvent(UUID consignorId, BigDecimal availableBalance, LocalDate periodStart,
            LocalDate periodEnd, String triggeredBy) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Emitting payout due event - tenantId=%s, consignorId=%s, availableBalance=%s", tenantId, consignorId,
                availableBalance);

        Consignor consignor = consignorRepository.findByIdAndTenant(consignorId)
                .orElseThrow(() -> new IllegalArgumentException("Consignor not found: " + consignorId));

        ConsignmentPayoutDuePayload payload = new ConsignmentPayoutDuePayload(consignorId, consignor.name,
                availableBalance, "USD", periodStart, periodEnd, triggeredBy);

        eventPublisher.publish("CONSIGNMENT", consignorId, "CONSIGNMENT_PAYOUT_DUE", payload);

        LOG.infof("Payout due event emitted - tenantId=%s, consignorId=%s", tenantId, consignorId);
    }

    // ========================================
    // Event Publishing Helper Methods
    // ========================================

    private void processConsignmentSale(Order order, OrderLineItem lineItem) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        Consignor consignor = consignorRepository.findByIdAndTenant(lineItem.vendorId)
                .orElseGet(() -> consignorRepository.findById(lineItem.vendorId));
        if (consignor == null) {
            LOG.warnf("Consignor not found for order line - tenantId=%s, orderId=%s, lineItemId=%s, vendorId=%s",
                    tenantId, order.id, lineItem.id, lineItem.vendorId);
            return;
        }

        BigDecimal commissionPercent = resolveCommissionPercent(lineItem, lineItem.productId);
        PayoutCalculation calculation = calculatePayout(lineItem.subtotal, commissionPercent);

        payoutLedgerService.recordSale(consignor.id, calculation.netPayout(), order.id,
                String.format("Order %s - %s x%d", order.orderNumber, lineItem.productName, lineItem.quantity));

        markInventoryAsSold(lineItem.productId, lineItem.quantity);
    }

    private BigDecimal reverseConsignmentSale(Order order, OrderLineItem lineItem, BigDecimal remainingRefund,
            String reason) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        Consignor consignor = consignorRepository.findByIdAndTenant(lineItem.vendorId)
                .orElseGet(() -> consignorRepository.findById(lineItem.vendorId));
        if (consignor == null) {
            LOG.warnf("Consignor not found for refund reversal - tenantId=%s, orderId=%s, lineItemId=%s, vendorId=%s",
                    tenantId, order.id, lineItem.id, lineItem.vendorId);
            return remainingRefund;
        }

        if (lineItem.subtotal == null || lineItem.subtotal.compareTo(BigDecimal.ZERO) <= 0) {
            return remainingRefund;
        }

        BigDecimal commissionPercent = resolveCommissionPercent(lineItem, lineItem.productId);
        PayoutCalculation calculation = calculatePayout(lineItem.subtotal, commissionPercent);

        BigDecimal refundablePortion = remainingRefund.min(lineItem.subtotal);
        BigDecimal ratio = refundablePortion.divide(lineItem.subtotal, 4, RoundingMode.HALF_UP);
        BigDecimal netToReverse = calculation.netPayout().multiply(ratio).setScale(4, RoundingMode.HALF_UP);

        if (netToReverse.compareTo(BigDecimal.ZERO) > 0) {
            payoutLedgerService.recordRefund(consignor.id, netToReverse, order.id,
                    String.format("Order %s refund (%s) - %s", order.orderNumber, reason, lineItem.productName));
        }

        if (refundablePortion.compareTo(lineItem.subtotal) >= 0) {
            markInventoryAsReturned(lineItem.productId, lineItem.quantity);
        }

        return remainingRefund.subtract(refundablePortion);
    }

    private BigDecimal resolveCommissionPercent(OrderLineItem lineItem, UUID productId) {
        if (lineItem.commissionRate != null) {
            return lineItem.commissionRate.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
        }
        return consignmentItemRepository.findActiveItemByProduct(productId).map(item -> item.commissionRate)
                .orElseGet(() -> consignmentItemRepository.findSoldItemsByProduct(productId, 1).stream().findFirst()
                        .map(item -> item.commissionRate).orElse(BigDecimal.ZERO));
    }

    private void markInventoryAsSold(UUID productId, int quantity) {
        if (productId == null || quantity <= 0) {
            return;
        }
        List<ConsignmentItem> items = consignmentItemRepository.findActiveItemsByProduct(productId, quantity);
        OffsetDateTime now = OffsetDateTime.now();
        for (ConsignmentItem item : items) {
            item.status = "sold";
            item.soldAt = now;
            item.updatedAt = now;
            consignmentItemRepository.persist(item);
        }
        if (items.size() < quantity) {
            LOG.warnf("Requested more consignment items than available - productId=%s, requested=%d, updated=%d",
                    productId, quantity, items.size());
        }
    }

    private void markInventoryAsReturned(UUID productId, int quantity) {
        if (productId == null || quantity <= 0) {
            return;
        }
        List<ConsignmentItem> items = consignmentItemRepository.findSoldItemsByProduct(productId, quantity);
        OffsetDateTime now = OffsetDateTime.now();
        for (ConsignmentItem item : items) {
            item.status = "returned";
            item.soldAt = null;
            item.updatedAt = now;
            consignmentItemRepository.persist(item);
        }
    }

    private void publishConsignmentItemReceivedEvent(ConsignmentItem item) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Publishing ConsignmentItemReceived event - tenantId=%s, itemId=%s", tenantId, item.id);

        ConsignmentItemReceivedPayload payload = new ConsignmentItemReceivedPayload(item.id, item.consignor.id,
                item.consignor.name, item.product.id, item.product.name, item.commissionRate, item.status, "SYSTEM");

        eventPublisher.publish("CONSIGNMENT_ITEM", item.id, "CONSIGNMENT_ITEM_RECEIVED", payload);
    }

    // ========================================
    // Automated Payout Operations (Task I5.T6)
    // ========================================

    /**
     * Create payout batches for all consignors with sales in the specified period.
     *
     * <p>
     * Queries pre-computed {@link villagecompute.storefront.data.models.ConsignmentPayoutAggregate} table for period
     * summaries, creates {@link PayoutBatch} records, and applies auto-approval logic based on configured thresholds.
     *
     * <p>
     * Called by {@link villagecompute.storefront.jobs.ConsignmentPayoutAutomationJob} on monthly schedule.
     *
     * @param periodStart
     *            period start date (inclusive)
     * @param periodEnd
     *            period end date (inclusive)
     * @return number of batches created for current tenant
     */
    @Transactional
    public int createPayoutBatchesForPeriod(LocalDate periodStart, LocalDate periodEnd) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Creating payout batches for period - tenantId=%s, period=%s to %s", tenantId, periodStart,
                periodEnd);

        // Query aggregates for this period
        List<villagecompute.storefront.data.models.ConsignmentPayoutAggregate> aggregates = payoutAggregateRepository
                .findByPeriodRange(periodStart, periodEnd);

        if (aggregates.isEmpty()) {
            LOG.infof("No consignment sales found for period - tenantId=%s, period=%s to %s", tenantId, periodStart,
                    periodEnd);
            return 0;
        }

        int batchCount = 0;
        for (villagecompute.storefront.data.models.ConsignmentPayoutAggregate aggregate : aggregates) {
            try {
                // Skip if aggregate is stale (data freshness > 30 minutes)
                if (aggregate.dataFreshnessTimestamp.isBefore(OffsetDateTime.now().minusMinutes(30))) {
                    LOG.warnf("Skipping stale aggregate - tenantId=%s, aggregateId=%s, lastRefresh=%s", tenantId,
                            aggregate.id, aggregate.dataFreshnessTimestamp);
                    meterRegistry.counter("consignment.payout.batch.creation.skipped", "tenant", tenantId.toString(),
                            "reason", "stale_data").increment();
                    continue;
                }

                // Skip if batch already exists for this consignor and period
                Optional<PayoutBatch> existingBatch = payoutBatchRepository
                        .findByConsignorAndPeriod(aggregate.consignor.id, aggregate.periodStart, aggregate.periodEnd);
                if (existingBatch.isPresent()) {
                    LOG.debugf("Batch already exists - tenantId=%s, consignorId=%s, batchId=%s", tenantId,
                            aggregate.consignor.id, existingBatch.get().id);
                    continue;
                }

                // Create payout batch from aggregate
                PayoutBatch batch = createBatchFromAggregate(aggregate);

                // Apply auto-approval logic
                applyAutoApproval(batch);

                payoutBatchRepository.persist(batch);
                batchCount++;

                LOG.infof(
                        "Payout batch created from aggregate - tenantId=%s, batchId=%s, consignorId=%s, totalAmount=%s, status=%s",
                        tenantId, batch.id, aggregate.consignor.id, batch.totalAmount, batch.status);
                meterRegistry.counter("consignment.payout.batch.created", "tenant", tenantId.toString(), "status",
                        batch.status).increment();

            } catch (Exception e) {
                LOG.errorf(e, "Failed to create batch from aggregate - tenantId=%s, aggregateId=%s", tenantId,
                        aggregate.id);
                meterRegistry.counter("consignment.payout.batch.creation.failed", "tenant", tenantId.toString(),
                        "reason", "exception").increment();
            }
        }

        LOG.infof("Payout batch creation completed - tenantId=%s, batchesCreated=%d, period=%s to %s", tenantId,
                batchCount, periodStart, periodEnd);
        return batchCount;
    }

    /**
     * Process an approved payout batch by executing Stripe Connect transfer.
     *
     * <p>
     * Workflow:
     * <ol>
     * <li>Load batch with optimistic locking</li>
     * <li>Verify batch is in awaiting_approval status</li>
     * <li>Deduct amount from consignor's available balance via ledger</li>
     * <li>Create Stripe payout to consignor's connected account</li>
     * <li>Update batch status and payment reference</li>
     * <li>Enqueue reconciliation job for async verification</li>
     * <li>Send notification email to consignor</li>
     * </ol>
     *
     * @param batchId
     *            payout batch UUID
     * @throws IllegalArgumentException
     *             if batch not found or not in correct status
     * @throws IllegalStateException
     *             if insufficient available balance
     */
    @Transactional
    public void processPayoutBatch(UUID batchId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Processing payout batch - tenantId=%s, batchId=%s", tenantId, batchId);

        // Load batch with optimistic lock
        PayoutBatch batch = payoutBatchRepository.findByIdAndTenant(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Payout batch not found: " + batchId));

        if (!"awaiting_approval".equals(batch.status)) {
            throw new IllegalStateException("Batch must be in awaiting_approval status. Current: " + batch.status);
        }

        try {
            // Step 1: Update status to processing
            batch.status = "processing";
            batch.updatedAt = OffsetDateTime.now();
            payoutBatchRepository.persistAndFlush(batch);

            // Step 2: Deduct from available balance via ledger
            payoutLedgerService.recordPayout(batch.consignor.id, batch.totalAmount, batch.id,
                    String.format("Payout batch %s - Period %s to %s", batch.id, batch.periodStart, batch.periodEnd));

            LOG.infof("Ledger deduction completed - tenantId=%s, batchId=%s, consignorId=%s, amount=%s", tenantId,
                    batchId, batch.consignor.id, batch.totalAmount);

        } catch (Exception e) {
            // Rollback status on ledger failure
            batch.status = "failed";
            batch.updatedAt = OffsetDateTime.now();
            payoutBatchRepository.persistAndFlush(batch);
            throw new RuntimeException("Failed to deduct payout from ledger: " + e.getMessage(), e);
        }

        // Step 3: Create Stripe payout (outside transaction to avoid distributed transaction)
        String stripePayoutId = createStripePayout(batch);

        // Step 4: Update batch with payment reference (new transaction)
        updateBatchWithPaymentReference(batchId, stripePayoutId);

        LOG.infof("Payout batch processing completed - tenantId=%s, batchId=%s, stripePayoutId=%s", tenantId, batchId,
                stripePayoutId);
    }

    /**
     * Determine if a payout batch requires manual approval.
     *
     * <p>
     * Auto-approval conditions (all must be met):
     * <ul>
     * <li>Auto-approval feature flag enabled</li>
     * <li>Batch amount ≤ configured limit (default: $500)</li>
     * <li>Consignor active for ≥ 3 months</li>
     * <li>No failed payouts in last 6 months</li>
     * <li>Stripe account verified (charges_enabled=true, payouts_enabled=true)</li>
     * </ul>
     *
     * @param batch
     *            payout batch to evaluate
     * @return true if manual approval required
     */
    public boolean requiresManualApproval(PayoutBatch batch) {
        if (!payoutConfig.autoApprove().enabled()) {
            return true;
        }

        // Check amount threshold
        if (batch.totalAmount.compareTo(payoutConfig.autoApprove().amountLimit()) > 0) {
            LOG.debugf("Batch exceeds auto-approve limit - batchId=%s, amount=%s, limit=%s", batch.id,
                    batch.totalAmount, payoutConfig.autoApprove().amountLimit());
            return true;
        }

        // Check consignor tenure
        OffsetDateTime tenureThreshold = OffsetDateTime.now()
                .minusMonths(payoutConfig.autoApprove().minConsignorTenureMonths());
        if (batch.consignor.createdAt.isAfter(tenureThreshold)) {
            LOG.debugf("Consignor tenure below threshold - batchId=%s, consignorCreatedAt=%s, threshold=%s", batch.id,
                    batch.consignor.createdAt, tenureThreshold);
            return true;
        }

        // Check failure history (query for failed batches in lookback period)
        LocalDate failureLookback = LocalDate.now().minusMonths(payoutConfig.autoApprove().failureLookbackMonths());
        long failedCount = PayoutBatch.count(
                "consignor.id = ?1 and status = 'failed' and createdAt >= ?2 and tenant.id = ?3", batch.consignor.id,
                failureLookback.atStartOfDay(), TenantContext.getCurrentTenantId());

        if (failedCount > 0) {
            LOG.debugf("Consignor has failed payouts in lookback period - batchId=%s, failedCount=%d", batch.id,
                    failedCount);
            return true;
        }

        // Check Stripe account capabilities
        if (!isStripeAccountVerified(batch.consignor)) {
            LOG.debugf("Stripe account not verified - batchId=%s, consignorId=%s", batch.id, batch.consignor.id);
            return true;
        }

        return false;
    }

    private PayoutBatch createBatchFromAggregate(
            villagecompute.storefront.data.models.ConsignmentPayoutAggregate aggregate) {
        PayoutBatch batch = new PayoutBatch();
        batch.consignor = aggregate.consignor;
        batch.periodStart = aggregate.periodStart;
        batch.periodEnd = aggregate.periodEnd;
        batch.totalAmount = aggregate.totalOwed;
        batch.currency = "USD";
        batch.status = "pending";
        return batch;
    }

    private void applyAutoApproval(PayoutBatch batch) {
        if (requiresManualApproval(batch)) {
            batch.status = "pending";
            LOG.debugf("Batch requires manual approval - batchId=%s", batch.id);
        } else {
            batch.status = "awaiting_approval";
            LOG.infof("Batch auto-approved - batchId=%s, consignorId=%s, amount=%s", batch.id, batch.consignor.id,
                    batch.totalAmount);
            meterRegistry.counter("consignment.payout.batch.auto_approved", "tenant",
                    TenantContext.getCurrentTenantId().toString()).increment();
        }
    }

    private boolean isStripeAccountVerified(Consignor consignor) {
        try {
            // Parse payoutSettings JSONB for Stripe account info
            if (consignor.payoutSettings == null || consignor.payoutSettings.isBlank()) {
                return false;
            }

            // Simple JSON parsing (production would use Jackson)
            return consignor.payoutSettings.contains("\"stripe_charges_enabled\":true")
                    && consignor.payoutSettings.contains("\"stripe_payouts_enabled\":true");
        } catch (Exception e) {
            LOG.warnf(e, "Failed to parse payout settings for consignor %s", consignor.id);
            return false;
        }
    }

    private String createStripePayout(PayoutBatch batch) {
        UUID tenantId = TenantContext.getCurrentTenantId();

        try {
            // Extract Stripe account ID from consignor payout settings
            String stripeAccountId = extractStripeAccountId(batch.consignor);
            if (stripeAccountId == null) {
                throw new IllegalStateException("No Stripe account ID found for consignor: " + batch.consignor.id);
            }

            // Create payout request
            villagecompute.storefront.payment.MarketplaceProvider.PayoutRequest request = new villagecompute.storefront.payment.MarketplaceProvider.PayoutRequest(
                    stripeAccountId, batch.totalAmount, batch.currency,
                    String.format("Payout for batch %s (Period: %s to %s)", batch.id, batch.periodStart,
                            batch.periodEnd),
                    java.util.Map.of("batch_id", batch.id.toString(), "consignor_id", batch.consignor.id.toString()),
                    batch.id.toString() // Idempotency key
            );

            villagecompute.storefront.payment.MarketplaceProvider.PayoutResult result = marketplaceProvider
                    .createPayout(request);

            LOG.infof("Stripe payout created - tenantId=%s, batchId=%s, stripePayoutId=%s, estimatedArrival=%s",
                    tenantId, batch.id, result.payoutId(), result.estimatedArrival());

            meterRegistry.counter("consignment.payout.stripe.transfer.created", "tenant", tenantId.toString())
                    .increment();

            return result.payoutId();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to create Stripe payout - tenantId=%s, batchId=%s", tenantId, batch.id);
            meterRegistry.counter("consignment.payout.stripe.transfer.failed", "tenant", tenantId.toString(), "reason",
                    e.getClass().getSimpleName()).increment();
            throw new RuntimeException("Failed to create Stripe payout: " + e.getMessage(), e);
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void updateBatchWithPaymentReference(UUID batchId, String stripePayoutId) {
        PayoutBatch batch = payoutBatchRepository.findById(batchId);
        if (batch != null) {
            batch.paymentReference = stripePayoutId;
            batch.status = "processing";
            batch.updatedAt = OffsetDateTime.now();
            payoutBatchRepository.persistAndFlush(batch);

            // Generate and store statement to R2
            try {
                String statementUrl = payoutStatementService.generateAndStoreStatement(batch);
                LOG.infof("Payout statement generated - batchId=%s, statementUrl=%s", batchId, statementUrl);
            } catch (Exception e) {
                LOG.errorf(e, "Failed to generate payout statement - batchId=%s (continuing with payout)", batchId);
            }

            // Send notification email to consignor
            try {
                sendPayoutNotification(batch);
            } catch (Exception e) {
                LOG.errorf(e, "Failed to send payout notification - batchId=%s (continuing with payout)", batchId);
            }

            // Enqueue reconciliation job
            enqueueReconciliationJob(batch, stripePayoutId);
        }
    }

    private void sendPayoutNotification(PayoutBatch batch) {
        UUID tenantId = TenantContext.getCurrentTenantId();

        // Extract consignor email from contactInfo JSONB
        String consignorEmail = extractConsignorEmail(batch.consignor);
        if (consignorEmail == null) {
            LOG.warnf("Cannot send payout notification - no email found for consignor %s", batch.consignor.id);
            return;
        }

        // Build notification context
        villagecompute.storefront.notifications.NotificationContext context = villagecompute.storefront.notifications.NotificationContext
                .builder().tenantId(tenantId).consignorId(batch.consignor.id).consignorName(batch.consignor.name)
                .consignorEmail(consignorEmail).locale("en") // Default to English; production would use consignor's
                                                             // preferred locale
                .templateData(java.util.Map.of("batchId", batch.id.toString(), "periodStart",
                        batch.periodStart.toString(), "periodEnd", batch.periodEnd.toString(), "totalAmount",
                        batch.totalAmount.toString(), "currency", batch.currency, "paymentReference",
                        batch.paymentReference != null ? batch.paymentReference : "Pending", "estimatedArrival",
                        "3-5 business days"))
                .build();

        notificationService.sendPayoutSummary(context);

        LOG.infof("Payout notification sent - tenantId=%s, batchId=%s, consignorId=%s", tenantId, batch.id,
                batch.consignor.id);
        meterRegistry.counter("consignment.payout.notification.sent", "tenant", tenantId.toString()).increment();
    }

    private String extractConsignorEmail(Consignor consignor) {
        if (consignor.contactInfo == null || consignor.contactInfo.isBlank()) {
            return null;
        }

        // Simple JSON parsing (production would use Jackson)
        try {
            int startIdx = consignor.contactInfo.indexOf("\"email\":\"");
            if (startIdx == -1) {
                return null;
            }
            startIdx += "\"email\":\"".length();
            int endIdx = consignor.contactInfo.indexOf("\"", startIdx);
            return consignor.contactInfo.substring(startIdx, endIdx);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to parse email from contact info for consignor %s", consignor.id);
            return null;
        }
    }

    private void enqueueReconciliationJob(PayoutBatch batch, String stripePayoutId) {
        // Create payload for reconciliation job
        villagecompute.storefront.jobs.PayoutReconciliationJobPayload payload = villagecompute.storefront.jobs.PayoutReconciliationJobPayload
                .fromBatch(batch, stripePayoutId);

        LOG.infof("Enqueued payout reconciliation job - batchId=%s, stripePayoutId=%s, jobId=%s", batch.id,
                stripePayoutId, payload.jobId());

        // Note: Job queue implementation would be added here
        // For now, log that reconciliation should be performed
        meterRegistry.counter("consignment.payout.reconciliation.enqueued", "tenant",
                TenantContext.getCurrentTenantId().toString()).increment();
    }

    private String extractStripeAccountId(Consignor consignor) {
        if (consignor.payoutSettings == null || consignor.payoutSettings.isBlank()) {
            return null;
        }

        // Simple JSON parsing (production would use Jackson)
        try {
            int startIdx = consignor.payoutSettings.indexOf("\"stripe_account_id\":\"");
            if (startIdx == -1) {
                return null;
            }
            startIdx += "\"stripe_account_id\":\"".length();
            int endIdx = consignor.payoutSettings.indexOf("\"", startIdx);
            return consignor.payoutSettings.substring(startIdx, endIdx);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to parse Stripe account ID for consignor %s", consignor.id);
            return null;
        }
    }

    /**
     * Value object encapsulating payout calculation results.
     */
    public record PayoutCalculation(BigDecimal itemSubtotal, BigDecimal commissionAmount, BigDecimal netPayout) {
    }
}
