package villagecompute.storefront.data.models;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Audit record for manual inventory adjustments.
 *
 * <p>
 * Tracks all non-transfer inventory changes (cycle counts, damages, returns, etc.) with reason codes for reporting and
 * compliance.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Multi-location inventory workflow (adjustments)</li>
 * <li>Observability: Adjustment reason code logging</li>
 * </ul>
 */
@Entity
@Table(
        name = "inventory_adjustments")
public class InventoryAdjustment extends PanacheEntityBase {

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
            name = "variant_id",
            nullable = false)
    public ProductVariant variant;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false)
    @JoinColumn(
            name = "location_id",
            nullable = false)
    public InventoryLocation location;

    /**
     * Quantity change (positive or negative).
     */
    @Column(
            nullable = false)
    public Integer quantityChange;

    /**
     * Quantity before adjustment.
     */
    @Column(
            name = "quantity_before",
            nullable = false)
    public Integer quantityBefore;

    /**
     * Quantity after adjustment.
     */
    @Column(
            name = "quantity_after",
            nullable = false)
    public Integer quantityAfter;

    @Enumerated(EnumType.STRING)
    @Column(
            nullable = false,
            length = 50)
    public AdjustmentReason reason;

    /**
     * User or system that performed the adjustment.
     */
    @Column(
            name = "adjusted_by",
            nullable = false,
            length = 255)
    public String adjustedBy;

    /**
     * Additional notes or explanation.
     */
    @Column(
            columnDefinition = "text")
    public String notes;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (tenant == null && TenantContext.hasContext()) {
            UUID tenantId = TenantContext.getCurrentTenantId();
            tenant = Tenant.findById(tenantId);
        }
        createdAt = OffsetDateTime.now();
    }
}
