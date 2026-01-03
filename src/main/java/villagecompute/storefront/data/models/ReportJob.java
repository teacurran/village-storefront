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

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Background job entity for report generation and exports.
 *
 * <p>
 * Tracks status, parameters, and results of async report generation jobs. Once complete, provides signed URL for
 * download.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I3.T3 - Reporting Projection Service</li>
 * <li>Architecture: 04_Operational_Architecture.md (Section 3.6 - Background Jobs)</li>
 * <li>Migration: V20260106__reporting_aggregates.sql</li>
 * </ul>
 */
@Entity
@Table(
        name = "report_jobs")
public class ReportJob extends PanacheEntityBase {

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
            name = "report_type",
            length = 50,
            nullable = false)
    public String reportType;

    @Column(
            length = 20,
            nullable = false)
    public String status = "pending"; // pending, running, completed, failed

    @Column(
            name = "requested_by",
            length = 255)
    public String requestedBy;

    @Column(
            length = 5000)
    public String parameters; // JSON payload

    @Column(
            name = "result_url",
            length = 2048)
    public String resultUrl;

    @Column(
            name = "error_message",
            length = 5000)
    public String errorMessage;

    @Column(
            name = "started_at")
    public OffsetDateTime startedAt;

    @Column(
            name = "completed_at")
    public OffsetDateTime completedAt;

    @Column(
            name = "created_at",
            nullable = false)
    public OffsetDateTime createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public OffsetDateTime updatedAt;
}
