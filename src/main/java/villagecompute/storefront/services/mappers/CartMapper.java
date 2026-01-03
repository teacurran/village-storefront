package villagecompute.storefront.services.mappers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import villagecompute.storefront.api.types.CartDto;
import villagecompute.storefront.api.types.CartItemDto;
import villagecompute.storefront.api.types.CartLoyaltySummary;
import villagecompute.storefront.api.types.Money;
import villagecompute.storefront.data.models.Cart;
import villagecompute.storefront.data.models.CartItem;
import villagecompute.storefront.loyalty.CartLoyaltyProjection;
import villagecompute.storefront.loyalty.LoyaltyService;
import villagecompute.storefront.services.CartService;

/**
 * Mapper for converting between Cart entities and DTOs.
 *
 * <p>
 * Provides conversion methods to transform database entities into API response objects. Handles money formatting and
 * nested object mapping.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T4: DTO mappers for cart API</li>
 * <li>OpenAPI: Cart and CartItem schemas</li>
 * </ul>
 */
@ApplicationScoped
public class CartMapper {

    private static final String DEFAULT_CURRENCY = "USD";

    @Inject
    CartService cartService;

    @Inject
    LoyaltyService loyaltyService;

    /**
     * Convert Cart entity to DTO with items and calculated totals.
     *
     * @param cart
     *            cart entity
     * @return cart DTO
     */
    public CartDto toDto(Cart cart) {
        CartDto dto = new CartDto();
        dto.setId(cart.id);
        dto.setUserId(cart.user != null ? cart.user.id : null);
        dto.setSessionId(cart.sessionId);
        dto.setExpiresAt(cart.expiresAt);
        dto.setCreatedAt(cart.createdAt);
        dto.setUpdatedAt(cart.updatedAt);

        // Load cart items
        List<CartItem> items = cartService.getCartItems(cart.id);
        dto.setItems(items.stream().map(this::toItemDto).collect(Collectors.toList()));

        // Calculate totals
        BigDecimal subtotal = items.stream().map(item -> item.unitPrice.multiply(BigDecimal.valueOf(item.quantity)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        dto.setSubtotal(new Money(subtotal, DEFAULT_CURRENCY));
        dto.setItemCount(items.size());
        dto.setLoyalty(buildLoyaltySummary(cart, subtotal));

        return dto;
    }

    /**
     * Convert CartItem entity to DTO.
     *
     * @param item
     *            cart item entity
     * @return cart item DTO
     */
    public CartItemDto toItemDto(CartItem item) {
        CartItemDto dto = new CartItemDto();
        dto.setId(item.id);
        dto.setVariantId(item.variant.id);
        dto.setProductName(item.variant.product.name);
        dto.setVariantName(item.variant.name);
        dto.setSku(item.variant.sku);
        dto.setQuantity(item.quantity);
        dto.setUnitPrice(new Money(item.unitPrice, DEFAULT_CURRENCY));

        // Calculate line total
        BigDecimal lineTotal = item.unitPrice.multiply(BigDecimal.valueOf(item.quantity));
        dto.setLineTotal(new Money(lineTotal, DEFAULT_CURRENCY));

        dto.setCreatedAt(item.createdAt);
        dto.setUpdatedAt(item.updatedAt);

        return dto;
    }

    private CartLoyaltySummary buildLoyaltySummary(Cart cart, BigDecimal subtotal) {
        if (loyaltyService == null) {
            return null;
        }
        UUID userId = cart.user != null ? cart.user.id : null;
        CartLoyaltyProjection projection = loyaltyService.calculateCartSummary(subtotal, userId);
        CartLoyaltySummary summary = new CartLoyaltySummary();
        summary.setProgramEnabled(projection.isProgramEnabled());
        summary.setProgramId(projection.getProgramId());
        summary.setMemberPointsBalance(projection.getMemberPointsBalance());
        summary.setEstimatedPointsEarned(projection.getEstimatedPointsEarned());
        summary.setEstimatedRewardValue(toMoney(projection.getEstimatedRewardValue()));
        summary.setAvailableRedemptionValue(toMoney(projection.getAvailableRedemptionValue()));
        summary.setCurrentTier(projection.getCurrentTier());
        summary.setDataFreshnessTimestamp(projection.getDataFreshnessTimestamp());
        return summary;
    }

    private Money toMoney(BigDecimal amount) {
        BigDecimal normalized = amount != null ? amount : BigDecimal.ZERO;
        return new Money(normalized, DEFAULT_CURRENCY);
    }
}
