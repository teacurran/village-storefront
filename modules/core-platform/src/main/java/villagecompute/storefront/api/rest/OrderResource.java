package villagecompute.storefront.api.rest;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
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

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import villagecompute.storefront.api.types.UpdateOrderRequest;
import villagecompute.storefront.data.models.Order;
import villagecompute.storefront.data.models.OrderLineItem;
import villagecompute.storefront.services.OrderService;
import villagecompute.storefront.tenant.TenantContext;

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
public class OrderResource {

    private static final Logger LOG = Logger.getLogger(OrderResource.class);

    @Inject
    OrderService orderService;

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
    public Response getOrder(@PathParam("orderId") UUID orderId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /orders/%s - tenantId=%s", orderId, tenantId);

        try {
            Order order = Order.findById(orderId);
            if (order == null || !order.tenant.id.equals(tenantId)) {
                return Response.status(Status.NOT_FOUND)
                        .entity(createProblemDetails("Not Found", "Order not found", Status.NOT_FOUND)).build();
            }

            // TODO: Add authorization check - customers can only see their own orders

            List<OrderLineItem> lineItems = orderService.getOrderLineItems(orderId);
            Map<String, Object> orderDetail = buildOrderDetail(order, lineItems);

            return Response.ok(orderDetail).build();

        } catch (RuntimeException e) {
            LOG.errorf(e, "Failed to retrieve order - tenantId=%s, orderId=%s", tenantId, orderId);
            return Response.status(Status.INTERNAL_SERVER_ERROR).entity(createProblemDetails("Internal Server Error",
                    "Failed to retrieve order. Please contact support.", Status.INTERNAL_SERVER_ERROR)).build();
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
    public Response listOrders(@QueryParam("page") Integer page, @QueryParam("pageSize") Integer pageSize,
            @QueryParam("status") String status) {
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
            return Response.status(Status.INTERNAL_SERVER_ERROR)
                    .entity(createProblemDetails("Internal Server Error",
                            "Failed to retrieve orders. Please contact support.", Status.INTERNAL_SERVER_ERROR))
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
    public Response adminListOrders(@QueryParam("page") Integer page, @QueryParam("pageSize") Integer pageSize,
            @QueryParam("status") String status, @QueryParam("search") String search,
            @QueryParam("sortBy") String sortBy, @QueryParam("sortOrder") String sortOrder) {
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
                    return Response.status(Status.BAD_REQUEST).entity(
                            createProblemDetails("Bad Request", "Invalid status filter: " + status, Status.BAD_REQUEST))
                            .build();
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
            return Response.status(Status.INTERNAL_SERVER_ERROR)
                    .entity(createProblemDetails("Internal Server Error",
                            "Failed to retrieve orders. Please contact support.", Status.INTERNAL_SERVER_ERROR))
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
    public Response adminGetOrder(@PathParam("orderId") UUID orderId) {
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
    public Response adminUpdateOrder(@PathParam("orderId") UUID orderId, @Valid UpdateOrderRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("PATCH /admin/orders/%s - tenantId=%s", orderId, tenantId);

        try {
            Order order = Order.findById(orderId);
            if (order == null || !order.tenant.id.equals(tenantId)) {
                return Response.status(Status.NOT_FOUND)
                        .entity(createProblemDetails("Not Found", "Order not found", Status.NOT_FOUND)).build();
            }

            boolean metadataUpdated = false;

            if (request.getStatus() != null && !request.getStatus().isBlank()) {
                Order.OrderStatus newStatus;
                try {
                    newStatus = Order.OrderStatus.valueOf(request.getStatus().trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return Response.status(Status.BAD_REQUEST).entity(createProblemDetails("Bad Request",
                            "Invalid order status: " + request.getStatus(), Status.BAD_REQUEST)).build();
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
            return Response.status(Status.BAD_REQUEST)
                    .entity(createProblemDetails("Bad Request", e.getMessage(), Status.BAD_REQUEST)).build();

        } catch (IllegalStateException e) {
            LOG.warnf("Invalid order state transition - tenantId=%s, orderId=%s, error=%s", tenantId, orderId,
                    e.getMessage());
            return Response.status(Status.BAD_REQUEST)
                    .entity(createProblemDetails("Bad Request", e.getMessage(), Status.BAD_REQUEST)).build();

        } catch (OptimisticLockException e) {
            LOG.warnf("Optimistic lock exception - tenantId=%s, orderId=%s", tenantId, orderId);
            return Response.status(Status.CONFLICT).entity(createProblemDetails("Conflict",
                    "Order was modified concurrently. Please refresh and try again.", Status.CONFLICT)).build();

        } catch (RuntimeException e) {
            LOG.errorf(e, "Failed to update order - tenantId=%s, orderId=%s", tenantId, orderId);
            return Response.status(Status.INTERNAL_SERVER_ERROR).entity(createProblemDetails("Internal Server Error",
                    "Failed to update order. Please contact support.", Status.INTERNAL_SERVER_ERROR)).build();
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
    public Response adminCancelOrder(@PathParam("orderId") UUID orderId, Map<String, Object> request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /admin/orders/%s/cancel - tenantId=%s", orderId, tenantId);

        String reason = (String) request.get("reason");
        if (reason == null || reason.isBlank()) {
            return Response.status(Status.BAD_REQUEST)
                    .entity(createProblemDetails("Bad Request", "Cancellation reason is required", Status.BAD_REQUEST))
                    .build();
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
            return Response.status(Status.NOT_FOUND)
                    .entity(createProblemDetails("Not Found", e.getMessage(), Status.NOT_FOUND)).build();

        } catch (IllegalStateException e) {
            LOG.warnf("Cannot cancel order - tenantId=%s, orderId=%s, error=%s", tenantId, orderId, e.getMessage());
            return Response.status(Status.BAD_REQUEST)
                    .entity(createProblemDetails("Bad Request", e.getMessage(), Status.BAD_REQUEST)).build();

        } catch (RuntimeException e) {
            LOG.errorf(e, "Failed to cancel order - tenantId=%s, orderId=%s", tenantId, orderId);
            return Response.status(Status.INTERNAL_SERVER_ERROR).entity(createProblemDetails("Internal Server Error",
                    "Failed to cancel order. Please contact support.", Status.INTERNAL_SERVER_ERROR)).build();
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
    public Response adminRefundOrder(@PathParam("orderId") UUID orderId,
            @HeaderParam("X-Idempotency-Key") String idempotencyKey, Map<String, Object> request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /admin/orders/%s/refund - tenantId=%s, idempotencyKey=%s", orderId, tenantId, idempotencyKey);

        // Validate idempotency key
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Response.status(Status.BAD_REQUEST).entity(
                    createProblemDetails("Bad Request", "X-Idempotency-Key header is required", Status.BAD_REQUEST))
                    .build();
        }

        // TODO: Implement refund logic via payment service
        // This is a placeholder implementation

        Map<String, Object> refundResponse = new HashMap<>();
        refundResponse.put("refundId", UUID.randomUUID().toString());
        refundResponse.put("amount", request.get("amount"));
        refundResponse.put("status", "pending");
        refundResponse.put("createdAt", java.time.OffsetDateTime.now().toString());

        return Response.status(Status.CREATED).entity(refundResponse).build();
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

    /**
     * Create RFC 7807 Problem Details error response.
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
