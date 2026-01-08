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

    @JsonProperty("estimatedPointsEarned")
    private Integer estimatedPointsEarned;

    @JsonProperty("estimatedRewardValue")
    private Money estimatedRewardValue;

    @JsonProperty("availableRedemptionValue")
    private Money availableRedemptionValue;

    @JsonProperty("currentTier")
    private String currentTier;

    @JsonProperty("dataFreshnessTimestamp")
    private OffsetDateTime dataFreshnessTimestamp;

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
}
