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
 * Collection entity for grouping products together (e.g., "Summer Sale", "New Arrivals").
 *
 * <p>
 * Collections are curator-defined product groupings that can be used for merchandising, promotions, and storefront
 * navigation. Unlike categories (which are hierarchical), collections are flat and products can belong to multiple
 * collections.
 *
 * <p>
 * Collections support manual product assignment or automatic rules based on product attributes (stored in
 * selectionRules JSON field).
 *
 * <p>
 * References:
 * <ul>
 * <li>ERD: datamodel_erd.puml (collections table)</li>
 * <li>ADR-001: Tenant scoping via tenant_id FK</li>
 * <li>OpenAPI: api/v1/openapi.yaml (catalog schemas)</li>
 * </ul>
 */
@Entity
@Table(
        name = "collections",
        uniqueConstraints = {@UniqueConstraint(
                name = "uk_collections_tenant_code",
                columnNames = {"tenant_id", "code"}),
                @UniqueConstraint(
                        name = "uk_collections_tenant_slug",
                        columnNames = {"tenant_id", "slug"})})
public class Collection extends PanacheEntityBase {

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
            length = 50)
    public String code;

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
            name = "image_url",
            length = 500)
    public String imageUrl;

    @Column(
            name = "display_order")
    public Integer displayOrder = 0;

    @Column(
            nullable = false,
            length = 20)
    public String collectionType = "manual"; // manual|automatic

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "selection_rules",
            columnDefinition = "jsonb")
    public String selectionRules; // JSON rules for automatic collections

    @Column(
            nullable = false)
    public Boolean published = false;

    @Column(
            name = "published_at")
    public OffsetDateTime publishedAt;

    @Column(
            nullable = false,
            length = 20)
    public String status = "draft"; // draft|active|archived|deleted

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
