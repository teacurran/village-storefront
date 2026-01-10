package villagecompute.storefront.data.models;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Watermark checkpoint for idempotent domain event processing.
 *
 * <p>
 * Tracks the last successfully processed event timestamp for each aggregation processor. This enables idempotent
 * processing by allowing processors to resume from the last checkpoint after crashes or restarts.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T2: Reporting & Retention pipeline</li>
 * <li>Architecture: Watermark pattern for stream processing</li>
 * <li>Migration: V20260118__reporting_checkpoints_loyalty_aggregates.sql</li>
 * </ul>
 */
@Entity
@Table(
        name = "reporting_checkpoints")
public class ReportingCheckpoint extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    /**
     * Unique name of the event processor (e.g., "sales_aggregator", "inventory_aggregator").
     */
    @Column(
            name = "processor_name",
            nullable = false,
            unique = true,
            length = 100)
    public String processorName;

    /**
     * Timestamp of the last successfully processed event (watermark).
     */
    @Column(
            name = "last_processed_at",
            nullable = false)
    public OffsetDateTime lastProcessedAt;

    /**
     * Total count of events processed by this processor.
     */
    @Column(
            name = "events_processed",
            nullable = false)
    public Long eventsProcessed = 0L;

    /**
     * Last error message for debugging failed processing.
     */
    @Column(
            name = "last_error",
            columnDefinition = "TEXT")
    public String lastError;

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

    /**
     * Find checkpoint by processor name.
     *
     * @param processorName
     *            name of the processor
     * @return checkpoint or null if not found
     */
    public static ReportingCheckpoint findByProcessorName(String processorName) {
        return find("processorName", processorName).firstResult();
    }
}
