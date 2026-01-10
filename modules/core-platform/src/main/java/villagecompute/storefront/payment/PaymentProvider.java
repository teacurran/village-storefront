package villagecompute.storefront.payment;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Core payment provider abstraction for creating, capturing, and refunding payments. Implementations wrap external
 * payment processors (Stripe, PayPal, etc.) behind a uniform interface to enable provider switching without modifying
 * business logic.
 *
 * All methods are tenant-aware via TenantContext and return provider-agnostic result types.
 */
public interface PaymentProvider {

    /**
     * Create a payment intent (authorization) for a given amount.
     *
     * @param request
     *            Payment intent creation parameters
     * @return Result containing provider payment ID and client secret
     */
    PaymentIntentResult createIntent(CreatePaymentIntentRequest request);

    /**
     * Capture a previously authorized payment intent.
     *
     * @param paymentIntentId
     *            Provider-specific payment intent identifier
     * @param amountToCapture
     *            Optional amount to capture (null = full authorized amount)
     * @return Result with capture status and transaction details
     */
    CaptureResult capturePayment(String paymentIntentId, BigDecimal amountToCapture);

    /**
     * Refund a captured payment, either fully or partially.
     *
     * @param paymentIntentId
     *            Provider-specific payment intent identifier
     * @param amountToRefund
     *            Amount to refund (must not exceed captured amount)
     * @param reason
     *            Optional refund reason
     * @return Result with refund status and refund transaction ID
     */
    RefundResult refundPayment(String paymentIntentId, BigDecimal amountToRefund, String reason);

    /**
     * Retrieve current status of a payment intent from the provider.
     *
     * @param paymentIntentId
     *            Provider-specific payment intent identifier
     * @return Current payment status
     */
    PaymentStatus getPaymentStatus(String paymentIntentId);

    /**
     * Cancel an uncaptured payment intent.
     *
     * @param paymentIntentId
     *            Provider-specific payment intent identifier
     * @return Cancellation result
     */
    CancellationResult cancelPayment(String paymentIntentId);

    /**
     * Payment intent creation request parameters.
     */
    record CreatePaymentIntentRequest(BigDecimal amount, String currency, String customerId, // Provider-specific
                                                                                             // customer ID
            String paymentMethodId, // Provider-specific payment method ID
            boolean captureImmediately, // true = authorize+capture, false = authorize only
            Map<String, String> metadata, // Custom metadata (order ID, etc.)
            String idempotencyKey // Client-provided idempotency key
    ) {
    }

    /**
     * Result of payment intent creation.
     */
    record PaymentIntentResult(String paymentIntentId, String clientSecret, // For client-side confirmation
            PaymentStatus status, Map<String, String> providerMetadata) {
    }

    /**
     * Result of payment capture.
     */
    record CaptureResult(String transactionId, BigDecimal amountCaptured, PaymentStatus status,
            Map<String, String> providerMetadata) {
    }

    /**
     * Result of refund operation.
     */
    record RefundResult(String refundId, BigDecimal amountRefunded, RefundStatus status,
            Map<String, String> providerMetadata) {
    }

    /**
     * Result of payment cancellation.
     */
    record CancellationResult(boolean success, PaymentStatus finalStatus) {
    }

    /**
     * Payment lifecycle status.
     */
    enum PaymentStatus {
        PENDING, REQUIRES_ACTION, AUTHORIZED, CAPTURED, CANCELLED, FAILED, DISPUTED
    }

    /**
     * Refund status.
     */
    enum RefundStatus {
        PENDING, SUCCEEDED, FAILED, CANCELLED
    }
}
