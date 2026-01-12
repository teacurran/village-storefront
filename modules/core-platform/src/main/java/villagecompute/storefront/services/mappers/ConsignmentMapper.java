package villagecompute.storefront.services.mappers;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import villagecompute.storefront.api.types.CommissionScheduleDto;
import villagecompute.storefront.api.types.ConsignmentItemDto;
import villagecompute.storefront.api.types.ConsignorDto;
import villagecompute.storefront.api.types.Money;
import villagecompute.storefront.api.types.PayoutBatchDto;
import villagecompute.storefront.data.models.CommissionSchedule;
import villagecompute.storefront.data.models.ConsignmentItem;
import villagecompute.storefront.data.models.Consignor;
import villagecompute.storefront.data.models.PayoutBatch;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.util.PgCryptoUtil;

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

    @Inject
    PgCryptoUtil pgCryptoUtil;

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
        dto.setTaxIdMasked(maskTaxId(consignor));
        dto.setTaxIdLastRotated(consignor.taxIdLastRotated);
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
        if (item.variant != null) {
            dto.setVariantId(item.variant.id);
            dto.setVariantSku(item.variant.sku);
            dto.setVariantTitle(item.variant.name);
        }
        dto.setCommissionRate(item.commissionRate);
        dto.setStatus(item.status);
        dto.setSoldAt(item.soldAt);
        dto.setCostBasis(item.costBasis);
        dto.setIntakeBatchId(item.intakeBatchId);
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

    /**
     * Convert commission schedule entity to DTO.
     */
    public CommissionScheduleDto toDto(CommissionSchedule schedule) {
        CommissionScheduleDto dto = new CommissionScheduleDto();
        dto.setId(schedule.id);
        dto.setConsignorId(schedule.consignor != null ? schedule.consignor.id : null);
        dto.setCategoryId(schedule.categoryId);
        dto.setCommissionRate(schedule.commissionRate);
        dto.setEffectiveFrom(schedule.effectiveFrom);
        dto.setEffectiveUntil(schedule.effectiveUntil);
        dto.setPriority(schedule.priority);
        dto.setNotes(schedule.notes);
        dto.setMetadata(schedule.metadata);
        dto.setStatus(schedule.status);
        dto.setCreatedAt(schedule.createdAt);
        dto.setUpdatedAt(schedule.updatedAt);
        return dto;
    }

    private String maskTaxId(Consignor consignor) {
        if (consignor == null || consignor.taxIdEncrypted == null) {
            return null;
        }
        try {
            UUID tenantId = TenantContext.hasContext() ? TenantContext.getCurrentTenantId() : consignor.tenant.id;
            String decrypted = pgCryptoUtil.decrypt(consignor.taxIdEncrypted, tenantId,
                    consignor.taxIdKeyVersion != null ? consignor.taxIdKeyVersion
                            : pgCryptoUtil.getDefaultKeyVersion());
            if (decrypted == null || decrypted.length() < 4) {
                return "***";
            }
            String last4 = decrypted.substring(decrypted.length() - 4);
            return "****" + last4;
        } catch (Exception e) {
            return "****";
        }
    }
}
