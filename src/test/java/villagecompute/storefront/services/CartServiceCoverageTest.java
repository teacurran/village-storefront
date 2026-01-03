package villagecompute.storefront.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import villagecompute.storefront.data.models.Cart;
import villagecompute.storefront.data.models.CartItem;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.repositories.CartItemRepository;
import villagecompute.storefront.data.repositories.CartRepository;
import villagecompute.storefront.data.repositories.ProductVariantRepository;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Lightweight unit tests that exercise the hot paths in {@link CartService} without depending on the database. The
 * project relies heavily on Quarkus integration tests, but those run in a forked JVM that does not contribute to
 * JaCoCo. These pure unit tests keep coverage honest by directly invoking CartService methods with mocked dependencies.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(
        strictness = org.mockito.quality.Strictness.LENIENT)
class CartServiceCoverageTest {

    private static final UUID TENANT_ID = UUID.randomUUID();

    @InjectMocks
    CartService cartService;

    @Mock
    CartRepository cartRepository;

    @Mock
    CartItemRepository cartItemRepository;

    @Mock
    ProductVariantRepository productVariantRepository;

    @Mock
    MeterRegistry meterRegistry;

    @Mock
    CatalogCacheService catalogCacheService;

    @Mock
    Counter counter;

    private Cart cart;
    private ProductVariant variant;
    private Tenant tenant;

    @BeforeEach
    void init() {
        TenantContext.setCurrentTenant(new TenantInfo(TENANT_ID, "unit", "Unit Tenant", "active"));
        tenant = new Tenant();
        tenant.id = TENANT_ID;
        tenant.status = "active";
        tenant.subdomain = "unit";

        cart = new Cart();
        cart.id = UUID.randomUUID();
        cart.tenant = tenant;
        cart.sessionId = "session-123";
        cart.createdAt = OffsetDateTime.now();
        cart.updatedAt = OffsetDateTime.now();

        Product product = new Product();
        product.tenant = tenant;
        product.name = "Test Product";

        variant = new ProductVariant();
        variant.id = UUID.randomUUID();
        variant.tenant = tenant;
        variant.product = product;
        variant.price = new BigDecimal("29.99");
        variant.name = "Variant";

        lenient().when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(counter);

        doNothing().when(cartRepository).persist(any(Cart.class));
        doNothing().when(cartItemRepository).persist(any(CartItem.class));
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void addItemToCart_shouldUpdateExistingItem() {
        CartItem existing = new CartItem();
        existing.id = UUID.randomUUID();
        existing.cart = cart;
        existing.variant = variant;
        existing.tenant = tenant;
        existing.quantity = 1;
        existing.unitPrice = variant.price;

        when(cartRepository.findByIdOptional(cart.id)).thenReturn(Optional.of(cart));
        when(productVariantRepository.findByIdOptional(variant.id)).thenReturn(Optional.of(variant));
        when(cartItemRepository.findByCartAndVariant(cart.id, variant.id)).thenReturn(Optional.of(existing));

        CartItem result = cartService.addItemToCart(cart.id, variant.id, 2);

        assertEquals(3, result.quantity);
        verify(cartItemRepository).persist(existing);
        verify(catalogCacheService).invalidateTenantCache(eq(TENANT_ID), anyString());
    }

    @Test
    void addItemToCart_shouldCreateNewItem() {
        when(cartRepository.findByIdOptional(cart.id)).thenReturn(Optional.of(cart));
        when(productVariantRepository.findByIdOptional(variant.id)).thenReturn(Optional.of(variant));
        when(cartItemRepository.findByCartAndVariant(cart.id, variant.id)).thenReturn(Optional.empty());

        CartItem newItem = cartService.addItemToCart(cart.id, variant.id, 1);

        assertEquals(1, newItem.quantity);
        assertEquals(variant.price, newItem.unitPrice);
        verify(cartItemRepository).persist(any(CartItem.class));
        verify(catalogCacheService).invalidateTenantCache(eq(TENANT_ID), anyString());
    }

    @Test
    void updateCartItem_shouldChangeQuantity() {
        CartItem cartItem = new CartItem();
        cartItem.id = UUID.randomUUID();
        cartItem.cart = cart;
        cartItem.tenant = tenant;
        cartItem.variant = variant;
        cartItem.quantity = 1;

        when(cartItemRepository.findByIdOptional(cartItem.id)).thenReturn(Optional.of(cartItem));

        CartItem result = cartService.updateCartItemQuantity(cart.id, cartItem.id, 5);

        assertEquals(5, result.quantity);
        verify(cartRepository, times(1)).persist(cart);
        verify(catalogCacheService).invalidateTenantCache(eq(TENANT_ID), anyString());
    }

    @Test
    void removeCartItem_shouldDeleteAndInvalidateCache() {
        CartItem cartItem = new CartItem();
        cartItem.id = UUID.randomUUID();
        cartItem.cart = cart;
        cartItem.tenant = tenant;
        cartItem.variant = variant;

        when(cartItemRepository.findByIdOptional(cartItem.id)).thenReturn(Optional.of(cartItem));

        cartService.removeCartItem(cart.id, cartItem.id);

        verify(cartItemRepository).delete(cartItem);
        verify(catalogCacheService).invalidateTenantCache(eq(TENANT_ID), anyString());
    }

    @Test
    void clearCart_shouldRemoveAllItems() {
        when(cartRepository.findByIdOptional(cart.id)).thenReturn(Optional.of(cart));
        when(cartItemRepository.deleteByCart(cart.id)).thenReturn(2L);

        cartService.clearCart(cart.id);

        verify(cartItemRepository).deleteByCart(cart.id);
        verify(catalogCacheService).invalidateTenantCache(eq(TENANT_ID), anyString());
    }
}
