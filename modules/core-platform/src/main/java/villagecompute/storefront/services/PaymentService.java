package villagecompute.storefront.services;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.PaymentIntent;
import villagecompute.storefront.data.models.PlatformFeeConfig;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.payment.MarketplaceProvider;
import villagecompute.storefront.payment.PaymentProvider;
import villagecompute.storefront.payment.stripe.StripeMarketplaceProvider;
import villagecompute.storefront.payment.stripe.StripePaymentProvider;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Payment orchestration service. Coordinates payment provider operations with local persistence and business logic.
 *
 * Handles payment lifecycle, fee calculations, and state synchronization.
 */
@ApplicationScoped
public class PaymentService {

    private static final Logger LOGGER = Logger.getLogger(PaymentService.class);
    private static final String PROVIDER_STRIPE = "stripe";

    @Inject
    StripePaymentProvider stripePaymentProvider;

    @Inject
    StripeMarketplaceProvider stripeMarketplaceProvider;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Create a payment intent and persist it locally.
     *
     * @param amount
     *            Payment amount
     * @param currency
     *            Currency code (ISO 4217)
     * @param orderId
     *            Associated order ID
     * @param captureImmediately
     *            Whether to capture payment immediately
     * @param idempotencyKey
     *            Client-provided idempotency key
     * @return Created payment intent entity
     */
    @Transactional
    public PaymentIntent createPaymentIntent(BigDecimal amount, String currency, Long orderId,
            boolean captureImmediately, String idempotencyKey) {

        UUID tenantId = TenantContext.getCurrentTenantId();
        Tenant tenant = Tenant.findById(tenantId);
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // Check for duplicate request using idempotency key
            if (idempotencyKey != null) {
                PaymentIntent existing = PaymentIntent
                        .find("idempotencyKey = ?1 and tenant.id = ?2", idempotencyKey, tenantId).firstResult();
                if (existing != null) {
                    LOGGER.infof("[Tenant: %s] Returning existing payment intent for idempotency key: %s", tenantId,
                            idempotencyKey);
                    return existing;
                }
            }

            // Create payment intent via provider
            PaymentProvider.CreatePaymentIntentRequest request = new PaymentProvider.CreatePaymentIntentRequest(amount,
                    currency, null, // customerId - TODO: lookup from order
                    null, // paymentMethodId - will be provided by client
                    captureImmediately, Map.of("order_id", orderId.toString()), idempotencyKey);

            PaymentProvider.PaymentIntentResult result = stripePaymentProvider.createIntent(request);

            // Persist payment intent
            PaymentIntent paymentIntent = new PaymentIntent();
            paymentIntent.tenant = tenant;
            paymentIntent.provider = PROVIDER_STRIPE;
            paymentIntent.providerPaymentId = result.paymentIntentId();
            paymentIntent.orderId = orderId;
            paymentIntent.amount = amount;
            paymentIntent.currency = currency;
            paymentIntent.status = PaymentIntent.PaymentStatus.valueOf(result.status().name());
            paymentIntent.captureMethod = captureImmediately ? PaymentIntent.CaptureMethod.AUTOMATIC
                    : PaymentIntent.CaptureMethod.MANUAL;
            paymentIntent.clientSecret = result.clientSecret();
            paymentIntent.idempotencyKey = idempotencyKey;
            paymentIntent.createdAt = Instant.now();
            paymentIntent.updatedAt = Instant.now();
            paymentIntent.persist();

            LOGGER.infof("[Tenant: %s] Created payment intent: id=%d, provider_id=%s, amount=%s %s", tenantId,
                    paymentIntent.id, result.paymentIntentId(), amount, currency);

            meterRegistry.counter("payment.service.intent.created", "tenant", tenantId.toString(), "provider",
                    PROVIDER_STRIPE).increment();

            return paymentIntent;

        } finally {
            sample.stop(meterRegistry.timer("payment.service.intent.create.duration", "tenant", tenantId.toString()));
        }
    }

    /**
     * Capture a previously authorized payment.
     *
     * @param paymentIntentId
     *            Local payment intent ID
     * @param amountToCapture
     *            Optional amount to capture (null = full amount)
     * @return Updated payment intent
     */
    @Transactional
    public PaymentIntent capturePayment(Long paymentIntentId, BigDecimal amountToCapture) {
        UUID tenantId = TenantContext.getCurrentTenantId();

        PaymentIntent paymentIntent = PaymentIntent.findByIdAndTenant(tenantId, paymentIntentId);
        if (paymentIntent == null) {
            throw new IllegalArgumentException("Payment intent not found: " + paymentIntentId);
        }

        if (paymentIntent.status != PaymentIntent.PaymentStatus.AUTHORIZED) {
            throw new IllegalStateException("Payment intent not in authorized state: " + paymentIntent.status);
        }

        PaymentProvider.CaptureResult result = stripePaymentProvider.capturePayment(paymentIntent.providerPaymentId,
                amountToCapture);

        paymentIntent.status = PaymentIntent.PaymentStatus.valueOf(result.status().name());
        paymentIntent.amountCaptured = result.amountCaptured();
        paymentIntent.updatedAt = Instant.now();

        LOGGER.infof("[Tenant: %s] Captured payment: id=%d, amount=%s", tenantId, paymentIntentId,
                result.amountCaptured());

        return paymentIntent;
    }

    /**
     * Refund a captured payment.
     *
     * @param paymentIntentId
     *            Local payment intent ID
     * @param amountToRefund
     *            Amount to refund
     * @param reason
     *            Refund reason
     * @return Updated payment intent
     */
    @Transactional
    public PaymentIntent refundPayment(Long paymentIntentId, BigDecimal amountToRefund, String reason) {
        UUID tenantId = TenantContext.getCurrentTenantId();

        PaymentIntent paymentIntent = PaymentIntent.findByIdAndTenant(tenantId, paymentIntentId);
        if (paymentIntent == null) {
            throw new IllegalArgumentException("Payment intent not found: " + paymentIntentId);
        }

        if (paymentIntent.status != PaymentIntent.PaymentStatus.CAPTURED) {
            throw new IllegalStateException("Payment intent not captured: " + paymentIntent.status);
        }

        PaymentProvider.RefundResult result = stripePaymentProvider.refundPayment(paymentIntent.providerPaymentId,
                amountToRefund, reason);

        BigDecimal currentRefunded = paymentIntent.amountRefunded != null ? paymentIntent.amountRefunded
                : BigDecimal.ZERO;
        paymentIntent.amountRefunded = currentRefunded.add(result.amountRefunded());
        paymentIntent.updatedAt = Instant.now();

        LOGGER.infof("[Tenant: %s] Refunded payment: id=%d, amount=%s, reason=%s", tenantId, paymentIntentId,
                result.amountRefunded(), reason);

        return paymentIntent;
    }

    /**
     * Calculate platform fee for a transaction.
     *
     * @param transactionAmount
     *            Transaction amount
     * @return Platform fee calculation result
     */
    public MarketplaceProvider.PlatformFeeCalculation calculatePlatformFee(BigDecimal transactionAmount) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return stripeMarketplaceProvider.calculatePlatformFee(tenantId, transactionAmount);
    }

    /**
     * Get or create platform fee configuration for current tenant.
     *
     * @return Platform fee configuration
     */
    @Transactional
    public PlatformFeeConfig getPlatformFeeConfig() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        Tenant tenant = Tenant.findById(tenantId);

        PlatformFeeConfig config = PlatformFeeConfig.findByTenantId(tenantId);

        if (config == null) {
            // Create default fee configuration
            config = new PlatformFeeConfig();
            config.tenant = tenant;
            config.feePercentage = new BigDecimal("0.0300"); // Default 3%
            config.fixedFeeAmount = new BigDecimal("0.30"); // Default $0.30
            config.currency = "USD";
            config.minimumFee = new BigDecimal("0.50");
            config.active = true;
            config.effectiveFrom = Instant.now();
            config.notes = "Default platform fee configuration";
            config.persist();

            LOGGER.infof("[Tenant: %s] Created default platform fee configuration: %.2f%% + %s %s", tenantId,
                    config.feePercentage.multiply(BigDecimal.valueOf(100)), config.fixedFeeAmount, config.currency);
        }

        return config;
    }

    /**
     * Update platform fee configuration for current tenant.
     *
     * @param feePercentage
     *            Fee percentage (e.g., 0.03 for 3%)
     * @param fixedFeeAmount
     *            Fixed fee per transaction
     * @param minimumFee
     *            Minimum fee per transaction
     * @param maximumFee
     *            Maximum fee per transaction
     * @return Updated configuration
     */
    @Transactional
    public PlatformFeeConfig updatePlatformFeeConfig(BigDecimal feePercentage, BigDecimal fixedFeeAmount,
            BigDecimal minimumFee, BigDecimal maximumFee) {

        UUID tenantId = TenantContext.getCurrentTenantId();

        PlatformFeeConfig config = getPlatformFeeConfig();
        config.feePercentage = feePercentage;
        config.fixedFeeAmount = fixedFeeAmount;
        config.minimumFee = minimumFee;
        config.maximumFee = maximumFee;
        config.updatedAt = Instant.now();

        LOGGER.infof("[Tenant: %s] Updated platform fee configuration: %.2f%% + %s %s", tenantId,
                config.feePercentage.multiply(BigDecimal.valueOf(100)), config.fixedFeeAmount, config.currency);

        return config;
    }
}
