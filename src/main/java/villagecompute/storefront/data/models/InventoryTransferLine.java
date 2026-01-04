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

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Line item in an inventory transfer.
 *
 * <p>
 * Represents a specific variant and quantity being transferred from source to destination location.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Multi-location inventory workflow</li>
 * <li>Architecture: Inventory transfer DTO contract (lines array)</li>
 * </ul>
 */
@Entity
@Table(
        name = "inventory_transfer_lines")
public class InventoryTransferLine extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false)
    @JoinColumn(
            name = "transfer_id",
            nullable = false)
    public InventoryTransfer transfer;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false)
    @JoinColumn(
            name = "variant_id",
            nullable = false)
    public ProductVariant variant;

    /**
     * Quantity to transfer.
     */
    @Column(
            nullable = false)
    public Integer quantity;

    /**
     * Quantity actually received (may differ from requested).
     */
    @Column(
            name = "received_quantity")
    public Integer receivedQuantity;

    /**
     * Notes specific to this line item.
     */
    @Column(
            columnDefinition = "text")
    public String notes;

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
}
