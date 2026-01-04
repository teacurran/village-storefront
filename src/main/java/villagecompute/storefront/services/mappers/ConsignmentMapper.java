package villagecompute.storefront.services.mappers;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.api.types.ConsignmentItemDto;
import villagecompute.storefront.api.types.ConsignorDto;
import villagecompute.storefront.api.types.Money;
import villagecompute.storefront.api.types.PayoutBatchDto;
import villagecompute.storefront.data.models.ConsignmentItem;
import villagecompute.storefront.data.models.Consignor;
import villagecompute.storefront.data.models.PayoutBatch;

/**
 * Mapper for converting between Consignment entities and DTOs.
 *
 * <p>
 * Provides conversion methods to transform database entities into API response objects. Handles money formatting and
 * nested object mapping.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T1: Consignment domain DTO mappers</li>
 * <li>OpenAPI: Consignment schemas</li>
 * </ul>
 */
@ApplicationScoped
public class ConsignmentMapper {

    private static final String DEFAULT_CURRENCY = "USD";

    /**
     * Convert Consignor entity to DTO.
     *
     * @param consignor
     *            consignor entity
     * @return consignor DTO
     */
    public ConsignorDto toDto(Consignor consignor) {
        ConsignorDto dto = new ConsignorDto();
        dto.setId(consignor.id);
        dto.setName(consignor.name);
        dto.setContactInfo(consignor.contactInfo);
        dto.setPayoutSettings(consignor.payoutSettings);
        dto.setStatus(consignor.status);
        dto.setCreatedAt(consignor.createdAt);
        dto.setUpdatedAt(consignor.updatedAt);
        return dto;
    }

    /**
     * Convert ConsignmentItem entity to DTO.
     *
     * @param item
     *            consignment item entity
     * @return consignment item DTO
     */
    public ConsignmentItemDto toDto(ConsignmentItem item) {
        ConsignmentItemDto dto = new ConsignmentItemDto();
        dto.setId(item.id);
        dto.setProductId(item.product.id);
        dto.setProductName(item.product.name);
        dto.setConsignorId(item.consignor.id);
        dto.setConsignorName(item.consignor.name);
        dto.setCommissionRate(item.commissionRate);
        dto.setStatus(item.status);
        dto.setSoldAt(item.soldAt);
        dto.setCreatedAt(item.createdAt);
        dto.setUpdatedAt(item.updatedAt);
        return dto;
    }

    /**
     * Convert PayoutBatch entity to DTO.
     *
     * @param batch
     *            payout batch entity
     * @return payout batch DTO
     */
    public PayoutBatchDto toDto(PayoutBatch batch) {
        PayoutBatchDto dto = new PayoutBatchDto();
        dto.setId(batch.id);
        dto.setConsignorId(batch.consignor.id);
        dto.setConsignorName(batch.consignor.name);
        dto.setPeriodStart(batch.periodStart);
        dto.setPeriodEnd(batch.periodEnd);
        dto.setTotalAmount(new Money(batch.totalAmount, batch.currency));
        dto.setStatus(batch.status);
        dto.setProcessedAt(batch.processedAt);
        dto.setPaymentReference(batch.paymentReference);
        dto.setCreatedAt(batch.createdAt);
        dto.setUpdatedAt(batch.updatedAt);
        return dto;
    }
}
