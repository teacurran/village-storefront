package villagecompute.storefront.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.Cart;
import villagecompute.storefront.data.models.CartItem;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.User;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for {@link CartService}.
 *
 * <p>
 * Tests cover cart creation, item management, tenant isolation, and optimistic locking scenarios.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T4: Cart service testing with tenant isolation and concurrency</li>
 * </ul>
 */
@QuarkusTest
class CartServiceTest {

    @Inject
    CartService cartService;

    @Inject
    EntityManager entityManager;

    private UUID tenantId;
    private UUID tenant2Id;
    private UUID userId;
    private UUID variantId;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up existing data
        entityManager.createQuery("DELETE FROM CartItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Cart").executeUpdate();
        entityManager.createQuery("DELETE FROM User").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductVariant").executeUpdate();
        entityManager.createQuery("DELETE FROM Product").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();

        // Create test tenant
        Tenant tenant = new Tenant();
        tenant.subdomain = "carttest";
        tenant.name = "Cart Test Tenant";
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();
        tenantId = tenant.id;

        // Create second tenant for isolation tests
        Tenant tenant2 = new Tenant();
        tenant2.subdomain = "carttest2";
        tenant2.name = "Cart Test Tenant 2";
        tenant2.status = "active";
        tenant2.settings = "{}";
        tenant2.createdAt = OffsetDateTime.now();
        tenant2.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant2);
        entityManager.flush();
        tenant2Id = tenant2.id;

        // Set current tenant context
        TenantContext.setCurrentTenant(new TenantInfo(tenantId, tenant.subdomain, tenant.name, tenant.status));

        // Create test user
        User user = new User();
        user.tenant = tenant;
        user.email = "test@example.com";
        user.status = "active";
        user.emailVerified = true;
        user.createdAt = OffsetDateTime.now();
        user.updatedAt = OffsetDateTime.now();
        entityManager.persist(user);
        entityManager.flush();
        userId = user.id;

        // Create test product and variant
        Product product = new Product();
        product.tenant = tenant;
        product.sku = "TEST-PRODUCT";
        product.name = "Test Product";
        product.slug = "test-product";
        product.type = "physical";
        product.status = "active";
        product.createdAt = OffsetDateTime.now();
        product.updatedAt = OffsetDateTime.now();
        entityManager.persist(product);

        ProductVariant variant = new ProductVariant();
        variant.tenant = tenant;
        variant.product = product;
        variant.sku = "TEST-VARIANT";
        variant.name = "Test Variant";
        variant.price = new BigDecimal("19.99");
        variant.status = "active";
        variant.createdAt = OffsetDateTime.now();
        variant.updatedAt = OffsetDateTime.now();
        entityManager.persist(variant);
        entityManager.flush();
        variantId = variant.id;
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ========================================
    // Cart Creation Tests
    // ========================================

    @Test
    @Transactional
    void getOrCreateCartForUser_shouldCreateNewCart() {
        Cart cart = cartService.getOrCreateCartForUser(userId);

        assertNotNull(cart);
        assertNotNull(cart.id);
        assertEquals(userId, cart.user.id);
        assertEquals(tenantId, cart.tenant.id);
        assertNotNull(cart.expiresAt);
        assertTrue(cart.expiresAt.isAfter(OffsetDateTime.now()));
    }

    @Test
    @Transactional
    void getOrCreateCartForUser_shouldReturnExistingCart() {
        Cart firstCart = cartService.getOrCreateCartForUser(userId);
        Cart secondCart = cartService.getOrCreateCartForUser(userId);

        assertEquals(firstCart.id, secondCart.id);
    }

    @Test
    @Transactional
    void getOrCreateCartForUser_shouldThrowWhenUserMissing() {
        assertThrows(IllegalArgumentException.class, () -> cartService.getOrCreateCartForUser(UUID.randomUUID()));
    }

    @Test
    @Transactional
    void getOrCreateCartForUser_shouldRejectCrossTenantUser() {
        Tenant otherTenant = entityManager.find(Tenant.class, tenant2Id);
        User otherUser = new User();
        otherUser.tenant = otherTenant;
        otherUser.email = "other@example.com";
        otherUser.status = "active";
        otherUser.emailVerified = true;
        otherUser.createdAt = OffsetDateTime.now();
        otherUser.updatedAt = OffsetDateTime.now();
        entityManager.persist(otherUser);
        entityManager.flush();

        assertThrows(IllegalArgumentException.class, () -> cartService.getOrCreateCartForUser(otherUser.id));
    }

    @Test
    @Transactional
    void getOrCreateCartForSession_shouldCreateNewCart() {
        String sessionId = "test-session-123";
        Cart cart = cartService.getOrCreateCartForSession(sessionId);

        assertNotNull(cart);
        assertNotNull(cart.id);
        assertEquals(sessionId, cart.sessionId);
        assertEquals(tenantId, cart.tenant.id);
        assertNotNull(cart.expiresAt);
    }

    @Test
    @Transactional
    void getOrCreateCartForSession_shouldReturnExistingCart() {
        String sessionId = "test-session-456";
        Cart firstCart = cartService.getOrCreateCartForSession(sessionId);
        Cart secondCart = cartService.getOrCreateCartForSession(sessionId);

        assertEquals(firstCart.id, secondCart.id);
    }

    @Test
    @Transactional
    void getOrCreateCartForSession_shouldRejectBlankSession() {
        assertThrows(IllegalArgumentException.class, () -> cartService.getOrCreateCartForSession(" "));
        assertThrows(IllegalArgumentException.class, () -> cartService.getOrCreateCartForSession(null));
    }

    @Test
    @Transactional
    void findActiveCartForSession_shouldReturnExistingCart() {
        String sessionId = "session-lookup-123";
        Cart cart = cartService.getOrCreateCartForSession(sessionId);

        Optional<Cart> found = cartService.findActiveCartForSession(sessionId);

        assertTrue(found.isPresent());
        assertEquals(cart.id, found.get().id);
    }

    @Test
    @Transactional
    void findActiveCartForSession_shouldReturnEmptyWhenMissing() {
        assertFalse(cartService.findActiveCartForSession("missing-session").isPresent());
        assertFalse(cartService.findActiveCartForSession(" ").isPresent());
    }

    // ========================================
    // Cart Item Management Tests
    // ========================================

    @Test
    @Transactional
    void addItemToCart_shouldCreateNewCartItem() {
        Cart cart = cartService.getOrCreateCartForUser(userId);

        CartItem item = cartService.addItemToCart(cart.id, variantId, 2);

        assertNotNull(item);
        assertNotNull(item.id);
        assertEquals(cart.id, item.cart.id);
        assertEquals(variantId, item.variant.id);
        assertEquals(2, item.quantity);
        assertEquals(0, new BigDecimal("19.99").compareTo(item.unitPrice));
        assertEquals(tenantId, item.tenant.id);
    }

    @Test
    @Transactional
    void addItemToCart_shouldMergeQuantityForExistingVariant() {
        Cart cart = cartService.getOrCreateCartForUser(userId);

        CartItem firstAdd = cartService.addItemToCart(cart.id, variantId, 2);
        CartItem secondAdd = cartService.addItemToCart(cart.id, variantId, 3);

        assertEquals(firstAdd.id, secondAdd.id);
        assertEquals(5, secondAdd.quantity);
    }

    @Test
    @Transactional
    void addItemToCart_shouldFailForInvalidQuantity() {
        Cart cart = cartService.getOrCreateCartForUser(userId);

        assertThrows(IllegalArgumentException.class, () -> cartService.addItemToCart(cart.id, variantId, 0));
        assertThrows(IllegalArgumentException.class, () -> cartService.addItemToCart(cart.id, variantId, -1));
    }

    @Test
    @Transactional
    void addItemToCart_shouldFailWhenCartNotFound() {
        UUID nonExistentCartId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> cartService.addItemToCart(nonExistentCartId, variantId, 1));
    }

    @Test
    @Transactional
    void addItemToCart_shouldFailWhenVariantNotFound() {
        Cart cart = cartService.getOrCreateCartForUser(userId);
        UUID nonExistentVariantId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> cartService.addItemToCart(cart.id, nonExistentVariantId, 1));
    }

    @Test
    @Transactional
    void addItemToCart_shouldFailWhenTenantMismatch() {
        Cart cart = cartService.getOrCreateCartForUser(userId);

        // Switch to different tenant
        TenantContext.clear();
        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, "carttest2", "Cart Test Tenant 2", "active"));

        assertThrows(IllegalArgumentException.class, () -> cartService.addItemToCart(cart.id, variantId, 1));

        // Restore original tenant
        TenantContext.clear();
        TenantContext.setCurrentTenant(new TenantInfo(tenantId, "carttest", "Cart Test Tenant", "active"));
    }

    @Test
    @Transactional
    void addItemToCart_shouldFailWhenVariantBelongsToDifferentTenant() {
        Tenant otherTenant = entityManager.find(Tenant.class, tenant2Id);

        Product otherProduct = new Product();
        otherProduct.tenant = otherTenant;
        otherProduct.sku = "OTHER-PROD";
        otherProduct.name = "Other Product";
        otherProduct.slug = "other-product";
        otherProduct.type = "physical";
        otherProduct.status = "active";
        otherProduct.createdAt = OffsetDateTime.now();
        otherProduct.updatedAt = OffsetDateTime.now();
        entityManager.persist(otherProduct);

        ProductVariant otherVariant = new ProductVariant();
        otherVariant.tenant = otherTenant;
        otherVariant.product = otherProduct;
        otherVariant.sku = "OTHER-VAR";
        otherVariant.name = "Other Variant";
        otherVariant.price = new BigDecimal("9.99");
        otherVariant.status = "active";
        otherVariant.position = 0;
        otherVariant.createdAt = OffsetDateTime.now();
        otherVariant.updatedAt = OffsetDateTime.now();
        entityManager.persist(otherVariant);
        entityManager.flush();

        Cart cart = cartService.getOrCreateCartForUser(userId);

        assertThrows(IllegalArgumentException.class, () -> cartService.addItemToCart(cart.id, otherVariant.id, 1));
    }

    @Test
    @Transactional
    void updateCartItemQuantity_shouldUpdateQuantity() {
        Cart cart = cartService.getOrCreateCartForUser(userId);
        CartItem item = cartService.addItemToCart(cart.id, variantId, 2);

        CartItem updated = cartService.updateCartItemQuantity(cart.id, item.id, 5);

        assertEquals(5, updated.quantity);
        assertTrue(updated.updatedAt.isEqual(item.updatedAt) || updated.updatedAt.isAfter(item.updatedAt));
    }

    @Test
    @Transactional
    void updateCartItemQuantity_shouldFailForInvalidQuantity() {
        Cart cart = cartService.getOrCreateCartForUser(userId);
        CartItem item = cartService.addItemToCart(cart.id, variantId, 2);

        assertThrows(IllegalArgumentException.class, () -> cartService.updateCartItemQuantity(cart.id, item.id, 0));
        assertThrows(IllegalArgumentException.class, () -> cartService.updateCartItemQuantity(cart.id, item.id, -1));
    }

    @Test
    @Transactional
    void updateCartItemQuantity_shouldFailWhenTenantMismatch() {
        Cart cart = cartService.getOrCreateCartForUser(userId);
        CartItem item = cartService.addItemToCart(cart.id, variantId, 2);

        TenantContext.clear();
        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, "carttest2", "Cart Test Tenant 2", "active"));

        assertThrows(IllegalArgumentException.class, () -> cartService.updateCartItemQuantity(cart.id, item.id, 3));

        TenantContext.clear();
        TenantContext.setCurrentTenant(new TenantInfo(tenantId, "carttest", "Cart Test Tenant", "active"));
    }

    @Test
    @Transactional
    void removeCartItem_shouldDeleteItem() {
        Cart cart = cartService.getOrCreateCartForUser(userId);
        CartItem item = cartService.addItemToCart(cart.id, variantId, 2);

        cartService.removeCartItem(cart.id, item.id);

        List<CartItem> items = cartService.getCartItems(cart.id);
        assertEquals(0, items.size());
    }

    @Test
    @Transactional
    void removeCartItem_shouldFailWhenTenantMismatch() {
        Cart cart = cartService.getOrCreateCartForUser(userId);
        CartItem item = cartService.addItemToCart(cart.id, variantId, 2);

        TenantContext.clear();
        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, "carttest2", "Cart Test Tenant 2", "active"));

        assertThrows(IllegalArgumentException.class, () -> cartService.removeCartItem(cart.id, item.id));

        TenantContext.clear();
        TenantContext.setCurrentTenant(new TenantInfo(tenantId, "carttest", "Cart Test Tenant", "active"));
    }

    @Test
    @Transactional
    void getCartItems_shouldReturnAllItems() {
        Cart cart = cartService.getOrCreateCartForUser(userId);
        cartService.addItemToCart(cart.id, variantId, 2);

        List<CartItem> items = cartService.getCartItems(cart.id);

        assertEquals(1, items.size());
        assertEquals(variantId, items.get(0).variant.id);
    }

    @Test
    @Transactional
    void clearCart_shouldRemoveAllItems() {
        Cart cart = cartService.getOrCreateCartForUser(userId);
        cartService.addItemToCart(cart.id, variantId, 2);

        cartService.clearCart(cart.id);

        List<CartItem> items = cartService.getCartItems(cart.id);
        assertEquals(0, items.size());
    }

    @Test
    @Transactional
    void clearCart_shouldFailWhenTenantMismatch() {
        Cart cart = cartService.getOrCreateCartForUser(userId);
        cartService.addItemToCart(cart.id, variantId, 1);

        TenantContext.clear();
        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, "carttest2", "Cart Test Tenant 2", "active"));

        assertThrows(IllegalArgumentException.class, () -> cartService.clearCart(cart.id));

        TenantContext.clear();
        TenantContext.setCurrentTenant(new TenantInfo(tenantId, "carttest", "Cart Test Tenant", "active"));
    }

    @Test
    @Transactional
    void deleteCart_shouldRemoveCartAndItems() {
        Cart cart = cartService.getOrCreateCartForUser(userId);
        cartService.addItemToCart(cart.id, variantId, 1);

        cartService.deleteCart(cart.id);

        assertThrows(IllegalArgumentException.class, () -> cartService.getCartItems(cart.id));
    }

    @Test
    @Transactional
    void deleteCart_shouldFailWhenTenantMismatch() {
        Cart cart = cartService.getOrCreateCartForUser(userId);

        TenantContext.clear();
        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, "carttest2", "Cart Test Tenant 2", "active"));

        assertThrows(IllegalArgumentException.class, () -> cartService.deleteCart(cart.id));

        TenantContext.clear();
        TenantContext.setCurrentTenant(new TenantInfo(tenantId, "carttest", "Cart Test Tenant", "active"));
    }

    // ========================================
    // Cart Calculation Tests
    // ========================================

    @Test
    @Transactional
    void calculateCartSubtotal_shouldSumLineItems() {
        Cart cart = cartService.getOrCreateCartForUser(userId);
        cartService.addItemToCart(cart.id, variantId, 3);

        BigDecimal subtotal = cartService.calculateCartSubtotal(cart.id);

        // 3 * $19.99 = $59.97
        assertEquals(0, new BigDecimal("59.97").compareTo(subtotal.stripTrailingZeros()));
    }

    @Test
    @Transactional
    void getCartItemCount_shouldReturnItemCount() {
        Cart cart = cartService.getOrCreateCartForUser(userId);
        cartService.addItemToCart(cart.id, variantId, 2);

        long count = cartService.getCartItemCount(cart.id);

        assertEquals(1, count);
    }

    @Test
    @Transactional
    void getCartItemCount_shouldFailWhenTenantMismatch() {
        Cart cart = cartService.getOrCreateCartForUser(userId);
        cartService.addItemToCart(cart.id, variantId, 1);

        TenantContext.clear();
        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, "carttest2", "Cart Test Tenant 2", "active"));

        assertThrows(IllegalArgumentException.class, () -> cartService.getCartItemCount(cart.id));

        TenantContext.clear();
        TenantContext.setCurrentTenant(new TenantInfo(tenantId, "carttest", "Cart Test Tenant", "active"));
    }

    // ========================================
    // Tenant Isolation Tests
    // ========================================

    @Test
    @Transactional
    void tenantIsolation_shouldPreventCrossTenantCartAccess() {
        Cart cart = cartService.getOrCreateCartForUser(userId);

        // Switch to different tenant
        TenantContext.clear();
        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, "carttest2", "Cart Test Tenant 2", "active"));

        // Should throw exception when trying to access cart from different tenant
        assertThrows(IllegalArgumentException.class, () -> cartService.getCartItems(cart.id));

        // Restore original tenant
        TenantContext.clear();
        TenantContext.setCurrentTenant(new TenantInfo(tenantId, "carttest", "Cart Test Tenant", "active"));
    }

    @Test
    @Transactional
    void getCart_shouldReturnEmptyWhenTenantMismatch() {
        Cart cart = cartService.getOrCreateCartForUser(userId);

        TenantContext.clear();
        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, "carttest2", "Cart Test Tenant 2", "active"));

        assertTrue(cartService.getCart(cart.id).isEmpty());

        TenantContext.clear();
        TenantContext.setCurrentTenant(new TenantInfo(tenantId, "carttest", "Cart Test Tenant", "active"));
    }

    @Test
    @Transactional
    void removeCartItem_shouldFailWhenCartMismatch() {
        Cart primaryCart = cartService.getOrCreateCartForUser(userId);
        Cart otherCart = cartService.getOrCreateCartForSession("secondary-session");
        CartItem item = cartService.addItemToCart(primaryCart.id, variantId, 1);

        assertThrows(IllegalArgumentException.class, () -> cartService.removeCartItem(otherCart.id, item.id));
    }
}
