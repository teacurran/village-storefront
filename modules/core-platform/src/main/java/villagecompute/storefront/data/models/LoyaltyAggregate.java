package villagecompute.storefront.data.models;

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
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Pre-computed loyalty program metrics aggregate by period.
 *
 * <p>
 * Read-optimized denormalized table for loyalty program reporting dashboards and exports. Aggregated daily from
 * LOYALTY_EARNED and LOYALTY_REDEEMED domain events.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T2: Reporting & Retention pipeline</li>
 * <li>Architecture: docs/architecture/04_Operational_Architecture.md (Section 3.6 - Reporting Aggregates)</li>
 * <li>Migration: V20260118__reporting_checkpoints_loyalty_aggregates.sql</li>
 * </ul>
 */
@Entity
@Table(
        name = "loyalty_aggregates",
        uniqueConstraints = {@UniqueConstraint(
                name = "uq_loyalty_period",
                columnNames = {"tenant_id", "period_date"})})
public class LoyaltyAggregate extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false)
    @JoinColumn(
            name = "tenant_id",
            nullable = false)
    @OnDelete(
            action = OnDeleteAction.CASCADE)
    public Tenant tenant;

    /**
     * Date for this aggregate period (daily rollup).
     */
    @Column(
            name = "period_date",
            nullable = false)
    public LocalDate periodDate;

    /**
     * Total points earned across all customers for this period.
     */
    @Column(
            name = "points_earned",
            nullable = false)
    public Integer pointsEarned = 0;

    /**
     * Total points redeemed across all customers for this period.
     */
    @Column(
            name = "points_redeemed",
            nullable = false)
    public Integer pointsRedeemed = 0;

    /**
     * Count of customers with non-zero points balance.
     */
    @Column(
            name = "active_members",
            nullable = false)
    public Integer activeMembers = 0;

    /**
     * JSON object with counts per tier (e.g., {"Bronze": 100, "Silver": 50, "Gold": 10}).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "tier_distribution",
            columnDefinition = "jsonb")
    public String tierDistribution;

    /**
     * Timestamp of last refresh for SLA monitoring.
     */
    @Column(
            name = "data_freshness_timestamp",
            nullable = false)
    public OffsetDateTime dataFreshnessTimestamp;

    /**
     * Name of scheduled job that produced this aggregate.
     */
    @Column(
            name = "job_name",
            nullable = false)
    public String jobName;

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
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
