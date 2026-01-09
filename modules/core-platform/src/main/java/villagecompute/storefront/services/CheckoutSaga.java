package villagecompute.storefront.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import villagecompute.storefront.data.models.Cart;
import villagecompute.storefront.data.models.CartItem;
import villagecompute.storefront.data.models.IdempotencyKey;
import villagecompute.storefront.data.models.Order;
import villagecompute.storefront.data.models.PaymentIntent;
import villagecompute.storefront.data.models.ShippingProfile;
import villagecompute.storefront.exceptions.CheckoutDisabledException;
import villagecompute.storefront.exceptions.IdempotencyConflictException;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.Address;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.AddressValidationRequest;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.AddressValidationResult;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.Rate;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.ServiceLevel;
import villagecompute.storefront.services.ShippingService.AggregatedRateResult;
import villagecompute.storefront.tenant.TenantContext;

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
    private static final BigDecimal DEFAULT_ITEM_WEIGHT_OZ = new BigDecimal("16.0");
    private static final BigDecimal DEFAULT_PACKAGE_LENGTH_IN = new BigDecimal("12.0");
    private static final BigDecimal DEFAULT_PACKAGE_WIDTH_IN = new BigDecimal("8.0");
    private static final BigDecimal DEFAULT_PACKAGE_HEIGHT_IN = new BigDecimal("4.0");

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

    @Inject
    ShippingService shippingService;

    @Inject
    ShippingProfileService shippingProfileService;

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

            CheckoutShippingDecision shippingDecision = prepareShippingDecision(request, cartItems,
                    context.idempotencyKey());

            Order order = orderService.createOrderFromCart(cart, request.customerEmail(),
                    shippingDecision.shippingAddressJson(), request.billingAddress(), shippingDecision.shippingAmount(),
                    defaultBigDecimal(request.taxAmount()), request.currency());
            attachShippingMetadata(order, shippingDecision);
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

    private CheckoutShippingDecision prepareShippingDecision(CheckoutRequest request, List<CartItem> cartItems,
            String correlationId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        AddressComponents originalAddress = parseShippingAddress(request.shippingAddress());
        AddressValidationResult validationResult = validateAddress(originalAddress, correlationId);
        String normalizedAddressJson = request.shippingAddress();

        if (validationResult != null && validationResult.normalizedAddress() != null) {
            normalizedAddressJson = writeAddress(validationResult.normalizedAddress());
        }

        BigDecimal shippingAmount = defaultBigDecimal(request.shippingAmount());
        Rate selectedRate = null;
        boolean fallbackUsed = false;
        String fallbackReason = null;

        if (request.shippingAmount() == null) {
            Optional<ShippingProfile> profile = shippingProfileService.findDefaultProfile(tenantId);
            if (profile.isPresent()) {
                RateSelection selection = fetchBestRate(profile.get(), cartItems, validationResult, originalAddress,
                        correlationId);
                selectedRate = selection.rate();
                shippingAmount = selection.rate().cost();
                fallbackUsed = selection.fallbackUsed();
                fallbackReason = selection.fallbackReason();
            } else {
                LOG.warnf("No shipping profile configured - tenantId=%s, correlationId=%s", tenantId, correlationId);
                shippingAmount = BigDecimal.ZERO;
                fallbackUsed = true;
                fallbackReason = "NO_PROFILE_CONFIGURED";
            }
        }

        return new CheckoutShippingDecision(shippingAmount, normalizedAddressJson, selectedRate, fallbackUsed,
                fallbackReason, validationResult);
    }

    private RateSelection fetchBestRate(ShippingProfile profile, List<CartItem> cartItems,
            AddressValidationResult validationResult, AddressComponents destination, String correlationId) {
        Address originAddress = parseOriginAddress(profile.originAddress);
        Address destinationAddress = validationResult != null && validationResult.normalizedAddress() != null
                ? validationResult.normalizedAddress()
                : toAdapterAddress(destination);

        ShippingService.ShippingRateRequest rateRequest = new ShippingService.ShippingRateRequest(originAddress,
                destinationAddress, buildPackage(cartItems), defaultServiceLevels());
        AggregatedRateResult rateResult = shippingService.fetchRates(rateRequest, correlationId);

        Optional<Rate> cheapest = rateResult.rates().stream().filter(rate -> rate.cost() != null)
                .min(Comparator.comparing(Rate::cost));

        Rate selected = cheapest
                .orElseThrow(() -> new IllegalStateException("No shipping rates available for this order"));
        return new RateSelection(selected, rateResult.fallbackUsed(), rateResult.fallbackReason());
    }

    private AddressValidationResult validateAddress(AddressComponents destination, String correlationId) {
        try {
            AddressValidationRequest request = new AddressValidationRequest(destination.street1(),
                    destination.street2(), destination.city(), destination.state(), destination.postalCode(),
                    destination.country(), correlationId);
            return shippingService.validateAddress(request, correlationId);
        } catch (Exception e) {
            LOG.warnf(e, "Address validation failed - correlationId=%s", correlationId);
            Address fallbackAddress = toAdapterAddress(destination);
            return new AddressValidationResult(CarrierRateAdapter.ValidationStatus.PROVIDER_UNAVAILABLE,
                    fallbackAddress, Collections.emptyList(), e.getMessage(), Collections.emptyMap());
        }
    }

    private Address parseOriginAddress(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return new Address(node.path("street1").asText(), node.path("street2").asText(null),
                    node.path("city").asText(), node.path("state").asText(), node.path("postalCode").asText(),
                    node.path("country").asText("US"), node.path("residential").asBoolean(false));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid origin address configuration for shipping profile", e);
        }
    }

    private CarrierRateAdapter.Package buildPackage(List<CartItem> cartItems) {
        BigDecimal declaredValue = cartItems.stream()
                .map(item -> item.unitPrice.multiply(new BigDecimal(item.quantity)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalWeightOz = cartItems.stream()
                .map(item -> convertToOunces(item.variant.weight, item.variant.weightUnit)
                        .multiply(new BigDecimal(item.quantity)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalWeightOz.compareTo(BigDecimal.ZERO) <= 0) {
            int quantity = cartItems.stream().mapToInt(item -> item.quantity).sum();
            totalWeightOz = DEFAULT_ITEM_WEIGHT_OZ.multiply(BigDecimal.valueOf(quantity > 0 ? quantity : 1));
        }

        if (declaredValue.compareTo(BigDecimal.ZERO) < 0) {
            declaredValue = BigDecimal.ZERO;
        }

        return new CarrierRateAdapter.Package(totalWeightOz, DEFAULT_PACKAGE_LENGTH_IN, DEFAULT_PACKAGE_WIDTH_IN,
                DEFAULT_PACKAGE_HEIGHT_IN, declaredValue, "Checkout shipment");
    }

    private BigDecimal convertToOunces(BigDecimal weight, String unit) {
        if (weight == null || unit == null) {
            return BigDecimal.ZERO;
        }
        String normalizedUnit = unit.toLowerCase();
        BigDecimal value = switch (normalizedUnit) {
            case "lb", "lbs" -> weight.multiply(new BigDecimal("16"));
            case "kg" -> weight.multiply(new BigDecimal("35.274"));
            case "g" -> weight.multiply(new BigDecimal("0.035274"));
            case "oz", "ounce", "ounces" -> weight;
            default -> BigDecimal.ZERO;
        };
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private List<ServiceLevel> defaultServiceLevels() {
        List<ServiceLevel> levels = new ArrayList<>();
        levels.add(ServiceLevel.GROUND);
        levels.add(ServiceLevel.PRIORITY);
        levels.add(ServiceLevel.TWO_DAY);
        levels.add(ServiceLevel.NEXT_DAY_AIR);
        return levels;
    }

    private AddressComponents parseShippingAddress(String shippingAddress) {
        if (shippingAddress == null || shippingAddress.isBlank()) {
            throw new IllegalArgumentException("Shipping address is required for checkout");
        }
        try {
            JsonNode node = objectMapper.readTree(shippingAddress);
            return new AddressComponents(node.path("line1").asText(), node.path("line2").asText(null),
                    node.path("city").asText(), node.path("state").asText(), node.path("postalCode").asText(),
                    node.path("country").asText("US"), node.path("residential").asBoolean(true));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid shipping address payload", e);
        }
    }

    private Address toAdapterAddress(AddressComponents components) {
        return new Address(components.street1(), components.street2(), components.city(), components.state(),
                components.postalCode(), components.country(), components.residential());
    }

    private String writeAddress(Address address) {
        try {
            HashMap<String, Object> payload = new HashMap<>();
            payload.put("line1", address.street1());
            payload.put("line2", address.street2());
            payload.put("city", address.city());
            payload.put("state", address.state());
            payload.put("postalCode", address.postalCode());
            payload.put("country", address.country());
            payload.put("residential", address.residential());
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize normalized address", e);
        }
    }

    private void attachShippingMetadata(Order order, CheckoutShippingDecision decision) {
        HashMap<String, Object> metadata = new HashMap<>();
        if (decision.selectedRate() != null) {
            metadata.put("carrierCode", decision.selectedRate().carrierCode());
            metadata.put("serviceLevel", decision.selectedRate().serviceLevel().name());
            metadata.put("serviceName", decision.selectedRate().serviceName());
            metadata.put("rateCurrency", decision.selectedRate().currency());
            metadata.put("rateCost", decision.selectedRate().cost());
            metadata.put("estimatedDelivery", decision.selectedRate().estimatedDelivery());
        }
        metadata.put("fallbackUsed", decision.fallbackUsed());
        if (decision.fallbackReason() != null) {
            metadata.put("fallbackReason", decision.fallbackReason());
        }
        if (decision.validationResult() != null) {
            metadata.put("addressValidationStatus", decision.validationResult().status().name());
            metadata.put("addressWarnings", decision.validationResult().warnings());
        }

        if (metadata.isEmpty()) {
            return;
        }

        try {
            ObjectNode root = order.metadata != null ? (ObjectNode) objectMapper.readTree(order.metadata)
                    : objectMapper.createObjectNode();
            root.set("shipping", objectMapper.valueToTree(metadata));
            order.metadata = objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to persist shipping metadata for order %s", order.id);
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

    private record CheckoutShippingDecision(BigDecimal shippingAmount, String shippingAddressJson, Rate selectedRate,
            boolean fallbackUsed, String fallbackReason, AddressValidationResult validationResult) {
    }

    private record RateSelection(Rate rate, boolean fallbackUsed, String fallbackReason) {
    }

    private record AddressComponents(String street1, String street2, String city, String state, String postalCode,
            String country, boolean residential) {
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
