package villagecompute.storefront.payment.stripe;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.Payout;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.PayoutCreateParams;

import villagecompute.storefront.data.models.PlatformFeeConfig;
import villagecompute.storefront.payment.MarketplaceProvider;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Stripe Connect implementation of MarketplaceProvider interface. Handles connected account onboarding, platform fee
 * calculation, and payout management.
 *
 * Uses Stripe Express accounts for simplified onboarding flow.
 */
@ApplicationScoped
public class StripeMarketplaceProvider implements MarketplaceProvider {

    private static final Logger LOGGER = Logger.getLogger(StripeMarketplaceProvider.class);

    @Inject
    StripeConfig stripeConfig;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Initialize Stripe SDK.
     */
    private void init() {
        Stripe.apiKey = stripeConfig.apiSecretKey();
        Stripe.setMaxNetworkRetries(stripeConfig.maxRetries());
    }

    @Override
    @Transactional
    public OnboardingResult beginOnboarding(OnboardingRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String tenantTag = tenantId.toString();
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            init();

            // Create Stripe Connect account
            AccountCreateParams.Builder accountParams = AccountCreateParams.builder()
                    .setType(request.type() == OnboardingType.EXPRESS ? AccountCreateParams.Type.EXPRESS
                            : AccountCreateParams.Type.STANDARD)
                    .setCountry(request.country()).setEmail(request.email())
                    .setCapabilities(AccountCreateParams.Capabilities.builder()
                            .setCardPayments(
                                    AccountCreateParams.Capabilities.CardPayments.builder().setRequested(true).build())
                            .setTransfers(
                                    AccountCreateParams.Capabilities.Transfers.builder().setRequested(true).build())
                            .build());

            if (request.businessName() != null) {
                accountParams.setBusinessProfile(
                        AccountCreateParams.BusinessProfile.builder().setName(request.businessName()).build());
            }

            if (request.metadata() != null) {
                accountParams.putAllMetadata(request.metadata());
            }

            // Add tenant ID to metadata
            accountParams.putMetadata("tenant_id", tenantTag);

            Account account = Account.create(accountParams.build());

            // Create account link for onboarding
            AccountLinkCreateParams linkParams = AccountLinkCreateParams.builder().setAccount(account.getId())
                    .setRefreshUrl(request.refreshUrl()).setReturnUrl(request.returnUrl())
                    .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING).build();

            AccountLink accountLink = AccountLink.create(linkParams);

            LOGGER.infof("[Tenant: %s] Created Stripe Connect account: %s for %s", tenantTag, account.getId(),
                    request.email());

            meterRegistry.counter("marketplace.onboarding.started", "tenant", tenantTag, "provider", "stripe", "type",
                    request.type().toString()).increment();

            return new OnboardingResult(account.getId(), accountLink.getUrl(), mapOnboardingStatus(account));

        } catch (StripeException e) {
            LOGGER.errorf(e, "[Tenant: %s] Failed to create Stripe Connect account: %s", tenantTag, e.getMessage());
            meterRegistry.counter("marketplace.onboarding.failed", "tenant", tenantTag, "provider", "stripe", "error",
                    e.getCode()).increment();
            throw new MarketplaceProviderException("Failed to begin onboarding: " + e.getMessage(), e);
        } finally {
            sample.stop(
                    meterRegistry.timer("marketplace.onboarding.duration", "tenant", tenantTag, "provider", "stripe"));
        }
    }

    @Override
    public OnboardingStatus getOnboardingStatus(String connectedAccountId) {
        UUID tenantId = TenantContext.getCurrentTenantId();

        try {
            init();

            Account account = Account.retrieve(connectedAccountId);
            return mapOnboardingStatus(account);

        } catch (StripeException e) {
            LOGGER.errorf(e, "[Tenant: %s] Failed to retrieve Connect account status %s: %s", tenantId,
                    connectedAccountId, e.getMessage());
            throw new MarketplaceProviderException("Failed to retrieve onboarding status: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public PlatformFeeCalculation calculatePlatformFee(UUID tenantId, BigDecimal transactionAmount) {
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            PlatformFeeConfig feeConfig = PlatformFeeConfig.findByTenantId(tenantId);

            if (feeConfig == null) {
                LOGGER.warnf("[Tenant: %s] No platform fee configuration found, using 0% fee", tenantId);
                return new PlatformFeeCalculation(transactionAmount, BigDecimal.ZERO, BigDecimal.ZERO,
                        transactionAmount, "No fee configuration found");
            }

            BigDecimal feeAmount = feeConfig.calculateFee(transactionAmount);
            BigDecimal netAmount = transactionAmount.subtract(feeAmount);

            LOGGER.debugf("[Tenant: %s] Calculated platform fee: %s (%.2f%%) on transaction: %s, net: %s", tenantId,
                    feeAmount, feeConfig.feePercentage.multiply(BigDecimal.valueOf(100)), transactionAmount, netAmount);

            meterRegistry.counter("marketplace.fee.calculated", "tenant", tenantId.toString()).increment();

            return new PlatformFeeCalculation(transactionAmount, feeAmount, feeConfig.feePercentage, netAmount,
                    String.format("Fee: %.2f%% + %s %s", feeConfig.feePercentage.multiply(BigDecimal.valueOf(100)),
                            feeConfig.fixedFeeAmount != null ? feeConfig.fixedFeeAmount : "0.00", feeConfig.currency));

        } finally {
            sample.stop(meterRegistry.timer("marketplace.fee.calculation.duration", "tenant", tenantId.toString()));
        }
    }

    @Override
    public PayoutResult createPayout(PayoutRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String tenantTag = tenantId.toString();
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            init();

            Long amountInCents = request.amount().multiply(BigDecimal.valueOf(100)).longValue();

            PayoutCreateParams.Builder paramsBuilder = PayoutCreateParams.builder().setAmount(amountInCents)
                    .setCurrency(request.currency().toLowerCase()).setDescription(request.description());

            if (request.metadata() != null) {
                paramsBuilder.putAllMetadata(request.metadata());
            }

            paramsBuilder.putMetadata("tenant_id", tenantTag);

            // Create payout on connected account
            com.stripe.net.RequestOptions requestOptions = com.stripe.net.RequestOptions.builder()
                    .setStripeAccount(request.connectedAccountId()).setIdempotencyKey(request.idempotencyKey()).build();

            Payout payout = Payout.create(paramsBuilder.build(), requestOptions);

            Instant estimatedArrival = payout.getArrivalDate() != null ? Instant.ofEpochSecond(payout.getArrivalDate())
                    : Instant.now().plusSeconds(86400 * 3); // Default: 3 days

            LOGGER.infof("[Tenant: %s] Created payout: %s for account: %s, amount: %s %s", tenantTag, payout.getId(),
                    request.connectedAccountId(), request.amount(), request.currency());

            meterRegistry.counter("marketplace.payout.created", "tenant", tenantTag, "provider", "stripe").increment();

            return new PayoutResult(payout.getId(), request.amount(), mapPayoutStatus(payout.getStatus()),
                    estimatedArrival);

        } catch (StripeException e) {
            LOGGER.errorf(e, "[Tenant: %s] Failed to create payout for account %s: %s", tenantTag,
                    request.connectedAccountId(), e.getMessage());
            meterRegistry.counter("marketplace.payout.failed", "tenant", tenantTag, "provider", "stripe", "error",
                    e.getCode()).increment();
            throw new MarketplaceProviderException("Failed to create payout: " + e.getMessage(), e);
        } finally {
            sample.stop(meterRegistry.timer("marketplace.payout.duration", "tenant", tenantTag, "provider", "stripe"));
        }
    }

    @Override
    public PayoutStatus getPayoutStatus(String payoutId) {
        UUID tenantId = TenantContext.getCurrentTenantId();

        try {
            init();

            Payout payout = Payout.retrieve(payoutId);
            return mapPayoutStatus(payout.getStatus());

        } catch (StripeException e) {
            LOGGER.errorf(e, "[Tenant: %s] Failed to retrieve payout status %s: %s", tenantId, payoutId,
                    e.getMessage());
            throw new MarketplaceProviderException("Failed to retrieve payout status: " + e.getMessage(), e);
        }
    }

    @Override
    public DisputeInfo getDisputeInfo(String disputeId) {
        UUID tenantId = TenantContext.getCurrentTenantId();

        try {
            init();

            com.stripe.model.Dispute dispute = com.stripe.model.Dispute.retrieve(disputeId);

            return new DisputeInfo(dispute.getId(), dispute.getPaymentIntent(),
                    BigDecimal.valueOf(dispute.getAmount()).divide(BigDecimal.valueOf(100)),
                    mapDisputeReason(dispute.getReason()), mapDisputeStatus(dispute.getStatus()),
                    Instant.ofEpochSecond(dispute.getCreated()));

        } catch (StripeException e) {
            LOGGER.errorf(e, "[Tenant: %s] Failed to retrieve dispute info %s: %s", tenantId, disputeId,
                    e.getMessage());
            throw new MarketplaceProviderException("Failed to retrieve dispute info: " + e.getMessage(), e);
        }
    }

    /**
     * Map Stripe account status to onboarding status.
     */
    private OnboardingStatus mapOnboardingStatus(Account account) {
        if (account.getChargesEnabled() && account.getPayoutsEnabled()) {
            return OnboardingStatus.COMPLETED;
        } else if (account.getDetailsSubmitted()) {
            return OnboardingStatus.IN_PROGRESS;
        } else if (account.getRequirements() != null && account.getRequirements().getDisabledReason() != null) {
            return OnboardingStatus.RESTRICTED;
        }
        return OnboardingStatus.PENDING;
    }

    /**
     * Map Stripe payout status to provider-agnostic status.
     */
    private PayoutStatus mapPayoutStatus(String stripeStatus) {
        return switch (stripeStatus) {
            case "pending" -> PayoutStatus.PENDING;
            case "in_transit" -> PayoutStatus.IN_TRANSIT;
            case "paid" -> PayoutStatus.PAID;
            case "failed" -> PayoutStatus.FAILED;
            case "canceled" -> PayoutStatus.CANCELLED;
            default -> PayoutStatus.PENDING;
        };
    }

    /**
     * Map Stripe dispute reason to provider-agnostic reason.
     */
    private DisputeReason mapDisputeReason(String stripeReason) {
        return switch (stripeReason) {
            case "fraudulent" -> DisputeReason.FRAUDULENT;
            case "duplicate" -> DisputeReason.DUPLICATE;
            case "product_not_received" -> DisputeReason.PRODUCT_NOT_RECEIVED;
            case "product_unacceptable" -> DisputeReason.PRODUCT_UNACCEPTABLE;
            case "subscription_canceled" -> DisputeReason.SUBSCRIPTION_CANCELLED;
            case "unrecognized" -> DisputeReason.UNRECOGNIZED;
            case "credit_not_processed" -> DisputeReason.CREDIT_NOT_PROCESSED;
            default -> DisputeReason.GENERAL;
        };
    }

    /**
     * Map Stripe dispute status to provider-agnostic status.
     */
    private DisputeStatus mapDisputeStatus(String stripeStatus) {
        return switch (stripeStatus) {
            case "warning_needs_response" -> DisputeStatus.WARNING_NEEDS_RESPONSE;
            case "warning_under_review" -> DisputeStatus.WARNING_UNDER_REVIEW;
            case "warning_closed" -> DisputeStatus.WARNING_CLOSED;
            case "needs_response" -> DisputeStatus.NEEDS_RESPONSE;
            case "under_review" -> DisputeStatus.UNDER_REVIEW;
            case "charge_refunded" -> DisputeStatus.CHARGE_REFUNDED;
            case "won" -> DisputeStatus.WON;
            case "lost" -> DisputeStatus.LOST;
            default -> DisputeStatus.UNDER_REVIEW;
        };
    }

    /**
     * Custom exception for marketplace provider errors.
     */
    public static class MarketplaceProviderException extends RuntimeException {
        public MarketplaceProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
