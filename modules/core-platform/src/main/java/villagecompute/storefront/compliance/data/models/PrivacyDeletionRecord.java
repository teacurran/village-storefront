package villagecompute.storefront.compliance.data.models;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import villagecompute.storefront.data.models.Tenant;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Tracks deletion phases for privacy requests so purge jobs can be scheduled after the retention window.
 *
 * <p>
 * Retention metadata is stored outside {@link PrivacyRequest} to avoid schema churn on the primary request table and to
 * make it easy to query pending purges without tenant-specific filters.
 */
@Entity
@Table(
        name = "privacy_deletions")
public class PrivacyDeletionRecord extends PanacheEntityBase {

    public enum DeletionStatus {
        SOFT_DELETED, PURGE_QUEUED, PURGED
    }

    @Id
    @GeneratedValue
    public UUID id;

    @ManyToOne(
            optional = false)
    @JoinColumn(
            name = "tenant_id",
            nullable = false)
    public Tenant tenant;

    @ManyToOne(
            optional = false)
    @JoinColumn(
            name = "privacy_request_id",
            nullable = false,
            unique = true)
    public PrivacyRequest privacyRequest;

    @Column(
            name = "subject_identifier_hash",
            length = 64,
            nullable = false)
    public String subjectIdentifierHash;

    @Column(
            name = "soft_deleted_at")
    public OffsetDateTime softDeletedAt;

    @Column(
            name = "purge_after",
            nullable = false)
    public OffsetDateTime purgeAfter;

    @Column(
            name = "purge_job_id")
    public UUID purgeJobId;

    @Column(
            name = "purge_job_enqueued_at")
    public OffsetDateTime purgeJobEnqueuedAt;

    @Column(
            name = "purged_at")
    public OffsetDateTime purgedAt;

    @Enumerated(EnumType.STRING)
    @Column(
            nullable = false,
            length = 50)
    public DeletionStatus status = DeletionStatus.SOFT_DELETED;
}
