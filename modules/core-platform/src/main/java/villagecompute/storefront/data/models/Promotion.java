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
 * Promotion entity representing discount codes and promotional campaigns.
 *
 * <p>
 * Supports percentage discounts, fixed amount discounts, and free shipping. Promotions can have minimum order
 * requirements, usage limits, and expiration dates. All promotions are tenant-scoped.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T1: Cart + Checkout services with promotion validation</li>
 * <li>ADR-001: Multi-tenant data isolation via tenant_id</li>
 * </ul>
 */
@Entity
@Table(
        name = "promotions")
public class Promotion extends PanacheEntityBase {

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

    /**
     * Promotion code entered by customers (e.g., "SAVE20", "FREESHIP").
     */
    @Column(
            name = "code",
            nullable = false,
            length = 50)
    public String code;

    /**
     * Human-readable description of the promotion.
     */
    @Column(
            name = "description",
            length = 500)
    public String description;

    /**
     * Type of discount applied by this promotion.
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "discount_type",
            nullable = false,
            length = 20)
    public DiscountType discountType;

    /**
     * Discount value (percentage as decimal 0-100, or fixed amount in cents).
     */
    @Column(
            name = "discount_value",
            nullable = false,
            precision = 19,
            scale = 4)
    public BigDecimal discountValue;

    /**
     * Minimum order subtotal required to apply this promotion (in cents).
     */
    @Column(
            name = "minimum_order_amount",
            precision = 19,
            scale = 4)
    public BigDecimal minimumOrderAmount;

    /**
     * Maximum number of times this promotion can be used across all customers.
     */
    @Column(
            name = "max_uses")
    public Integer maxUses;

    /**
     * Current number of times this promotion has been used.
     */
    @Column(
            name = "current_uses",
            nullable = false)
    public Integer currentUses = 0;

    /**
     * Promotion becomes active at this timestamp.
     */
    @Column(
            name = "starts_at",
            nullable = false)
    public OffsetDateTime startsAt;

    /**
     * Promotion expires at this timestamp.
     */
    @Column(
            name = "expires_at")
    public OffsetDateTime expiresAt;

    /**
     * Whether this promotion is currently active.
     */
    @Column(
            name = "is_active",
            nullable = false)
    public Boolean isActive = true;

    /**
     * Additional configuration as JSON (product restrictions, category filters, etc.).
     */
    @JdbcTypeCode(SqlTypes.JSON)
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
     * Check if this promotion is currently valid.
     *
     * @return true if active and within valid date range
     */
    public boolean isValid() {
        if (!isActive) {
            return false;
        }

        OffsetDateTime now = OffsetDateTime.now();

        if (startsAt != null && now.isBefore(startsAt)) {
            return false;
        }

        if (expiresAt != null && now.isAfter(expiresAt)) {
            return false;
        }

        if (maxUses != null && currentUses >= maxUses) {
            return false;
        }

        return true;
    }

    /**
     * Check if this promotion can be applied to an order with the given subtotal.
     *
     * @param subtotal
     *            order subtotal in cents
     * @return true if minimum order requirement is met
     */
    public boolean meetsMinimumOrder(BigDecimal subtotal) {
        return minimumOrderAmount == null || subtotal.compareTo(minimumOrderAmount) >= 0;
    }

    /**
     * Calculate discount amount for the given subtotal.
     *
     * @param subtotal
     *            order subtotal
     * @return discount amount
     */
    public BigDecimal calculateDiscount(BigDecimal subtotal) {
        switch (discountType) {
            case PERCENTAGE :
                return subtotal.multiply(discountValue).divide(new BigDecimal("100"));
            case FIXED_AMOUNT :
                return discountValue.min(subtotal); // Don't discount more than subtotal
            case FREE_SHIPPING :
                return BigDecimal.ZERO; // Shipping discount handled separately
            default :
                return BigDecimal.ZERO;
        }
    }

    /**
     * Discount type enumeration.
     */
    public enum DiscountType {
        PERCENTAGE, // Percentage off subtotal (0-100)
        FIXED_AMOUNT, // Fixed amount off in cents
        FREE_SHIPPING // Free shipping (no subtotal discount)
    }
}
