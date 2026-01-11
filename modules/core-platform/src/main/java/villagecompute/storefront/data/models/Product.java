package villagecompute.storefront.data.models;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
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
 * Products are the core catalog entity containing descriptive information, pricing, and relationships to categories and
 * collections. A product must have at least one variant (SKU) to be sellable. Products support rich content (title,
 * description, SEO metadata) and flexible categorization.
 *
 * <p>
 * Status Lifecycle:
 * <ul>
 * <li>draft: Product being edited, not visible on storefront</li>
 * <li>active: Published and visible to customers</li>
 * <li>archived: Hidden from storefront but preserved for historical data</li>
 * <li>deleted: Soft-deleted, excluded from queries</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>ERD: datamodel_erd.mmd (products table)</li>
 * <li>ADR-001: Tenant scoping via tenant_id FK</li>
 * <li>Task I2.T1: Catalog domain model implementation</li>
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
            nullable = false,
            length = 500)
    public String title;

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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "visibility_window",
            columnDefinition = "jsonb")
    public String visibilityWindow; // JSON: {start_date, end_date}

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "seo",
            columnDefinition = "jsonb")
    public String seo; // JSON: {title, description, keywords}

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "category_ids",
            columnDefinition = "jsonb")
    public String categoryIds; // JSON array of category UUIDs

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "collection_ids",
            columnDefinition = "jsonb")
    public String collectionIds; // JSON array of collection UUIDs

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "custom_attributes",
            columnDefinition = "jsonb")
    public String customAttributes; // Extensible metadata

    @Column(
            name = "seo_title",
            length = 255)
    public String seoTitle;

    @Column(
            name = "seo_description",
            columnDefinition = "TEXT")
    public String seoDescription;

    @OneToMany(
            mappedBy = "product",
            fetch = FetchType.LAZY,
            cascade = CascadeType.ALL)
    public List<ProductVariant> variants = new ArrayList<>();

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
        if (title == null || title.isBlank()) {
            title = name != null ? name : "Untitled Product";
        }
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        if (title == null || title.isBlank()) {
            title = name != null ? name : "Untitled Product";
        }
        updatedAt = OffsetDateTime.now();
    }
}
