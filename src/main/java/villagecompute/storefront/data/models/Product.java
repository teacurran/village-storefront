package villagecompute.storefront.data.models;

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
 * Product entity representing a sellable item in the catalog.
 *
 * <p>
 * Products can have multiple variants (sizes, colors, etc.) represented by {@link ProductVariant}. A product must have
 * at least one variant to be purchasable.
 *
 * <p>
 * References:
 * <ul>
 * <li>ERD: datamodel_erd.puml (products table)</li>
 * <li>ADR-001: Multi-tenant data isolation via tenant_id</li>
 * <li>OpenAPI: ProductSummary, ProductDetail schemas</li>
 * </ul>
 */
@Entity
@Table(
        name = "products",
        uniqueConstraints = {@UniqueConstraint(
                name = "uk_products_tenant_sku",
                columnNames = {"tenant_id", "sku"}),
                @UniqueConstraint(
                        name = "uk_products_tenant_slug",
                        columnNames = {"tenant_id", "slug"})})
public class Product extends PanacheEntityBase {

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

    @Column(
            nullable = false,
            length = 100)
    public String sku;

    @Column(
            nullable = false,
            length = 255)
    public String name;

    @Column(
            length = 255)
    public String slug;

    @Column(
            columnDefinition = "TEXT")
    public String description;

    @Column(
            nullable = false,
            length = 20)
    public String type = "physical"; // physical|digital|service

    @Column(
            nullable = false,
            length = 20)
    public String status = "draft"; // draft|active|archived|deleted

    @JdbcTypeCode(SqlTypes.JSON)
    public String metadata;

    @Column(
            name = "seo_title",
            length = 255)
    public String seoTitle;

    @Column(
            name = "seo_description",
            columnDefinition = "TEXT")
    public String seoDescription;

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
