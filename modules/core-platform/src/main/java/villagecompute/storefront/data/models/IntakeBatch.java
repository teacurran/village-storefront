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
import jakarta.persistence.Version;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * IntakeBatch entity representing a batch of consignment items received from a vendor.
 *
 * <p>
 * Tracks the intake workflow for groups of consignment items, allowing bulk processing and audit trails.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T1: Batch intake implementation</li>
 * <li>Clarification 4: Batch intake workflows</li>
 * </ul>
 */
@Entity
@Table(
        name = "intake_batches")
public class IntakeBatch extends PanacheEntityBase {

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
            name = "consignor_id",
            nullable = false)
    public Consignor consignor;

    @Column(
            name = "batch_name",
            nullable = false,
            length = 255)
    public String batchName;

    @Column(
            name = "notes",
            length = 2000)
    public String notes;

    @Column(
            nullable = false,
            length = 20)
    public String status = "pending"; // pending|completed|cancelled

    @Column(
            name = "total_items",
            nullable = false)
    public Integer totalItems = 0;

    @Column(
            name = "processed_items",
            nullable = false)
    public Integer processedItems = 0;

    @Column(
            name = "completed_at")
    public OffsetDateTime completedAt;

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
