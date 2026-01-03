package villagecompute.storefront.data.models;

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
 * Read-optimized aggregate table for inventory aging analysis.
 *
 * <p>
 * Tracks how long inventory items have been in stock, refreshed via scheduled jobs consuming inventory domain events.
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
        name = "inventory_aging_aggregates",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"tenant_id", "variant_id", "location_id"}))
public class InventoryAgingAggregate extends PanacheEntityBase {

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
            name = "variant_id",
            nullable = false)
    public ProductVariant variant;

    @ManyToOne(
            optional = false)
    @OnDelete(
            action = OnDeleteAction.CASCADE)
    @JoinColumn(
            name = "location_id",
            nullable = false)
    public InventoryLocation location;

    @Column(
            name = "days_in_stock",
            nullable = false)
    public int daysInStock = 0;

    @Column(
            nullable = false)
    public int quantity = 0;

    @Column(
            name = "first_received_at")
    public OffsetDateTime firstReceivedAt;

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
