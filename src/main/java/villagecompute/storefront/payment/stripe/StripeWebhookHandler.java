package villagecompute.storefront.payment.stripe;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;

import villagecompute.storefront.data.models.ConnectAccount;
import villagecompute.storefront.data.models.PaymentIntent;
import villagecompute.storefront.data.models.PayoutBatch;
import villagecompute.storefront.data.models.WebhookEvent;
import villagecompute.storefront.payment.WebhookHandler;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Stripe webhook handler implementation with signature verification and idempotency. Processes asynchronous Stripe
 * events (payment_intent.succeeded, payout.paid, etc.).
 *
 * All webhook processing is idempotent - duplicate events are detected and skipped.
 */
@ApplicationScoped
public class StripeWebhookHandler implements WebhookHandler {

    private static final Logger LOGGER = Logger.getLogger(StripeWebhookHandler.class);
    private static final String PROVIDER_NAME = "stripe";

    @Inject
    StripeConfig stripeConfig;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public boolean verifySignature(String payload, String signature, String secret) {
        if (stripeConfig.webhookSkipVerification()) {
            LOGGER.debug("Stripe webhook signature verification skipped (configured override)");
            return true;
        }

        try {
            Webhook.constructEvent(payload, signature, secret);
            return true;
        } catch (SignatureVerificationException e) {
            LOGGER.warnf("Stripe webhook signature verification failed: %s", e.getMessage());
            return false;
        }
    }

    @Override
    @Transactional
    public WebhookProcessingResult processWebhook(WebhookRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        UUID tenantId = TenantContext.getCurrentTenantId();
        String tenantTag = tenantId.toString();
        String timerEventType = request.eventType() != null ? request.eventType() : "unknown";

        try {
            boolean signatureValid = verifySignature(request.payload(), request.signature(),
                    stripeConfig.webhookSigningSecret());

            if (!signatureValid) {
                LOGGER.errorf("[Tenant: %s] Webhook signature verification failed for event: %s", tenantTag,
                        request.providerEventId());
                meterRegistry.counter("webhooks.signature.invalid", "tenant", tenantTag, "provider", PROVIDER_NAME)
                        .increment();
                return new WebhookProcessingResult(false, false, null, "Signature verification failed");
            }

            JsonNode rootNode;
            try {
                rootNode = objectMapper.readTree(request.payload());
            } catch (IOException e) {
                LOGGER.errorf(e, "[Tenant: %s] Failed to parse webhook payload", tenantTag);
                return new WebhookProcessingResult(false, false, null, "Invalid webhook payload");
            }

            String providerEventId = request.providerEventId() != null ? request.providerEventId()
                    : rootNode.path("id").asText("evt_unknown");
            String eventType = request.eventType() != null ? request.eventType()
                    : rootNode.path("type").asText("unknown");
            timerEventType = eventType;

            WebhookEvent existingEvent = WebhookEvent.findByProviderEventId(providerEventId);
            if (existingEvent != null) {
                LOGGER.debugf("[Tenant: %s] Webhook event already processed: %s", tenantTag, providerEventId);
                meterRegistry.counter("webhooks.duplicate", "tenant", tenantTag, "provider", PROVIDER_NAME,
                        "event_type", eventType).increment();
                return new WebhookProcessingResult(true, true, existingEvent.id.toString(), null);
            }

            WebhookEvent webhookEvent = new WebhookEvent();
            webhookEvent.provider = PROVIDER_NAME;
            webhookEvent.providerEventId = providerEventId;
            webhookEvent.eventType = eventType;
            webhookEvent.payload = request.payload();
            webhookEvent.receivedAt = Instant.now();
            webhookEvent.persist();

            try {
                processEventByType(tenantId, eventType, rootNode);
                webhookEvent.processed = true;
                webhookEvent.processedAt = Instant.now();

                LOGGER.infof("[Tenant: %s] Processed webhook event: %s (%s)", tenantTag, providerEventId, eventType);
                meterRegistry.counter("webhooks.processed", "tenant", tenantTag, "provider", PROVIDER_NAME,
                        "event_type", eventType).increment();

                return new WebhookProcessingResult(true, false, webhookEvent.id.toString(), null);
            } catch (Exception e) {
                LOGGER.errorf(e, "[Tenant: %s] Failed to process webhook event %s", tenantTag, providerEventId);
                webhookEvent.processed = false;
                webhookEvent.processingError = e.getMessage();

                meterRegistry.counter("webhooks.processing.failed", "tenant", tenantTag, "provider", PROVIDER_NAME,
                        "event_type", eventType).increment();

                return new WebhookProcessingResult(false, false, webhookEvent.id.toString(), e.getMessage());
            }

        } finally {
            sample.stop(meterRegistry.timer("webhooks.processing.duration", "tenant", tenantTag, "provider",
                    PROVIDER_NAME, "event_type", timerEventType));
        }
    }

    @Override
    public WebhookEventInfo getWebhookEvent(String providerEventId) {
        WebhookEvent event = WebhookEvent.findByProviderEventId(providerEventId);
        if (event == null) {
            return null;
        }

        return new WebhookEventInfo(event.id.toString(), event.providerEventId, event.eventType, event.payload,
                event.processed, event.processingError, event.receivedAt, event.processedAt);
    }

    private void processEventByType(UUID tenantId, String eventType, JsonNode payload) {
        switch (eventType) {
            case "payment_intent.succeeded" ->
                updatePaymentIntentStatus(tenantId, payload, PaymentIntent.PaymentStatus.CAPTURED);
            case "payment_intent.payment_failed" ->
                updatePaymentIntentStatus(tenantId, payload, PaymentIntent.PaymentStatus.FAILED);
            case "payment_intent.canceled" ->
                updatePaymentIntentStatus(tenantId, payload, PaymentIntent.PaymentStatus.CANCELLED);
            case "charge.refunded" -> handleChargeRefunded(tenantId, payload);
            case "charge.dispute.created" -> handleDisputeCreated(tenantId, payload);
            case "payout.paid" -> handlePayoutUpdate(tenantId, payload, true);
            case "payout.failed" -> handlePayoutUpdate(tenantId, payload, false);
            case "account.updated" -> handleAccountUpdated(tenantId, payload);
            default -> LOGGER.debugf("[Tenant: %s] No handler implemented for event type %s", tenantId, eventType);
        }
    }

    private void updatePaymentIntentStatus(UUID tenantId, JsonNode payload, PaymentIntent.PaymentStatus status) {
        JsonNode objectNode = payload.path("data").path("object");
        String providerPaymentId = objectNode.path("id").asText(null);
        if (providerPaymentId == null) {
            LOGGER.warnf("[Tenant: %s] Payment intent webhook missing payment ID", tenantId);
            return;
        }

        PaymentIntent paymentIntent = PaymentIntent.findByProviderPaymentId(tenantId, providerPaymentId);
        if (paymentIntent == null) {
            LOGGER.warnf("[Tenant: %s] Payment intent %s not found for webhook update", tenantId, providerPaymentId);
            return;
        }

        paymentIntent.status = status;
        if (status == PaymentIntent.PaymentStatus.CAPTURED) {
            BigDecimal captured = amountFromCents(objectNode.path("amount_received"));
            if (captured != null) {
                paymentIntent.amountCaptured = captured;
            }
        }
        paymentIntent.updatedAt = Instant.now();
    }

    private void handleChargeRefunded(UUID tenantId, JsonNode payload) {
        JsonNode objectNode = payload.path("data").path("object");
        String paymentIntentId = objectNode.path("payment_intent").asText(null);
        if (paymentIntentId == null) {
            LOGGER.warnf("[Tenant: %s] charge.refunded missing payment_intent reference", tenantId);
            return;
        }

        PaymentIntent paymentIntent = PaymentIntent.findByProviderPaymentId(tenantId, paymentIntentId);
        if (paymentIntent == null) {
            LOGGER.warnf("[Tenant: %s] charge.refunded could not find payment intent %s", tenantId, paymentIntentId);
            return;
        }

        BigDecimal refunded = amountFromCents(objectNode.path("amount_refunded"));
        if (refunded != null) {
            paymentIntent.amountRefunded = refunded;
        }
        paymentIntent.updatedAt = Instant.now();
    }

    private void handleDisputeCreated(UUID tenantId, JsonNode payload) {
        JsonNode objectNode = payload.path("data").path("object");
        String paymentIntentId = objectNode.path("payment_intent").asText(null);
        if (paymentIntentId == null) {
            LOGGER.warnf("[Tenant: %s] charge.dispute.created missing payment_intent", tenantId);
            return;
        }

        PaymentIntent paymentIntent = PaymentIntent.findByProviderPaymentId(tenantId, paymentIntentId);
        if (paymentIntent == null) {
            LOGGER.warnf("[Tenant: %s] Unable to mark dispute for payment intent %s", tenantId, paymentIntentId);
            return;
        }

        paymentIntent.status = PaymentIntent.PaymentStatus.DISPUTED;
        paymentIntent.updatedAt = Instant.now();
    }

    private void handlePayoutUpdate(UUID tenantId, JsonNode payload, boolean success) {
        JsonNode objectNode = payload.path("data").path("object");
        String payoutId = objectNode.path("id").asText(null);
        if (payoutId == null) {
            LOGGER.warnf("[Tenant: %s] payout webhook missing id", tenantId);
            return;
        }

        PayoutBatch payoutBatch = PayoutBatch.find("tenant.id = ?1 and paymentReference = ?2", tenantId, payoutId)
                .firstResult();
        if (payoutBatch == null) {
            LOGGER.debugf("[Tenant: %s] No payout batch found for payout %s", tenantId, payoutId);
            return;
        }

        payoutBatch.status = success ? "completed" : "failed";
        payoutBatch.processedAt = OffsetDateTime.now();
    }

    private void handleAccountUpdated(UUID tenantId, JsonNode payload) {
        JsonNode objectNode = payload.path("data").path("object");
        String accountId = objectNode.path("id").asText(null);
        if (accountId == null) {
            LOGGER.warnf("[Tenant: %s] account.updated missing account id", tenantId);
            return;
        }

        ConnectAccount connectAccount = ConnectAccount.findByProviderAccountId(tenantId, accountId);
        if (connectAccount == null) {
            connectAccount = new ConnectAccount();
            connectAccount.provider = PROVIDER_NAME;
            connectAccount.providerAccountId = accountId;
            connectAccount.onboardingStatus = ConnectAccount.OnboardingStatus.PENDING;
        }

        connectAccount.email = objectNode.path("email").asText(connectAccount.email);
        connectAccount.businessName = objectNode.path("business_profile").path("name")
                .asText(connectAccount.businessName);
        connectAccount.country = objectNode.path("country").asText(connectAccount.country);
        connectAccount.payoutsEnabled = objectNode.path("payouts_enabled").asBoolean(connectAccount.payoutsEnabled);
        connectAccount.chargesEnabled = objectNode.path("charges_enabled").asBoolean(connectAccount.chargesEnabled);
        connectAccount.capabilitiesEnabled = objectNode.path("capabilities").toString();
        connectAccount.metadata = objectNode.path("metadata").isMissingNode() ? connectAccount.metadata
                : objectNode.path("metadata").toString();
        connectAccount.onboardingStatus = deriveOnboardingStatus(objectNode);

        if (connectAccount.id == null) {
            connectAccount.persist();
        }
    }

    private ConnectAccount.OnboardingStatus deriveOnboardingStatus(JsonNode accountNode) {
        boolean payoutsEnabled = accountNode.path("payouts_enabled").asBoolean(false);
        boolean chargesEnabled = accountNode.path("charges_enabled").asBoolean(false);
        boolean detailsSubmitted = accountNode.path("details_submitted").asBoolean(false);
        String disabledReason = accountNode.path("requirements").path("disabled_reason").asText(null);

        if (payoutsEnabled && chargesEnabled) {
            return ConnectAccount.OnboardingStatus.COMPLETED;
        } else if (disabledReason != null && !disabledReason.isBlank()) {
            return ConnectAccount.OnboardingStatus.RESTRICTED;
        } else if (detailsSubmitted) {
            return ConnectAccount.OnboardingStatus.IN_PROGRESS;
        }
        return ConnectAccount.OnboardingStatus.PENDING;
    }

    private BigDecimal amountFromCents(JsonNode amountNode) {
        if (amountNode.isNumber()) {
            return BigDecimal.valueOf(amountNode.asLong()).movePointLeft(2);
        }
        return null;
    }
}
