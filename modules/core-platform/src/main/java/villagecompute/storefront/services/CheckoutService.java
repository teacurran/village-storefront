package villagecompute.storefront.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.Cart;
import villagecompute.storefront.data.models.CartItem;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.Address;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.AddressValidationRequest;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.AddressValidationResult;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.Rate;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.ServiceLevel;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.ValidationStatus;
import villagecompute.storefront.loyalty.LoyaltyService;
import villagecompute.storefront.services.ShippingService.AggregatedRateResult;
import villagecompute.storefront.services.ShippingService.ShippingRateRequest;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Service orchestrating checkout pre-flight operations including address validation, shipping rate calculation, totals
 * computation, and loyalty point provisioning.
 *
 * <p>
 * This service provides read-only checkout preparation (with the exception of provisional loyalty reservations) that
 * precedes the commit phase handled by {@link CheckoutSaga}. It coordinates:
 * <ul>
 * <li>Address validation via {@link ShippingService}</li>
 * <li>Shipping rate aggregation and selection</li>
 * <li>Tax calculation (placeholder for future integration)</li>
 * <li>Totals computation with itemized breakdown</li>
 * <li>Provisional loyalty point reservations (if enabled)</li>
 * </ul>
 *
 * <p>
 * All operations respect tenant context and feature flags. Invalid addresses produce actionable errors per RFC 7807
 * contract patterns.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Checkout orchestrator with address validation and shipping rate integration</li>
 * <li>Section 3.2.7: Checkout and Order Orchestrator architecture</li>
 * <li>Section 5: Contract Patterns for error handling and DTOs</li>
 * </ul>
 */
@ApplicationScoped
public class CheckoutService {

    private static final Logger LOG = Logger.getLogger(CheckoutService.class);
    private static final BigDecimal DEFAULT_PACKAGE_LENGTH_IN = new BigDecimal("12.0");
    private static final BigDecimal DEFAULT_PACKAGE_WIDTH_IN = new BigDecimal("8.0");
    private static final BigDecimal DEFAULT_PACKAGE_HEIGHT_IN = new BigDecimal("4.0");
    private static final BigDecimal DEFAULT_ITEM_WEIGHT_OZ = new BigDecimal("16.0");

    @Inject
    CartService cartService;

    @Inject
    ShippingService shippingService;

    @Inject
    LoyaltyService loyaltyService;

    @Inject
    FeatureToggle featureToggle;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Prepare checkout summary with address validation, shipping options, totals, and loyalty reservation.
     *
     * @param request
     *            checkout preparation request
     * @return checkout summary with all calculated values
     */
    @Transactional
    public CheckoutSummary prepareCheckout(CheckoutPreparationRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String correlationId = request.correlationId() != null ? request.correlationId() : UUID.randomUUID().toString();

        LOG.infof("Preparing checkout - tenantId=%s, cartId=%s, correlationId=%s", tenantId, request.cartId(),
                correlationId);

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // Step 1: Load and validate cart
            Cart cart = cartService.getCart(request.cartId())
                    .orElseThrow(() -> new IllegalArgumentException("Cart not found: " + request.cartId()));
            List<CartItem> cartItems = cartService.getCartItems(cart.id);

            if (cartItems.isEmpty()) {
                throw new IllegalStateException("Cannot prepare checkout for empty cart");
            }

            // Step 2: Validate shipping address
            AddressValidationResult addressValidation = validateShippingAddress(request.shippingAddress(),
                    correlationId);

            if (addressValidation.status() == ValidationStatus.INVALID) {
                throw new IllegalArgumentException("Invalid shipping address: " + addressValidation.errorMessage());
            }

            Address validatedAddress = addressValidation.normalizedAddress() != null
                    ? addressValidation.normalizedAddress()
                    : parseAddress(request.shippingAddress());

            // Step 3: Fetch shipping rate options
            List<ShippingOption> shippingOptions = fetchShippingOptions(cartItems, request.originAddress(),
                    validatedAddress, correlationId);

            // Step 4: Calculate subtotal
            BigDecimal subtotal = calculateSubtotal(cartItems);

            // Step 5: Apply discounts and calculate tax (placeholder for future)
            BigDecimal discountAmount = BigDecimal.ZERO;
            BigDecimal taxAmount = BigDecimal.ZERO; // TODO: Integrate with tax calculation service

            // Step 6: Provisional loyalty reservation (if requested and enabled)
            LoyaltyReservation loyaltyReservation = null;
            if (request.applyLoyaltyPoints() != null && request.applyLoyaltyPoints() > 0
                    && featureToggle.isEnabled("loyalty.enabled")) {
                loyaltyReservation = reserveLoyaltyPoints(request.userId(), request.applyLoyaltyPoints(),
                        correlationId);
                discountAmount = discountAmount.add(loyaltyReservation.discountValue());
            }

            // Step 7: Select shipping option (use cheapest if not specified)
            ShippingOption selectedShipping = selectShippingOption(shippingOptions, request.preferredShippingService());
            BigDecimal shippingAmount = selectedShipping != null ? selectedShipping.cost() : BigDecimal.ZERO;

            // Step 8: Calculate grand total
            BigDecimal total = subtotal.subtract(discountAmount).add(taxAmount).add(shippingAmount);

            if (total.compareTo(BigDecimal.ZERO) < 0) {
                total = BigDecimal.ZERO;
            }

            // Build summary response
            String currency = "USD"; // Default currency (cart-level currency to be added in future task)
            CheckoutTotals totals = new CheckoutTotals(subtotal, discountAmount, taxAmount, shippingAmount, total,
                    currency);

            CheckoutSummary summary = new CheckoutSummary(cart.id, totals, shippingOptions, selectedShipping,
                    addressValidation, loyaltyReservation, currency, OffsetDateTime.now());

            sample.stop(meterRegistry.timer("checkout.prepare", "status", "success"));
            meterRegistry.counter("checkout.prepare.completed", "tenant_id", tenantId.toString(), "status", "success")
                    .increment();

            LOG.infof("Checkout prepared successfully - tenantId=%s, cartId=%s, total=%s, correlationId=%s", tenantId,
                    cart.id, total, correlationId);

            return summary;

        } catch (IllegalArgumentException | IllegalStateException e) {
            sample.stop(meterRegistry.timer("checkout.prepare", "status", "validation_error"));
            meterRegistry.counter("checkout.prepare.completed", "tenant_id", tenantId.toString(), "status", "error")
                    .increment();
            LOG.warnf("Checkout preparation failed - tenantId=%s, cartId=%s, error=%s, correlationId=%s", tenantId,
                    request.cartId(), e.getMessage(), correlationId);
            throw e;

        } catch (RuntimeException e) {
            sample.stop(meterRegistry.timer("checkout.prepare", "status", "error"));
            meterRegistry.counter("checkout.prepare.completed", "tenant_id", tenantId.toString(), "status", "error")
                    .increment();
            LOG.errorf(e, "Checkout preparation failed - tenantId=%s, cartId=%s, correlationId=%s", tenantId,
                    request.cartId(), correlationId);
            throw new RuntimeException("Failed to prepare checkout: " + e.getMessage(), e);
        }
    }

    /**
     * Validate shipping address using configured providers.
     */
    private AddressValidationResult validateShippingAddress(CheckoutAddress address, String correlationId) {
        if (address == null) {
            throw new IllegalArgumentException("Shipping address is required");
        }

        AddressValidationRequest validationRequest = new AddressValidationRequest(address.street1(), address.street2(),
                address.city(), address.state(), address.postalCode(),
                address.country() != null ? address.country() : "US", correlationId);

        try {
            return shippingService.validateAddress(validationRequest, correlationId);
        } catch (Exception e) {
            LOG.warnf(e, "Address validation failed - correlationId=%s", correlationId);
            // Return provider unavailable status with original address
            Address fallbackAddress = new Address(address.street1(), address.street2(), address.city(), address.state(),
                    address.postalCode(), address.country() != null ? address.country() : "US",
                    address.residential() != null ? address.residential() : true);
            return new AddressValidationResult(ValidationStatus.PROVIDER_UNAVAILABLE, fallbackAddress,
                    List.of("Address validation service temporarily unavailable"), e.getMessage(), java.util.Map.of());
        }
    }

    /**
     * Fetch available shipping rate options for cart.
     */
    private List<ShippingOption> fetchShippingOptions(List<CartItem> cartItems, CheckoutAddress origin,
            Address destination, String correlationId) {
        try {
            Address originAddress = origin != null ? parseAddress(origin)
                    : new Address("123 Main St", null, "San Francisco", "CA", "94102", "US", false);

            CarrierRateAdapter.Package shipmentPackage = buildPackageFromCart(cartItems);

            List<ServiceLevel> requestedLevels = List.of(ServiceLevel.GROUND, ServiceLevel.PRIORITY,
                    ServiceLevel.TWO_DAY, ServiceLevel.NEXT_DAY_AIR);

            ShippingRateRequest rateRequest = new ShippingRateRequest(originAddress, destination, shipmentPackage,
                    requestedLevels);

            AggregatedRateResult rateResult = shippingService.fetchRates(rateRequest, correlationId);

            List<ShippingOption> options = new ArrayList<>();
            for (Rate rate : rateResult.rates()) {
                ShippingOption option = new ShippingOption(rate.carrierCode(), rate.serviceLevel(), rate.serviceName(),
                        rate.cost(), rate.currency(), rate.estimatedDays(), rate.estimatedDelivery(),
                        rateResult.fallbackUsed());
                options.add(option);
            }

            if (options.isEmpty()) {
                LOG.warnf("No shipping rates available - correlationId=%s, fallbackUsed=%s, fallbackReason=%s",
                        correlationId, rateResult.fallbackUsed(), rateResult.fallbackReason());
                throw new IllegalStateException("No shipping rates available. "
                        + (rateResult.fallbackReason() != null ? "Reason: " + rateResult.fallbackReason()
                                : "Please try again later."));
            }

            return options;

        } catch (IllegalStateException e) {
            // Surface actionable shipping errors (e.g., no rates) to callers
            throw e;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to fetch shipping rates - correlationId=%s", correlationId);
            throw new RuntimeException("Failed to calculate shipping rates: " + e.getMessage(), e);
        }
    }

    /**
     * Reserve loyalty points provisionally (can be rolled back if checkout fails).
     */
    private LoyaltyReservation reserveLoyaltyPoints(UUID userId, int pointsToRedeem, String correlationId) {
        if (userId == null) {
            throw new IllegalArgumentException("Loyalty redemption requires authenticated user");
        }

        try {
            Optional<villagecompute.storefront.data.models.LoyaltyMember> memberOpt = loyaltyService
                    .getMemberByUser(userId);

            if (memberOpt.isEmpty()) {
                throw new IllegalArgumentException("User not enrolled in loyalty program");
            }

            villagecompute.storefront.data.models.LoyaltyMember member = memberOpt.get();

            if (pointsToRedeem > member.pointsBalance) {
                throw new IllegalArgumentException("Insufficient loyalty points. Available: " + member.pointsBalance
                        + ", requested: " + pointsToRedeem);
            }

            BigDecimal discountValue = loyaltyService.calculateRedemptionValue(pointsToRedeem);

            LOG.infof("Loyalty reservation successful - userId=%s, points=%d, discount=%s, correlationId=%s", userId,
                    pointsToRedeem, discountValue, correlationId);

            meterRegistry
                    .counter("checkout.loyalty.reserved", "tenant_id", TenantContext.getCurrentTenantId().toString())
                    .increment();

            return new LoyaltyReservation(pointsToRedeem, member.pointsBalance, discountValue, "USD", true, null);

        } catch (IllegalArgumentException e) {
            LOG.warnf("Loyalty reservation failed - userId=%s, points=%d, error=%s, correlationId=%s", userId,
                    pointsToRedeem, e.getMessage(), correlationId);
            return new LoyaltyReservation(pointsToRedeem, 0, BigDecimal.ZERO, "USD", false, e.getMessage());

        } catch (Exception e) {
            LOG.errorf(e, "Loyalty reservation error - userId=%s, points=%d, correlationId=%s", userId, pointsToRedeem,
                    correlationId);
            return new LoyaltyReservation(pointsToRedeem, 0, BigDecimal.ZERO, "USD", false,
                    "Loyalty service temporarily unavailable");
        }
    }

    /**
     * Calculate cart subtotal.
     */
    private BigDecimal calculateSubtotal(List<CartItem> cartItems) {
        return cartItems.stream().map(item -> item.unitPrice.multiply(new BigDecimal(item.quantity)))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Select shipping option based on preference or default to cheapest.
     */
    private ShippingOption selectShippingOption(List<ShippingOption> options, String preferredService) {
        if (options.isEmpty()) {
            return null;
        }

        // If preferred service specified, find matching option
        if (preferredService != null && !preferredService.isBlank()) {
            for (ShippingOption option : options) {
                if (option.serviceLevel().name().equalsIgnoreCase(preferredService)
                        || option.serviceName().equalsIgnoreCase(preferredService)) {
                    return option;
                }
            }
            LOG.warnf("Preferred shipping service not found: %s, using cheapest option", preferredService);
        }

        // Default to cheapest option
        return options.stream().min((o1, o2) -> o1.cost().compareTo(o2.cost())).orElse(options.get(0));
    }

    /**
     * Build package dimensions and weight from cart items.
     */
    private CarrierRateAdapter.Package buildPackageFromCart(List<CartItem> cartItems) {
        BigDecimal totalWeightOz = cartItems.stream()
                .map(item -> convertToOunces(item.variant.weight, item.variant.weightUnit)
                        .multiply(new BigDecimal(item.quantity)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalWeightOz.compareTo(BigDecimal.ZERO) <= 0) {
            int quantity = cartItems.stream().mapToInt(item -> item.quantity).sum();
            totalWeightOz = DEFAULT_ITEM_WEIGHT_OZ.multiply(BigDecimal.valueOf(quantity > 0 ? quantity : 1));
        }

        BigDecimal declaredValue = cartItems.stream()
                .map(item -> item.unitPrice.multiply(new BigDecimal(item.quantity)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new CarrierRateAdapter.Package(totalWeightOz, DEFAULT_PACKAGE_LENGTH_IN, DEFAULT_PACKAGE_WIDTH_IN,
                DEFAULT_PACKAGE_HEIGHT_IN, declaredValue, "Checkout shipment");
    }

    /**
     * Convert weight to ounces.
     */
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

    /**
     * Parse checkout address to shipping adapter address.
     */
    private Address parseAddress(CheckoutAddress address) {
        return new Address(address.street1(), address.street2(), address.city(), address.state(), address.postalCode(),
                address.country() != null ? address.country() : "US",
                address.residential() != null ? address.residential() : true);
    }

    // ========================================
    // REQUEST/RESPONSE RECORDS
    // ========================================

    /**
     * Request for checkout preparation.
     */
    public record CheckoutPreparationRequest(UUID cartId, CheckoutAddress shippingAddress,
            CheckoutAddress originAddress, UUID userId, Integer applyLoyaltyPoints, String preferredShippingService,
            String correlationId) {
    }

    /**
     * Address representation for checkout requests.
     */
    public record CheckoutAddress(String street1, String street2, String city, String state, String postalCode,
            String country, Boolean residential) {
    }

    /**
     * Complete checkout summary with all calculated values.
     */
    public record CheckoutSummary(UUID cartId, CheckoutTotals totals, List<ShippingOption> shippingOptions,
            ShippingOption selectedShipping, AddressValidationResult addressValidation,
            LoyaltyReservation loyaltyReservation, String currency, OffsetDateTime calculatedAt) {
    }

    /**
     * Itemized totals breakdown.
     */
    public record CheckoutTotals(BigDecimal subtotal, BigDecimal discount, BigDecimal tax, BigDecimal shipping,
            BigDecimal total, String currency) {
    }

    /**
     * Shipping option with carrier and service details.
     */
    public record ShippingOption(String carrierCode, ServiceLevel serviceLevel, String serviceName, BigDecimal cost,
            String currency, Integer estimatedDays, OffsetDateTime estimatedDelivery, boolean fallbackUsed) {
    }

    /**
     * Provisional loyalty point reservation.
     */
    public record LoyaltyReservation(int pointsRequested, int availableBalance, BigDecimal discountValue,
            String currency, boolean success, String errorMessage) {
    }
}
