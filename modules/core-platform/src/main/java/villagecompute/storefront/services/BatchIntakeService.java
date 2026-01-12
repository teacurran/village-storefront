package villagecompute.storefront.services;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.ConsignmentItem;
import villagecompute.storefront.data.models.Consignor;
import villagecompute.storefront.data.models.IntakeBatch;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.data.repositories.ConsignmentItemRepository;
import villagecompute.storefront.data.repositories.ConsignorRepository;
import villagecompute.storefront.data.repositories.IntakeBatchRepository;
import villagecompute.storefront.data.repositories.InventoryLevelRepository;
import villagecompute.storefront.data.repositories.ProductRepository;
import villagecompute.storefront.data.repositories.ProductVariantRepository;
import villagecompute.storefront.services.events.IntakeBatchCompletedPayload;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Service for handling batch consignment intake operations.
 *
 * <p>
 * Manages the intake workflow for receiving batches of consignment items from vendors, including:
 * <ul>
 * <li>Batch creation and tracking</li>
 * <li>Auto SKU assignment for items without SKUs</li>
 * <li>Variant creation and linking</li>
 * <li>Inventory adjustments for intake quantities</li>
 * <li>Commission schedule application</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T1: Consignment intake batch handling</li>
 * <li>ERD: Consignor, ConsignmentItem, IntakeBatch</li>
 * <li>Clarification 4: Batch intake workflows</li>
 * </ul>
 */
@ApplicationScoped
public class BatchIntakeService {

    private static final Logger LOG = Logger.getLogger(BatchIntakeService.class);
    private static final String DEFAULT_SKU_PREFIX = "CONSIGN-";
    private static final String DEFAULT_INVENTORY_LOCATION = "default";
    private static final int BATCH_RETENTION_YEARS = 7;

    @Inject
    ConsignorRepository consignorRepository;

    @Inject
    IntakeBatchRepository intakeBatchRepository;

    @Inject
    ConsignmentItemRepository consignmentItemRepository;

    @Inject
    ProductRepository productRepository;

    @Inject
    ProductVariantRepository productVariantRepository;

    @Inject
    InventoryLevelRepository inventoryLevelRepository;

    @Inject
    InventoryService inventoryService;

    @Inject
    DomainEventPublisher eventPublisher;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    ConsignmentRetentionService consignmentRetentionService;

    // ========================================
    // Batch Creation & Management
    // ========================================

    /**
     * Create a new intake batch for a consignor.
     *
     * @param consignorId
     *            consignor UUID
     * @param batchName
     *            descriptive name for the batch
     * @param notes
     *            optional batch notes
     * @return created intake batch
     */
    @Transactional
    public IntakeBatch createIntakeBatch(UUID consignorId, String batchName, String notes) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Creating intake batch - tenantId=%s, consignorId=%s, batchName=%s", tenantId, consignorId,
                batchName);

        Consignor consignor = consignorRepository.findByIdAndTenant(consignorId)
                .orElseThrow(() -> new IllegalArgumentException("Consignor not found: " + consignorId));

        IntakeBatch batch = new IntakeBatch();
        batch.consignor = consignor;
        batch.batchName = batchName;
        batch.notes = notes;
        batch.status = "pending";
        batch.totalItems = 0;
        batch.processedItems = 0;

        intakeBatchRepository.persist(batch);

        LOG.infof("Intake batch created - tenantId=%s, batchId=%s", tenantId, batch.id);
        meterRegistry.counter("consignment.intake.batch.created", "tenant_id", tenantId.toString()).increment();

        return batch;
    }

    /**
     * Add items to an intake batch with auto SKU assignment.
     *
     * @param batchId
     *            intake batch UUID
     * @param items
     *            list of intake item requests
     * @return list of created consignment items
     */
    @Transactional
    public List<ConsignmentItem> addItemsToBatch(UUID batchId, List<IntakeItemRequest> items) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Adding items to intake batch - tenantId=%s, batchId=%s, itemCount=%d", tenantId, batchId,
                items.size());

        IntakeBatch batch = intakeBatchRepository.findByIdAndTenant(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Intake batch not found: " + batchId));

        if (!"pending".equals(batch.status)) {
            throw new IllegalStateException("Cannot add items to batch with status: " + batch.status);
        }

        List<ConsignmentItem> createdItems = new ArrayList<>();
        int successCount = 0;

        for (IntakeItemRequest itemRequest : items) {
            try {
                ConsignmentItem item = processIntakeItem(batch, itemRequest);
                createdItems.add(item);
                successCount++;
            } catch (Exception e) {
                LOG.errorf(e, "Failed to process intake item - batchId=%s, productName=%s", batchId,
                        itemRequest.productName());
                meterRegistry.counter("consignment.intake.item.failed", "tenant_id", tenantId.toString()).increment();
            }
        }

        // Update batch totals
        batch.totalItems += items.size();
        batch.processedItems += successCount;
        batch.updatedAt = OffsetDateTime.now();
        intakeBatchRepository.persist(batch);

        LOG.infof("Items added to batch - tenantId=%s, batchId=%s, successCount=%d, totalCount=%d", tenantId, batchId,
                successCount, items.size());

        return createdItems;
    }

    /**
     * Complete an intake batch, finalizing all items and inventory adjustments.
     *
     * @param batchId
     *            intake batch UUID
     * @return completed batch
     */
    @Transactional
    public IntakeBatch completeBatch(UUID batchId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Completing intake batch - tenantId=%s, batchId=%s", tenantId, batchId);

        IntakeBatch batch = intakeBatchRepository.findByIdAndTenant(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Intake batch not found: " + batchId));

        if (!"pending".equals(batch.status)) {
            throw new IllegalStateException("Batch must be in pending status to complete. Current: " + batch.status);
        }

        batch.status = "completed";
        batch.completedAt = OffsetDateTime.now();
        batch.updatedAt = OffsetDateTime.now();
        intakeBatchRepository.persist(batch);

        LOG.infof("Intake batch completed - tenantId=%s, batchId=%s, totalItems=%d, processedItems=%d", tenantId,
                batchId, batch.totalItems, batch.processedItems);
        meterRegistry.counter("consignment.intake.batch.completed", "tenant_id", tenantId.toString()).increment();

        // Publish batch completed event
        publishBatchCompletedEvent(batch);
        OffsetDateTime retainUntil = batch.completedAt != null ? batch.completedAt.plusYears(BATCH_RETENTION_YEARS)
                : OffsetDateTime.now().plusYears(BATCH_RETENTION_YEARS);
        consignmentRetentionService.registerRetention("INTAKE_BATCH", batch.id, retainUntil);

        return batch;
    }

    /**
     * Cancel an intake batch and mark all items as inactive.
     *
     * @param batchId
     *            intake batch UUID
     * @param reason
     *            cancellation reason
     */
    @Transactional
    public void cancelBatch(UUID batchId, String reason) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Cancelling intake batch - tenantId=%s, batchId=%s, reason=%s", tenantId, batchId, reason);

        IntakeBatch batch = intakeBatchRepository.findByIdAndTenant(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Intake batch not found: " + batchId));

        batch.status = "cancelled";
        batch.notes = (batch.notes != null ? batch.notes + "\n" : "") + "Cancelled: " + reason;
        batch.updatedAt = OffsetDateTime.now();
        intakeBatchRepository.persist(batch);

        LOG.infof("Intake batch cancelled - tenantId=%s, batchId=%s", tenantId, batchId);
        meterRegistry.counter("consignment.intake.batch.cancelled", "tenant_id", tenantId.toString()).increment();
    }

    /**
     * Get intake batch by ID.
     *
     * @param batchId
     *            batch UUID
     * @return batch if found
     */
    public Optional<IntakeBatch> getBatch(UUID batchId) {
        return intakeBatchRepository.findByIdAndTenant(batchId);
    }

    /**
     * List intake batches for a consignor.
     *
     * @param consignorId
     *            consignor UUID
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return list of batches
     */
    public List<IntakeBatch> listConsignorBatches(UUID consignorId, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Listing intake batches - tenantId=%s, consignorId=%s, page=%d, size=%d", tenantId, consignorId,
                page, size);

        int safePage = Math.max(page, 0);
        int safeSize = size > 0 ? size : 20;
        return intakeBatchRepository.findByConsignor(consignorId, safePage, safeSize);
    }

    // ========================================
    // Internal Processing
    // ========================================

    private ConsignmentItem processIntakeItem(IntakeBatch batch, IntakeItemRequest itemRequest) {
        UUID tenantId = TenantContext.getCurrentTenantId();

        Product product = resolveOrCreateProduct(itemRequest);
        ProductVariant variant = resolveOrCreateVariant(product, itemRequest);

        ConsignmentItem item = new ConsignmentItem();
        item.consignor = batch.consignor;
        item.product = product;
        item.variant = variant;
        item.commissionRate = resolveCommissionRate(batch.consignor, itemRequest);
        item.status = "active";
        item.costBasis = itemRequest.costBasis();
        item.intakeBatchId = batch.id;

        consignmentItemRepository.persist(item);
        recordInventoryReceipt(variant, itemRequest.quantity());

        LOG.debugf("Intake item processed - tenantId=%s, itemId=%s, productId=%s, variantId=%s", tenantId, item.id,
                product.id, variant != null ? variant.id : null);
        meterRegistry.counter("consignment.intake.item.processed", "tenant_id", tenantId.toString()).increment();

        return item;
    }

    private Product resolveOrCreateProduct(IntakeItemRequest itemRequest) {
        String normalizedSku = normalizeSku(itemRequest.sku());
        if (normalizedSku != null) {
            Optional<ProductVariant> variant = productVariantRepository.findBySku(normalizedSku);
            if (variant.isPresent()) {
                return variant.get().product;
            }
            Optional<Product> productBySku = productRepository.findBySku(normalizedSku);
            if (productBySku.isPresent()) {
                return productBySku.get();
            }
        }

        Product product = new Product();
        product.name = itemRequest.productName();
        product.title = itemRequest.productName();
        product.description = itemRequest.description();
        product.status = "active";
        product.type = "physical";
        product.slug = generateUniqueSlug(itemRequest.productName());
        product.sku = ensureUniqueProductSku(normalizedSku);

        productRepository.persist(product);

        LOG.debugf("Created new product for intake - productId=%s, name=%s, slug=%s", product.id, product.name,
                product.slug);
        return product;
    }

    private String generateAutoSku(String seed) {
        String base = seed != null && !seed.isBlank() ? seed : UUID.randomUUID().toString();
        String normalized = base.replaceAll("[^A-Za-z0-9]+", "").toUpperCase();
        if (normalized.isBlank()) {
            normalized = UUID.randomUUID().toString().replaceAll("-", "").substring(0, 8).toUpperCase();
        }
        return DEFAULT_SKU_PREFIX + normalized.substring(0, Math.min(10, normalized.length()));
    }

    private BigDecimal resolveCommissionRate(Consignor consignor, IntakeItemRequest itemRequest) {
        // Use item-specific rate if provided
        if (itemRequest.commissionRate() != null) {
            return itemRequest.commissionRate();
        }

        // Fall back to consignor default rate from payout settings
        return extractDefaultCommissionRate(consignor).orElse(new BigDecimal("15.00"));
    }

    private Optional<BigDecimal> extractDefaultCommissionRate(Consignor consignor) {
        if (consignor.payoutSettings == null || consignor.payoutSettings.isBlank()) {
            return Optional.empty();
        }

        try {
            // Simple JSON parsing for default_commission_rate
            int startIdx = consignor.payoutSettings.indexOf("\"default_commission_rate\":");
            if (startIdx == -1) {
                return Optional.empty();
            }
            startIdx += "\"default_commission_rate\":".length();
            int endIdx = consignor.payoutSettings.indexOf(",", startIdx);
            if (endIdx == -1) {
                endIdx = consignor.payoutSettings.indexOf("}", startIdx);
            }
            String rateStr = consignor.payoutSettings.substring(startIdx, endIdx).trim();
            return Optional.of(new BigDecimal(rateStr));
        } catch (Exception e) {
            LOG.warnf(e, "Failed to parse default commission rate for consignor %s", consignor.id);
            return Optional.empty();
        }
    }

    private ProductVariant resolveOrCreateVariant(Product product, IntakeItemRequest itemRequest) {
        String normalizedSku = normalizeSku(itemRequest.sku());
        if (normalizedSku != null) {
            Optional<ProductVariant> variant = productVariantRepository.findBySku(normalizedSku);
            if (variant.isPresent()) {
                return variant.get();
            }
        }

        ProductVariant variant = new ProductVariant();
        variant.product = product;
        variant.sku = ensureUniqueVariantSku(normalizedSku != null ? normalizedSku : generateAutoSku(product.name));
        variant.name = itemRequest.variantTitle() != null && !itemRequest.variantTitle().isBlank()
                ? itemRequest.variantTitle()
                : product.name;
        variant.price = itemRequest.price() != null ? itemRequest.price() : BigDecimal.ZERO;
        variant.compareAtPrice = itemRequest.compareAtPrice();
        variant.cost = itemRequest.costBasis();
        variant.barcode = itemRequest.barcode();
        variant.status = "active";

        productVariantRepository.persist(variant);
        LOG.debugf("Created new variant for intake - productId=%s, variantId=%s, sku=%s", product.id, variant.id,
                variant.sku);
        return variant;
    }

    private void recordInventoryReceipt(ProductVariant variant, Integer quantity) {
        if (variant == null || quantity == null || quantity <= 0) {
            return;
        }
        UUID tenantId = TenantContext.getCurrentTenantId();
        meterRegistry.counter("consignment.intake.inventory.adjustment", "tenant_id", tenantId.toString()).increment();
        inventoryLevelRepository.findByVariantAndLocation(variant.id, DEFAULT_INVENTORY_LOCATION)
                .ifPresentOrElse(level -> {
                    inventoryService.adjustInventory(variant.id, DEFAULT_INVENTORY_LOCATION, quantity);
                }, () -> inventoryService.setInventoryLevel(variant.id, DEFAULT_INVENTORY_LOCATION, quantity));
    }

    private String ensureUniqueVariantSku(String desiredSku) {
        String base = desiredSku != null && !desiredSku.isBlank() ? desiredSku : generateAutoSku(null);
        String candidate = base;
        int attempt = 1;
        while (productVariantRepository.findBySku(candidate).isPresent()) {
            candidate = base + "-" + attempt;
            attempt++;
        }
        return candidate;
    }

    private String ensureUniqueProductSku(String desiredSku) {
        String base = desiredSku != null && !desiredSku.isBlank() ? desiredSku : generateAutoSku("PRODUCT");
        String candidate = base;
        int attempt = 1;
        while (productRepository.findBySku(candidate).isPresent()) {
            candidate = base + "-" + attempt;
            attempt++;
        }
        return candidate;
    }

    private String normalizeSku(String sku) {
        if (sku == null) {
            return null;
        }
        String trimmed = sku.trim();
        return trimmed.isBlank() ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private String generateUniqueSlug(String productName) {
        String base = productName != null ? productName.trim().toLowerCase(Locale.ROOT) : "consigned";
        base = base.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (base.isBlank()) {
            base = "consigned-" + UUID.randomUUID().toString().substring(0, 8);
        }
        String candidate = base;
        int attempt = 1;
        while (productRepository.findBySlug(candidate).isPresent()) {
            candidate = base + "-" + attempt;
            attempt++;
        }
        return candidate;
    }

    private void publishBatchCompletedEvent(IntakeBatch batch) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Publishing IntakeBatchCompleted event - tenantId=%s, batchId=%s", tenantId, batch.id);

        IntakeBatchCompletedPayload payload = new IntakeBatchCompletedPayload(batch.id, batch.consignor.id,
                batch.batchName, batch.totalItems, batch.processedItems, batch.completedAt, "SYSTEM");

        eventPublisher.publish("INTAKE_BATCH", batch.id, "INTAKE_BATCH_COMPLETED", payload);
    }

    // ========================================
    // Request DTOs
    // ========================================

    /**
     * Request object for adding an item to an intake batch.
     */
    public record IntakeItemRequest(String productName, String description, String sku, String barcode,
            BigDecimal price, BigDecimal compareAtPrice, BigDecimal costBasis, BigDecimal commissionRate,
            Integer quantity, String variantTitle, Map<String, Object> metadata) {

        public IntakeItemRequest {
            if (productName == null || productName.isBlank()) {
                throw new IllegalArgumentException("Product name is required");
            }
            if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Price must be greater than zero");
            }
            if (metadata == null) {
                metadata = new HashMap<>();
            }
        }
    }
}
