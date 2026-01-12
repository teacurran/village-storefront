package villagecompute.storefront.api.types;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * VendorDashboardDto aggregates all consignor portal dashboard data.
 *
 * <p>
 * Provides a comprehensive view of consignor balances, payout history, item summaries, notifications, and Stripe
 * Express onboarding status.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T2: Consignment vendor portal</li>
 * <li>Endpoint: GET /api/v1/vendor/portal/dashboard</li>
 * <li>Architecture §3.2.6: Consignment Module</li>
 * </ul>
 */
public class VendorDashboardDto {

    @JsonProperty("consignorId")
    private UUID consignorId;

    @JsonProperty("consignorName")
    private String consignorName;

    @JsonProperty("consignorStatus")
    private String consignorStatus;

    @JsonProperty("balances")
    private BalanceInfo balances;

    @JsonProperty("payoutSummary")
    private PayoutSummary payoutSummary;

    @JsonProperty("itemSummary")
    private ItemSummary itemSummary;

    @JsonProperty("stripeConnect")
    private StripeConnectInfo stripeConnect;

    @JsonProperty("notificationSummary")
    private NotificationSummary notificationSummary;

    @JsonProperty("lastUpdated")
    private OffsetDateTime lastUpdated;

    // Getters and setters

    public UUID getConsignorId() {
        return consignorId;
    }

    public void setConsignorId(UUID consignorId) {
        this.consignorId = consignorId;
    }

    public String getConsignorName() {
        return consignorName;
    }

    public void setConsignorName(String consignorName) {
        this.consignorName = consignorName;
    }

    public String getConsignorStatus() {
        return consignorStatus;
    }

    public void setConsignorStatus(String consignorStatus) {
        this.consignorStatus = consignorStatus;
    }

    public BalanceInfo getBalances() {
        return balances;
    }

    public void setBalances(BalanceInfo balances) {
        this.balances = balances;
    }

    public PayoutSummary getPayoutSummary() {
        return payoutSummary;
    }

    public void setPayoutSummary(PayoutSummary payoutSummary) {
        this.payoutSummary = payoutSummary;
    }

    public ItemSummary getItemSummary() {
        return itemSummary;
    }

    public void setItemSummary(ItemSummary itemSummary) {
        this.itemSummary = itemSummary;
    }

    public StripeConnectInfo getStripeConnect() {
        return stripeConnect;
    }

    public void setStripeConnect(StripeConnectInfo stripeConnect) {
        this.stripeConnect = stripeConnect;
    }

    public NotificationSummary getNotificationSummary() {
        return notificationSummary;
    }

    public void setNotificationSummary(NotificationSummary notificationSummary) {
        this.notificationSummary = notificationSummary;
    }

    public OffsetDateTime getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(OffsetDateTime lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    /**
     * Nested DTO for balance information.
     */
    public static class BalanceInfo {

        @JsonProperty("pendingBalance")
        private BigDecimal pendingBalance;

        @JsonProperty("availableBalance")
        private BigDecimal availableBalance;

        @JsonProperty("totalEarnings")
        private BigDecimal totalEarnings;

        @JsonProperty("currency")
        private String currency;

        @JsonProperty("nextSettlementDate")
        private LocalDate nextSettlementDate;

        // Getters and setters

        public BigDecimal getPendingBalance() {
            return pendingBalance;
        }

        public void setPendingBalance(BigDecimal pendingBalance) {
            this.pendingBalance = pendingBalance;
        }

        public BigDecimal getAvailableBalance() {
            return availableBalance;
        }

        public void setAvailableBalance(BigDecimal availableBalance) {
            this.availableBalance = availableBalance;
        }

        public BigDecimal getTotalEarnings() {
            return totalEarnings;
        }

        public void setTotalEarnings(BigDecimal totalEarnings) {
            this.totalEarnings = totalEarnings;
        }

        public String getCurrency() {
            return currency;
        }

        public void setCurrency(String currency) {
            this.currency = currency;
        }

        public LocalDate getNextSettlementDate() {
            return nextSettlementDate;
        }

        public void setNextSettlementDate(LocalDate nextSettlementDate) {
            this.nextSettlementDate = nextSettlementDate;
        }
    }

    /**
     * Nested DTO for payout summary.
     */
    public static class PayoutSummary {

        @JsonProperty("recentPayouts")
        private List<PayoutBatchDto> recentPayouts;

        @JsonProperty("lastPayoutDate")
        private OffsetDateTime lastPayoutDate;

        @JsonProperty("lastPayoutAmount")
        private BigDecimal lastPayoutAmount;

        @JsonProperty("nextPayoutSchedule")
        private String nextPayoutSchedule;

        // Getters and setters

        public List<PayoutBatchDto> getRecentPayouts() {
            return recentPayouts;
        }

        public void setRecentPayouts(List<PayoutBatchDto> recentPayouts) {
            this.recentPayouts = recentPayouts;
        }

        public OffsetDateTime getLastPayoutDate() {
            return lastPayoutDate;
        }

        public void setLastPayoutDate(OffsetDateTime lastPayoutDate) {
            this.lastPayoutDate = lastPayoutDate;
        }

        public BigDecimal getLastPayoutAmount() {
            return lastPayoutAmount;
        }

        public void setLastPayoutAmount(BigDecimal lastPayoutAmount) {
            this.lastPayoutAmount = lastPayoutAmount;
        }

        public String getNextPayoutSchedule() {
            return nextPayoutSchedule;
        }

        public void setNextPayoutSchedule(String nextPayoutSchedule) {
            this.nextPayoutSchedule = nextPayoutSchedule;
        }
    }

    /**
     * Nested DTO for item summary.
     */
    public static class ItemSummary {

        @JsonProperty("activeCount")
        private int activeCount;

        @JsonProperty("soldThisMonth")
        private int soldThisMonth;

        @JsonProperty("totalSold")
        private int totalSold;

        @JsonProperty("recentItems")
        private List<ConsignmentItemDto> recentItems;

        // Getters and setters

        public int getActiveCount() {
            return activeCount;
        }

        public void setActiveCount(int activeCount) {
            this.activeCount = activeCount;
        }

        public int getSoldThisMonth() {
            return soldThisMonth;
        }

        public void setSoldThisMonth(int soldThisMonth) {
            this.soldThisMonth = soldThisMonth;
        }

        public int getTotalSold() {
            return totalSold;
        }

        public void setTotalSold(int totalSold) {
            this.totalSold = totalSold;
        }

        public List<ConsignmentItemDto> getRecentItems() {
            return recentItems;
        }

        public void setRecentItems(List<ConsignmentItemDto> recentItems) {
            this.recentItems = recentItems;
        }
    }

    /**
     * Nested DTO for Stripe Express account status and onboarding information.
     */
    public static class StripeConnectInfo {

        @JsonProperty("accountId")
        private String accountId;

        @JsonProperty("chargesEnabled")
        private boolean chargesEnabled;

        @JsonProperty("payoutsEnabled")
        private boolean payoutsEnabled;

        @JsonProperty("detailsSubmitted")
        private boolean detailsSubmitted;

        @JsonProperty("onboardingUrl")
        private String onboardingUrl;

        @JsonProperty("requiresOnboarding")
        private boolean requiresOnboarding;

        @JsonProperty("onboardingMessage")
        private String onboardingMessage;

        // Getters and setters

        public String getAccountId() {
            return accountId;
        }

        public void setAccountId(String accountId) {
            this.accountId = accountId;
        }

        public boolean isChargesEnabled() {
            return chargesEnabled;
        }

        public void setChargesEnabled(boolean chargesEnabled) {
            this.chargesEnabled = chargesEnabled;
        }

        public boolean isPayoutsEnabled() {
            return payoutsEnabled;
        }

        public void setPayoutsEnabled(boolean payoutsEnabled) {
            this.payoutsEnabled = payoutsEnabled;
        }

        public boolean isDetailsSubmitted() {
            return detailsSubmitted;
        }

        public void setDetailsSubmitted(boolean detailsSubmitted) {
            this.detailsSubmitted = detailsSubmitted;
        }

        public String getOnboardingUrl() {
            return onboardingUrl;
        }

        public void setOnboardingUrl(String onboardingUrl) {
            this.onboardingUrl = onboardingUrl;
        }

        public boolean isRequiresOnboarding() {
            return requiresOnboarding;
        }

        public void setRequiresOnboarding(boolean requiresOnboarding) {
            this.requiresOnboarding = requiresOnboarding;
        }

        public String getOnboardingMessage() {
            return onboardingMessage;
        }

        public void setOnboardingMessage(String onboardingMessage) {
            this.onboardingMessage = onboardingMessage;
        }
    }

    /**
     * Nested DTO for notification summary.
     */
    public static class NotificationSummary {

        @JsonProperty("unreadCount")
        private int unreadCount;

        @JsonProperty("recentNotifications")
        private List<String> recentNotifications;

        // Getters and setters

        public int getUnreadCount() {
            return unreadCount;
        }

        public void setUnreadCount(int unreadCount) {
            this.unreadCount = unreadCount;
        }

        public List<String> getRecentNotifications() {
            return recentNotifications;
        }

        public void setRecentNotifications(List<String> recentNotifications) {
            this.recentNotifications = recentNotifications;
        }
    }
}
