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

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * ProductImage entity for storing product and variant image metadata.
 *
 * <p>
 * Images are stored in object storage (S3/R2) and this entity tracks URLs, dimensions, and display order. Images can be
 * associated with either the product (general images) or specific variants.
 *
 * <p>
 * References:
 * <ul>
 * <li>ERD: datamodel_erd.puml (product_images table)</li>
 * <li>ADR-001: Tenant-scoped entities</li>
 * </ul>
 */
@Entity
@Table(
        name = "product_images")
public class ProductImage extends PanacheEntityBase {

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

    @ManyToOne(
            fetch = FetchType.LAZY)
    @JoinColumn(
            name = "variant_id")
    public ProductVariant variant;

    @Column(
            nullable = false,
            length = 500)
    public String url;

    @Column(
            nullable = false)
    public Integer position = 0;

    @Column(
            name = "alt_text",
            length = 255)
    public String altText;

    @Column
    public Integer width;

    @Column
    public Integer height;

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
