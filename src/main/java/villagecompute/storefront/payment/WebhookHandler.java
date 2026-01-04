package villagecompute.storefront.payment;

import java.util.Map;

/**
 * Webhook event processing abstraction for asynchronous provider notifications. Implementations verify signatures,
 * enforce idempotency, and route events to handlers.
 *
 * All webhook processing must be idempotent and transactional.
 */
public interface WebhookHandler {

    /**
     * Verify webhook signature to ensure authenticity.
     *
     * @param payload
     *            Raw webhook payload
     * @param signature
     *            Provider-specific signature header
     * @param secret
     *            Webhook signing secret
     * @return true if signature is valid
     */
    boolean verifySignature(String payload, String signature, String secret);

    /**
     * Process a webhook event with idempotency guarantees.
     *
     * @param request
     *            Webhook processing request
     * @return Processing result
     */
    WebhookProcessingResult processWebhook(WebhookRequest request);

    /**
     * Retrieve webhook event by provider event ID for replay or auditing.
     *
     * @param providerEventId
     *            Provider-specific event identifier
     * @return Webhook event details, or null if not found
     */
    WebhookEventInfo getWebhookEvent(String providerEventId);

    /**
     * Webhook processing request.
     */
    record WebhookRequest(String providerEventId, // Unique event ID from provider
            String eventType, // Event type (e.g., "payment_intent.succeeded")
            String payload, // Raw JSON payload
            String signature, // Signature header for verification
            Map<String, String> headers // Additional webhook headers
    ) {
    }

    /**
     * Webhook processing result.
     */
    record WebhookProcessingResult(boolean success, boolean alreadyProcessed, // true if event was previously processed
            String eventId, // Internal event ID for tracking
            String error // Error message if processing failed
    ) {
    }

    /**
     * Webhook event information.
     */
    record WebhookEventInfo(String eventId, String providerEventId, String eventType, String payload, boolean processed,
            String processingError, java.time.Instant receivedAt, java.time.Instant processedAt) {
    }
}
