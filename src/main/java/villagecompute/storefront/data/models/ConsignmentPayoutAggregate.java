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
 * Read-optimized aggregate table for consignment payout reporting.
 *
 * <p>
 * Tracks amounts owed to consignors per period, refreshed via scheduled jobs consuming consignment domain events.
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
        name = "consignment_payout_aggregates",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"tenant_id", "consignor_id", "period_start", "period_end"}))
public class ConsignmentPayoutAggregate extends PanacheEntityBase {

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

    @ManyToOne(
            optional = false)
    @OnDelete(
            action = OnDeleteAction.CASCADE)
    @JoinColumn(
            name = "consignor_id",
            nullable = false)
    public Consignor consignor;

    @Column(
            name = "period_start",
            nullable = false)
    public LocalDate periodStart;

    @Column(
            name = "period_end",
            nullable = false)
    public LocalDate periodEnd;

    @Column(
            name = "total_owed",
            precision = 19,
            scale = 4,
            nullable = false)
    public BigDecimal totalOwed = BigDecimal.ZERO;

    @Column(
            name = "item_count",
            nullable = false)
    public int itemCount = 0;

    @Column(
            name = "items_sold",
            nullable = false)
    public int itemsSold = 0;

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
