package villagecompute.storefront.data.models;

import java.math.BigDecimal;
import java.time.LocalDate;
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

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * CommissionSchedule entity defining commission rates for consignors by category or product type.
 *
 * <p>
 * Allows flexible commission structures:
 * <ul>
 * <li>Consignor-wide default rates</li>
 * <li>Category-specific rates</li>
 * <li>Time-bound promotional rates</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T1: Commission schedule storage</li>
 * <li>Clarification 4: Commission structures</li>
 * </ul>
 */
@Entity
@Table(
        name = "commission_schedules")
public class CommissionSchedule extends PanacheEntityBase {

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

    /**
     * Category ID if this schedule is category-specific, null for default.
     */
    @Column(
            name = "category_id")
    public UUID categoryId;

    /**
     * Commission rate as percentage (e.g., 15.00 for 15%).
     */
    @Column(
            name = "commission_rate",
            nullable = false,
            precision = 5,
            scale = 2)
    public BigDecimal commissionRate;

    /**
     * Effective date for this schedule (inclusive).
     */
    @Column(
            name = "effective_from",
            nullable = false)
    public LocalDate effectiveFrom;

    /**
     * Expiration date for this schedule (inclusive), null for no expiration.
     */
    @Column(
            name = "effective_until")
    public LocalDate effectiveUntil;

    /**
     * Priority for conflict resolution (higher priority wins).
     */
    @Column(
            nullable = false)
    public Integer priority = 0;

    /**
     * Optional notes for audit purposes.
     */
    @Column(
            length = 500)
    public String notes;

    /**
     * Additional metadata stored as JSONB.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            columnDefinition = "jsonb")
    public String metadata;

    @Column(
            nullable = false,
            length = 20)
    public String status = "active"; // active|expired|superseded

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

    @Column(
            name = "created_by",
            length = 255)
    public String createdBy;

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
