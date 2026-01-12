package villagecompute.storefront.services.mappers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import villagecompute.storefront.api.types.CartDto;
import villagecompute.storefront.api.types.CartItemDto;
import villagecompute.storefront.api.types.CartLoyaltySummary;
import villagecompute.storefront.api.types.Money;
import villagecompute.storefront.api.types.SavedCartItemDto;
import villagecompute.storefront.data.models.Cart;
import villagecompute.storefront.data.models.CartItem;
import villagecompute.storefront.loyalty.CartLoyaltyProjection;
import villagecompute.storefront.loyalty.LoyaltyService;
import villagecompute.storefront.services.CartService;
import villagecompute.storefront.services.dto.CartSavedItem;

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
        dto.setSavedForLater(buildSavedForLaterItems(cart.id));

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
        dto.setProductName(item.variant.product.title);
        dto.setVariantName(item.variant.sku); // Use SKU as variant name since ProductVariant no longer has name field
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
        Map<String, Object> storedSnapshot = cartService.getLoyaltySnapshot(cart.id);
        if (!storedSnapshot.isEmpty()) {
            return buildLoyaltySummaryFromSnapshot(storedSnapshot);
        }
        CartLoyaltyProjection projection = loyaltyService.calculateCartSummary(subtotal, userId);
        CartLoyaltySummary summary = new CartLoyaltySummary();
        summary.setProgramEnabled(projection.isProgramEnabled());
        summary.setProgramId(projection.getProgramId());
        summary.setMemberPointsBalance(projection.getMemberPointsBalance());
        summary.setAvailablePointsBalance(projection.getAvailablePointsBalance());
        summary.setReservedPoints(projection.getReservedPoints());
        summary.setEstimatedPointsEarned(projection.getEstimatedPointsEarned());
        summary.setEstimatedRewardValue(toMoney(projection.getEstimatedRewardValue()));
        summary.setAvailableRedemptionValue(toMoney(projection.getAvailableRedemptionValue()));
        summary.setRedemptionValuePerPoint(toPlainString(projection.getRedemptionValuePerPoint()));
        summary.setCurrentTier(projection.getCurrentTier());
        summary.setPointsExpirationWarning(projection.getPointsExpirationWarning());
        summary.setDataFreshnessTimestamp(projection.getDataFreshnessTimestamp());
        cartService.storeLoyaltySnapshot(cart.id, loyaltySnapshotFromSummary(summary));
        return summary;
    }

    private Money toMoney(BigDecimal amount) {
        BigDecimal normalized = amount != null ? amount : BigDecimal.ZERO;
        return new Money(normalized, DEFAULT_CURRENCY);
    }

    private List<SavedCartItemDto> buildSavedForLaterItems(UUID cartId) {
        List<CartSavedItem> savedItems = cartService.getSavedForLaterItems(cartId);
        return savedItems.stream().map(this::toSavedItemDto).collect(Collectors.toList());
    }

    public SavedCartItemDto toSavedItemDto(CartSavedItem saved) {
        SavedCartItemDto dto = new SavedCartItemDto();
        dto.setId(saved.getId());
        dto.setVariantId(saved.getVariantId());
        dto.setProductName(saved.getProductName());
        dto.setVariantName(saved.getVariantName());
        dto.setSku(saved.getSku());
        dto.setQuantity(saved.getQuantity());
        dto.setUnitPrice(toMoney(saved.getUnitPrice()));
        dto.setSavedAt(saved.getSavedAt());
        return dto;
    }

    private CartLoyaltySummary buildLoyaltySummaryFromSnapshot(Map<String, Object> snapshot) {
        CartLoyaltySummary summary = new CartLoyaltySummary();
        summary.setProgramEnabled(Boolean.TRUE.equals(snapshot.getOrDefault("programEnabled", Boolean.FALSE)));
        summary.setProgramId(snapshot.get("programId") instanceof UUID uuid ? uuid
                : snapshot.get("programId") instanceof String str && !str.isBlank() ? UUID.fromString(str) : null);
        summary.setMemberPointsBalance(toInteger(snapshot.get("memberPointsBalance")));
        summary.setAvailablePointsBalance(toInteger(snapshot.get("availablePointsBalance")));
        summary.setReservedPoints(toInteger(snapshot.get("reservedPoints")));
        summary.setEstimatedPointsEarned(toInteger(snapshot.get("estimatedPointsEarned")));
        summary.setEstimatedRewardValue(toMoney(convertToBigDecimal(snapshot.get("estimatedRewardValue"))));
        summary.setAvailableRedemptionValue(toMoney(convertToBigDecimal(snapshot.get("availableRedemptionValue"))));
        summary.setRedemptionValuePerPoint(
                snapshot.get("redemptionValuePerPoint") != null ? snapshot.get("redemptionValuePerPoint").toString()
                        : null);
        summary.setCurrentTier(snapshot.get("currentTier") != null ? snapshot.get("currentTier").toString() : null);
        Object expiration = snapshot.get("pointsExpirationWarning");
        if (expiration instanceof OffsetDateTime warning) {
            summary.setPointsExpirationWarning(warning);
        } else if (expiration instanceof String warningStr && !warningStr.isBlank()) {
            summary.setPointsExpirationWarning(OffsetDateTime.parse(warningStr));
        }
        Object freshness = snapshot.get("dataFreshnessTimestamp");
        if (freshness instanceof OffsetDateTime time) {
            summary.setDataFreshnessTimestamp(time);
        } else if (freshness instanceof String str && !str.isBlank()) {
            summary.setDataFreshnessTimestamp(OffsetDateTime.parse(str));
        }
        return summary;
    }

    private Map<String, Object> loyaltySnapshotFromSummary(CartLoyaltySummary summary) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("programEnabled", summary.isProgramEnabled());
        snapshot.put("programId", summary.getProgramId());
        snapshot.put("memberPointsBalance", summary.getMemberPointsBalance());
        snapshot.put("availablePointsBalance", summary.getAvailablePointsBalance());
        snapshot.put("reservedPoints", summary.getReservedPoints());
        snapshot.put("estimatedPointsEarned", summary.getEstimatedPointsEarned());
        snapshot.put("estimatedRewardValue",
                summary.getEstimatedRewardValue() != null ? summary.getEstimatedRewardValue().getAmount() : null);
        snapshot.put("availableRedemptionValue",
                summary.getAvailableRedemptionValue() != null ? summary.getAvailableRedemptionValue().getAmount()
                        : null);
        snapshot.put("redemptionValuePerPoint", summary.getRedemptionValuePerPoint());
        snapshot.put("currentTier", summary.getCurrentTier());
        snapshot.put("pointsExpirationWarning", summary.getPointsExpirationWarning());
        snapshot.put("dataFreshnessTimestamp", summary.getDataFreshnessTimestamp());
        return snapshot;
    }

    private Integer toInteger(Object value) {
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            return Integer.parseInt(str);
        }
        return null;
    }

    private BigDecimal convertToBigDecimal(Object value) {
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String str && !str.isBlank()) {
            return new BigDecimal(str);
        }
        return BigDecimal.ZERO;
    }

    private String toPlainString(BigDecimal value) {
        return value != null ? value.stripTrailingZeros().toPlainString() : null;
    }
}
