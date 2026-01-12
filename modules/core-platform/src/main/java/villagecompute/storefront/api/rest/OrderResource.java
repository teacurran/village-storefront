package villagecompute.storefront.api.rest;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import villagecompute.storefront.api.types.Money;
import villagecompute.storefront.api.types.RefundResponse;
import villagecompute.storefront.api.types.UpdateOrderRequest;
import villagecompute.storefront.data.models.Order;
import villagecompute.storefront.data.models.OrderLineItem;
import villagecompute.storefront.services.OrderService;
import villagecompute.storefront.services.RefundService;
import villagecompute.storefront.services.ReturnService;
import villagecompute.storefront.services.returns.ReturnWorkflowItem;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.util.ProblemDetailsUtil;

import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;

/**
 * REST resource for order operations (storefront and admin).
 *
 * <p>
 * Provides endpoints for:
 * <ul>
 * <li>GET /api/v1/orders - List customer orders</li>
 * <li>GET /api/v1/orders/{orderId} - Get order details</li>
 * <li>GET /api/v1/admin/orders - List all tenant orders (admin)</li>
 * <li>GET /api/v1/admin/orders/{orderId} - Get order details (admin)</li>
 * <li>PATCH /api/v1/admin/orders/{orderId} - Update order (admin)</li>
 * <li>POST /api/v1/admin/orders/{orderId}/cancel - Cancel order (admin)</li>
 * <li>POST /api/v1/admin/orders/{orderId}/refund - Refund order (admin)</li>
 * </ul>
 *
 * <p>
 * All endpoints are tenant-scoped via TenantContext. Admin endpoints require appropriate role/scope enforcement.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Order REST endpoints with admin operations</li>
 * <li>OpenAPI: /orders and /admin/orders endpoints specification</li>
 * </ul>
 */
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Tag(
        name = "Storefront",
        description = "Customer-facing order operations")
public class OrderResource {

    private static final Logger LOG = Logger.getLogger(OrderResource.class);

    @Inject
    OrderService orderService;

    @Inject
    RefundService refundService;

    @Inject
    ReturnService returnService;

    @Inject
    ObjectMapper objectMapper;

    // ========================================
    // STOREFRONT ENDPOINTS
    // ========================================

    /**
     * Get order details by ID.
     *
     * @param orderId
     *            order UUID
     * @return order detail DTO
     */
    @GET
    @Path("/orders/{orderId}")
    @Operation(
            summary = "Get order details",
            description = """
                    Returns complete order details including line items, payment status, shipping address,
                    and fulfillment tracking information.

                    **Authentication:** Required - customers can only access their own orders.
                    **Admin users:** Can access all orders within their tenant.
                    """)
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Order retrieved successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    ref = "#/components/schemas/OrderDetail"))),
                    @APIResponse(
                            responseCode = "404",
                            description = "Order not found or belongs to different tenant",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    public Response getOrder(@Parameter(
            description = "Order UUID",
            required = true) @PathParam("orderId") UUID orderId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /orders/%s - tenantId=%s", orderId, tenantId);

        try {
            Order order = Order.findById(orderId);
            if (order == null || !order.tenant.id.equals(tenantId)) {
                return Response.status(Status.NOT_FOUND).entity(ProblemDetailsUtil.notFound("Order not found")).build();
            }

            // TODO: Add authorization check - customers can only see their own orders

            List<OrderLineItem> lineItems = orderService.getOrderLineItems(orderId);
            Map<String, Object> orderDetail = buildOrderDetail(order, lineItems);

            return Response.ok(orderDetail).build();

        } catch (RuntimeException e) {
            LOG.errorf(e, "Failed to retrieve order - tenantId=%s, orderId=%s", tenantId, orderId);
            return Response.status(Status.INTERNAL_SERVER_ERROR)
                    .entity(ProblemDetailsUtil.internalServerError("Failed to retrieve order. Please contact support."))
                    .build();
        }
    }

    /**
     * List orders for current user with pagination and filtering.
     *
     * @param page
     *            page number (1-indexed)
     * @param pageSize
     *            items per page
     * @param status
     *            optional status filter
     * @return paginated list of order summaries
     */
    @GET
    @Path("/orders")
    @Operation(
            summary = "List customer orders",
            description = """
                    Returns a paginated list of orders for the authenticated customer.
                    Results are sorted by creation date (newest first).

                    **Authentication:** Required - returns orders for the authenticated user only.
                    **Pagination:** Default page size is 20, maximum is 100.
                    """)
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Orders retrieved successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    ref = "#/components/schemas/OrderListResponse"))),
                    @APIResponse(
                            responseCode = "400",
                            description = "Invalid status filter",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    public Response listOrders(@Parameter(
            description = "Page number (1-indexed)",
            example = "1") @QueryParam("page") Integer page,
            @Parameter(
                    description = "Items per page (max 100)",
                    example = "20") @QueryParam("pageSize") Integer pageSize,
            @Parameter(
                    description = "Filter by order status",
                    example = "shipped") @QueryParam("status") String status) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        int pageNumber = page != null && page > 0 ? page : 1;
        int size = pageSize != null && pageSize > 0 && pageSize <= 100 ? pageSize : 20;
        LOG.infof("GET /orders - tenantId=%s, page=%d, pageSize=%d, status=%s", tenantId, pageNumber, size, status);

        // TODO: Add user ID filter for non-admin users

        try {
            // Build query and sort
            String query = "tenant.id = ?1";
            Object[] params = new Object[]{tenantId};
            if (status != null && !status.isBlank()) {
                query += " AND status = ?2";
                params = new Object[]{tenantId, Order.OrderStatus.valueOf(status.toUpperCase())};
            }

            Sort sort = Sort.by("createdAt", Sort.Direction.Descending);

            // Fetch page
            List<Order> orders = Order.find(query, sort, params).page(Page.of(pageNumber - 1, size)).list();
            long totalCount = Order.count(query, params);

            // Build response
            List<Map<String, Object>> orderSummaries = orders.stream().map(this::buildOrderSummary)
                    .collect(Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            response.put("items", orderSummaries);
            response.put("pagination", buildPaginationMetadata(pageNumber, size, totalCount));

            return Response.ok(response).build();

        } catch (RuntimeException e) {
            LOG.errorf(e, "Failed to list orders - tenantId=%s", tenantId);
            return Response.status(Status.INTERNAL_SERVER_ERROR).entity(
                    ProblemDetailsUtil.internalServerError("Failed to retrieve orders. Please contact support."))
                    .build();
        }
    }

    // ========================================
    // ADMIN ENDPOINTS
    // ========================================

    /**
     * List all tenant orders with advanced filtering (admin).
     *
     * @param page
     *            page number
     * @param pageSize
     *            items per page
     * @param status
     *            optional status filter
     * @param search
     *            optional search term (order number, email, name)
     * @param sortBy
     *            sort field
     * @param sortOrder
     *            sort direction (asc/desc)
     * @return paginated list of order summaries
     */
    @GET
    @Path("/admin/orders")
    @Tag(
            name = "Admin",
            description = "Store administration operations")
    @Operation(
            summary = "List all tenant orders (Admin)",
            description = """
                    Returns a paginated list of all orders for the current tenant with advanced filtering,
                    searching, and sorting capabilities. Admin-only endpoint.

                    **Authentication:** Requires admin role.
                    **Filtering:** Filter by order status (pending_payment, processing, shipped, delivered, cancelled, refunded).
                    **Search:** Search by order number or customer email.
                    **Sorting:** Sort by order number, customer email, status, total amount, or created date.
                    """)
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Orders retrieved successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    ref = "#/components/schemas/OrderListResponse"))),
                    @APIResponse(
                            responseCode = "400",
                            description = "Invalid filter or sort parameters",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    public Response adminListOrders(@Parameter(
            description = "Page number (1-indexed)",
            example = "1") @QueryParam("page") Integer page,
            @Parameter(
                    description = "Items per page (max 100)",
                    example = "20") @QueryParam("pageSize") Integer pageSize,
            @Parameter(
                    description = "Filter by order status",
                    example = "processing") @QueryParam("status") String status,
            @Parameter(
                    description = "Search by order number or customer email",
                    example = "ORD-2026-00042") @QueryParam("search") String search,
            @Parameter(
                    description = "Field to sort by",
                    example = "createdAt") @QueryParam("sortBy") String sortBy,
            @Parameter(
                    description = "Sort direction (asc or desc)",
                    example = "desc") @QueryParam("sortOrder") String sortOrder) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        int pageNumber = page != null && page > 0 ? page : 1;
        int size = pageSize != null && pageSize > 0 && pageSize <= 100 ? pageSize : 20;
        LOG.infof("GET /admin/orders - tenantId=%s, page=%d, pageSize=%d, status=%s, search=%s", tenantId, pageNumber,
                size, status, search);

        // TODO: Add @RolesAllowed("admin") or similar security annotation

        try {
            StringBuilder queryBuilder = new StringBuilder("tenant.id = ?1");
            List<Object> paramsList = new java.util.ArrayList<>();
            paramsList.add(tenantId);

            if (status != null && !status.isBlank()) {
                Order.OrderStatus statusFilter;
                try {
                    statusFilter = Order.OrderStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return Response.status(Status.BAD_REQUEST)
                            .entity(ProblemDetailsUtil.badRequest("Invalid status filter: " + status)).build();
                }
                queryBuilder.append(" AND status = ?").append(paramsList.size() + 1);
                paramsList.add(statusFilter);
            }

            if (search != null && !search.isBlank()) {
                String normalizedSearch = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                int nextIndex = paramsList.size() + 1;
                queryBuilder.append(" AND (LOWER(orderNumber) LIKE ?").append(nextIndex);
                queryBuilder.append(" OR LOWER(customerEmail) LIKE ?").append(nextIndex + 1).append(")");
                paramsList.add(normalizedSearch);
                paramsList.add(normalizedSearch);
            }

            String sortField = resolveAdminSortField(sortBy);
            Sort.Direction direction = "asc".equalsIgnoreCase(sortOrder) ? Sort.Direction.Ascending
                    : Sort.Direction.Descending;
            Sort sort = Sort.by(sortField, direction);

            // Fetch page
            List<Order> orders = Order.find(queryBuilder.toString(), sort, paramsList.toArray())
                    .page(Page.of(pageNumber - 1, size)).list();
            long totalCount = Order.count(queryBuilder.toString(), paramsList.toArray());

            // Build response
            List<Map<String, Object>> orderSummaries = orders.stream().map(this::buildOrderSummary)
                    .collect(Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            response.put("items", orderSummaries);
            response.put("pagination", buildPaginationMetadata(pageNumber, size, totalCount));

            return Response.ok(response).build();

        } catch (RuntimeException e) {
            LOG.errorf(e, "Failed to list admin orders - tenantId=%s", tenantId);
            return Response.status(Status.INTERNAL_SERVER_ERROR).entity(
                    ProblemDetailsUtil.internalServerError("Failed to retrieve orders. Please contact support."))
                    .build();
        }
    }

    /**
     * Get order details (admin).
     *
     * @param orderId
     *            order UUID
     * @return order detail DTO
     */
    @GET
    @Path("/admin/orders/{orderId}")
    @Tag(
            name = "Admin",
            description = "Store administration operations")
    @Operation(
            summary = "Get order details (Admin)",
            description = """
                    Returns complete order details including all fields visible to admins
                    (notes, tags, internal metadata).

                    **Authentication:** Requires admin role.
                    **Tenant isolation:** Returns 404 if order belongs to different tenant.
                    """)
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Order retrieved successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    ref = "#/components/schemas/OrderDetail"))),
                    @APIResponse(
                            responseCode = "404",
                            description = "Order not found",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    public Response adminGetOrder(@Parameter(
            description = "Order UUID",
            required = true) @PathParam("orderId") UUID orderId) {
        // Delegate to storefront endpoint with admin privileges
        return getOrder(orderId);
    }

    /**
     * Update order details (admin).
     *
     * @param orderId
     *            order UUID
     * @param request
     *            update request
     * @return updated order detail DTO
     */
    @PATCH
    @Path("/admin/orders/{orderId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    @Tag(
            name = "Admin",
            description = "Store administration operations")
    @Operation(
            summary = "Update order (Admin)",
            description = """
                    Updates order status, admin notes, or tags. Partial update - only provided fields are modified.

                    **Authentication:** Requires admin role.
                    **Status Transitions:** Some status changes may be restricted (see OrderService for state machine rules).
                    **Optimistic Locking:** Returns HTTP 409 if order was modified concurrently.

                    Use dedicated endpoints for cancellation (/cancel) and refunds (/refund).
                    """)
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Order updated successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    ref = "#/components/schemas/OrderDetail"))),
                    @APIResponse(
                            responseCode = "400",
                            description = "Invalid request (invalid status, illegal state transition)",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "404",
                            description = "Order not found",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "409",
                            description = "Optimistic lock exception (order was modified concurrently)",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    public Response adminUpdateOrder(@Parameter(
            description = "Order UUID",
            required = true) @PathParam("orderId") UUID orderId, @Valid UpdateOrderRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("PATCH /admin/orders/%s - tenantId=%s", orderId, tenantId);

        try {
            Order order = Order.findById(orderId);
            if (order == null || !order.tenant.id.equals(tenantId)) {
                return Response.status(Status.NOT_FOUND).entity(ProblemDetailsUtil.notFound("Order not found")).build();
            }

            boolean metadataUpdated = false;

            if (request.getStatus() != null && !request.getStatus().isBlank()) {
                Order.OrderStatus newStatus;
                try {
                    newStatus = Order.OrderStatus.valueOf(request.getStatus().trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return Response.status(Status.BAD_REQUEST)
                            .entity(ProblemDetailsUtil.badRequest("Invalid order status: " + request.getStatus()))
                            .build();
                }
                orderService.updateOrderStatus(orderId, newStatus);
            }

            // Reload to include updates performed by service layer
            order = Order.findById(orderId);

            ObjectNode metadataNode = null;
            if (request.getNotes() != null || request.getTags() != null) {
                metadataNode = readMetadataNode(order);
            }

            if (request.getNotes() != null) {
                metadataNode.put("notes", request.getNotes());
                metadataUpdated = true;
            }

            if (request.getTags() != null) {
                metadataNode.set("tags", objectMapper.valueToTree(request.getTags()));
                metadataUpdated = true;
            }

            if (metadataUpdated) {
                order.metadata = metadataNode.toString();
                order.persist();
            }

            List<OrderLineItem> lineItems = orderService.getOrderLineItems(orderId);
            Map<String, Object> orderDetail = buildOrderDetail(order, lineItems);

            return Response.ok(orderDetail).build();

        } catch (IllegalArgumentException e) {
            LOG.warnf("Invalid order update - tenantId=%s, orderId=%s, error=%s", tenantId, orderId, e.getMessage());
            return Response.status(Status.BAD_REQUEST).entity(ProblemDetailsUtil.badRequest(e.getMessage())).build();

        } catch (IllegalStateException e) {
            LOG.warnf("Invalid order state transition - tenantId=%s, orderId=%s, error=%s", tenantId, orderId,
                    e.getMessage());
            return Response.status(Status.BAD_REQUEST).entity(ProblemDetailsUtil.badRequest(e.getMessage())).build();

        } catch (OptimisticLockException e) {
            LOG.warnf("Optimistic lock exception - tenantId=%s, orderId=%s", tenantId, orderId);
            return Response.status(Status.CONFLICT).entity(
                    ProblemDetailsUtil.conflict("Order was modified concurrently. Please refresh and try again."))
                    .build();

        } catch (RuntimeException e) {
            LOG.errorf(e, "Failed to update order - tenantId=%s, orderId=%s", tenantId, orderId);
            return Response.status(Status.INTERNAL_SERVER_ERROR)
                    .entity(ProblemDetailsUtil.internalServerError("Failed to update order. Please contact support."))
                    .build();
        }
    }

    /**
     * Cancel order (admin).
     *
     * @param orderId
     *            order UUID
     * @param request
     *            cancellation request (reason, refund flag)
     * @return updated order detail DTO
     */
    @POST
    @Path("/admin/orders/{orderId}/cancel")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    @Tag(
            name = "Admin",
            description = "Store administration operations")
    @Operation(
            summary = "Cancel order (Admin)",
            description = """
                    Cancels an order with a required reason. Optionally triggers refund if payment was captured.

                    **Authentication:** Requires admin role.
                    **State Machine:** Only certain order statuses can be cancelled (see OrderService rules).
                    **Inventory:** Cancelled orders should restore inventory (future enhancement).
                    """)
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Order cancelled successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    ref = "#/components/schemas/OrderDetail"))),
                    @APIResponse(
                            responseCode = "400",
                            description = "Invalid request (missing reason, illegal state for cancellation)",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "404",
                            description = "Order not found",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    public Response adminCancelOrder(@Parameter(
            description = "Order UUID",
            required = true) @PathParam("orderId") UUID orderId,
            @Parameter(
                    description = "Cancellation request with reason field",
                    required = true) Map<String, Object> request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /admin/orders/%s/cancel - tenantId=%s", orderId, tenantId);

        String reason = (String) request.get("reason");
        if (reason == null || reason.isBlank()) {
            return Response.status(Status.BAD_REQUEST)
                    .entity(ProblemDetailsUtil.badRequest("Cancellation reason is required")).build();
        }

        try {
            orderService.cancelOrder(orderId, reason);

            // TODO: If refund flag is true, initiate refund via payment service

            Order order = Order.findById(orderId);
            List<OrderLineItem> lineItems = orderService.getOrderLineItems(orderId);
            Map<String, Object> orderDetail = buildOrderDetail(order, lineItems);

            return Response.ok(orderDetail).build();

        } catch (IllegalArgumentException e) {
            LOG.warnf("Order not found for cancellation - tenantId=%s, orderId=%s, error=%s", tenantId, orderId,
                    e.getMessage());
            return Response.status(Status.NOT_FOUND).entity(ProblemDetailsUtil.notFound(e.getMessage())).build();

        } catch (IllegalStateException e) {
            LOG.warnf("Cannot cancel order - tenantId=%s, orderId=%s, error=%s", tenantId, orderId, e.getMessage());
            return Response.status(Status.BAD_REQUEST).entity(ProblemDetailsUtil.badRequest(e.getMessage())).build();

        } catch (RuntimeException e) {
            LOG.errorf(e, "Failed to cancel order - tenantId=%s, orderId=%s", tenantId, orderId);
            return Response.status(Status.INTERNAL_SERVER_ERROR)
                    .entity(ProblemDetailsUtil.internalServerError("Failed to cancel order. Please contact support."))
                    .build();
        }
    }

    /**
     * Refund order (admin).
     *
     * @param orderId
     *            order UUID
     * @param idempotencyKey
     *            X-Idempotency-Key header for safe retries
     * @param request
     *            refund request (amount, reason, restock flag)
     * @return refund result
     */
    @POST
    @Path("/admin/orders/{orderId}/refund")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    @Tag(
            name = "Admin",
            description = "Store administration operations")
    @Operation(
            summary = "Refund order (Admin)",
            description = """
                    Issues a refund for an order via the payment provider (Stripe). Supports partial or full refunds.

                    **Authentication:** Requires admin role.
                    **Idempotent:** Requires X-Idempotency-Key header to prevent duplicate refunds.
                    **Payment Provider:** Integrates with Stripe Refunds API.
                    **Audit Trail:** Emits domain events for compliance and reporting.
                    """)
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "201",
                    description = "Refund initiated successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    ref = "#/components/schemas/RefundResponse"))),
                    @APIResponse(
                            responseCode = "200",
                            description = "Cached refund response (duplicate request)",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON,
                                    schema = @Schema(
                                            ref = "#/components/schemas/RefundResponse"))),
                    @APIResponse(
                            responseCode = "400",
                            description = "Invalid request (missing idempotency key, invalid refund amount)",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "404",
                            description = "Order not found",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "409",
                            description = "Idempotency conflict (request still processing)",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    public Response adminRefundOrder(@Parameter(
            description = "Order UUID",
            required = true) @PathParam("orderId") UUID orderId,
            @Parameter(
                    description = "Idempotency key (UUID v4) for safe retries",
                    required = true) @HeaderParam("X-Idempotency-Key") String idempotencyKey,
            @Parameter(
                    description = "Refund request with amount, reason, and restock flag",
                    required = true) Map<String, Object> request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /admin/orders/%s/refund - tenantId=%s, idempotencyKey=%s", orderId, tenantId, idempotencyKey);

        // Validate idempotency key
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Response.status(Status.BAD_REQUEST)
                    .entity(ProblemDetailsUtil.badRequest("X-Idempotency-Key header is required")).build();
        }

        try {
            // Parse request parameters
            BigDecimal amountToRefund = null;
            if (request.get("amount") != null) {
                if (request.get("amount") instanceof Number) {
                    amountToRefund = BigDecimal.valueOf(((Number) request.get("amount")).doubleValue());
                } else if (request.get("amount") instanceof String) {
                    amountToRefund = new BigDecimal((String) request.get("amount"));
                }
            }

            String reason = (String) request.getOrDefault("reason", "requested_by_customer");
            boolean restockItems = request.containsKey("restock") && Boolean.TRUE.equals(request.get("restock"));

            // Initiate refund via service
            RefundService.RefundResult result = refundService.initiateRefund(orderId, amountToRefund, reason,
                    idempotencyKey, restockItems);

            RefundResponse refundResponse = toRefundResponse(result);
            Status status = result.fromCache() ? Status.OK : Status.CREATED;
            return Response.status(status).entity(refundResponse).build();

        } catch (villagecompute.storefront.exceptions.IdempotencyConflictException e) {
            LOG.warnf("Idempotency conflict - tenantId=%s, orderId=%s, idempotencyKey=%s", tenantId, orderId,
                    idempotencyKey);
            return Response.status(Status.CONFLICT)
                    .entity(ProblemDetailsUtil.conflict("Request with this idempotency key is still being processed"))
                    .build();

        } catch (IllegalArgumentException e) {
            LOG.warnf("Refund validation failed - tenantId=%s, orderId=%s, error=%s", tenantId, orderId,
                    e.getMessage());
            return Response.status(Status.BAD_REQUEST).entity(ProblemDetailsUtil.badRequest(e.getMessage())).build();

        } catch (EntityNotFoundException e) {
            LOG.warnf("Refund order not found - tenantId=%s, orderId=%s, error=%s", tenantId, orderId, e.getMessage());
            return Response.status(Status.NOT_FOUND).entity(ProblemDetailsUtil.notFound(e.getMessage())).build();

        } catch (IllegalStateException e) {
            LOG.warnf("Refund state error - tenantId=%s, orderId=%s, error=%s", tenantId, orderId, e.getMessage());
            return Response.status(Status.BAD_REQUEST).entity(ProblemDetailsUtil.badRequest(e.getMessage())).build();

        } catch (RuntimeException e) {
            LOG.errorf(e, "Refund processing failed - tenantId=%s, orderId=%s", tenantId, orderId);
            return Response.status(Status.INTERNAL_SERVER_ERROR)
                    .entity(ProblemDetailsUtil.internalServerError("Failed to process refund. Please contact support."))
                    .build();
        }
    }

    /**
     * Initiate return for order (admin).
     *
     * @param orderId
     *            order UUID
     * @param request
     *            return request with items and reasons
     * @return return authorization details
     */
    @POST
    @Path("/admin/orders/{orderId}/returns")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    @Tag(
            name = "Admin",
            description = "Store administration operations")
    @Operation(
            summary = "Initiate return (Admin)",
            description = """
                    Initiates a product return authorization for an order. Admin can specify which line items
                    to return along with reason codes.

                    **Authentication:** Requires admin role.
                    **Workflow:** Creates return authorization → Admin approves → Items received → Inventory restocked → Refund issued.
                    **Audit Trail:** Emits RETURN_INITIATED domain event.
                    """)
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "201",
                    description = "Return authorization created successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "400",
                            description = "Invalid request (invalid line items, quantities)",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "404",
                            description = "Order not found",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    public Response adminInitiateReturn(@Parameter(
            description = "Order UUID",
            required = true) @PathParam("orderId") UUID orderId,
            @Parameter(
                    description = "Return request with items array",
                    required = true) Map<String, Object> request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /admin/orders/%s/returns - tenantId=%s", orderId, tenantId);

        try {
            // Parse return items from request
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> itemsRaw = (List<Map<String, Object>>) request.get("items");

            if (itemsRaw == null || itemsRaw.isEmpty()) {
                return Response.status(Status.BAD_REQUEST)
                        .entity(ProblemDetailsUtil.badRequest("Return items are required")).build();
            }

            List<ReturnWorkflowItem> returnItems = itemsRaw.stream().map(item -> {
                UUID lineItemId = item.get("lineItemId") instanceof String
                        ? UUID.fromString((String) item.get("lineItemId"))
                        : UUID.fromString(item.get("lineItemId").toString());
                int quantity = item.get("quantity") instanceof Number ? ((Number) item.get("quantity")).intValue()
                        : Integer.parseInt(item.get("quantity").toString());
                String reasonCode = (String) item.getOrDefault("reasonCode", "customer_request");
                String notes = (String) item.get("notes");
                return new ReturnWorkflowItem(lineItemId, quantity, reasonCode, notes);
            }).collect(Collectors.toList());

            // TODO: Extract requesting user ID from auth context
            UUID requestedBy = UUID.randomUUID(); // Placeholder

            // Initiate return via service
            villagecompute.storefront.data.models.ReturnAuthorization authorization = returnService
                    .initiateReturn(orderId, returnItems, requestedBy);

            // Build response
            Map<String, Object> returnResponse = new HashMap<>();
            returnResponse.put("returnId", authorization.id.toString());
            returnResponse.put("orderId", authorization.order.id.toString());
            returnResponse.put("status", authorization.status.name().toLowerCase());
            returnResponse.put("restockDecision", authorization.restockDecision.name().toLowerCase());
            returnResponse.put("createdAt", authorization.createdAt.toString());

            return Response.status(Status.CREATED).entity(returnResponse).build();

        } catch (IllegalArgumentException e) {
            LOG.warnf("Return validation failed - tenantId=%s, orderId=%s, error=%s", tenantId, orderId,
                    e.getMessage());
            return Response.status(Status.BAD_REQUEST).entity(ProblemDetailsUtil.badRequest(e.getMessage())).build();

        } catch (EntityNotFoundException e) {
            LOG.warnf("Return order not found - tenantId=%s, orderId=%s, error=%s", tenantId, orderId, e.getMessage());
            return Response.status(Status.NOT_FOUND).entity(ProblemDetailsUtil.notFound(e.getMessage())).build();

        } catch (IllegalStateException e) {
            LOG.warnf("Return state error - tenantId=%s, orderId=%s, error=%s", tenantId, orderId, e.getMessage());
            return Response.status(Status.BAD_REQUEST).entity(ProblemDetailsUtil.badRequest(e.getMessage())).build();

        } catch (RuntimeException e) {
            LOG.errorf(e, "Return initiation failed - tenantId=%s, orderId=%s", tenantId, orderId);
            return Response.status(Status.INTERNAL_SERVER_ERROR).entity(
                    ProblemDetailsUtil.internalServerError("Failed to initiate return. Please contact support."))
                    .build();
        }
    }

    // ========================================
    // HELPER METHODS
    // ========================================

    /**
     * Build order detail DTO from order entity and line items.
     */
    private Map<String, Object> buildOrderDetail(Order order, List<OrderLineItem> lineItems) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("orderId", order.id.toString());
        detail.put("orderNumber", order.orderNumber);
        detail.put("status", order.status.name().toLowerCase());
        detail.put("customerEmail", order.customerEmail);

        // Parse addresses from JSON strings
        try {
            if (order.shippingAddress != null) {
                detail.put("shippingAddress", objectMapper.readValue(order.shippingAddress, Map.class));
            }
            if (order.billingAddress != null) {
                detail.put("billingAddress", objectMapper.readValue(order.billingAddress, Map.class));
            }
        } catch (JsonProcessingException e) {
            LOG.warnf(e, "Failed to parse order addresses - orderId=%s", order.id);
        }

        // Build line items
        List<Map<String, Object>> lineItemDtos = lineItems.stream().map(item -> {
            Map<String, Object> itemDto = new HashMap<>();
            itemDto.put("lineItemId", item.id.toString());
            itemDto.put("productName", item.productName);
            itemDto.put("variantName", item.variantName);
            itemDto.put("sku", item.sku);
            itemDto.put("quantity", item.quantity);
            itemDto.put("unitPrice", toMoneyDto(item.unitPrice, order.currency));
            itemDto.put("lineTotal", toMoneyDto(item.subtotal, order.currency));
            return itemDto;
        }).collect(Collectors.toList());
        detail.put("lineItems", lineItemDtos);

        // Totals
        detail.put("subtotal", toMoneyDto(order.subtotalAmount, order.currency));
        detail.put("tax", toMoneyDto(order.taxAmount, order.currency));
        detail.put("shipping", toMoneyDto(order.shippingAmount, order.currency));
        detail.put("total", toMoneyDto(order.totalAmount, order.currency));
        detail.put("currency", order.currency);

        // Payment
        if (order.paymentIntentId != null) {
            Map<String, Object> payment = new HashMap<>();
            payment.put("paymentIntentId", order.paymentIntentId);
            payment.put("status", order.status == Order.OrderStatus.PAID ? "succeeded" : "pending");
            payment.put("amount", toMoneyDto(order.totalAmount, order.currency));
            detail.put("paymentIntent", payment);
        }

        // Timestamps
        detail.put("createdAt", order.createdAt.toString());
        detail.put("updatedAt", order.updatedAt.toString());
        if (order.paidAt != null)
            detail.put("paidAt", order.paidAt.toString());
        if (order.fulfilledAt != null)
            detail.put("shippedAt", order.fulfilledAt.toString());
        if (order.cancelledAt != null)
            detail.put("cancelledAt", order.cancelledAt.toString());

        // Admin fields - extract notes from metadata JSON if present
        try {
            if (order.metadata != null) {
                Map<String, Object> metadata = objectMapper.readValue(order.metadata, Map.class);
                if (metadata.containsKey("notes")) {
                    detail.put("notes", metadata.get("notes"));
                }
                if (metadata.containsKey("tags")) {
                    detail.put("tags", metadata.get("tags"));
                }
            }
        } catch (JsonProcessingException e) {
            LOG.warnf(e, "Failed to parse order metadata - orderId=%s", order.id);
        }

        return detail;
    }

    /**
     * Build order summary DTO from order entity.
     */
    private Map<String, Object> buildOrderSummary(Order order) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("orderId", order.id.toString());
        summary.put("orderNumber", order.orderNumber);
        summary.put("status", order.status.name().toLowerCase());
        summary.put("customerEmail", order.customerEmail);
        summary.put("total", toMoneyDto(order.totalAmount, order.currency));
        summary.put("createdAt", order.createdAt.toString());
        if (order.paidAt != null)
            summary.put("paidAt", order.paidAt.toString());
        if (order.fulfilledAt != null)
            summary.put("shippedAt", order.fulfilledAt.toString());
        return summary;
    }

    /**
     * Convert BigDecimal + currency to Money DTO.
     */
    private Map<String, String> toMoneyDto(BigDecimal amount, String currency) {
        Map<String, String> money = new HashMap<>();
        money.put("amount", amount != null ? amount.toPlainString() : "0.00");
        money.put("currency", currency != null ? currency : "USD");
        return money;
    }

    private RefundResponse toRefundResponse(RefundService.RefundResult result) {
        RefundResponse response = new RefundResponse();
        response.setRefundId(result.referenceId());
        response.setPaymentIntentId(result.paymentIntentId());
        response.setProviderReference(result.providerRefundId());
        response.setAmount(new Money(result.amount(), result.currency()));
        response.setStatus(result.status());
        response.setReason(result.reason());
        response.setCreatedAt(result.createdAt());
        response.setProcessedAt(result.processedAt());
        return response;
    }

    /**
     * Build pagination metadata.
     */
    private Map<String, Object> buildPaginationMetadata(int page, int pageSize, long totalItems) {
        long totalPages = (totalItems + pageSize - 1) / pageSize;
        Map<String, Object> pagination = new HashMap<>();
        pagination.put("page", page);
        pagination.put("pageSize", pageSize);
        pagination.put("totalItems", totalItems);
        pagination.put("totalPages", totalPages);
        pagination.put("hasNext", page < totalPages);
        pagination.put("hasPrev", page > 1);
        return pagination;
    }

    private String resolveAdminSortField(String requestedField) {
        if (requestedField == null || requestedField.isBlank()) {
            return "createdAt";
        }
        String normalized = requestedField.trim().replace("-", "_").toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "order_number" :
            case "ordernumber" :
                return "orderNumber";
            case "customer_email" :
            case "customeremail" :
                return "customerEmail";
            case "status" :
                return "status";
            case "total" :
            case "total_amount" :
            case "totalamount" :
                return "totalAmount";
            case "created_at" :
            case "createdat" :
            default :
                return "createdAt";
        }
    }

    private ObjectNode readMetadataNode(Order order) {
        try {
            if (order.metadata != null) {
                return (ObjectNode) objectMapper.readTree(order.metadata);
            }
        } catch (JsonProcessingException e) {
            LOG.warnf(e, "Failed to parse order metadata for update - orderId=%s", order.id);
        }
        return objectMapper.createObjectNode();
    }
}
