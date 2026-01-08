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

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Category entity for organizing products into hierarchical categories.
 *
 * <p>
 * Supports nested categories (parent-child relationships) for multi-level navigation.
 *
 * <p>
 * References:
 * <ul>
 * <li>ERD: datamodel_erd.puml (categories table)</li>
 * <li>ADR-001: Tenant scoping via tenant_id FK</li>
 * <li>OpenAPI: api/v1/openapi.yaml (catalog schemas)</li>
 * </ul>
 */
@Entity
@Table(
        name = "categories",
        uniqueConstraints = {@UniqueConstraint(
                name = "uk_categories_tenant_code",
                columnNames = {"tenant_id", "code"}),
                @UniqueConstraint(
                        name = "uk_categories_tenant_slug",
                        columnNames = {"tenant_id", "slug"})})
public class Category extends PanacheEntityBase {

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
            name = "parent_id")
    public Category parent;

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
            name = "display_order")
    public Integer displayOrder;

    @Column(
            nullable = false,
            length = 20)
    public String status = "active";

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
