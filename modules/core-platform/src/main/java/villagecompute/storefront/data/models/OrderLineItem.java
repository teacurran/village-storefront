package villagecompute.storefront.data.models;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Order line item representing a single product/variant within an order.
 *
 * <p>
 * Line items store immutable snapshots of product details at order time (name, price) to preserve order history even if
 * products change. For consignment products, vendor attribution and commission rates are tracked.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T1: Order aggregate with line item snapshots</li>
 * <li>ADR-003: Price snapshot strategy for order integrity</li>
 * <li>ERD: order_line_items table specification</li>
 * </ul>
 */
@Entity
@Table(
        name = "order_line_items")
public class OrderLineItem extends PanacheEntityBase {

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
            fetch = FetchType.LAZY,
            optional = false)
    @JoinColumn(
            name = "order_id",
            nullable = false)
    public Order order;

    /**
     * Product ID for reference (not a foreign key to allow historical queries).
     */
    @Column(
            name = "product_id",
            nullable = false)
    public UUID productId;

    /**
     * Variant ID for reference (not a foreign key to allow historical queries).
     */
    @Column(
            name = "variant_id",
            nullable = false)
    public UUID variantId;

    /**
     * Product name snapshot at order time.
     */
    @Column(
            name = "product_name",
            nullable = false,
            length = 500)
    public String productName;

    /**
     * Variant name snapshot at order time (e.g., "Size: Large, Color: Red").
     */
    @Column(
            name = "variant_name",
            length = 500)
    public String variantName;

    /**
     * SKU snapshot at order time.
     */
    @Column(
            name = "sku",
            length = 100)
    public String sku;

    /**
     * Quantity ordered.
     */
    @Column(
            nullable = false)
    public Integer quantity;

    /**
     * Unit price at order time (price snapshot).
     */
    @Column(
            name = "unit_price",
            nullable = false,
            precision = 19,
            scale = 4)
    public BigDecimal unitPrice;

    /**
     * Line item subtotal (unit_price * quantity).
     */
    @Column(
            name = "subtotal",
            nullable = false,
            precision = 19,
            scale = 4)
    public BigDecimal subtotal;

    /**
     * Vendor ID for consignment products (nullable for store-owned inventory).
     */
    @Column(
            name = "vendor_id")
    public UUID vendorId;

    /**
     * Commission rate for consignment products (decimal 0-1, e.g., 0.15 = 15%).
     */
    @Column(
            name = "commission_rate",
            precision = 5,
            scale = 4)
    public BigDecimal commissionRate;

    /**
     * Line-item metadata (customizations, gift messages, etc.).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "metadata",
            columnDefinition = "jsonb")
    public String metadata;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false)
    public OffsetDateTime createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (tenant == null && TenantContext.hasContext()) {
            UUID tenantId = TenantContext.getCurrentTenantId();
            tenant = Tenant.findById(tenantId);
        }
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;

        // Calculate subtotal if not set
        if (subtotal == null) {
            calculateSubtotal();
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    /**
     * Calculate line item subtotal from unit price and quantity.
     */
    public void calculateSubtotal() {
        if (unitPrice != null && quantity != null) {
            subtotal = unitPrice.multiply(new BigDecimal(quantity));
        }
    }
}
