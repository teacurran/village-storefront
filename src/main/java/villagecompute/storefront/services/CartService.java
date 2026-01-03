package villagecompute.storefront.services;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.Cart;
import villagecompute.storefront.data.models.CartItem;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.data.models.User;
import villagecompute.storefront.data.repositories.CartItemRepository;
import villagecompute.storefront.data.repositories.CartRepository;
import villagecompute.storefront.data.repositories.ProductVariantRepository;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Service layer for cart operations (create, add items, update quantities, remove items).
 *
 * <p>
 * Provides business logic for managing shopping carts including CRUD operations, line item management, and price
 * calculations. All operations are tenant-scoped and include structured logging for observability.
 *
 * <p>
 * Supports both authenticated user carts and guest session carts. Optimistic locking ensures safe concurrent updates.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T4: Cart service implementation with tenant isolation and optimistic locking</li>
 * <li>ADR-001: Tenant-scoped services</li>
 * </ul>
 */
@ApplicationScoped
public class CartService {

    private static final Logger LOG = Logger.getLogger(CartService.class);

    @Inject
    CartRepository cartRepository;

    @Inject
    CartItemRepository cartItemRepository;

    @Inject
    ProductVariantRepository variantRepository;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    CatalogCacheService catalogCacheService;

    // ========================================
    // Cart Operations
    // ========================================

    /**
     * Get or create cart for authenticated user.
     *
     * @param userId
     *            user UUID
     * @return active cart for user
     */
    @Transactional
    public Cart getOrCreateCartForUser(UUID userId) {
        Objects.requireNonNull(userId, "User ID is required");
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Getting or creating cart - tenantId=%s, userId=%s", tenantId, userId);

        Optional<Cart> existingCart = cartRepository.findActiveByUser(userId);
        if (existingCart.isPresent()) {
            LOG.debugf("Found existing cart - tenantId=%s, userId=%s, cartId=%s", tenantId, userId,
                    existingCart.get().id);
            return existingCart.get();
        }

        // Create new cart
        Cart cart = new Cart();
        User user = User.findById(userId);
        if (user == null) {
            throw new IllegalArgumentException("User not found: " + userId);
        }
        if (user.tenant == null || !user.tenant.id.equals(tenantId)) {
            throw new IllegalArgumentException("User does not belong to current tenant");
        }
        cart.user = user;
        cart.expiresAt = OffsetDateTime.now().plusDays(30);
        cartRepository.persist(cart);

        LOG.infof("Created new cart - tenantId=%s, userId=%s, cartId=%s", tenantId, userId, cart.id);
        meterRegistry.counter("cart.created", "tenant_id", tenantId.toString(), "type", "user").increment();

        return cart;
    }

    /**
     * Get or create cart for guest session.
     *
     * @param sessionId
     *            session identifier
     * @return active cart for session
     */
    @Transactional
    public Cart getOrCreateCartForSession(String sessionId) {
        String normalizedSessionId = requireSessionId(sessionId);
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Getting or creating cart for session - tenantId=%s, sessionId=%s", tenantId, normalizedSessionId);

        Optional<Cart> existingCart = cartRepository.findActiveBySession(normalizedSessionId);
        if (existingCart.isPresent()) {
            LOG.debugf("Found existing cart - tenantId=%s, sessionId=%s, cartId=%s", tenantId, normalizedSessionId,
                    existingCart.get().id);
            return existingCart.get();
        }

        // Create new cart
        Cart cart = new Cart();
        cart.sessionId = normalizedSessionId;
        cart.expiresAt = OffsetDateTime.now().plusDays(30);
        cartRepository.persist(cart);

        LOG.infof("Created new cart - tenantId=%s, sessionId=%s, cartId=%s", tenantId, normalizedSessionId, cart.id);
        meterRegistry.counter("cart.created", "tenant_id", tenantId.toString(), "type", "guest").increment();

        return cart;
    }

    /**
     * Get cart by ID.
     *
     * @param cartId
     *            cart UUID
     * @return cart if found
     */
    @Transactional(TxType.SUPPORTS)
    public Optional<Cart> getCart(UUID cartId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Fetching cart - tenantId=%s, cartId=%s", tenantId, cartId);

        Optional<Cart> cart = cartRepository.findByIdOptional(cartId);

        // Verify tenant ownership
        if (cart.isPresent() && !cart.get().tenant.id.equals(tenantId)) {
            LOG.warnf("Tenant mismatch detected - tenantId=%s, cartId=%s, cartTenantId=%s", tenantId, cartId,
                    cart.get().tenant.id);
            return Optional.empty();
        }

        return cart;
    }

    /**
     * Delete cart and all its items.
     *
     * @param cartId
     *            cart UUID
     */
    @Transactional
    public void deleteCart(UUID cartId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Deleting cart - tenantId=%s, cartId=%s", tenantId, cartId);

        Cart cart = cartRepository.findByIdOptional(cartId)
                .orElseThrow(() -> new IllegalArgumentException("Cart not found: " + cartId));

        if (!cart.tenant.id.equals(tenantId)) {
            throw new IllegalArgumentException("Cart does not belong to current tenant");
        }

        // Delete all items first
        long deletedItems = cartItemRepository.deleteByCart(cartId);
        LOG.debugf("Deleted %d cart items - tenantId=%s, cartId=%s", deletedItems, tenantId, cartId);

        // Delete cart
        cartRepository.delete(cart);

        LOG.infof("Cart deleted successfully - tenantId=%s, cartId=%s", tenantId, cartId);
        meterRegistry.counter("cart.deleted", "tenant_id", tenantId.toString()).increment();
    }

    // ========================================
    // Cart Item Operations
    // ========================================

    /**
     * Add item to cart or update quantity if already exists.
     *
     * @param cartId
     *            cart UUID
     * @param variantId
     *            product variant UUID
     * @param quantity
     *            quantity to add
     * @return updated cart item
     * @throws OptimisticLockException
     *             if concurrent modification detected
     */
    @Transactional
    public CartItem addItemToCart(UUID cartId, UUID variantId, int quantity) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Adding item to cart - tenantId=%s, cartId=%s, variantId=%s, quantity=%d", tenantId, cartId,
                variantId, quantity);

        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        // Verify cart belongs to tenant
        Cart cart = cartRepository.findByIdOptional(cartId)
                .orElseThrow(() -> new IllegalArgumentException("Cart not found: " + cartId));

        if (!cart.tenant.id.equals(tenantId)) {
            throw new IllegalArgumentException("Cart does not belong to current tenant");
        }

        // Get variant and verify it belongs to tenant
        ProductVariant variant = variantRepository.findByIdOptional(variantId)
                .orElseThrow(() -> new IllegalArgumentException("Variant not found: " + variantId));

        if (!variant.tenant.id.equals(tenantId)) {
            throw new IllegalArgumentException("Variant does not belong to current tenant");
        }

        // Check if item already exists in cart
        Optional<CartItem> existingItem = cartItemRepository.findByCartAndVariant(cartId, variantId);

        CartItem cartItem;
        if (existingItem.isPresent()) {
            // Update existing item quantity
            cartItem = existingItem.get();
            cartItem.quantity += quantity;
            cartItemRepository.persist(cartItem);

            LOG.infof("Updated existing cart item - tenantId=%s, cartId=%s, itemId=%s, newQuantity=%d", tenantId,
                    cartId, cartItem.id, cartItem.quantity);
            meterRegistry.counter("cart.item.updated", "tenant_id", tenantId.toString()).increment();
        } else {
            // Create new cart item
            cartItem = new CartItem();
            cartItem.cart = cart;
            cartItem.variant = variant;
            cartItem.quantity = quantity;
            cartItem.unitPrice = variant.price; // Snapshot price
            cartItemRepository.persist(cartItem);

            LOG.infof("Added new cart item - tenantId=%s, cartId=%s, itemId=%s, variantId=%s, quantity=%d", tenantId,
                    cartId, cartItem.id, variantId, quantity);
            meterRegistry.counter("cart.item.added", "tenant_id", tenantId.toString()).increment();
        }

        // Update cart's updated_at timestamp
        cart.updatedAt = OffsetDateTime.now();
        cartRepository.persist(cart);
        catalogCacheService.invalidateTenantCache(tenantId, "cart-item-upsert");

        // Eagerly initialize lazy-loaded relationships before transaction ends
        if (cartItem.variant != null) {
            cartItem.variant.name.length(); // Force Hibernate to load
            if (cartItem.variant.product != null) {
                cartItem.variant.product.name.length(); // Force Hibernate to load
            }
        }

        return cartItem;
    }

    /**
     * Update cart item quantity.
     *
     * @param cartId
     *            cart UUID
     * @param itemId
     *            cart item UUID
     * @param quantity
     *            new quantity
     * @return updated cart item
     * @throws OptimisticLockException
     *             if concurrent modification detected
     */
    @Transactional
    public CartItem updateCartItemQuantity(UUID cartId, UUID itemId, int quantity) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Updating cart item quantity - tenantId=%s, cartId=%s, itemId=%s, quantity=%d", tenantId, cartId,
                itemId, quantity);

        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        CartItem cartItem = cartItemRepository.findByIdOptional(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Cart item not found: " + itemId));

        // Verify tenant ownership
        if (!cartItem.tenant.id.equals(tenantId)) {
            throw new IllegalArgumentException("Cart item does not belong to current tenant");
        }

        // Verify item belongs to cart
        if (!cartItem.cart.id.equals(cartId)) {
            throw new IllegalArgumentException("Cart item does not belong to specified cart");
        }

        cartItem.quantity = quantity;
        cartItemRepository.persist(cartItem);

        // Update cart's updated_at timestamp
        cartItem.cart.updatedAt = OffsetDateTime.now();
        cartRepository.persist(cartItem.cart);

        LOG.infof("Cart item quantity updated - tenantId=%s, cartId=%s, itemId=%s, newQuantity=%d", tenantId, cartId,
                itemId, quantity);
        meterRegistry.counter("cart.item.quantity_updated", "tenant_id", tenantId.toString()).increment();
        catalogCacheService.invalidateTenantCache(tenantId, "cart-item-quantity");

        // Eagerly initialize lazy-loaded relationships before transaction ends
        if (cartItem.variant != null) {
            cartItem.variant.name.length(); // Force Hibernate to load
            if (cartItem.variant.product != null) {
                cartItem.variant.product.name.length(); // Force Hibernate to load
            }
        }

        return cartItem;
    }

    /**
     * Remove item from cart.
     *
     * @param cartId
     *            cart UUID
     * @param itemId
     *            cart item UUID
     */
    @Transactional
    public void removeCartItem(UUID cartId, UUID itemId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        CartItem cartItem = cartItemRepository.findByIdOptional(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Cart item not found: " + itemId));
        LOG.infof("Removing cart item - tenantId=%s, cartId=%s, itemId=%s, sessionId=%s", tenantId, cartId, itemId,
                cartItem.cart.sessionId);

        // Verify tenant ownership
        if (!cartItem.tenant.id.equals(tenantId)) {
            throw new IllegalArgumentException("Cart item does not belong to current tenant");
        }

        // Verify item belongs to cart
        if (!cartItem.cart.id.equals(cartId)) {
            throw new IllegalArgumentException("Cart item does not belong to specified cart");
        }

        Cart cart = cartItem.cart;
        cartItemRepository.delete(cartItem);

        // Update cart's updated_at timestamp
        cart.updatedAt = OffsetDateTime.now();
        cartRepository.persist(cart);

        LOG.infof("Cart item removed - tenantId=%s, cartId=%s, itemId=%s", tenantId, cartId, itemId);
        meterRegistry.counter("cart.item.removed", "tenant_id", tenantId.toString()).increment();
        catalogCacheService.invalidateTenantCache(tenantId, "cart-item-removed");
    }

    /**
     * Get all items in a cart.
     *
     * @param cartId
     *            cart UUID
     * @return list of cart items
     */
    @Transactional(TxType.SUPPORTS)
    public List<CartItem> getCartItems(UUID cartId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Fetching cart items - tenantId=%s, cartId=%s", tenantId, cartId);

        // Verify cart belongs to tenant
        Cart cart = cartRepository.findByIdOptional(cartId)
                .orElseThrow(() -> new IllegalArgumentException("Cart not found: " + cartId));

        if (!cart.tenant.id.equals(tenantId)) {
            throw new IllegalArgumentException("Cart does not belong to current tenant");
        }

        List<CartItem> items = cartItemRepository.findByCart(cartId);

        // Eagerly initialize lazy-loaded relationships for all items
        items.forEach(item -> {
            if (item.variant != null) {
                item.variant.name.length(); // Force Hibernate to load
                if (item.variant.product != null) {
                    item.variant.product.name.length(); // Force Hibernate to load
                }
            }
        });

        return items;
    }

    /**
     * Clear all items from a cart.
     *
     * @param cartId
     *            cart UUID
     */
    @Transactional
    public void clearCart(UUID cartId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        Cart cart = cartRepository.findByIdOptional(cartId)
                .orElseThrow(() -> new IllegalArgumentException("Cart not found: " + cartId));
        LOG.infof("Clearing cart - tenantId=%s, cartId=%s, sessionId=%s, userId=%s", tenantId, cartId, cart.sessionId,
                cart.user != null ? cart.user.id : null);

        if (!cart.tenant.id.equals(tenantId)) {
            throw new IllegalArgumentException("Cart does not belong to current tenant");
        }

        long deletedItems = cartItemRepository.deleteByCart(cartId);

        // Update cart's updated_at timestamp
        cart.updatedAt = OffsetDateTime.now();
        cartRepository.persist(cart);

        LOG.infof("Cart cleared - tenantId=%s, cartId=%s, deletedItems=%d", tenantId, cartId, deletedItems);
        meterRegistry.counter("cart.cleared", "tenant_id", tenantId.toString()).increment();
        catalogCacheService.invalidateTenantCache(tenantId, "cart-cleared");
    }

    // ========================================
    // Cart Calculations
    // ========================================

    /**
     * Calculate cart subtotal (sum of all line item totals).
     *
     * @param cartId
     *            cart UUID
     * @return subtotal amount
     */
    @Transactional(TxType.SUPPORTS)
    public BigDecimal calculateCartSubtotal(UUID cartId) {
        List<CartItem> items = getCartItems(cartId);
        return items.stream().map(item -> item.unitPrice.multiply(BigDecimal.valueOf(item.quantity)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Get cart item count.
     *
     * @param cartId
     *            cart UUID
     * @return total number of items
     */
    @Transactional(TxType.SUPPORTS)
    public long getCartItemCount(UUID cartId) {
        UUID tenantId = TenantContext.getCurrentTenantId();

        // Verify cart belongs to tenant
        Cart cart = cartRepository.findByIdOptional(cartId)
                .orElseThrow(() -> new IllegalArgumentException("Cart not found: " + cartId));

        if (!cart.tenant.id.equals(tenantId)) {
            throw new IllegalArgumentException("Cart does not belong to current tenant");
        }

        return cartItemRepository.countByCart(cartId);
    }

    /**
     * Find an active cart for the current tenant/session without creating a new cart.
     *
     * @param sessionId
     *            guest session identifier
     * @return active cart if present
     */
    @Transactional(TxType.SUPPORTS)
    public Optional<Cart> findActiveCartForSession(String sessionId) {
        return normalizeSessionId(sessionId).flatMap(cartRepository::findActiveBySession);
    }

    private String requireSessionId(String sessionId) {
        return normalizeSessionId(sessionId).orElseThrow(() -> new IllegalArgumentException("Session ID is required"));
    }

    private Optional<String> normalizeSessionId(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        String trimmed = sessionId.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(trimmed);
    }
}
