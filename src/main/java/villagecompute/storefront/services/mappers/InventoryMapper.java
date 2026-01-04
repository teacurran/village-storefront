package villagecompute.storefront.services.mappers;

import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.api.types.InventoryAdjustmentDto;
import villagecompute.storefront.api.types.InventoryLocationDto;
import villagecompute.storefront.api.types.InventoryTransferDto;
import villagecompute.storefront.data.models.InventoryAdjustment;
import villagecompute.storefront.data.models.InventoryLocation;
import villagecompute.storefront.data.models.InventoryTransfer;

/**
 * Mapper for converting between Inventory entities and DTOs.
 *
 * <p>
 * Provides conversion methods to transform database entities into API response objects for multi-location inventory
 * operations.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Multi-location inventory workflow</li>
 * <li>OpenAPI: Inventory schemas</li>
 * </ul>
 */
@ApplicationScoped
public class InventoryMapper {

    /**
     * Convert InventoryLocation entity to DTO.
     *
     * @param location
     *            location entity
     * @return location DTO
     */
    public InventoryLocationDto toDto(InventoryLocation location) {
        InventoryLocationDto dto = new InventoryLocationDto();
        dto.setId(location.id);
        dto.setCode(location.code);
        dto.setName(location.name);
        dto.setType(location.type);
        dto.setAddress(location.address);
        dto.setActive(location.active);
        dto.setCreatedAt(location.createdAt);
        dto.setUpdatedAt(location.updatedAt);
        return dto;
    }

    /**
     * Convert InventoryTransfer entity to DTO.
     *
     * @param transfer
     *            transfer entity
     * @return transfer DTO
     */
    public InventoryTransferDto toDto(InventoryTransfer transfer) {
        InventoryTransferDto dto = new InventoryTransferDto();
        dto.setTransferId(transfer.id);
        dto.setSourceLocationId(transfer.sourceLocation.id);
        dto.setDestinationLocationId(transfer.destinationLocation.id);
        dto.setStatus(transfer.status.toString());
        dto.setInitiatedBy(transfer.initiatedBy);
        dto.setExpectedArrivalDate(transfer.expectedArrivalDate);
        dto.setCarrier(transfer.carrier);
        dto.setTrackingNumber(transfer.trackingNumber);
        dto.setShippingCost(transfer.shippingCost);
        dto.setNotes(transfer.notes);
        dto.setBarcodeJobId(transfer.barcodeJobId);
        dto.setCreatedAt(transfer.createdAt);
        dto.setUpdatedAt(transfer.updatedAt);

        // Map transfer lines
        dto.setLines(transfer.lines.stream().map(line -> {
            InventoryTransferDto.TransferLineDto lineDto = new InventoryTransferDto.TransferLineDto();
            lineDto.setVariantId(line.variant.id);
            lineDto.setQuantity(line.quantity);
            lineDto.setReceivedQuantity(line.receivedQuantity);
            lineDto.setNotes(line.notes);
            return lineDto;
        }).collect(Collectors.toList()));

        return dto;
    }

    /**
     * Convert InventoryAdjustment entity to DTO.
     *
     * @param adjustment
     *            adjustment entity
     * @return adjustment DTO
     */
    public InventoryAdjustmentDto toDto(InventoryAdjustment adjustment) {
        InventoryAdjustmentDto dto = new InventoryAdjustmentDto();
        dto.setId(adjustment.id);
        dto.setVariantId(adjustment.variant.id);
        dto.setLocationId(adjustment.location.id);
        dto.setQuantityChange(adjustment.quantityChange);
        dto.setQuantityBefore(adjustment.quantityBefore);
        dto.setQuantityAfter(adjustment.quantityAfter);
        dto.setReason(adjustment.reason.toString());
        dto.setAdjustedBy(adjustment.adjustedBy);
        dto.setNotes(adjustment.notes);
        dto.setCreatedAt(adjustment.createdAt);
        return dto;
    }
}
