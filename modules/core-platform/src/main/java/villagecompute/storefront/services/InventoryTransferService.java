package villagecompute.storefront.services;

import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.AdjustmentReason;
import villagecompute.storefront.data.models.InventoryAdjustment;
import villagecompute.storefront.data.models.InventoryLevel;
import villagecompute.storefront.data.models.InventoryLocation;
import villagecompute.storefront.data.models.InventoryTransfer;
import villagecompute.storefront.data.models.InventoryTransferLine;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.data.models.TransferStatus;
import villagecompute.storefront.data.repositories.InventoryAdjustmentRepository;
import villagecompute.storefront.data.repositories.InventoryLevelRepository;
import villagecompute.storefront.data.repositories.InventoryLocationRepository;
import villagecompute.storefront.data.repositories.InventoryTransferRepository;
import villagecompute.storefront.data.repositories.ProductVariantRepository;
import villagecompute.storefront.services.jobs.BarcodeLabelJobPayload;
import villagecompute.storefront.services.jobs.BarcodeLabelJobQueue;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Service layer for multi-location inventory transfers and adjustments.
 *
 * <p>
 * Manages inventory transfers between locations, manual adjustments with reason codes, and coordinates background jobs
 * for barcode label generation. All operations are tenant-scoped with comprehensive logging and metrics.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Multi-location inventory workflow</li>
 * <li>Architecture: Multi-location communication patterns</li>
 * <li>Behavior: Inventory transfer DTO contract</li>
 * </ul>
 */
@ApplicationScoped
public class InventoryTransferService {

    private static final Logger LOG = Logger.getLogger(InventoryTransferService.class);

    @Inject
    InventoryLocationRepository locationRepository;

    @Inject
    InventoryTransferRepository transferRepository;

    @Inject
    InventoryLevelRepository inventoryLevelRepository;

    @Inject
    InventoryAdjustmentRepository adjustmentRepository;

    @Inject
    ProductVariantRepository productVariantRepository;

    @Inject
    InventoryService inventoryService;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    BarcodeLabelJobQueue barcodeLabelJobQueue;

    /**
     * Create a new inventory transfer between locations.
     *
     * @param transfer
     *            transfer entity with source, destination, and line items
     * @return persisted transfer with generated ID
     */
    @Transactional
    public InventoryTransfer createTransfer(InventoryTransfer transfer) {
        UUID tenantId = TenantContext.getCurrentTenantId();

        if (transfer == null) {
            throw new IllegalArgumentException("Transfer details are required");
        }

        // Validate source and destination locations
        if (transfer.sourceLocation == null || transfer.destinationLocation == null) {
            throw new IllegalArgumentException("Source and destination locations are required");
        }

        if (transfer.sourceLocation.id.equals(transfer.destinationLocation.id)) {
            throw new IllegalArgumentException("Source and destination locations cannot be the same");
        }

        InventoryLocation source = locationRepository.findByIdForTenant(transfer.sourceLocation.id)
                .orElseThrow(() -> new InvalidLocationException(transfer.sourceLocation.id));
        InventoryLocation destination = locationRepository.findByIdForTenant(transfer.destinationLocation.id)
                .orElseThrow(() -> new InvalidLocationException(transfer.destinationLocation.id));

        if (Boolean.FALSE.equals(source.active)) {
            throw new IllegalStateException("Source location is inactive: " + source.code);
        }
        if (Boolean.FALSE.equals(destination.active)) {
            throw new IllegalStateException("Destination location is inactive: " + destination.code);
        }

        transfer.sourceLocation = source;
        transfer.destinationLocation = destination;

        if (transfer.lines == null || transfer.lines.isEmpty()) {
            throw new IllegalArgumentException("At least one transfer line is required");
        }

        LOG.infof(
                "Creating inventory transfer - tenantId=%s, sourceLocationId=%s, destinationLocationId=%s, lineCount=%d",
                tenantId, source.id, destination.id, transfer.lines.size());

        // Validate and reserve inventory at source for each line
        for (InventoryTransferLine line : transfer.lines) {
            if (line.quantity == null || line.quantity <= 0) {
                throw new IllegalArgumentException("Transfer quantity must be positive");
            }

            if (line.variant == null || line.variant.id == null) {
                throw new IllegalArgumentException("Variant is required for each transfer line");
            }

            ProductVariant variant = productVariantRepository.findByIdForTenant(line.variant.id)
                    .orElseThrow(() -> new IllegalArgumentException("Variant not found: " + line.variant.id));
            if (!"active".equalsIgnoreCase(variant.status)) {
                throw new IllegalStateException("Variant is not active: " + variant.id);
            }
            line.variant = variant;

            // Check availability at source
            InventoryLevel sourceLevel = inventoryLevelRepository.findByVariantAndLocation(variant.id, source.code)
                    .orElseThrow(() -> new InsufficientStockException(line.variant.id, source.code, 0, line.quantity));

            int available = sourceLevel.getAvailableQuantity();
            if (available < line.quantity) {
                throw new InsufficientStockException(line.variant.id, source.code, available, line.quantity);
            }

            // Reserve inventory at source
            inventoryService.reserveInventory(line.variant.id, source.code, line.quantity);

            LOG.infof("Reserved inventory for transfer - tenantId=%s, variantId=%s, location=%s, quantity=%d", tenantId,
                    line.variant.id, source.code, line.quantity);
        }

        // Persist transfer
        transferRepository.persist(transfer);

        // Emit metrics
        meterRegistry.counter("inventory.transfer.started", "tenant_id", tenantId.toString(), "source_location",
                source.code, "destination_location", destination.code).increment();

        // Enqueue barcode label generation job (stub for now)
        UUID jobId = enqueueBarcodeJob(transfer);
        transfer.barcodeJobId = jobId;

        LOG.infof("Transfer created - tenantId=%s, transferId=%s, jobId=%s", tenantId, transfer.id, jobId);

        return transfer;
    }

    /**
     * Complete a transfer by marking it received and updating destination inventory.
     *
     * @param transferId
     *            transfer UUID
     * @return updated transfer
     */
    @Transactional
    public InventoryTransfer receiveTransfer(UUID transferId) {
        UUID tenantId = TenantContext.getCurrentTenantId();

        InventoryTransfer transfer = transferRepository.findByIdForTenant(transferId)
                .orElseThrow(() -> new IllegalArgumentException("Transfer not found: " + transferId));

        if (transfer.status == TransferStatus.RECEIVED) {
            throw new IllegalStateException("Transfer already received");
        }
        if (transfer.status == TransferStatus.CANCELLED) {
            throw new IllegalStateException("Cancelled transfers cannot be received");
        }

        LOG.infof("Receiving transfer - tenantId=%s, transferId=%s", tenantId, transferId);

        String sourceCode = transfer.sourceLocation.code;
        String destCode = transfer.destinationLocation.code;

        // Process each line
        for (InventoryTransferLine line : transfer.lines) {
            int quantityReceived = line.receivedQuantity != null ? line.receivedQuantity : line.quantity;

            // Commit reservation at source (decreases both quantity and reserved)
            inventoryService.commitReservation(line.variant.id, sourceCode, line.quantity);

            // Ensure destination inventory level exists before adjustment
            ensureInventoryLevelExists(line.variant, destCode);

            // Add inventory at destination
            inventoryService.adjustInventory(line.variant.id, destCode, quantityReceived);

            LOG.infof("Transferred inventory - tenantId=%s, variantId=%s, from=%s, to=%s, quantity=%d", tenantId,
                    line.variant.id, sourceCode, destCode, quantityReceived);
        }

        transfer.status = TransferStatus.RECEIVED;
        transferRepository.persist(transfer);

        meterRegistry.counter("inventory.transfer.completed", "tenant_id", tenantId.toString(), "source_location",
                sourceCode, "destination_location", destCode).increment();

        LOG.infof("Transfer received - tenantId=%s, transferId=%s", tenantId, transferId);

        return transfer;
    }

    /**
     * Record a manual inventory adjustment with reason code.
     *
     * @param variantId
     *            variant UUID
     * @param locationId
     *            location UUID
     * @param quantityChange
     *            quantity change (positive or negative)
     * @param reason
     *            adjustment reason
     * @param adjustedBy
     *            user performing adjustment
     * @param notes
     *            optional notes
     * @return persisted adjustment record
     */
    @Transactional
    public InventoryAdjustment recordAdjustment(UUID variantId, UUID locationId, int quantityChange,
            AdjustmentReason reason, String adjustedBy, String notes) {
        UUID tenantId = TenantContext.getCurrentTenantId();

        InventoryLocation location = locationRepository.findByIdForTenant(locationId)
                .orElseThrow(() -> new InvalidLocationException(locationId));
        if (Boolean.FALSE.equals(location.active)) {
            throw new IllegalStateException("Location is inactive: " + location.code);
        }

        ProductVariant variant = productVariantRepository.findByIdForTenant(variantId)
                .orElseThrow(() -> new IllegalArgumentException("Variant not found: " + variantId));

        LOG.infof("Recording inventory adjustment - tenantId=%s, variantId=%s, locationId=%s, change=%d, reason=%s",
                tenantId, variantId, locationId, quantityChange, reason);

        // Get current inventory level
        InventoryLevel level = inventoryLevelRepository.findByVariantAndLocation(variantId, location.code)
                .orElseGet(() -> {
                    // Create new level if doesn't exist
                    InventoryLevel newLevel = new InventoryLevel();
                    newLevel.variant = variant;
                    newLevel.location = location.code;
                    newLevel.quantity = 0;
                    newLevel.reserved = 0;
                    inventoryLevelRepository.persist(newLevel);
                    return newLevel;
                });

        int quantityBefore = level.quantity;

        // Apply adjustment
        inventoryService.adjustInventory(variantId, location.code, quantityChange);

        // Refresh to get updated quantity
        level = inventoryLevelRepository.findByVariantAndLocation(variantId, location.code).get();
        int quantityAfter = level.quantity;

        // Create audit record
        InventoryAdjustment adjustment = new InventoryAdjustment();
        adjustment.variant = variant;
        adjustment.location = location;
        adjustment.quantityChange = quantityChange;
        adjustment.quantityBefore = quantityBefore;
        adjustment.quantityAfter = quantityAfter;
        adjustment.reason = reason;
        adjustment.adjustedBy = adjustedBy;
        adjustment.notes = notes;

        adjustmentRepository.persist(adjustment);

        meterRegistry.counter("inventory.adjustment.count", "tenant_id", tenantId.toString(), "location", location.code,
                "reason", reason.toString()).increment();

        LOG.infof("Adjustment recorded - tenantId=%s, adjustmentId=%s, before=%d, after=%d", tenantId, adjustment.id,
                quantityBefore, quantityAfter);

        return adjustment;
    }

    /**
     * Get all transfers for current tenant.
     *
     * @return list of transfers
     */
    public List<InventoryTransfer> getAllTransfers() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Fetching all transfers - tenantId=%s", tenantId);
        return transferRepository.findAllForTenant();
    }

    /**
     * Get transfer by ID.
     *
     * @param transferId
     *            transfer UUID
     * @return transfer if found
     */
    public InventoryTransfer getTransfer(UUID transferId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Fetching transfer - tenantId=%s, transferId=%s", tenantId, transferId);

        return transferRepository.findByIdForTenant(transferId)
                .orElseThrow(() -> new IllegalArgumentException("Transfer not found: " + transferId));
    }

    /**
     * Enqueue background job for barcode label generation via {@link BarcodeLabelJobQueue}.
     *
     * @param transfer
     *            transfer requiring labels
     * @return job ID
     */
    private UUID enqueueBarcodeJob(InventoryTransfer transfer) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        BarcodeLabelJobPayload payload = BarcodeLabelJobPayload.fromTransfer(tenantId, transfer);
        UUID jobId = barcodeLabelJobQueue.enqueue(payload);

        LOG.infof("Enqueued barcode job - tenantId=%s, transferId=%s, jobId=%s, queueDepth=%d", tenantId, transfer.id,
                jobId, barcodeLabelJobQueue.getQueueDepth());

        return jobId;
    }

    private void ensureInventoryLevelExists(ProductVariant variant, String locationCode) {
        inventoryLevelRepository.findByVariantAndLocation(variant.id, locationCode).orElseGet(() -> {
            InventoryLevel newLevel = new InventoryLevel();
            newLevel.variant = variant;
            newLevel.location = locationCode;
            newLevel.quantity = 0;
            newLevel.reserved = 0;
            inventoryLevelRepository.persist(newLevel);
            LOG.infof("Created inventory level for destination - tenantId=%s, variantId=%s, location=%s",
                    TenantContext.getCurrentTenantId(), variant.id, locationCode);
            return newLevel;
        });
    }
}
