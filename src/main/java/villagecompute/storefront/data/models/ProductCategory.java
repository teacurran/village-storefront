package villagecompute.storefront.data.models;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * ProductCategory join entity for many-to-many relationship between products and categories.
 *
 * <p>
 * A product can belong to multiple categories, and categories can contain multiple products. This entity also tracks
 * display order for featured products within a category.
 *
 * <p>
 * References:
 * <ul>
 * <li>ERD: datamodel_erd.puml (product_categories table)</li>
 * <li>ADR-001: Tenant scoping via composite key</li>
 * </ul>
 */
@Entity
@Table(
        name = "product_categories")
public class ProductCategory extends PanacheEntityBase {

    @EmbeddedId
    public ProductCategoryId id;

    @ManyToOne(
            fetch = FetchType.LAZY)
    @MapsId("productId")
    @JoinColumn(
            name = "product_id")
    public Product product;

    @ManyToOne(
            fetch = FetchType.LAZY)
    @MapsId("categoryId")
    @JoinColumn(
            name = "category_id")
    public Category category;

    @Column(
            name = "display_order")
    public Integer displayOrder;

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
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    /**
     * Composite primary key for ProductCategory (tenant_id, product_id, category_id).
     */
    @Embeddable
    public static class ProductCategoryId implements Serializable {

        private static final long serialVersionUID = 1L;

        @Column(
                name = "tenant_id")
        public UUID tenantId;

        @Column(
                name = "product_id")
        public UUID productId;

        @Column(
                name = "category_id")
        public UUID categoryId;

        public ProductCategoryId() {
        }

        public ProductCategoryId(UUID tenantId, UUID productId, UUID categoryId) {
            this.tenantId = tenantId;
            this.productId = productId;
            this.categoryId = categoryId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof ProductCategoryId))
                return false;
            ProductCategoryId that = (ProductCategoryId) o;
            return Objects.equals(tenantId, that.tenantId) && Objects.equals(productId, that.productId)
                    && Objects.equals(categoryId, that.categoryId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenantId, productId, categoryId);
        }
    }
}
