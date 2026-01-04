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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * ProductVariant entity representing a specific variation of a product (e.g., size, color).
 *
 * <p>
 * Each variant has its own SKU, pricing, inventory tracking, and attribute values. The attributes field stores
 * variant-specific options as JSONB (e.g., {"color": "Red", "size": "Large"}).
 *
 * <p>
 * References:
 * <ul>
 * <li>ERD: datamodel_erd.puml (product_variants table)</li>
 * <li>ADR-001: Tenant-scoped entities</li>
 * <li>OpenAPI: ProductVariant schema</li>
 * </ul>
 */
@Entity
@Table(
        name = "product_variants",
        uniqueConstraints = {@UniqueConstraint(
                name = "uk_product_variants_tenant_sku",
                columnNames = {"tenant_id", "sku"})})
public class ProductVariant extends PanacheEntityBase {

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
            name = "product_id",
            nullable = false)
    public Product product;

    @Column(
            nullable = false,
            length = 100)
    public String sku;

    @Column(
            nullable = false,
            length = 255)
    public String name;

    @JdbcTypeCode(SqlTypes.JSON)
    public String attributes; // JSON object: {"color": "Red", "size": "Large"}

    @Column(
            nullable = false,
            precision = 19,
            scale = 4)
    public BigDecimal price;

    @Column(
            name = "compare_at_price",
            precision = 19,
            scale = 4)
    public BigDecimal compareAtPrice;

    @Column(
            precision = 19,
            scale = 4)
    public BigDecimal cost;

    @Column(
            precision = 10,
            scale = 2)
    public BigDecimal weight;

    @Column(
            name = "weight_unit",
            length = 10)
    public String weightUnit; // kg, lb, oz, g

    @Column(
            length = 100)
    public String barcode;

    @Column(
            name = "requires_shipping",
            nullable = false)
    public Boolean requiresShipping = true;

    @Column(
            nullable = false)
    public Boolean taxable = true;

    @Column(
            nullable = false)
    public Integer position = 0;

    @Column(
            nullable = false,
            length = 20)
    public String status = "active"; // active|archived|deleted

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
