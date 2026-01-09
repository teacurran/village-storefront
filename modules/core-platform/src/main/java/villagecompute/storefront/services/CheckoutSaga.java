package villagecompute.storefront.services;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import villagecompute.storefront.data.models.Cart;
import villagecompute.storefront.data.models.CartItem;
import villagecompute.storefront.data.models.IdempotencyKey;
import villagecompute.storefront.data.models.Order;
import villagecompute.storefront.data.models.PaymentIntent;
import villagecompute.storefront.exceptions.CheckoutDisabledException;
import villagecompute.storefront.exceptions.IdempotencyConflictException;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Choreographed checkout saga that coordinates cart validation, order creation, payment processing, and
 * idempotency/compensation handling.
 *
 * <p>
 * Implements the workflow described in ADR-003 with transactional safeguards so duplicate requests cannot create
 * multiple orders or charge a customer twice.
 * </p>
 */
@ApplicationScoped
public class CheckoutSaga {

    private static final Logger LOG = Logger.getLogger(CheckoutSaga.class);
    private static final String IDEMPOTENCY_OPERATION = "checkout";

    @Inject
    FeatureToggle featureToggle;

    @Inject
    IdempotencyService idempotencyService;

    @Inject
    CartService cartService;

    @Inject
    OrderService orderService;

    @Inject
    PaymentService paymentService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Execute the checkout flow for the given request.
     *
     * @param request
     *            checkout request payload
     * @return checkout result
     */
    @Transactional
    public CheckoutResult execute(CheckoutRequest request) {
        Objects.requireNonNull(request, "Checkout request is required");

        if (!featureToggle.isCheckoutEnabled()) {
            throw new CheckoutDisabledException("Checkout temporarily disabled via feature flag");
        }

        String idempotencyKey = normalizeKey(request.idempotencyKey());
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            IdempotencyKey keyRecord = idempotencyService.acquire(idempotencyKey, IDEMPOTENCY_OPERATION, 60);
            if (keyRecord.isSuccess() && keyRecord.result != null) {
                CheckoutResult cached = deserializeResult(keyRecord.result);
                meterRegistry.counter("checkout.saga.completed", "result", "cached").increment();
                return cached;
            }

            CheckoutContext context = new CheckoutContext(idempotencyKey);
            CheckoutResult result = executeOnce(request, context);
            idempotencyService.markSuccess(idempotencyKey, serializeResult(result), 200);
            meterRegistry.counter("checkout.saga.completed", "result", "success").increment();
            sample.stop(meterRegistry.timer("checkout.saga.duration", "result", "success"));
            return result;
        } catch (IdempotencyConflictException e) {
            sample.stop(meterRegistry.timer("checkout.saga.duration", "result", "conflict"));
            meterRegistry.counter("checkout.saga.completed", "result", "conflict").increment();
            throw e;
        } catch (RuntimeException e) {
            sample.stop(meterRegistry.timer("checkout.saga.duration", "result", "failure"));
            meterRegistry.counter("checkout.saga.completed", "result", "failure").increment();
            // Compensation + idempotency cleanup handled inside executeOnce
            throw e;
        }
    }

    private CheckoutResult executeOnce(CheckoutRequest request, CheckoutContext context) {
        try {
            validateRequest(request);
            Cart cart = cartService.getCart(request.cartId())
                    .orElseThrow(() -> new IllegalArgumentException("Cart not found: " + request.cartId()));
            List<CartItem> cartItems = cartService.getCartItems(cart.id);
            if (cartItems.isEmpty()) {
                throw new IllegalStateException("Cannot checkout an empty cart");
            }

            Order order = orderService.createOrderFromCart(cart, request.customerEmail(), request.shippingAddress(),
                    request.billingAddress(), defaultBigDecimal(request.shippingAmount()),
                    defaultBigDecimal(request.taxAmount()), request.currency());
            context.orderCreated(order.id);

            PaymentIntent paymentIntent = paymentService.createPaymentIntent(order.totalAmount, order.currency,
                    order.id, true, context.idempotencyKey());
            orderService.markOrderPaid(order.id, paymentIntent.providerPaymentId);

            cartService.clearCart(cart.id);

            return new CheckoutResult(order.id, order.orderNumber, paymentIntent.providerPaymentId, order.status.name(),
                    order.totalAmount, order.currency, order.paidAt);
        } catch (RuntimeException e) {
            handleFailure(context, e);
            throw e;
        }
    }

    private void handleFailure(CheckoutContext context, RuntimeException e) {
        LOG.errorf(e, "Checkout failed for key=%s, executing compensation", context.idempotencyKey());
        if (context.orderId != null && context.orderCreated) {
            try {
                orderService.cancelOrder(context.orderId, "Checkout failed: " + e.getMessage());
            } catch (Exception cancelEx) {
                LOG.warnf(cancelEx, "Failed to cancel order %s during checkout compensation", context.orderId);
            }
        }

        try {
            idempotencyService.markFailed(context.idempotencyKey(), serializeError(e), determineStatusCode(e));
        } catch (Exception markEx) {
            LOG.warnf(markEx, "Failed to mark idempotency key %s as failed", context.idempotencyKey());
        }
    }

    private String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency key is required");
        }
        return key.trim();
    }

    private void validateRequest(CheckoutRequest request) {
        if (request.cartId() == null) {
            throw new IllegalArgumentException("Cart ID is required");
        }
        if (request.customerEmail() == null || request.customerEmail().isBlank()) {
            throw new IllegalArgumentException("Customer email is required");
        }
        if (request.shippingAddress() == null || request.shippingAddress().isBlank()) {
            throw new IllegalArgumentException("Shipping address is required");
        }
        if (request.billingAddress() == null || request.billingAddress().isBlank()) {
            throw new IllegalArgumentException("Billing address is required");
        }
    }

    private CheckoutResult deserializeResult(String payload) {
        try {
            return objectMapper.readValue(payload, CheckoutResult.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize cached checkout result", e);
        }
    }

    private String serializeResult(CheckoutResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize checkout result", e);
        }
    }

    private String serializeError(Throwable e) {
        try {
            return objectMapper.writeValueAsString(new CheckoutError(e.getClass().getSimpleName(), e.getMessage()));
        } catch (JsonProcessingException ex) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private int determineStatusCode(Throwable e) {
        if (e instanceof CheckoutDisabledException) {
            return 503;
        }
        if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) {
            return 400;
        }
        return 500;
    }

    private BigDecimal defaultBigDecimal(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /**
     * Request payload for checkout execution.
     */
    public record CheckoutRequest(UUID cartId, String customerEmail, String shippingAddress, String billingAddress,
            BigDecimal shippingAmount, BigDecimal taxAmount, String currency, String paymentMethodId,
            String idempotencyKey) {
    }

    /**
     * Checkout result returned to API clients and cached in the idempotency table.
     */
    public record CheckoutResult(UUID orderId, String orderNumber, String paymentIntentId, String orderStatus,
            BigDecimal totalAmount, String currency, OffsetDateTime paidAt) {
    }

    /**
     * Lightweight error payload recorded for idempotency failures.
     */
    public record CheckoutError(String type, String message) {
    }

    private static final class CheckoutContext {

        private final String idempotencyKey;
        private UUID orderId;
        private boolean orderCreated;

        CheckoutContext(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
        }

        void orderCreated(UUID orderId) {
            this.orderId = orderId;
            this.orderCreated = true;
        }

        String idempotencyKey() {
            return idempotencyKey;
        }
    }
}
