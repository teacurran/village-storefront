package villagecompute.storefront.data.models;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Inventory transfer between locations.
 *
 * <p>
 * Represents the movement of inventory from a source location to a destination location. Transfers track status,
 * initiation details, and can trigger background jobs for barcode label generation.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Multi-location inventory workflow</li>
 * <li>Architecture: Inventory transfer DTO contract</li>
 * <li>Behavior: Multi-location communication patterns</li>
 * </ul>
 */
@Entity
@Table(
        name = "inventory_transfers")
public class InventoryTransfer extends PanacheEntityBase {

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
            name = "source_location_id",
            nullable = false)
    public InventoryLocation sourceLocation;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false)
    @JoinColumn(
            name = "destination_location_id",
            nullable = false)
    public InventoryLocation destinationLocation;

    @Enumerated(EnumType.STRING)
    @Column(
            nullable = false,
            length = 20)
    public TransferStatus status = TransferStatus.PENDING;

    /**
     * User or system that initiated the transfer.
     */
    @Column(
            name = "initiated_by",
            length = 255)
    public String initiatedBy;

    /**
     * Expected arrival date at destination.
     */
    @Column(
            name = "expected_arrival_date")
    public OffsetDateTime expectedArrivalDate;

    /**
     * Shipping carrier name.
     */
    @Column(
            length = 100)
    public String carrier;

    /**
     * Tracking number from carrier.
     */
    @Column(
            name = "tracking_number",
            length = 255)
    public String trackingNumber;

    /**
     * Shipping cost in cents.
     */
    @Column(
            name = "shipping_cost")
    public Integer shippingCost;

    /**
     * Background job ID for barcode label generation.
     */
    @Column(
            name = "barcode_job_id")
    public UUID barcodeJobId;

    /**
     * Notes or special instructions.
     */
    @Column(
            columnDefinition = "text")
    public String notes;

    @OneToMany(
            mappedBy = "transfer",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    public List<InventoryTransferLine> lines = new ArrayList<>();

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

    /**
     * Add a line item to this transfer.
     *
     * @param line
     *            transfer line to add
     */
    public void addLine(InventoryTransferLine line) {
        lines.add(line);
        line.transfer = this;
    }
}
