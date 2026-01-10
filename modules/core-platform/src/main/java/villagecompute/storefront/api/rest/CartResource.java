package villagecompute.storefront.api.rest;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import villagecompute.storefront.api.types.AddToCartRequest;
import villagecompute.storefront.api.types.ApplyPromotionRequest;
import villagecompute.storefront.api.types.CartDto;
import villagecompute.storefront.api.types.CartItemDto;
import villagecompute.storefront.api.types.UpdateCartItemRequest;
import villagecompute.storefront.data.models.Cart;
import villagecompute.storefront.data.models.CartItem;
import villagecompute.storefront.data.models.Promotion;
import villagecompute.storefront.services.CartService;
import villagecompute.storefront.services.mappers.CartMapper;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.util.ProblemDetailsUtil;

/**
 * REST resource for cart operations.
 *
 * <p>
 * Provides endpoints for managing shopping carts including:
 * <ul>
 * <li>GET /cart - Get current cart</li>
 * <li>DELETE /cart - Clear cart</li>
 * <li>POST /cart/items - Add item to cart</li>
 * <li>PATCH /cart/items/{itemId} - Update cart item quantity</li>
 * <li>DELETE /cart/items/{itemId} - Remove cart item</li>
 * </ul>
 *
 * <p>
 * All endpoints are tenant-scoped via TenantContext. Supports both authenticated user carts and guest session carts.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T4: Cart REST endpoints with optimistic locking and Problem Details</li>
 * <li>Task I3.T2: Add OpenAPI annotations and ProblemDetailsUtil usage</li>
 * <li>OpenAPI: /cart endpoints specification</li>
 * </ul>
 */
@Path("/api/v1/cart")
@Produces(MediaType.APPLICATION_JSON)
@Tag(
        name = "Storefront",
        description = "Customer-facing cart operations")
public class CartResource {

    private static final Logger LOG = Logger.getLogger(CartResource.class);

    private static final String SESSION_COOKIE = "vs_session";
    private static final String SESSION_HEADER = "X-Session-Id";
    private static final int SESSION_TTL_SECONDS = 60 * 60 * 24 * 30;

    @Inject
    CartService cartService;

    @Inject
    CartMapper cartMapper;

    @Context
    HttpHeaders httpHeaders;

    /**
     * Get current user's cart.
     *
     * @return cart DTO
     */
    @GET
    @Operation(
            summary = "Get current user's cart",
            description = """
                    Returns the active cart for the authenticated user or guest session.
                    Includes all line items with calculated totals.

                    **Authentication:** Optional - guest carts tracked via session, user carts via JWT.
                    Provide the X-Session-Id header (or rely on the vs_session cookie issued by the API) to continue a guest cart.
                    """)
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Cart retrieved successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = CartDto.class))),
                    @APIResponse(
                            responseCode = "404",
                            description = "No active cart found",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    public Response getCart() {
        SessionContext session = resolveSessionContext();
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /cart - tenantId=%s, sessionId=%s", tenantId, session.sessionId());

        Optional<Cart> cartOptional = cartService.findActiveCartForSession(session.sessionId());
        if (cartOptional.isEmpty()) {
            return respond(session, Response.status(Status.NOT_FOUND)
                    .entity(ProblemDetailsUtil.notFound("No active cart found for session")));
        }

        Cart cart = cartOptional.get();
        CartDto dto = cartMapper.toDto(cart);
        LOG.debugf("Cart resolved - tenantId=%s, sessionId=%s, cartId=%s", tenantId, session.sessionId(), cart.id);
        return respond(session, Response.ok(dto));
    }

    /**
     * Clear all items from cart.
     *
     * @return 204 No Content
     */
    @DELETE
    @Operation(
            summary = "Clear all items from cart",
            description = """
                    Removes all line items from the cart. The cart entity itself remains
                    and can be reused for future items.
                    """)
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "204",
                    description = "Cart cleared successfully"),
                    @APIResponse(
                            responseCode = "404",
                            description = "Cart not found",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    public Response clearCart() {
        SessionContext session = resolveSessionContext();
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("DELETE /cart - tenantId=%s, sessionId=%s", tenantId, session.sessionId());

        Optional<Cart> cartOptional = cartService.findActiveCartForSession(session.sessionId());
        if (cartOptional.isEmpty()) {
            return respond(session, Response.status(Status.NOT_FOUND)
                    .entity(ProblemDetailsUtil.notFound("No active cart found for session")));
        }

        cartService.clearCart(cartOptional.get().id);
        return respond(session, Response.noContent());
    }

    /**
     * Add item to cart.
     *
     * @param request
     *            add to cart request
     * @return created cart item DTO
     */
    @POST
    @Path("/items")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Add item to cart",
            description = """
                    Adds a product variant to the cart with specified quantity.
                    If the variant already exists in the cart, the quantities are merged.

                    **Price Snapshot:** The current variant price is captured at add-to-cart
                    time to prevent cart total changes when product prices are updated.

                    **Optimistic Locking:** Returns HTTP 409 if concurrent modification detected.
                    **Guest Session:** Provide X-Session-Id or use the vs_session cookie to add to the same anonymous cart.
                    """)
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "201",
                    description = "Item added to cart successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = CartItemDto.class))),
                    @APIResponse(
                            responseCode = "400",
                            description = "Validation error (invalid variant, negative quantity, etc.)",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "404",
                            description = "Product variant not found",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "409",
                            description = "Optimistic lock exception (concurrent modification detected)",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    public Response addToCart(@Valid AddToCartRequest request) {
        SessionContext session = resolveSessionContext();
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /cart/items - tenantId=%s, sessionId=%s, variantId=%s, quantity=%d", tenantId,
                session.sessionId(), request.getVariantId(), request.getQuantity());

        try {
            Cart cart = cartService.getOrCreateCartForSession(session.sessionId());

            CartItem cartItem = cartService.addItemToCart(cart.id, request.getVariantId(), request.getQuantity());

            CartItemDto dto = cartMapper.toItemDto(cartItem);
            return respond(session, Response.status(Status.CREATED).entity(dto));

        } catch (IllegalArgumentException e) {
            LOG.warnf("Invalid add to cart request - tenantId=%s, error=%s", tenantId, e.getMessage());
            Status status = isResourceMissing(e) ? Status.NOT_FOUND : Status.BAD_REQUEST;
            Map<String, Object> problemDetails = status == Status.NOT_FOUND
                    ? ProblemDetailsUtil.notFound(e.getMessage())
                    : ProblemDetailsUtil.badRequest(e.getMessage());
            return respond(session, Response.status(status).entity(problemDetails));

        } catch (OptimisticLockException e) {
            LOG.warnf("Optimistic lock exception - tenantId=%s, cartId=%s", tenantId, session.sessionId());
            return respond(session, Response.status(Status.CONFLICT).entity(
                    ProblemDetailsUtil.conflict("Cart was modified concurrently. Please refresh and try again.")));
        }
    }

    /**
     * Update cart item quantity.
     *
     * @param itemId
     *            cart item UUID
     * @param request
     *            update request
     * @return updated cart item DTO
     */
    @PATCH
    @Path("/items/{itemId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Update cart item quantity",
            description = """
                    Updates the quantity of a specific cart line item.

                    **Optimistic Locking:** Returns HTTP 409 if concurrent modification detected.
                    """)
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Cart item updated successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = CartItemDto.class))),
                    @APIResponse(
                            responseCode = "400",
                            description = "Validation error (negative quantity, etc.)",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "404",
                            description = "Cart item not found",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "409",
                            description = "Optimistic lock exception (concurrent modification detected)",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    public Response updateCartItem(@Parameter(
            description = "Cart item UUID",
            required = true) @PathParam("itemId") UUID itemId, @Valid UpdateCartItemRequest request) {
        SessionContext session = resolveSessionContext();
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("PATCH /cart/items/%s - tenantId=%s, sessionId=%s, quantity=%d", itemId, tenantId,
                session.sessionId(), request.getQuantity());

        try {
            Cart cart = cartService.getOrCreateCartForSession(session.sessionId());

            CartItem cartItem = cartService.updateCartItemQuantity(cart.id, itemId, request.getQuantity());

            CartItemDto dto = cartMapper.toItemDto(cartItem);
            return respond(session, Response.ok(dto));

        } catch (IllegalArgumentException e) {
            LOG.warnf("Cart item not found or invalid - tenantId=%s, itemId=%s, error=%s", tenantId, itemId,
                    e.getMessage());

            Status status = isResourceMissing(e) ? Status.NOT_FOUND : Status.BAD_REQUEST;
            Map<String, Object> problemDetails = status == Status.NOT_FOUND
                    ? ProblemDetailsUtil.notFound(e.getMessage())
                    : ProblemDetailsUtil.badRequest(e.getMessage());
            return respond(session, Response.status(status).entity(problemDetails));

        } catch (OptimisticLockException e) {
            LOG.warnf("Optimistic lock exception - tenantId=%s, itemId=%s, sessionId=%s", tenantId, itemId,
                    session.sessionId());
            return respond(session, Response.status(Status.CONFLICT).entity(
                    ProblemDetailsUtil.conflict("Cart item was modified concurrently. Please refresh and try again.")));
        }
    }

    /**
     * Remove item from cart.
     *
     * @param itemId
     *            cart item UUID
     * @return 204 No Content
     */
    @DELETE
    @Path("/items/{itemId}")
    @Operation(
            summary = "Remove item from cart",
            description = "Removes a specific line item from the cart.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "204",
                    description = "Cart item removed successfully"),
                    @APIResponse(
                            responseCode = "404",
                            description = "Cart item not found",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    public Response removeCartItem(@Parameter(
            description = "Cart item UUID",
            required = true) @PathParam("itemId") UUID itemId) {
        SessionContext session = resolveSessionContext();
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("DELETE /cart/items/%s - tenantId=%s, sessionId=%s", itemId, tenantId, session.sessionId());

        try {
            Cart cart = cartService.getOrCreateCartForSession(session.sessionId());

            cartService.removeCartItem(cart.id, itemId);
            return respond(session, Response.noContent());

        } catch (IllegalArgumentException e) {
            LOG.warnf("Cart item not found - tenantId=%s, itemId=%s, error=%s", tenantId, itemId, e.getMessage());
            return respond(session,
                    Response.status(Status.NOT_FOUND).entity(ProblemDetailsUtil.notFound(e.getMessage())));
        }
    }

    /**
     * Apply promotion code to cart.
     *
     * @param request
     *            apply promotion request with promo code
     * @return updated cart DTO with applied discount
     */
    @POST
    @Path("/promotions")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            operationId = "applyPromotion",
            summary = "Apply promotion code to cart",
            description = """
                    Applies a discount code to the current cart. Validates the promotion code against
                    expiry dates, usage limits, and minimum order requirements.

                    **Validation:**
                    - Promotion must be active and not expired
                    - Usage limits (global and per-customer) enforced
                    - Minimum order amount validated (if configured)
                    - Only one promotion per cart (replaces existing)

                    **Guest Session:** Provide X-Session-Id or use the vs_session cookie.
                    """)
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Promotion applied successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = CartDto.class))),
                    @APIResponse(
                            responseCode = "400",
                            description = "Invalid promotion code (expired, usage limit exceeded, minimum not met)",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "404",
                            description = "Cart or promotion not found",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    public Response applyPromotion(@Valid ApplyPromotionRequest request) {
        SessionContext session = resolveSessionContext();
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /cart/promotions - tenantId=%s, sessionId=%s, promoCode=%s", tenantId, session.sessionId(),
                request.getPromoCode());

        try {
            Cart cart = cartService.getOrCreateCartForSession(session.sessionId());

            Promotion promotion = cartService.applyPromotion(cart.id, request.getPromoCode());

            // Reload cart to get updated totals
            cart = Cart.findById(cart.id);
            CartDto dto = cartMapper.toDto(cart);
            return respond(session, Response.ok(dto));

        } catch (IllegalArgumentException e) {
            LOG.warnf("Invalid promotion code - tenantId=%s, promoCode=%s, error=%s", tenantId, request.getPromoCode(),
                    e.getMessage());
            Status status = isResourceMissing(e) ? Status.NOT_FOUND : Status.BAD_REQUEST;
            Map<String, Object> problemDetails = status == Status.NOT_FOUND
                    ? ProblemDetailsUtil.notFound(e.getMessage())
                    : ProblemDetailsUtil.badRequest(e.getMessage());
            return respond(session, Response.status(status).entity(problemDetails));
        }
    }

    /**
     * Remove promotion code from cart.
     *
     * @return updated cart DTO without discount
     */
    @DELETE
    @Path("/promotions")
    @Operation(
            operationId = "removePromotion",
            summary = "Remove promotion from cart",
            description = """
                    Removes the applied discount code from the cart and recalculates totals.

                    **Guest Session:** Provide X-Session-Id or use the vs_session cookie.
                    """)
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Promotion removed successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = CartDto.class))),
                    @APIResponse(
                            responseCode = "404",
                            description = "Cart not found",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    public Response removePromotion() {
        SessionContext session = resolveSessionContext();
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("DELETE /cart/promotions - tenantId=%s, sessionId=%s", tenantId, session.sessionId());

        Optional<Cart> cartOptional = cartService.findActiveCartForSession(session.sessionId());
        if (cartOptional.isEmpty()) {
            return respond(session, Response.status(Status.NOT_FOUND)
                    .entity(ProblemDetailsUtil.notFound("No active cart found for session")));
        }

        Cart cart = cartOptional.get();
        cartService.removePromotion(cart.id);

        // Reload cart to get updated totals
        cart = Cart.findById(cart.id);
        CartDto dto = cartMapper.toDto(cart);
        return respond(session, Response.ok(dto));
    }

    /**
     * Get cart totals breakdown.
     *
     * @return cart totals including subtotal, discount, tax, and total
     */
    @GET
    @Path("/totals")
    @Operation(
            operationId = "getCartTotals",
            summary = "Get cart totals",
            description = """
                    Returns a breakdown of cart pricing including subtotal, applied discounts,
                    estimated tax (if configured), and grand total.

                    **Note:** Tax and shipping are not calculated until checkout.
                    This endpoint provides subtotal and discount only.

                    **Guest Session:** Provide X-Session-Id or use the vs_session cookie.
                    """)
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Cart totals retrieved successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "404",
                            description = "Cart not found",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    public Response getCartTotals() {
        SessionContext session = resolveSessionContext();
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /cart/totals - tenantId=%s, sessionId=%s", tenantId, session.sessionId());

        Optional<Cart> cartOptional = cartService.findActiveCartForSession(session.sessionId());
        if (cartOptional.isEmpty()) {
            return respond(session, Response.status(Status.NOT_FOUND)
                    .entity(ProblemDetailsUtil.notFound("No active cart found for session")));
        }

        Cart cart = cartOptional.get();

        // Calculate totals using CartService
        java.math.BigDecimal subtotal = cartService.calculateCartSubtotal(cart.id);
        java.math.BigDecimal total = cartService.calculateCartTotal(cart.id);
        java.math.BigDecimal discount = subtotal.subtract(total);
        long itemCount = cartService.getCartItemCount(cart.id);

        // Default currency is USD - could be made tenant-specific in future
        String currency = "USD";

        Map<String, Object> totals = new java.util.HashMap<>();
        totals.put("subtotal", buildMoney(subtotal, currency));
        totals.put("discount", buildMoney(discount, currency));
        totals.put("total", buildMoney(total, currency));
        totals.put("currency", currency);
        totals.put("itemCount", itemCount);

        return respond(session, Response.ok(totals));
    }

    private Map<String, String> buildMoney(java.math.BigDecimal amount, String currency) {
        Map<String, String> money = new java.util.HashMap<>();
        money.put("amount", amount != null ? amount.toPlainString() : "0.00");
        money.put("currency", currency != null ? currency : "USD");
        return money;
    }

    private SessionContext resolveSessionContext() {
        Optional<String> headerSession = readSessionIdFromHeader();
        if (headerSession.isPresent()) {
            return new SessionContext(headerSession.get(), false);
        }

        Optional<String> cookieSession = readSessionIdFromCookie();
        if (cookieSession.isPresent()) {
            return new SessionContext(cookieSession.get(), false);
        }

        String generated = UUID.randomUUID().toString();
        LOG.debugf("Generated new guest session id=%s", generated);
        return new SessionContext(generated, true);
    }

    private Optional<String> readSessionIdFromHeader() {
        if (httpHeaders == null) {
            return Optional.empty();
        }
        return normalizeSessionId(httpHeaders.getHeaderString(SESSION_HEADER));
    }

    private Optional<String> readSessionIdFromCookie() {
        if (httpHeaders == null || httpHeaders.getCookies() == null) {
            return Optional.empty();
        }
        Cookie cookie = httpHeaders.getCookies().get(SESSION_COOKIE);
        if (cookie == null) {
            return Optional.empty();
        }
        return normalizeSessionId(cookie.getValue());
    }

    private Optional<String> normalizeSessionId(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(trimmed).toString());
        } catch (IllegalArgumentException ex) {
            LOG.warnf("Ignoring invalid session identifier value=%s", trimmed);
            return Optional.empty();
        }
    }

    private Response respond(SessionContext sessionContext, Response.ResponseBuilder builder) {
        if (sessionContext.newSession()) {
            builder.cookie(new NewCookie(SESSION_COOKIE, sessionContext.sessionId(), "/", null, "Guest session",
                    SESSION_TTL_SECONDS, false, true));
            builder.header(SESSION_HEADER, sessionContext.sessionId());
        }
        return builder.build();
    }

    private boolean isResourceMissing(IllegalArgumentException e) {
        if (e == null || e.getMessage() == null) {
            return false;
        }
        String message = e.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("not found") || message.contains("does not belong");
    }

    private record SessionContext(String sessionId, boolean newSession) {
    }
}
