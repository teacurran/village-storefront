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
 * ConsignmentRetentionRecord captures retention hooks (archive/purge schedules) for consignment resources.
 *
 * <p>
 * The records allow compliance jobs to archive or purge intake batches, commission documents, and payouts after the
 * mandated retention period.
 * </p>
 */
@Entity
@Table(
        name = "consignment_retention_hooks")
public class ConsignmentRetentionRecord extends PanacheEntityBase {

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
            name = "resource_type",
            nullable = false,
            length = 50)
    public String resourceType;

    @Column(
            name = "resource_id",
            nullable = false)
    public UUID resourceId;

    @Column(
            name = "retain_until",
            nullable = false)
    public OffsetDateTime retainUntil;

    @Column(
            nullable = false,
            length = 20)
    public String status = "scheduled"; // scheduled|archived|purged

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
            tenant = Tenant.findById(TenantContext.getCurrentTenantId());
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
