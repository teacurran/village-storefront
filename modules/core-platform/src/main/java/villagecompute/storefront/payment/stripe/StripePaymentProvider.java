package villagecompute.storefront.payment.stripe;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;

import villagecompute.storefront.payment.PaymentProvider;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Stripe implementation of PaymentProvider interface. Handles payment intent creation, capture, refund, and status
 * retrieval using Stripe SDK.
 *
 * All operations are tenant-aware and emit metrics for observability.
 */
@ApplicationScoped
public class StripePaymentProvider implements PaymentProvider {

    private static final Logger LOGGER = Logger.getLogger(StripePaymentProvider.class);

    @Inject
    StripeConfig stripeConfig;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Initialize Stripe SDK with API key.
     */
    public void init() {
        Stripe.apiKey = stripeConfig.apiSecretKey();
        Stripe.setMaxNetworkRetries(stripeConfig.maxRetries());
    }

    @Override
    public PaymentIntentResult createIntent(CreatePaymentIntentRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String tenantTag = tenantId.toString();
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            init();

            // Convert amount to cents (Stripe requires smallest currency unit)
            Long amountInCents = request.amount().multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();

            PaymentIntentCreateParams.Builder paramsBuilder = PaymentIntentCreateParams.builder()
                    .setAmount(amountInCents).setCurrency(request.currency().toLowerCase())
                    .setCaptureMethod(request.captureImmediately() ? PaymentIntentCreateParams.CaptureMethod.AUTOMATIC
                            : PaymentIntentCreateParams.CaptureMethod.MANUAL);

            if (request.customerId() != null) {
                paramsBuilder.setCustomer(request.customerId());
            }

            if (request.paymentMethodId() != null) {
                paramsBuilder.setPaymentMethod(request.paymentMethodId());
                paramsBuilder.setConfirm(true); // Auto-confirm if payment method provided
            }

            if (request.metadata() != null && !request.metadata().isEmpty()) {
                paramsBuilder.putAllMetadata(request.metadata());
            }

            // Add tenant ID to metadata for tracking
            paramsBuilder.putMetadata("tenant_id", tenantTag);

            RequestOptions requestOptions = null;
            if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
                requestOptions = RequestOptions.builder().setIdempotencyKey(request.idempotencyKey()).build();
            }

            PaymentIntent paymentIntent = requestOptions != null
                    ? PaymentIntent.create(paramsBuilder.build(), requestOptions)
                    : PaymentIntent.create(paramsBuilder.build());

            LOGGER.infof("[Tenant: %s] Created Stripe payment intent: %s, status: %s, amount: %s %s", tenantTag,
                    paymentIntent.getId(), paymentIntent.getStatus(), request.amount(), request.currency());

            meterRegistry.counter("payments.intent.created", "tenant", tenantTag, "provider", "stripe", "currency",
                    request.currency()).increment();

            return new PaymentIntentResult(paymentIntent.getId(), paymentIntent.getClientSecret(),
                    mapStripeStatus(paymentIntent.getStatus()), Map.of("stripe_status", paymentIntent.getStatus()));

        } catch (StripeException e) {
            LOGGER.errorf(e, "[Tenant: %s] Failed to create Stripe payment intent: %s", tenantTag, e.getMessage());
            meterRegistry
                    .counter("payments.intent.failed", "tenant", tenantTag, "provider", "stripe", "error", e.getCode())
                    .increment();
            throw new PaymentProviderException("Failed to create payment intent: " + e.getMessage(), e);
        } finally {
            sample.stop(
                    meterRegistry.timer("payments.intent.create.duration", "tenant", tenantTag, "provider", "stripe"));
        }
    }

    @Override
    public CaptureResult capturePayment(String paymentIntentId, BigDecimal amountToCapture) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String tenantTag = tenantId.toString();
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            init();

            PaymentIntent paymentIntent = PaymentIntent.retrieve(paymentIntentId);

            PaymentIntentCaptureParams.Builder paramsBuilder = PaymentIntentCaptureParams.builder();

            if (amountToCapture != null) {
                Long amountInCents = amountToCapture.multiply(BigDecimal.valueOf(100)).longValue();
                paramsBuilder.setAmountToCapture(amountInCents);
            }

            PaymentIntent capturedIntent = paymentIntent.capture(paramsBuilder.build());

            Long amountReceived = capturedIntent.getAmountReceived();
            if (amountReceived == null) {
                amountReceived = capturedIntent.getAmount();
            }
            BigDecimal capturedAmount = BigDecimal.valueOf(amountReceived).movePointLeft(2);

            LOGGER.infof("[Tenant: %s] Captured Stripe payment: %s, amount: %s", tenantTag, paymentIntentId,
                    capturedAmount);

            meterRegistry.counter("payments.captured", "tenant", tenantTag, "provider", "stripe").increment();

            return new CaptureResult(capturedIntent.getId(), capturedAmount,
                    mapStripeStatus(capturedIntent.getStatus()), Map.of("stripe_status", capturedIntent.getStatus()));

        } catch (StripeException e) {
            LOGGER.errorf(e, "[Tenant: %s] Failed to capture Stripe payment %s: %s", tenantTag, paymentIntentId,
                    e.getMessage());
            meterRegistry
                    .counter("payments.capture.failed", "tenant", tenantTag, "provider", "stripe", "error", e.getCode())
                    .increment();
            throw new PaymentProviderException("Failed to capture payment: " + e.getMessage(), e);
        } finally {
            sample.stop(meterRegistry.timer("payments.capture.duration", "tenant", tenantTag, "provider", "stripe"));
        }
    }

    @Override
    public RefundResult refundPayment(String paymentIntentId, BigDecimal amountToRefund, String reason) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String tenantTag = tenantId.toString();
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            init();

            Long amountInCents = amountToRefund.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();

            RefundCreateParams.Builder paramsBuilder = RefundCreateParams.builder().setPaymentIntent(paymentIntentId)
                    .setAmount(amountInCents);

            if (reason != null) {
                paramsBuilder.setReason(mapRefundReason(reason));
            }

            Refund refund = Refund.create(paramsBuilder.build());

            BigDecimal refundedAmount = BigDecimal.valueOf(refund.getAmount()).movePointLeft(2);

            LOGGER.infof("[Tenant: %s] Refunded Stripe payment: %s, amount: %s, reason: %s", tenantTag, paymentIntentId,
                    refundedAmount, reason);

            meterRegistry.counter("payments.refunded", "tenant", tenantTag, "provider", "stripe", "reason",
                    reason != null ? reason : "none").increment();

            return new RefundResult(refund.getId(), refundedAmount, mapStripeRefundStatus(refund.getStatus()),
                    Map.of("stripe_status", refund.getStatus()));

        } catch (StripeException e) {
            LOGGER.errorf(e, "[Tenant: %s] Failed to refund Stripe payment %s: %s", tenantTag, paymentIntentId,
                    e.getMessage());
            meterRegistry
                    .counter("payments.refund.failed", "tenant", tenantTag, "provider", "stripe", "error", e.getCode())
                    .increment();
            throw new PaymentProviderException("Failed to refund payment: " + e.getMessage(), e);
        } finally {
            sample.stop(meterRegistry.timer("payments.refund.duration", "tenant", tenantTag, "provider", "stripe"));
        }
    }

    @Override
    public PaymentStatus getPaymentStatus(String paymentIntentId) {
        UUID tenantId = TenantContext.getCurrentTenantId();

        try {
            init();

            PaymentIntent paymentIntent = PaymentIntent.retrieve(paymentIntentId);
            return mapStripeStatus(paymentIntent.getStatus());

        } catch (StripeException e) {
            LOGGER.errorf(e, "[Tenant: %s] Failed to retrieve Stripe payment status %s: %s", tenantId, paymentIntentId,
                    e.getMessage());
            throw new PaymentProviderException("Failed to retrieve payment status: " + e.getMessage(), e);
        }
    }

    @Override
    public CancellationResult cancelPayment(String paymentIntentId) {
        UUID tenantId = TenantContext.getCurrentTenantId();

        try {
            init();

            PaymentIntent paymentIntent = PaymentIntent.retrieve(paymentIntentId);
            PaymentIntent cancelledIntent = paymentIntent.cancel(PaymentIntentCancelParams.builder().build());

            LOGGER.infof("[Tenant: %s] Cancelled Stripe payment intent: %s", tenantId, paymentIntentId);

            meterRegistry.counter("payments.cancelled", "tenant", tenantId.toString(), "provider", "stripe")
                    .increment();

            return new CancellationResult(true, mapStripeStatus(cancelledIntent.getStatus()));

        } catch (StripeException e) {
            LOGGER.errorf(e, "[Tenant: %s] Failed to cancel Stripe payment %s: %s", tenantId, paymentIntentId,
                    e.getMessage());
            return new CancellationResult(false, PaymentStatus.FAILED);
        }
    }

    /**
     * Map Stripe payment intent status to provider-agnostic status.
     */
    private PaymentStatus mapStripeStatus(String stripeStatus) {
        switch (stripeStatus) {
            case "requires_payment_method" :
            case "requires_confirmation" :
                return PaymentStatus.PENDING;
            case "requires_action" :
                return PaymentStatus.REQUIRES_ACTION;
            case "requires_capture" :
                return PaymentStatus.AUTHORIZED;
            case "processing" :
                return PaymentStatus.PENDING;
            case "succeeded" :
                return PaymentStatus.CAPTURED;
            case "canceled" :
                return PaymentStatus.CANCELLED;
            default :
                return PaymentStatus.FAILED;
        }
    }

    /**
     * Map refund reason to Stripe enum.
     */
    private RefundCreateParams.Reason mapRefundReason(String reason) {
        return switch (reason.toLowerCase()) {
            case "duplicate" -> RefundCreateParams.Reason.DUPLICATE;
            case "fraudulent" -> RefundCreateParams.Reason.FRAUDULENT;
            default -> RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER;
        };
    }

    /**
     * Map Stripe refund status to provider-agnostic status.
     */
    private RefundStatus mapStripeRefundStatus(String stripeStatus) {
        return switch (stripeStatus) {
            case "pending" -> RefundStatus.PENDING;
            case "succeeded" -> RefundStatus.SUCCEEDED;
            case "failed" -> RefundStatus.FAILED;
            case "canceled" -> RefundStatus.CANCELLED;
            default -> RefundStatus.FAILED;
        };
    }

    /**
     * Custom exception for payment provider errors.
     */
    public static class PaymentProviderException extends RuntimeException {
        public PaymentProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
