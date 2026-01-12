package villagecompute.storefront.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.Cart;
import villagecompute.storefront.data.models.CartItem;
import villagecompute.storefront.data.models.LoyaltyMember;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.Address;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.AddressValidationRequest;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.AddressValidationResult;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.Rate;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.ServiceLevel;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.ValidationStatus;
import villagecompute.storefront.loyalty.LoyaltyService;
import villagecompute.storefront.services.CheckoutService.CheckoutAddress;
import villagecompute.storefront.services.CheckoutService.CheckoutPreparationRequest;
import villagecompute.storefront.services.CheckoutService.CheckoutSummary;
import villagecompute.storefront.services.ShippingService.AggregatedRateResult;
import villagecompute.storefront.services.ShippingService.ShippingRateRequest;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Tests for {@link CheckoutService} covering happy path, invalid addresses, shipping failures, and loyalty reservation
 * collisions.
 *
 * <p>
 * Acceptance Criteria from Task I3.T2:
 * <ul>
 * <li>Checkout returns totals summary, shipping options, and provisional loyalty reservation</li>
 * <li>Integrates with TenantContext + feature flags; invalid addresses produce actionable errors</li>
 * <li>Tests cover happy path, invalid shipping, and loyalty reservation collisions</li>
 * </ul>
 */
@QuarkusTest
class CheckoutServiceTest {

    @Inject
    CheckoutService checkoutService;

    @InjectMock
    CartService cartService;

    @InjectMock
    ShippingService shippingService;

    @InjectMock
    LoyaltyService loyaltyService;

    @InjectMock
    FeatureToggle featureToggle;

    private UUID testCartId;
    private UUID testUserId;
    private Cart testCart;
    private List<CartItem> testCartItems;
    private Address validatedAddress;
    private AggregatedRateResult testRates;

    @BeforeEach
    void setUp() {
        testCartId = UUID.randomUUID();
        testUserId = UUID.randomUUID();

        // Setup test cart
        testCart = new Cart();
        testCart.id = testCartId;

        // Setup test cart items
        CartItem item1 = new CartItem();
        item1.id = UUID.randomUUID();
        item1.quantity = 2;
        item1.unitPrice = new BigDecimal("25.00");
        item1.variant = new ProductVariant();
        item1.variant.weight = new BigDecimal("8");
        item1.variant.weightUnit = "oz";

        CartItem item2 = new CartItem();
        item2.id = UUID.randomUUID();
        item2.quantity = 1;
        item2.unitPrice = new BigDecimal("50.00");
        item2.variant = new ProductVariant();
        item2.variant.weight = new BigDecimal("16");
        item2.variant.weightUnit = "oz";

        testCartItems = List.of(item1, item2);

        // Setup validated address
        validatedAddress = new Address("123 Main St", null, "San Francisco", "CA", "94102", "US", true);

        // Setup shipping rates
        Rate rate1 = new Rate("USPS", ServiceLevel.GROUND, "USPS Ground", new BigDecimal("9.99"), "USD", 5,
                OffsetDateTime.now().plusDays(5));
        Rate rate2 = new Rate("USPS", ServiceLevel.PRIORITY, "USPS Priority", new BigDecimal("19.99"), "USD", 2,
                OffsetDateTime.now().plusDays(2));

        testRates = new AggregatedRateResult(List.of(rate1, rate2), false, null);
    }

    @Test
    void testPrepareCheckout_HappyPath() {
        // Given
        CheckoutAddress shippingAddress = new CheckoutAddress("123 Main St", null, "San Francisco", "CA", "94102", "US",
                true);

        CheckoutPreparationRequest request = new CheckoutPreparationRequest(testCartId, shippingAddress, null, null,
                null, null, "test-correlation-id");

        when(cartService.getCart(testCartId)).thenReturn(Optional.of(testCart));
        when(cartService.getCartItems(testCartId)).thenReturn(testCartItems);

        AddressValidationResult addressValidation = new AddressValidationResult(ValidationStatus.VALID,
                validatedAddress, List.of(), null, java.util.Map.of());
        when(shippingService.validateAddress(any(AddressValidationRequest.class), anyString()))
                .thenReturn(addressValidation);

        when(shippingService.fetchRates(any(ShippingRateRequest.class), anyString())).thenReturn(testRates);

        // When
        CheckoutSummary summary = checkoutService.prepareCheckout(request);

        // Then
        assertNotNull(summary);
        assertEquals(testCartId, summary.cartId());
        assertEquals("USD", summary.currency());

        // Verify totals (2 * $25 + 1 * $50 = $100 subtotal, +$9.99 shipping = $109.99)
        assertEquals(new BigDecimal("100.00"), summary.totals().subtotal());
        assertEquals(BigDecimal.ZERO, summary.totals().discount());
        assertEquals(BigDecimal.ZERO, summary.totals().tax());
        assertEquals(new BigDecimal("9.99"), summary.totals().shipping()); // cheapest rate selected
        assertEquals(new BigDecimal("109.99"), summary.totals().total());

        // Verify shipping options
        assertEquals(2, summary.shippingOptions().size());
        assertNotNull(summary.selectedShipping());
        assertEquals(ServiceLevel.GROUND, summary.selectedShipping().serviceLevel()); // cheapest selected

        // Verify address validation
        assertEquals(ValidationStatus.VALID, summary.addressValidation().status());

        verify(cartService).getCart(testCartId);
        verify(cartService).getCartItems(testCartId);
        verify(shippingService).validateAddress(any(AddressValidationRequest.class), anyString());
        verify(shippingService).fetchRates(any(ShippingRateRequest.class), anyString());
    }

    @Test
    void testPrepareCheckout_WithLoyaltyReservation() {
        // Given
        CheckoutAddress shippingAddress = new CheckoutAddress("123 Main St", null, "San Francisco", "CA", "94102", "US",
                true);

        CheckoutPreparationRequest request = new CheckoutPreparationRequest(testCartId, shippingAddress, null,
                testUserId, 500, null, "test-correlation-id");

        when(cartService.getCart(testCartId)).thenReturn(Optional.of(testCart));
        when(cartService.getCartItems(testCartId)).thenReturn(testCartItems);

        AddressValidationResult addressValidation = new AddressValidationResult(ValidationStatus.VALID,
                validatedAddress, List.of(), null, java.util.Map.of());
        when(shippingService.validateAddress(any(AddressValidationRequest.class), anyString()))
                .thenReturn(addressValidation);

        when(shippingService.fetchRates(any(ShippingRateRequest.class), anyString())).thenReturn(testRates);

        when(featureToggle.isEnabled("loyalty.enabled")).thenReturn(true);

        LoyaltyMember member = new LoyaltyMember();
        member.id = UUID.randomUUID();
        member.pointsBalance = 1000;
        when(loyaltyService.getMemberByUser(testUserId)).thenReturn(Optional.of(member));
        when(loyaltyService.calculateRedemptionValue(500)).thenReturn(new BigDecimal("5.00"));

        // When
        CheckoutSummary summary = checkoutService.prepareCheckout(request);

        // Then
        assertNotNull(summary);
        assertNotNull(summary.loyaltyReservation());
        assertTrue(summary.loyaltyReservation().success());
        assertEquals(500, summary.loyaltyReservation().pointsRequested());
        assertEquals(1000, summary.loyaltyReservation().availableBalance());
        assertEquals(new BigDecimal("5.00"), summary.loyaltyReservation().discountValue());

        // Verify discount applied to total ($100 - $5 + $9.99 = $104.99)
        assertEquals(new BigDecimal("5.00"), summary.totals().discount());
        assertEquals(new BigDecimal("104.99"), summary.totals().total());

        verify(loyaltyService).getMemberByUser(testUserId);
        verify(loyaltyService).calculateRedemptionValue(500);
    }

    @Test
    void testPrepareCheckout_LoyaltyInsufficientPoints() {
        // Given
        CheckoutAddress shippingAddress = new CheckoutAddress("123 Main St", null, "San Francisco", "CA", "94102", "US",
                true);

        CheckoutPreparationRequest request = new CheckoutPreparationRequest(testCartId, shippingAddress, null,
                testUserId, 2000, null, "test-correlation-id");

        when(cartService.getCart(testCartId)).thenReturn(Optional.of(testCart));
        when(cartService.getCartItems(testCartId)).thenReturn(testCartItems);

        AddressValidationResult addressValidation = new AddressValidationResult(ValidationStatus.VALID,
                validatedAddress, List.of(), null, java.util.Map.of());
        when(shippingService.validateAddress(any(AddressValidationRequest.class), anyString()))
                .thenReturn(addressValidation);

        when(shippingService.fetchRates(any(ShippingRateRequest.class), anyString())).thenReturn(testRates);

        when(featureToggle.isEnabled("loyalty.enabled")).thenReturn(true);

        LoyaltyMember member = new LoyaltyMember();
        member.id = UUID.randomUUID();
        member.pointsBalance = 500; // Less than requested
        when(loyaltyService.getMemberByUser(testUserId)).thenReturn(Optional.of(member));

        // When
        CheckoutSummary summary = checkoutService.prepareCheckout(request);

        // Then
        assertNotNull(summary);
        assertNotNull(summary.loyaltyReservation());
        assertFalse(summary.loyaltyReservation().success());
        assertEquals(2000, summary.loyaltyReservation().pointsRequested());
        assertTrue(summary.loyaltyReservation().errorMessage().contains("Insufficient loyalty points"));

        // Verify no discount applied
        assertEquals(BigDecimal.ZERO, summary.totals().discount());
    }

    @Test
    void testPrepareCheckout_InvalidAddress() {
        // Given
        CheckoutAddress shippingAddress = new CheckoutAddress("Invalid Street", null, "NoCity", "XX", "00000", "US",
                true);

        CheckoutPreparationRequest request = new CheckoutPreparationRequest(testCartId, shippingAddress, null, null,
                null, null, "test-correlation-id");

        when(cartService.getCart(testCartId)).thenReturn(Optional.of(testCart));
        when(cartService.getCartItems(testCartId)).thenReturn(testCartItems);

        AddressValidationResult addressValidation = new AddressValidationResult(ValidationStatus.INVALID, null,
                List.of("Invalid postal code"), "Address could not be validated", java.util.Map.of());
        when(shippingService.validateAddress(any(AddressValidationRequest.class), anyString()))
                .thenReturn(addressValidation);

        // When/Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> checkoutService.prepareCheckout(request));

        assertTrue(exception.getMessage().contains("Invalid shipping address"));
        verify(shippingService).validateAddress(any(AddressValidationRequest.class), anyString());
    }

    @Test
    void testPrepareCheckout_AddressValidationProviderUnavailable() {
        // Given
        CheckoutAddress shippingAddress = new CheckoutAddress("123 Main St", null, "San Francisco", "CA", "94102", "US",
                true);

        CheckoutPreparationRequest request = new CheckoutPreparationRequest(testCartId, shippingAddress, null, null,
                null, null, "test-correlation-id");

        when(cartService.getCart(testCartId)).thenReturn(Optional.of(testCart));
        when(cartService.getCartItems(testCartId)).thenReturn(testCartItems);

        // Simulate provider unavailable
        when(shippingService.validateAddress(any(AddressValidationRequest.class), anyString()))
                .thenThrow(new RuntimeException("Provider API timeout"));

        when(shippingService.fetchRates(any(ShippingRateRequest.class), anyString())).thenReturn(testRates);

        // When
        CheckoutSummary summary = checkoutService.prepareCheckout(request);

        // Then - should proceed with fallback address
        assertNotNull(summary);
        assertEquals(ValidationStatus.PROVIDER_UNAVAILABLE, summary.addressValidation().status());
        assertTrue(
                summary.addressValidation().warnings().contains("Address validation service temporarily unavailable"));

        // Should still have shipping options and totals
        assertFalse(summary.shippingOptions().isEmpty());
        assertNotNull(summary.totals());
    }

    @Test
    void testPrepareCheckout_NoShippingRatesAvailable() {
        // Given
        CheckoutAddress shippingAddress = new CheckoutAddress("123 Main St", null, "San Francisco", "CA", "94102", "US",
                true);

        CheckoutPreparationRequest request = new CheckoutPreparationRequest(testCartId, shippingAddress, null, null,
                null, null, "test-correlation-id");

        when(cartService.getCart(testCartId)).thenReturn(Optional.of(testCart));
        when(cartService.getCartItems(testCartId)).thenReturn(testCartItems);

        AddressValidationResult addressValidation = new AddressValidationResult(ValidationStatus.VALID,
                validatedAddress, List.of(), null, java.util.Map.of());
        when(shippingService.validateAddress(any(AddressValidationRequest.class), anyString()))
                .thenReturn(addressValidation);

        // Return empty rates
        AggregatedRateResult emptyRates = new AggregatedRateResult(List.of(), true, "All carriers unavailable");
        when(shippingService.fetchRates(any(ShippingRateRequest.class), anyString())).thenReturn(emptyRates);

        // When/Then
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> checkoutService.prepareCheckout(request));

        assertTrue(exception.getMessage().contains("No shipping rates available"));
    }

    @Test
    void testPrepareCheckout_CartNotFound() {
        // Given
        CheckoutAddress shippingAddress = new CheckoutAddress("123 Main St", null, "San Francisco", "CA", "94102", "US",
                true);

        CheckoutPreparationRequest request = new CheckoutPreparationRequest(testCartId, shippingAddress, null, null,
                null, null, "test-correlation-id");

        when(cartService.getCart(testCartId)).thenReturn(Optional.empty());

        // When/Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> checkoutService.prepareCheckout(request));

        assertTrue(exception.getMessage().contains("Cart not found"));
        verify(cartService).getCart(testCartId);
    }

    @Test
    void testPrepareCheckout_EmptyCart() {
        // Given
        CheckoutAddress shippingAddress = new CheckoutAddress("123 Main St", null, "San Francisco", "CA", "94102", "US",
                true);

        CheckoutPreparationRequest request = new CheckoutPreparationRequest(testCartId, shippingAddress, null, null,
                null, null, "test-correlation-id");

        when(cartService.getCart(testCartId)).thenReturn(Optional.of(testCart));
        when(cartService.getCartItems(testCartId)).thenReturn(List.of()); // Empty cart

        // When/Then
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> checkoutService.prepareCheckout(request));

        assertTrue(exception.getMessage().contains("Cannot prepare checkout for empty cart"));
    }

    @Test
    void testPrepareCheckout_PreferredShippingService() {
        // Given
        CheckoutAddress shippingAddress = new CheckoutAddress("123 Main St", null, "San Francisco", "CA", "94102", "US",
                true);

        CheckoutPreparationRequest request = new CheckoutPreparationRequest(testCartId, shippingAddress, null, null,
                null, "PRIORITY", "test-correlation-id");

        when(cartService.getCart(testCartId)).thenReturn(Optional.of(testCart));
        when(cartService.getCartItems(testCartId)).thenReturn(testCartItems);

        AddressValidationResult addressValidation = new AddressValidationResult(ValidationStatus.VALID,
                validatedAddress, List.of(), null, java.util.Map.of());
        when(shippingService.validateAddress(any(AddressValidationRequest.class), anyString()))
                .thenReturn(addressValidation);

        when(shippingService.fetchRates(any(ShippingRateRequest.class), anyString())).thenReturn(testRates);

        // When
        CheckoutSummary summary = checkoutService.prepareCheckout(request);

        // Then
        assertNotNull(summary);
        assertNotNull(summary.selectedShipping());
        assertEquals(ServiceLevel.PRIORITY, summary.selectedShipping().serviceLevel()); // Preferred service selected
        assertEquals(new BigDecimal("19.99"), summary.selectedShipping().cost());
    }

    @Test
    void testPrepareCheckout_FallbackShippingRatesUsed() {
        // Given
        CheckoutAddress shippingAddress = new CheckoutAddress("123 Main St", null, "San Francisco", "CA", "94102", "US",
                true);

        CheckoutPreparationRequest request = new CheckoutPreparationRequest(testCartId, shippingAddress, null, null,
                null, null, "test-correlation-id");

        when(cartService.getCart(testCartId)).thenReturn(Optional.of(testCart));
        when(cartService.getCartItems(testCartId)).thenReturn(testCartItems);

        AddressValidationResult addressValidation = new AddressValidationResult(ValidationStatus.VALID,
                validatedAddress, List.of(), null, java.util.Map.of());
        when(shippingService.validateAddress(any(AddressValidationRequest.class), anyString()))
                .thenReturn(addressValidation);

        // Return fallback rates
        Rate fallbackRate = new Rate("FALLBACK", ServiceLevel.GROUND, "Standard Shipping (Fallback)",
                new BigDecimal("5.99"), "USD", 7, OffsetDateTime.now().plusDays(7));
        AggregatedRateResult fallbackRates = new AggregatedRateResult(List.of(fallbackRate), true,
                "Carriers unavailable");
        when(shippingService.fetchRates(any(ShippingRateRequest.class), anyString())).thenReturn(fallbackRates);

        // When
        CheckoutSummary summary = checkoutService.prepareCheckout(request);

        // Then
        assertNotNull(summary);
        assertEquals(1, summary.shippingOptions().size());
        assertTrue(summary.shippingOptions().get(0).fallbackUsed());
        assertEquals("FALLBACK", summary.shippingOptions().get(0).carrierCode());
    }
}
