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
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * CartItem entity representing a line item within a shopping cart.
 *
 * <p>
 * Each cart item references a product variant and stores the quantity plus a snapshot of the unit price at the time the
 * item was added. This price snapshot prevents cart total changes when product prices are updated.
 *
 * <p>
 * Optimistic locking via {@link #version} ensures concurrent updates to the same cart item are handled safely. The
 * metadata field can store item-level customizations, gift messages, or promotional attributes.
 *
 * <p>
 * References:
 * <ul>
 * <li>ERD: datamodel_erd.puml (cart_items table)</li>
 * <li>ADR-001: Multi-tenant data isolation via tenant_id</li>
 * <li>Task I2.T4: Cart line items with price snapshots and optimistic locking</li>
 * </ul>
 */
@Entity
@Table(
        name = "cart_items")
public class CartItem extends PanacheEntityBase {

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
            name = "cart_id",
            nullable = false)
    public Cart cart;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false)
    @JoinColumn(
            name = "variant_id",
            nullable = false)
    public ProductVariant variant;

    @Column(
            nullable = false)
    public Integer quantity = 1;

    @Column(
            name = "unit_price",
            nullable = false,
            precision = 19,
            scale = 4)
    public BigDecimal unitPrice; // Price snapshot at add-to-cart time

    @JdbcTypeCode(SqlTypes.JSON)
    public String metadata; // Item-level customizations, gift messages, etc.

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
}
