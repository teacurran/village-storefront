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
 * Join entity linking products to collections for merchandising assignments.
 *
 * <p>
 * Provides many-to-many relationship between {@link Product} and {@link Collection} with tenant-scoped composite key.
 * Tracks display order for featured placement plus timestamps for auditing.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T2: Catalog/Inventory migrations</li>
 * <li>ADR-001: Tenant-scoped data enforcement</li>
 * </ul>
 */
@Entity
@Table(
        name = "product_collections")
public class ProductCollection extends PanacheEntityBase {

    @EmbeddedId
    public ProductCollectionId id;

    @ManyToOne(
            fetch = FetchType.LAZY)
    @MapsId("productId")
    @JoinColumn(
            name = "product_id")
    public Product product;

    @ManyToOne(
            fetch = FetchType.LAZY)
    @MapsId("collectionId")
    @JoinColumn(
            name = "collection_id")
    public Collection collection;

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
     * Composite key referencing tenant, product, and collection IDs.
     */
    @Embeddable
    public static class ProductCollectionId implements Serializable {

        private static final long serialVersionUID = 1L;

        @Column(
                name = "tenant_id",
                nullable = false)
        public UUID tenantId;

        @Column(
                name = "product_id",
                nullable = false)
        public UUID productId;

        @Column(
                name = "collection_id",
                nullable = false)
        public UUID collectionId;

        public ProductCollectionId() {
        }

        public ProductCollectionId(UUID tenantId, UUID productId, UUID collectionId) {
            this.tenantId = tenantId;
            this.productId = productId;
            this.collectionId = collectionId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof ProductCollectionId))
                return false;
            ProductCollectionId that = (ProductCollectionId) o;
            return Objects.equals(tenantId, that.tenantId) && Objects.equals(productId, that.productId)
                    && Objects.equals(collectionId, that.collectionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenantId, productId, collectionId);
        }
    }
}
