package villagecompute.storefront.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Marketplace and platform payment capabilities for multi-party transactions. Handles Connect-style onboarding, split
 * payments, platform fees, and payouts.
 *
 * Used for consignment scenarios where platform takes fees and routes funds to vendors.
 */
public interface MarketplaceProvider {

    /**
     * Begin onboarding flow for a connected account (consignor, vendor).
     *
     * @param request
     *            Onboarding request parameters
     * @return Onboarding result with URL and account ID
     */
    OnboardingResult beginOnboarding(OnboardingRequest request);

    /**
     * Retrieve onboarding status for a connected account.
     *
     * @param connectedAccountId
     *            Provider-specific connected account identifier
     * @return Current onboarding status
     */
    OnboardingStatus getOnboardingStatus(String connectedAccountId);

    /**
     * Calculate platform fees for a transaction amount based on tenant configuration.
     *
     * @param tenantId
     *            Tenant identifier
     * @param transactionAmount
     *            Gross transaction amount
     * @return Platform fee breakdown
     */
    PlatformFeeCalculation calculatePlatformFee(UUID tenantId, BigDecimal transactionAmount);

    /**
     * Create a payout to a connected account.
     *
     * @param request
     *            Payout request parameters
     * @return Payout result with transaction ID
     */
    PayoutResult createPayout(PayoutRequest request);

    /**
     * Retrieve payout status from provider.
     *
     * @param payoutId
     *            Provider-specific payout identifier
     * @return Current payout status
     */
    PayoutStatus getPayoutStatus(String payoutId);

    /**
     * Handle a dispute or chargeback for a marketplace transaction.
     *
     * @param disputeId
     *            Provider-specific dispute identifier
     * @return Dispute information
     */
    DisputeInfo getDisputeInfo(String disputeId);

    /**
     * Onboarding request for connected accounts.
     */
    record OnboardingRequest(String email, String businessName, String country, OnboardingType type, String returnUrl, // URL
                                                                                                                       // to
                                                                                                                       // redirect
                                                                                                                       // after
                                                                                                                       // onboarding
            String refreshUrl, // URL to redirect if onboarding link expires
            Map<String, String> metadata) {
    }

    /**
     * Onboarding result.
     */
    record OnboardingResult(String connectedAccountId, String onboardingUrl, // URL to send user to complete onboarding
            OnboardingStatus status) {
    }

    /**
     * Onboarding status enumeration.
     */
    enum OnboardingStatus {
        PENDING, IN_PROGRESS, COMPLETED, RESTRICTED, // Account has restrictions
        DISABLED
    }

    /**
     * Onboarding type.
     */
    enum OnboardingType {
        STANDARD, // Full account onboarding
        EXPRESS // Simplified onboarding
    }

    /**
     * Platform fee calculation result.
     */
    record PlatformFeeCalculation(BigDecimal transactionAmount, BigDecimal platformFeeAmount,
            BigDecimal platformFeePercentage, BigDecimal netAmount, // Amount after fees
            String calculationNote) {
    }

    /**
     * Payout request parameters.
     */
    record PayoutRequest(String connectedAccountId, BigDecimal amount, String currency, String description,
            Map<String, String> metadata, String idempotencyKey) {
    }

    /**
     * Payout result.
     */
    record PayoutResult(String payoutId, BigDecimal amount, PayoutStatus status, Instant estimatedArrival) {
    }

    /**
     * Payout status.
     */
    enum PayoutStatus {
        PENDING, IN_TRANSIT, PAID, FAILED, CANCELLED
    }

    /**
     * Dispute information.
     */
    record DisputeInfo(String disputeId, String paymentIntentId, BigDecimal amount, DisputeReason reason,
            DisputeStatus status, Instant created) {
    }

    /**
     * Dispute reason.
     */
    enum DisputeReason {
        FRAUDULENT, DUPLICATE, PRODUCT_NOT_RECEIVED, PRODUCT_UNACCEPTABLE, SUBSCRIPTION_CANCELLED, UNRECOGNIZED, CREDIT_NOT_PROCESSED, GENERAL
    }

    /**
     * Dispute lifecycle status.
     */
    enum DisputeStatus {
        WARNING_NEEDS_RESPONSE, WARNING_UNDER_REVIEW, WARNING_CLOSED, NEEDS_RESPONSE, UNDER_REVIEW, CHARGE_REFUNDED, WON, LOST
    }
}
