package villagecompute.storefront.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import villagecompute.storefront.data.models.Cart;
import villagecompute.storefront.data.models.CartItem;
import villagecompute.storefront.data.models.DomainEvent;
import villagecompute.storefront.data.models.Order;
import villagecompute.storefront.data.models.OrderLineItem;
import villagecompute.storefront.data.repositories.ConsignmentItemRepository;
import villagecompute.storefront.tenant.TenantContext;

/**
 * Service for managing order lifecycle, state transitions, and fulfillment.
 *
 * <p>
 * Handles order creation from carts, state transitions through the fulfillment pipeline, and domain event publishing
 * for downstream consumers. All operations are tenant-scoped with structured logging and observability.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T1: Order service with state machine and event publishing</li>
 * <li>ADR-003: Checkout saga (order creation and finalization stages)</li>
 * <li>Architecture: Order lifecycle management</li>
 * </ul>
 */
@ApplicationScoped
public class OrderService {

    private static final Logger LOG = Logger.getLogger(OrderService.class);
    private static final DateTimeFormatter ORDER_NUMBER_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Inject
    ObjectMapper objectMapper;

    @Inject
    CartService cartService;

    @Inject
    ConsignmentItemRepository consignmentItemRepository;

    @Inject
    ConsignmentService consignmentService;

    /**
     * Create order from cart (initial checkout stage).
     *
     * @param cart
     *            cart to convert to order
     * @param customerEmail
     *            customer email for notifications
     * @param shippingAddress
     *            shipping address (JSON string)
     * @param billingAddress
     *            billing address (JSON string)
     * @param shippingAmount
     *            calculated shipping cost
     * @param taxAmount
     *            calculated tax amount
     * @param currency
     *            ISO currency code (defaults to USD)
     * @return created order in PENDING_PAYMENT status
     */
    @Transactional
    public Order createOrderFromCart(Cart cart, String customerEmail, String shippingAddress, String billingAddress,
            BigDecimal shippingAmount, BigDecimal taxAmount, String currency) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Creating order from cart - tenantId=%s, cartId=%s, customerEmail=%s", tenantId, cart.id,
                customerEmail);

        // Verify cart belongs to tenant
        if (!cart.tenant.id.equals(tenantId)) {
            throw new IllegalArgumentException("Cart does not belong to current tenant");
        }

        // Get cart items
        List<CartItem> cartItems = cartService.getCartItems(cart.id);
        if (cartItems.isEmpty()) {
            throw new IllegalStateException("Cannot create order from empty cart");
        }

        // Create order
        Order order = new Order();
        order.tenant = cart.tenant;
        order.user = cart.user;
        order.orderNumber = generateOrderNumber();
        order.status = Order.OrderStatus.PENDING_PAYMENT;
        order.customerEmail = customerEmail;
        order.shippingAddress = shippingAddress;
        order.billingAddress = billingAddress;
        order.currency = currency != null ? currency : "USD";

        // Calculate totals
        BigDecimal subtotal = BigDecimal.ZERO;
        for (CartItem item : cartItems) {
            BigDecimal itemSubtotal = item.unitPrice.multiply(new BigDecimal(item.quantity));
            subtotal = subtotal.add(itemSubtotal);
        }
        order.subtotalAmount = subtotal;

        // Apply promotion if present
        BigDecimal discountAmount = BigDecimal.ZERO;
        try {
            if (cart.metadata != null) {
                JsonNode metadata = objectMapper.readTree(cart.metadata);
                if (metadata.has("promotionCode")) {
                    order.promotionCode = metadata.get("promotionCode").asText();
                    if (metadata.has("discountAmount")) {
                        discountAmount = new BigDecimal(metadata.get("discountAmount").asText());
                    }
                }
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to parse promotion from cart metadata - cartId=%s", cart.id);
        }
        order.discountAmount = discountAmount;

        // Shipping and tax will be calculated in checkout saga
        order.shippingAmount = shippingAmount != null ? shippingAmount : BigDecimal.ZERO;
        order.taxAmount = taxAmount != null ? taxAmount : BigDecimal.ZERO;
        order.calculateTotal();

        order.persist();

        // Create order line items
        for (CartItem cartItem : cartItems) {
            OrderLineItem lineItem = new OrderLineItem();
            lineItem.tenant = order.tenant;
            lineItem.order = order;
            lineItem.productId = cartItem.variant.product.id;
            lineItem.variantId = cartItem.variant.id;
            lineItem.productName = cartItem.variant.product.name;
            lineItem.variantName = cartItem.variant.name;
            lineItem.sku = cartItem.variant.sku;
            lineItem.quantity = cartItem.quantity;
            lineItem.unitPrice = cartItem.unitPrice;
            lineItem.metadata = cartItem.metadata;
            consignmentItemRepository.findActiveItemByProduct(cartItem.variant.product.id).ifPresent(item -> {
                lineItem.vendorId = item.consignor.id;
                lineItem.commissionRate = item.commissionRate.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
            });
            lineItem.calculateSubtotal();
            lineItem.persist();

            LOG.debugf("Created order line item - orderId=%s, lineItemId=%s, variantId=%s, quantity=%d", order.id,
                    lineItem.id, lineItem.variantId, lineItem.quantity);
        }

        LOG.infof("Created order from cart - tenantId=%s, orderId=%s, orderNumber=%s, total=%s", tenantId, order.id,
                order.orderNumber, order.totalAmount);

        publishOrderEvent("OrderInitiated", order);

        return order;
    }

    /**
     * Mark order as paid after successful payment processing.
     *
     * @param orderId
     *            order UUID
     * @param paymentIntentId
     *            Stripe Payment Intent ID
     */
    @Transactional
    public void markOrderPaid(UUID orderId, String paymentIntentId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Marking order as paid - tenantId=%s, orderId=%s, paymentIntentId=%s", tenantId, orderId,
                paymentIntentId);

        Order order = Order.findById(orderId);
        if (order == null || !order.tenant.id.equals(tenantId)) {
            throw new IllegalArgumentException("Order not found: " + orderId);
        }

        if (order.status != Order.OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException("Order cannot be marked as paid - current status: " + order.status);
        }

        order.status = Order.OrderStatus.PAID;
        order.paymentIntentId = paymentIntentId;
        order.paidAt = OffsetDateTime.now();
        order.persist();

        LOG.infof("Order marked as paid - tenantId=%s, orderId=%s, orderNumber=%s", tenantId, orderId,
                order.orderNumber);

        publishOrderEvent("OrderPaid", order);
        consignmentService.handleOrderPaid(order);
    }

    /**
     * Update order status.
     *
     * @param orderId
     *            order UUID
     * @param newStatus
     *            new order status
     */
    @Transactional
    public void updateOrderStatus(UUID orderId, Order.OrderStatus newStatus) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Updating order status - tenantId=%s, orderId=%s, newStatus=%s", tenantId, orderId, newStatus);

        Order order = Order.findById(orderId);
        if (order == null || !order.tenant.id.equals(tenantId)) {
            throw new IllegalArgumentException("Order not found: " + orderId);
        }

        Order.OrderStatus oldStatus = order.status;
        order.status = newStatus;

        // Update timestamps based on status
        switch (newStatus) {
            case PAID :
                if (order.paidAt == null) {
                    order.paidAt = OffsetDateTime.now();
                }
                break;
            case SHIPPED :
            case DELIVERED :
                if (order.fulfilledAt == null) {
                    order.fulfilledAt = OffsetDateTime.now();
                }
                break;
            case CANCELLED :
                if (order.cancelledAt == null) {
                    order.cancelledAt = OffsetDateTime.now();
                }
                break;
            default :
                break;
        }

        order.persist();

        LOG.infof("Order status updated - tenantId=%s, orderId=%s, oldStatus=%s, newStatus=%s", tenantId, orderId,
                oldStatus, newStatus);

        publishOrderStatusChangedEvent(order, oldStatus, newStatus);
    }

    /**
     * Cancel order before fulfillment.
     *
     * @param orderId
     *            order UUID
     * @param reason
     *            cancellation reason
     */
    @Transactional
    public void cancelOrder(UUID orderId, String reason) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Cancelling order - tenantId=%s, orderId=%s, reason=%s", tenantId, orderId, reason);

        Order order = Order.findById(orderId);
        if (order == null || !order.tenant.id.equals(tenantId)) {
            throw new IllegalArgumentException("Order not found: " + orderId);
        }

        if (order.status == Order.OrderStatus.SHIPPED || order.status == Order.OrderStatus.DELIVERED) {
            throw new IllegalStateException("Cannot cancel order that has been shipped or delivered");
        }

        if (order.status == Order.OrderStatus.CANCELLED || order.status == Order.OrderStatus.REFUNDED) {
            throw new IllegalStateException("Order is already cancelled or refunded");
        }

        Order.OrderStatus oldStatus = order.status;
        order.status = Order.OrderStatus.CANCELLED;
        order.cancelledAt = OffsetDateTime.now();

        // Store cancellation reason in metadata
        try {
            ObjectNode metadata = order.metadata != null ? (ObjectNode) objectMapper.readTree(order.metadata)
                    : objectMapper.createObjectNode();
            metadata.put("cancellationReason", reason);
            metadata.put("cancelledAt", order.cancelledAt.toString());
            order.metadata = objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to store cancellation reason in metadata - orderId=%s", orderId);
        }

        order.persist();

        LOG.infof("Order cancelled - tenantId=%s, orderId=%s, orderNumber=%s", tenantId, orderId, order.orderNumber);

        publishOrderCancelledEvent(order, oldStatus, reason);
    }

    /**
     * Get order line items.
     *
     * @param orderId
     *            order UUID
     * @return list of order line items
     */
    public List<OrderLineItem> getOrderLineItems(UUID orderId) {
        UUID tenantId = TenantContext.getCurrentTenantId();

        Order order = Order.findById(orderId);
        if (order == null || !order.tenant.id.equals(tenantId)) {
            throw new IllegalArgumentException("Order not found: " + orderId);
        }

        return OrderLineItem.find("order.id = ?1", orderId).list();
    }

    /**
     * Generate unique order number.
     *
     * @return order number (format: ORD-YYYYMMDD-NNNN)
     */
    private String generateOrderNumber() {
        String datePart = OffsetDateTime.now().format(ORDER_NUMBER_FORMAT);
        UUID tenantId = TenantContext.getCurrentTenantId();

        // Count orders today for this tenant
        OffsetDateTime startOfDay = OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        long todayCount = Order.count("tenant.id = ?1 AND createdAt >= ?2", tenantId, startOfDay);

        String sequencePart = String.format("%04d", todayCount + 1);
        return "ORD-" + datePart + "-" + sequencePart;
    }

    /**
     * Publish order domain event.
     */
    private void publishOrderEvent(String eventType, Order order) {
        try {
            DomainEvent event = new DomainEvent();
            event.tenant = order.tenant;
            event.aggregateType = "ORDER";
            event.aggregateId = order.id;
            event.eventType = eventType;

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("orderId", order.id.toString());
            payload.put("orderNumber", order.orderNumber);
            payload.put("status", order.status.name());
            payload.put("userId", order.user != null ? order.user.id.toString() : null);
            payload.put("customerEmail", order.customerEmail);
            payload.put("subtotalAmount", order.subtotalAmount.toString());
            payload.put("discountAmount", order.discountAmount.toString());
            payload.put("shippingAmount", order.shippingAmount.toString());
            payload.put("taxAmount", order.taxAmount.toString());
            payload.put("totalAmount", order.totalAmount.toString());

            event.payload = objectMapper.writeValueAsString(payload);
            event.persist();

            LOG.debugf("Published order event - eventType=%s, orderId=%s", eventType, order.id);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to publish order event - eventType=%s, orderId=%s", eventType, order.id);
        }
    }

    /**
     * Publish order status changed event.
     */
    private void publishOrderStatusChangedEvent(Order order, Order.OrderStatus oldStatus, Order.OrderStatus newStatus) {
        try {
            DomainEvent event = new DomainEvent();
            event.tenant = order.tenant;
            event.aggregateType = "ORDER";
            event.aggregateId = order.id;
            event.eventType = "OrderStatusChanged";

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("orderId", order.id.toString());
            payload.put("orderNumber", order.orderNumber);
            payload.put("oldStatus", oldStatus.name());
            payload.put("newStatus", newStatus.name());

            event.payload = objectMapper.writeValueAsString(payload);
            event.persist();

            LOG.debugf("Published order status changed event - orderId=%s, %s -> %s", order.id, oldStatus, newStatus);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to publish order status changed event - orderId=%s", order.id);
        }
    }

    /**
     * Publish order cancelled event.
     */
    private void publishOrderCancelledEvent(Order order, Order.OrderStatus oldStatus, String reason) {
        try {
            DomainEvent event = new DomainEvent();
            event.tenant = order.tenant;
            event.aggregateType = "ORDER";
            event.aggregateId = order.id;
            event.eventType = "OrderCancelled";

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("orderId", order.id.toString());
            payload.put("orderNumber", order.orderNumber);
            payload.put("oldStatus", oldStatus.name());
            payload.put("reason", reason);

            event.payload = objectMapper.writeValueAsString(payload);
            event.persist();

            LOG.debugf("Published order cancelled event - orderId=%s, reason=%s", order.id, reason);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to publish order cancelled event - orderId=%s", order.id);
        }
    }
}
