package villagecompute.storefront.api.rest;

import java.util.HashMap;
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

import org.jboss.logging.Logger;

import villagecompute.storefront.api.types.AddToCartRequest;
import villagecompute.storefront.api.types.CartDto;
import villagecompute.storefront.api.types.CartItemDto;
import villagecompute.storefront.api.types.UpdateCartItemRequest;
import villagecompute.storefront.data.models.Cart;
import villagecompute.storefront.data.models.CartItem;
import villagecompute.storefront.services.CartService;
import villagecompute.storefront.services.mappers.CartMapper;
import villagecompute.storefront.tenant.TenantContext;

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
 * <li>OpenAPI: /cart endpoints specification</li>
 * </ul>
 */
@Path("/api/v1/cart")
@Produces(MediaType.APPLICATION_JSON)
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
    public Response getCart() {
        SessionContext session = resolveSessionContext();
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /cart - tenantId=%s, sessionId=%s", tenantId, session.sessionId());

        Optional<Cart> cartOptional = cartService.findActiveCartForSession(session.sessionId());
        if (cartOptional.isEmpty()) {
            return respond(session, Response.status(Status.NOT_FOUND).entity(
                    createProblemDetails("Cart Not Found", "No active cart found for session", Status.NOT_FOUND)));
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
    public Response clearCart() {
        SessionContext session = resolveSessionContext();
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("DELETE /cart - tenantId=%s, sessionId=%s", tenantId, session.sessionId());

        Optional<Cart> cartOptional = cartService.findActiveCartForSession(session.sessionId());
        if (cartOptional.isEmpty()) {
            return respond(session, Response.status(Status.NOT_FOUND).entity(
                    createProblemDetails("Cart Not Found", "No active cart found for session", Status.NOT_FOUND)));
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
            String title = status == Status.NOT_FOUND ? "Not Found" : "Bad Request";
            return respond(session,
                    Response.status(status).entity(createProblemDetails(title, e.getMessage(), status)));

        } catch (OptimisticLockException e) {
            LOG.warnf("Optimistic lock exception - tenantId=%s, cartId=%s", tenantId, session.sessionId());
            return respond(session, Response.status(Status.CONFLICT).entity(createProblemDetails("Conflict",
                    "Cart was modified concurrently. Please refresh and try again.", Status.CONFLICT)));
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
    public Response updateCartItem(@PathParam("itemId") UUID itemId, @Valid UpdateCartItemRequest request) {
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
            String title = status == Status.NOT_FOUND ? "Not Found" : "Bad Request";
            return respond(session,
                    Response.status(status).entity(createProblemDetails(title, e.getMessage(), status)));

        } catch (OptimisticLockException e) {
            LOG.warnf("Optimistic lock exception - tenantId=%s, itemId=%s, sessionId=%s", tenantId, itemId,
                    session.sessionId());
            return respond(session, Response.status(Status.CONFLICT).entity(createProblemDetails("Conflict",
                    "Cart item was modified concurrently. Please refresh and try again.", Status.CONFLICT)));
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
    public Response removeCartItem(@PathParam("itemId") UUID itemId) {
        SessionContext session = resolveSessionContext();
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("DELETE /cart/items/%s - tenantId=%s, sessionId=%s", itemId, tenantId, session.sessionId());

        try {
            Cart cart = cartService.getOrCreateCartForSession(session.sessionId());

            cartService.removeCartItem(cart.id, itemId);
            return respond(session, Response.noContent());

        } catch (IllegalArgumentException e) {
            LOG.warnf("Cart item not found - tenantId=%s, itemId=%s, error=%s", tenantId, itemId, e.getMessage());
            return respond(session, Response.status(Status.NOT_FOUND)
                    .entity(createProblemDetails("Not Found", e.getMessage(), Status.NOT_FOUND)));
        }
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
