package villagecompute.storefront.data.models;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Order aggregate root representing a customer order with payment and fulfillment tracking.
 *
 * <p>
 * Orders transition through a defined state machine from PENDING_PAYMENT through fulfillment stages to terminal states
 * (DELIVERED, CANCELLED, REFUNDED). Line items are stored separately in {@link OrderLineItem}.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T1: Order aggregate with status/state machine</li>
 * <li>ADR-003: Checkout saga (order creation stage)</li>
 * <li>ERD: orders table specification</li>
 * </ul>
 */
@Entity
@Table(
        name = "orders")
public class Order extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false)
    @JoinColumn(
            name = "tenant_id",
            nullable = false)
    public Tenant tenant;

    @ManyToOne(
            fetch = FetchType.LAZY)
    @JoinColumn(
            name = "user_id")
    public User user; // Nullable for guest checkout

    /**
     * Human-readable order number (e.g., "ORD-20261108-1234").
     */
    @Column(
            name = "order_number",
            nullable = false,
            length = 50)
    public String orderNumber;

    /**
     * Current order status in the fulfillment lifecycle.
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 30)
    public OrderStatus status = OrderStatus.PENDING_PAYMENT;

    /**
     * Customer email for order notifications.
     */
    @Column(
            name = "customer_email",
            nullable = false,
            length = 255)
    public String customerEmail;

    /**
     * Shipping address (JSON structure: {line1, line2, city, state, zip, country}).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "shipping_address",
            nullable = false,
            columnDefinition = "jsonb")
    public String shippingAddress;

    /**
     * Billing address (JSON structure: {line1, line2, city, state, zip, country}).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "billing_address",
            nullable = false,
            columnDefinition = "jsonb")
    public String billingAddress;

    /**
     * Subtotal before discounts and tax (sum of line item subtotals).
     */
    @Column(
            name = "subtotal_amount",
            nullable = false,
            precision = 19,
            scale = 4)
    public BigDecimal subtotalAmount = BigDecimal.ZERO;

    /**
     * Total discount amount applied (from promotions).
     */
    @Column(
            name = "discount_amount",
            nullable = false,
            precision = 19,
            scale = 4)
    public BigDecimal discountAmount = BigDecimal.ZERO;

    /**
     * Shipping cost.
     */
    @Column(
            name = "shipping_amount",
            nullable = false,
            precision = 19,
            scale = 4)
    public BigDecimal shippingAmount = BigDecimal.ZERO;

    /**
     * Tax amount calculated.
     */
    @Column(
            name = "tax_amount",
            nullable = false,
            precision = 19,
            scale = 4)
    public BigDecimal taxAmount = BigDecimal.ZERO;

    /**
     * Final total amount charged to customer.
     */
    @Column(
            name = "total_amount",
            nullable = false,
            precision = 19,
            scale = 4)
    public BigDecimal totalAmount = BigDecimal.ZERO;

    /**
     * Currency code (ISO 4217, e.g., "USD").
     */
    @Column(
            name = "currency",
            nullable = false,
            length = 3)
    public String currency = "USD";

    /**
     * Promotion code applied to this order.
     */
    @Column(
            name = "promotion_code",
            length = 50)
    public String promotionCode;

    /**
     * Stripe Payment Intent ID for this order.
     */
    @Column(
            name = "payment_intent_id",
            length = 255)
    public String paymentIntentId;

    /**
     * Additional order metadata (selected shipping method, saga checkpoints, etc.).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "metadata",
            columnDefinition = "jsonb")
    public String metadata;

    @Version
    @Column(
            name = "version")
    public Long version;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false)
    public OffsetDateTime createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public OffsetDateTime updatedAt;

    /**
     * Timestamp when payment was captured.
     */
    @Column(
            name = "paid_at")
    public OffsetDateTime paidAt;

    /**
     * Timestamp when order was fulfilled/shipped.
     */
    @Column(
            name = "fulfilled_at")
    public OffsetDateTime fulfilledAt;

    /**
     * Timestamp when order was cancelled.
     */
    @Column(
            name = "cancelled_at")
    public OffsetDateTime cancelledAt;

    @PrePersist
    public void prePersist() {
        if (tenant == null && TenantContext.hasContext()) {
            UUID tenantId = TenantContext.getCurrentTenantId();
            tenant = Tenant.findById(tenantId);
        }
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    /**
     * Calculate total amount from components.
     */
    public void calculateTotal() {
        totalAmount = subtotalAmount.subtract(discountAmount).add(shippingAmount).add(taxAmount);
    }

    /**
     * Order status enumeration representing fulfillment lifecycle.
     */
    public enum OrderStatus {
        PENDING_PAYMENT, // Order created, awaiting payment
        PAID, // Payment captured successfully
        PROCESSING, // Order being prepared for fulfillment
        SHIPPED, // Order shipped to customer
        DELIVERED, // Order delivered (terminal state)
        CANCELLED, // Order cancelled before fulfillment (terminal state)
        REFUNDED // Order refunded after payment (terminal state)
    }
}
