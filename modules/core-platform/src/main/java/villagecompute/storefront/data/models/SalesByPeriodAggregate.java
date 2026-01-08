package villagecompute.storefront.data.models;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Read-optimized aggregate table for sales reporting by time period.
 *
 * <p>
 * Projection refreshed via scheduled jobs consuming cart/order domain events. Provides pre-computed totals for
 * dashboards and exports.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I3.T3 - Reporting Projection Service</li>
 * <li>Architecture: 02_System_Structure_and_Data.md (Module-to-Data Stewardship)</li>
 * <li>Migration: V20260106__reporting_aggregates.sql</li>
 * </ul>
 */
@Entity
@Table(
        name = "sales_by_period_aggregates",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"tenant_id", "period_start", "period_end"}))
public class SalesByPeriodAggregate extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @ManyToOne(
            optional = false)
    @OnDelete(
            action = OnDeleteAction.CASCADE)
    @JoinColumn(
            name = "tenant_id",
            nullable = false)
    public Tenant tenant;

    @Column(
            name = "period_start",
            nullable = false)
    public LocalDate periodStart;

    @Column(
            name = "period_end",
            nullable = false)
    public LocalDate periodEnd;

    @Column(
            name = "total_amount",
            precision = 19,
            scale = 4,
            nullable = false)
    public BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(
            name = "item_count",
            nullable = false)
    public int itemCount = 0;

    @Column(
            name = "order_count",
            nullable = false)
    public int orderCount = 0;

    @Column(
            name = "data_freshness_timestamp",
            nullable = false)
    public OffsetDateTime dataFreshnessTimestamp;

    @Column(
            name = "job_name",
            length = 255,
            nullable = false)
    public String jobName;

    @Column(
            name = "created_at",
            nullable = false)
    public OffsetDateTime createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public OffsetDateTime updatedAt;
}
