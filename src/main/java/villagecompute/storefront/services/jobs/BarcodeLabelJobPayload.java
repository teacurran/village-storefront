package villagecompute.storefront.services.jobs;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import villagecompute.storefront.data.models.InventoryTransfer;
import villagecompute.storefront.data.models.InventoryTransferLine;

/**
 * Payload representing a background job to generate barcode labels for an inventory transfer.
 *
 * <p>
 * The payload captures the transfer metadata and individual line items so the async worker can generate printable
 * labels without needing to access the primary database.
 */
public final class BarcodeLabelJobPayload {

    private final UUID jobId;
    private final UUID tenantId;
    private final UUID transferId;
    private final UUID sourceLocationId;
    private final UUID destinationLocationId;
    private final OffsetDateTime createdAt;
    private final List<JobLine> lines;

    private BarcodeLabelJobPayload(UUID jobId, UUID tenantId, UUID transferId, UUID sourceLocationId,
            UUID destinationLocationId, OffsetDateTime createdAt, List<JobLine> lines) {
        this.jobId = jobId;
        this.tenantId = tenantId;
        this.transferId = transferId;
        this.sourceLocationId = sourceLocationId;
        this.destinationLocationId = destinationLocationId;
        this.createdAt = createdAt;
        this.lines = lines;
    }

    public static BarcodeLabelJobPayload fromTransfer(UUID tenantId, InventoryTransfer transfer) {
        UUID jobId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.now();
        List<JobLine> lines = transfer.lines.stream().map(BarcodeLabelJobPayload::mapLine).collect(Collectors.toList());

        return new BarcodeLabelJobPayload(jobId, tenantId, transfer.id, transfer.sourceLocation.id,
                transfer.destinationLocation.id, createdAt, lines);
    }

    private static JobLine mapLine(InventoryTransferLine line) {
        UUID variantId = line.variant != null ? line.variant.id : null;
        String sku = line.variant != null ? line.variant.sku : null;
        Integer receivedQuantity = line.receivedQuantity;
        return new JobLine(variantId, sku, line.quantity, receivedQuantity);
    }

    public UUID getJobId() {
        return jobId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public UUID getSourceLocationId() {
        return sourceLocationId;
    }

    public UUID getDestinationLocationId() {
        return destinationLocationId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public List<JobLine> getLines() {
        return lines;
    }

    /**
     * Line level payload for label generation.
     */
    public static final class JobLine {

        private final UUID variantId;
        private final String sku;
        private final int requestedQuantity;
        private final Integer receivedQuantity;

        private JobLine(UUID variantId, String sku, int requestedQuantity, Integer receivedQuantity) {
            this.variantId = variantId;
            this.sku = sku;
            this.requestedQuantity = requestedQuantity;
            this.receivedQuantity = receivedQuantity;
        }

        public UUID getVariantId() {
            return variantId;
        }

        public String getSku() {
            return sku;
        }

        public int getRequestedQuantity() {
            return requestedQuantity;
        }

        public Integer getReceivedQuantity() {
            return receivedQuantity;
        }
    }
}
