package villagecompute.storefront.api.headless;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import org.jboss.logging.Logger;

import villagecompute.storefront.api.types.AddToCartRequest;
import villagecompute.storefront.api.types.CartDto;
import villagecompute.storefront.api.types.CartItemDto;
import villagecompute.storefront.api.types.UpdateCartItemRequest;
import villagecompute.storefront.data.models.Cart;
import villagecompute.storefront.data.models.CartItem;
import villagecompute.storefront.services.CartService;
import villagecompute.storefront.services.FeatureToggle;
import villagecompute.storefront.services.mappers.CartMapper;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Headless API resource for cart operations.
 *
 * <p>
 * Provides OAuth-secured endpoints for shopping cart management via headless/custom frontends. All endpoints require
 * valid OAuth client credentials with `cart:write` scope.
 *
 * <p>
 * <strong>Endpoints:</strong>
 * <ul>
 * <li>GET /api/v1/headless/cart - Get cart by session ID</li>
 * <li>POST /api/v1/headless/cart/items - Add item to cart</li>
 * <li>PATCH /api/v1/headless/cart/items/{itemId} - Update cart item quantity</li>
 * <li>DELETE /api/v1/headless/cart/items/{itemId} - Remove cart item</li>
 * <li>DELETE /api/v1/headless/cart - Clear entire cart</li>
 * </ul>
 *
 * <p>
 * <strong>Session Handling:</strong> Headless clients must pass `X-Session-Id` header with guest session UUID. Unlike
 * the browser-based cart API, cookies are not supported in headless mode.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T7: Headless cart endpoints with OAuth and session management</li>
 * <li>Architecture: Section 5 Contract Patterns (OAuth scopes)</li>
 * <li>Architecture: UI/UX Section 3.1.4 (Headless & Embedded Widgets)</li>
 * </ul>
 */
@Path("/api/v1/headless/cart")
@Produces(MediaType.APPLICATION_JSON)
@HeadlessApiBinding
public class HeadlessCartResource {

    private static final Logger LOG = Logger.getLogger(HeadlessCartResource.class);

    private static final String SESSION_HEADER = "X-Session-Id";

    @Inject
    CartService cartService;

    @Inject
    CartMapper cartMapper;

    @Inject
    FeatureToggle featureToggle;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Get cart by session ID.
     *
     * @param sessionId
     *            guest session UUID (required via X-Session-Id header)
     * @return cart DTO
     */
    @GET
    public Response getCart(@HeaderParam(SESSION_HEADER) String sessionId) {

        // Verify feature flag
        if (!featureToggle.isEnabled("headless.api.enabled")) {
            LOG.warn("Headless API feature disabled");
            return Response.status(Status.FORBIDDEN).entity(createProblemDetails("Feature Disabled",
                    "Headless API is not enabled for this tenant", Status.FORBIDDEN)).build();
        }

        if (sessionId == null || sessionId.isBlank()) {
            return Response.status(Status.BAD_REQUEST).entity(
                    createProblemDetails("Missing Session ID", "X-Session-Id header is required", Status.BAD_REQUEST))
                    .build();
        }

        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /headless/cart - tenantId=%s, sessionId=%s", tenantId, sessionId);

        Optional<Cart> cartOptional = cartService.findActiveCartForSession(sessionId);

        if (cartOptional.isEmpty()) {
            return Response.status(Status.NOT_FOUND).entity(
                    createProblemDetails("Cart Not Found", "No active cart found for session", Status.NOT_FOUND))
                    .build();
        }

        Cart cart = cartOptional.get();
        CartDto dto = cartMapper.toDto(cart);

        meterRegistry.counter("headless.cart.get", "tenant_id", tenantId.toString()).increment();

        return Response.ok(dto).build();
    }

    /**
     * Add item to cart.
     *
     * @param sessionId
     *            guest session UUID
     * @param request
     *            add to cart request
     * @return created cart item DTO
     */
    @POST
    @Path("/items")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addToCart(@HeaderParam(SESSION_HEADER) String sessionId, @Valid AddToCartRequest request) {

        // Verify feature flag
        if (!featureToggle.isEnabled("headless.api.enabled")) {
            LOG.warn("Headless API feature disabled");
            return Response.status(Status.FORBIDDEN).entity(createProblemDetails("Feature Disabled",
                    "Headless API is not enabled for this tenant", Status.FORBIDDEN)).build();
        }

        if (sessionId == null || sessionId.isBlank()) {
            return Response.status(Status.BAD_REQUEST).entity(
                    createProblemDetails("Missing Session ID", "X-Session-Id header is required", Status.BAD_REQUEST))
                    .build();
        }

        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /headless/cart/items - tenantId=%s, sessionId=%s, variantId=%s, quantity=%d", tenantId,
                sessionId, request.getVariantId(), request.getQuantity());

        try {
            Cart cart = cartService.getOrCreateCartForSession(sessionId);
            CartItem cartItem = cartService.addItemToCart(cart.id, request.getVariantId(), request.getQuantity());

            CartItemDto dto = cartMapper.toItemDto(cartItem);
            meterRegistry.counter("headless.cart.add_item", "tenant_id", tenantId.toString()).increment();

            return Response.status(Status.CREATED).entity(dto).build();

        } catch (IllegalArgumentException e) {
            LOG.warnf("Invalid add to cart request - tenantId=%s, error=%s", tenantId, e.getMessage());
            Status status = isResourceMissing(e) ? Status.NOT_FOUND : Status.BAD_REQUEST;
            String title = status == Status.NOT_FOUND ? "Not Found" : "Bad Request";
            return Response.status(status).entity(createProblemDetails(title, e.getMessage(), status)).build();

        } catch (OptimisticLockException e) {
            LOG.warnf("Optimistic lock exception - tenantId=%s, sessionId=%s", tenantId, sessionId);
            return Response.status(Status.CONFLICT).entity(createProblemDetails("Conflict",
                    "Cart was modified concurrently. Please refresh and try again.", Status.CONFLICT)).build();
        }
    }

    /**
     * Update cart item quantity.
     *
     * @param sessionId
     *            guest session UUID
     * @param itemId
     *            cart item UUID
     * @param request
     *            update request
     * @return updated cart item DTO
     */
    @PATCH
    @Path("/items/{itemId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateCartItem(@HeaderParam(SESSION_HEADER) String sessionId, @PathParam("itemId") UUID itemId,
            @Valid UpdateCartItemRequest request) {

        // Verify feature flag
        if (!featureToggle.isEnabled("headless.api.enabled")) {
            LOG.warn("Headless API feature disabled");
            return Response.status(Status.FORBIDDEN).entity(createProblemDetails("Feature Disabled",
                    "Headless API is not enabled for this tenant", Status.FORBIDDEN)).build();
        }

        if (sessionId == null || sessionId.isBlank()) {
            return Response.status(Status.BAD_REQUEST).entity(
                    createProblemDetails("Missing Session ID", "X-Session-Id header is required", Status.BAD_REQUEST))
                    .build();
        }

        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("PATCH /headless/cart/items/%s - tenantId=%s, sessionId=%s, quantity=%d", itemId, tenantId, sessionId,
                request.getQuantity());

        try {
            Cart cart = cartService.getOrCreateCartForSession(sessionId);
            CartItem cartItem = cartService.updateCartItemQuantity(cart.id, itemId, request.getQuantity());

            CartItemDto dto = cartMapper.toItemDto(cartItem);
            meterRegistry.counter("headless.cart.update_item", "tenant_id", tenantId.toString()).increment();

            return Response.ok(dto).build();

        } catch (IllegalArgumentException e) {
            LOG.warnf("Cart item not found or invalid - tenantId=%s, itemId=%s, error=%s", tenantId, itemId,
                    e.getMessage());
            Status status = isResourceMissing(e) ? Status.NOT_FOUND : Status.BAD_REQUEST;
            String title = status == Status.NOT_FOUND ? "Not Found" : "Bad Request";
            return Response.status(status).entity(createProblemDetails(title, e.getMessage(), status)).build();

        } catch (OptimisticLockException e) {
            LOG.warnf("Optimistic lock exception - tenantId=%s, itemId=%s, sessionId=%s", tenantId, itemId, sessionId);
            return Response.status(Status.CONFLICT)
                    .entity(createProblemDetails("Conflict",
                            "Cart item was modified concurrently. Please refresh and try again.", Status.CONFLICT))
                    .build();
        }
    }

    /**
     * Remove item from cart.
     *
     * @param sessionId
     *            guest session UUID
     * @param itemId
     *            cart item UUID
     * @return 204 No Content
     */
    @DELETE
    @Path("/items/{itemId}")
    public Response removeCartItem(@HeaderParam(SESSION_HEADER) String sessionId, @PathParam("itemId") UUID itemId) {

        // Verify feature flag
        if (!featureToggle.isEnabled("headless.api.enabled")) {
            LOG.warn("Headless API feature disabled");
            return Response.status(Status.FORBIDDEN).entity(createProblemDetails("Feature Disabled",
                    "Headless API is not enabled for this tenant", Status.FORBIDDEN)).build();
        }

        if (sessionId == null || sessionId.isBlank()) {
            return Response.status(Status.BAD_REQUEST).entity(
                    createProblemDetails("Missing Session ID", "X-Session-Id header is required", Status.BAD_REQUEST))
                    .build();
        }

        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("DELETE /headless/cart/items/%s - tenantId=%s, sessionId=%s", itemId, tenantId, sessionId);

        try {
            Cart cart = cartService.getOrCreateCartForSession(sessionId);
            cartService.removeCartItem(cart.id, itemId);

            meterRegistry.counter("headless.cart.remove_item", "tenant_id", tenantId.toString()).increment();

            return Response.noContent().build();

        } catch (IllegalArgumentException e) {
            LOG.warnf("Cart item not found - tenantId=%s, itemId=%s, error=%s", tenantId, itemId, e.getMessage());
            return Response.status(Status.NOT_FOUND)
                    .entity(createProblemDetails("Not Found", e.getMessage(), Status.NOT_FOUND)).build();
        }
    }

    /**
     * Clear all items from cart.
     *
     * @param sessionId
     *            guest session UUID
     * @return 204 No Content
     */
    @DELETE
    public Response clearCart(@HeaderParam(SESSION_HEADER) String sessionId) {

        // Verify feature flag
        if (!featureToggle.isEnabled("headless.api.enabled")) {
            LOG.warn("Headless API feature disabled");
            return Response.status(Status.FORBIDDEN).entity(createProblemDetails("Feature Disabled",
                    "Headless API is not enabled for this tenant", Status.FORBIDDEN)).build();
        }

        if (sessionId == null || sessionId.isBlank()) {
            return Response.status(Status.BAD_REQUEST).entity(
                    createProblemDetails("Missing Session ID", "X-Session-Id header is required", Status.BAD_REQUEST))
                    .build();
        }

        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("DELETE /headless/cart - tenantId=%s, sessionId=%s", tenantId, sessionId);

        Optional<Cart> cartOptional = cartService.findActiveCartForSession(sessionId);
        if (cartOptional.isEmpty()) {
            return Response.status(Status.NOT_FOUND).entity(
                    createProblemDetails("Cart Not Found", "No active cart found for session", Status.NOT_FOUND))
                    .build();
        }

        cartService.clearCart(cartOptional.get().id);
        meterRegistry.counter("headless.cart.clear", "tenant_id", tenantId.toString()).increment();

        return Response.noContent().build();
    }

    /**
     * Create RFC 7807 Problem Details error response.
     *
     * @param title
     *            error title
     * @param detail
     *            error detail message
     * @param status
     *            HTTP status code
     * @return problem details object
     */
    private Map<String, Object> createProblemDetails(String title, String detail, Status status) {
        Map<String, Object> problem = new HashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", title);
        problem.put("status", status.getStatusCode());
        if (detail != null && !detail.isBlank()) {
            problem.put("detail", detail);
        }
        return problem;
    }

    private boolean isResourceMissing(IllegalArgumentException e) {
        if (e == null || e.getMessage() == null) {
            return false;
        }
        String message = e.getMessage().toLowerCase();
        return message.contains("not found") || message.contains("does not belong");
    }
}
