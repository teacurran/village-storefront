package villagecompute.storefront.loyalty;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Domain projection describing loyalty context for a cart summary.
 *
 * <p>
 * Provides the data required by storefront/cart APIs to show available points, estimated earnings, and tier metadata
 * without leaking persistence-layer entities.
 * </p>
 */
public class CartLoyaltyProjection {

    private boolean programEnabled;
    private UUID programId;
    private Integer memberPointsBalance = 0;
    private Integer availablePointsBalance = 0; // Balance after active reservations
    private Integer reservedPoints = 0;
    private Integer estimatedPointsEarned = 0;
    private BigDecimal estimatedRewardValue = BigDecimal.ZERO;
    private BigDecimal availableRedemptionValue = BigDecimal.ZERO;
    private String currentTier;
    private BigDecimal redemptionValuePerPoint = BigDecimal.ZERO;
    private OffsetDateTime pointsExpirationWarning; // Next expiration date if points will expire soon
    private OffsetDateTime dataFreshnessTimestamp = OffsetDateTime.now();

    public boolean isProgramEnabled() {
        return programEnabled;
    }

    public void setProgramEnabled(boolean programEnabled) {
        this.programEnabled = programEnabled;
    }

    public UUID getProgramId() {
        return programId;
    }

    public void setProgramId(UUID programId) {
        this.programId = programId;
    }

    public Integer getMemberPointsBalance() {
        return memberPointsBalance;
    }

    public void setMemberPointsBalance(Integer memberPointsBalance) {
        this.memberPointsBalance = memberPointsBalance;
    }

    public Integer getAvailablePointsBalance() {
        return availablePointsBalance;
    }

    public void setAvailablePointsBalance(Integer availablePointsBalance) {
        this.availablePointsBalance = availablePointsBalance;
    }

    public Integer getReservedPoints() {
        return reservedPoints;
    }

    public void setReservedPoints(Integer reservedPoints) {
        this.reservedPoints = reservedPoints;
    }

    public Integer getEstimatedPointsEarned() {
        return estimatedPointsEarned;
    }

    public void setEstimatedPointsEarned(Integer estimatedPointsEarned) {
        this.estimatedPointsEarned = estimatedPointsEarned;
    }

    public BigDecimal getEstimatedRewardValue() {
        return estimatedRewardValue;
    }

    public void setEstimatedRewardValue(BigDecimal estimatedRewardValue) {
        this.estimatedRewardValue = estimatedRewardValue;
    }

    public BigDecimal getAvailableRedemptionValue() {
        return availableRedemptionValue;
    }

    public void setAvailableRedemptionValue(BigDecimal availableRedemptionValue) {
        this.availableRedemptionValue = availableRedemptionValue;
    }

    public String getCurrentTier() {
        return currentTier;
    }

    public void setCurrentTier(String currentTier) {
        this.currentTier = currentTier;
    }

    public BigDecimal getRedemptionValuePerPoint() {
        return redemptionValuePerPoint;
    }

    public void setRedemptionValuePerPoint(BigDecimal redemptionValuePerPoint) {
        this.redemptionValuePerPoint = redemptionValuePerPoint;
    }

    public OffsetDateTime getPointsExpirationWarning() {
        return pointsExpirationWarning;
    }

    public void setPointsExpirationWarning(OffsetDateTime pointsExpirationWarning) {
        this.pointsExpirationWarning = pointsExpirationWarning;
    }

    public OffsetDateTime getDataFreshnessTimestamp() {
        return dataFreshnessTimestamp;
    }

    public void setDataFreshnessTimestamp(OffsetDateTime dataFreshnessTimestamp) {
        this.dataFreshnessTimestamp = dataFreshnessTimestamp;
    }
}
