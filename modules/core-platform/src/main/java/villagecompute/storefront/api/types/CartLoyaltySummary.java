package villagecompute.storefront.api.types;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Loyalty summary embedded in cart API responses.
 */
public class CartLoyaltySummary {

    @JsonProperty("programEnabled")
    private boolean programEnabled;

    @JsonProperty("programId")
    private UUID programId;

    @JsonProperty("memberPointsBalance")
    private Integer memberPointsBalance;

    @JsonProperty("availablePointsBalance")
    private Integer availablePointsBalance;

    @JsonProperty("reservedPoints")
    private Integer reservedPoints;

    @JsonProperty("estimatedPointsEarned")
    private Integer estimatedPointsEarned;

    @JsonProperty("estimatedRewardValue")
    private Money estimatedRewardValue;

    @JsonProperty("availableRedemptionValue")
    private Money availableRedemptionValue;

    @JsonProperty("redemptionValuePerPoint")
    private String redemptionValuePerPoint;

    @JsonProperty("currentTier")
    private String currentTier;

    @JsonProperty("dataFreshnessTimestamp")
    private OffsetDateTime dataFreshnessTimestamp;

    @JsonProperty("pointsExpirationWarning")
    private OffsetDateTime pointsExpirationWarning;

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

    public Money getEstimatedRewardValue() {
        return estimatedRewardValue;
    }

    public void setEstimatedRewardValue(Money estimatedRewardValue) {
        this.estimatedRewardValue = estimatedRewardValue;
    }

    public Money getAvailableRedemptionValue() {
        return availableRedemptionValue;
    }

    public void setAvailableRedemptionValue(Money availableRedemptionValue) {
        this.availableRedemptionValue = availableRedemptionValue;
    }

    public String getRedemptionValuePerPoint() {
        return redemptionValuePerPoint;
    }

    public void setRedemptionValuePerPoint(String redemptionValuePerPoint) {
        this.redemptionValuePerPoint = redemptionValuePerPoint;
    }

    public String getCurrentTier() {
        return currentTier;
    }

    public void setCurrentTier(String currentTier) {
        this.currentTier = currentTier;
    }

    public OffsetDateTime getDataFreshnessTimestamp() {
        return dataFreshnessTimestamp;
    }

    public void setDataFreshnessTimestamp(OffsetDateTime dataFreshnessTimestamp) {
        this.dataFreshnessTimestamp = dataFreshnessTimestamp;
    }

    public OffsetDateTime getPointsExpirationWarning() {
        return pointsExpirationWarning;
    }

    public void setPointsExpirationWarning(OffsetDateTime pointsExpirationWarning) {
        this.pointsExpirationWarning = pointsExpirationWarning;
    }
}
