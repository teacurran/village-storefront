package villagecompute.storefront.loyalty;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.api.types.LoyaltyMemberDto;
import villagecompute.storefront.api.types.LoyaltyProgramDto;
import villagecompute.storefront.api.types.LoyaltyTransactionDto;
import villagecompute.storefront.data.models.LoyaltyMember;
import villagecompute.storefront.data.models.LoyaltyProgram;
import villagecompute.storefront.data.models.LoyaltyTransaction;

/**
 * Mapper for converting loyalty entities to DTOs.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T4: Loyalty DTO mapping</li>
 * </ul>
 */
@ApplicationScoped
public class LoyaltyMapper {

    public LoyaltyProgramDto toDto(LoyaltyProgram program) {
        if (program == null) {
            return null;
        }

        LoyaltyProgramDto dto = new LoyaltyProgramDto();
        dto.id = program.id;
        dto.name = program.name;
        dto.description = program.description;
        dto.enabled = program.enabled;
        dto.pointsPerDollar = program.pointsPerDollar;
        dto.redemptionValuePerPoint = program.redemptionValuePerPoint;
        dto.minRedemptionPoints = program.minRedemptionPoints;
        dto.maxRedemptionPoints = program.maxRedemptionPoints;
        dto.pointsExpirationDays = program.pointsExpirationDays;
        dto.tierConfig = program.tierConfig;
        dto.createdAt = program.createdAt;
        dto.updatedAt = program.updatedAt;
        return dto;
    }

    public LoyaltyMemberDto toDto(LoyaltyMember member) {
        if (member == null) {
            return null;
        }

        LoyaltyMemberDto dto = new LoyaltyMemberDto();
        dto.id = member.id;
        dto.userId = member.user != null ? member.user.id : null;
        dto.programId = member.program != null ? member.program.id : null;
        dto.pointsBalance = member.pointsBalance;
        dto.lifetimePointsEarned = member.lifetimePointsEarned;
        dto.lifetimePointsRedeemed = member.lifetimePointsRedeemed;
        dto.currentTier = member.currentTier;
        dto.tierUpdatedAt = member.tierUpdatedAt;
        dto.status = member.status;
        dto.enrolledAt = member.enrolledAt;
        return dto;
    }

    public LoyaltyTransactionDto toDto(LoyaltyTransaction transaction) {
        if (transaction == null) {
            return null;
        }

        LoyaltyTransactionDto dto = new LoyaltyTransactionDto();
        dto.id = transaction.id;
        dto.memberId = transaction.member != null ? transaction.member.id : null;
        dto.orderId = transaction.orderId;
        dto.pointsDelta = transaction.pointsDelta;
        dto.balanceAfter = transaction.balanceAfter;
        dto.transactionType = transaction.transactionType;
        dto.reason = transaction.reason;
        dto.source = transaction.source;
        dto.expiresAt = transaction.expiresAt;
        dto.createdAt = transaction.createdAt;
        return dto;
    }
}
