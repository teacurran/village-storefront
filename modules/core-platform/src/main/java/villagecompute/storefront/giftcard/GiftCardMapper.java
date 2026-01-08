package villagecompute.storefront.giftcard;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.api.types.GiftCardBalanceResponse;
import villagecompute.storefront.api.types.GiftCardDto;
import villagecompute.storefront.api.types.GiftCardTransactionDto;
import villagecompute.storefront.data.models.GiftCard;
import villagecompute.storefront.data.models.GiftCardTransaction;

/**
 * Mapper utilities for converting gift card entities into API DTOs.
 */
@ApplicationScoped
public class GiftCardMapper {

    public GiftCardDto toDto(GiftCard entity) {
        GiftCardDto dto = new GiftCardDto();
        dto.id = entity.id;
        dto.code = entity.code;
        dto.initialBalance = entity.initialBalance;
        dto.currentBalance = entity.currentBalance;
        dto.currency = entity.currency;
        dto.status = entity.status;
        dto.purchaserUserId = entity.purchaserUser != null ? entity.purchaserUser.id : null;
        dto.purchaserEmail = entity.purchaserEmail;
        dto.recipientEmail = entity.recipientEmail;
        dto.recipientName = entity.recipientName;
        dto.personalMessage = entity.personalMessage;
        dto.issuedAt = entity.issuedAt;
        dto.activatedAt = entity.activatedAt;
        dto.expiresAt = entity.expiresAt;
        dto.fullyRedeemedAt = entity.fullyRedeemedAt;
        dto.sourceOrderId = entity.sourceOrderId;
        dto.createdAt = entity.createdAt;
        dto.updatedAt = entity.updatedAt;
        return dto;
    }

    public GiftCardTransactionDto toDto(GiftCardTransaction entity) {
        GiftCardTransactionDto dto = new GiftCardTransactionDto();
        dto.id = entity.id;
        dto.giftCardId = entity.giftCard != null ? entity.giftCard.id : null;
        dto.orderId = entity.orderId;
        dto.amount = entity.amount;
        dto.transactionType = entity.transactionType;
        dto.balanceAfter = entity.balanceAfter;
        dto.reason = entity.reason;
        dto.posDeviceId = entity.posDeviceId;
        dto.createdAt = entity.createdAt;
        return dto;
    }

    public GiftCardBalanceResponse toBalanceResponse(GiftCard card) {
        GiftCardBalanceResponse response = new GiftCardBalanceResponse();
        response.currentBalance = card.currentBalance;
        response.currency = card.currency;
        response.status = card.status;
        response.expiresAt = card.expiresAt;
        return response;
    }
}
