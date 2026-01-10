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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.platformops.data.models.PlatformCommand;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Privacy request entity tracking export and delete requests for compliance workflows.
 *
 * <p>
 * Captures initial request, approval workflow state, and links to platform audit commands. Supports GDPR/CCPA data
 * export and deletion (right to erasure) requirements.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T6: Compliance automation</li>
 * <li>Architecture: 01_Blueprint_Foundation.md Section 5 (Data Governance)</li>
 * <li>Architecture: 04_Operational_Architecture.md Section 3.15 (Compliance personas)</li>
 * </ul>
 */
@Entity
@Table(
        name = "privacy_requests")
public class PrivacyRequest extends PanacheEntityBase {

    public static final String QUERY_FIND_BY_TENANT = "PrivacyRequest.findByTenant";
    public static final String QUERY_FIND_BY_STATUS = "PrivacyRequest.findByStatus";
    public static final String QUERY_FIND_PENDING_APPROVAL = "PrivacyRequest.findPendingApproval";

    public enum RequestType {
        EXPORT, DELETE
    }

    public enum RequestStatus {
        PENDING_REVIEW, APPROVED, REJECTED, IN_PROGRESS, AWAITING_PURGE, COMPLETED, FAILED
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

    @Enumerated(EnumType.STRING)
    @Column(
            name = "request_type",
            nullable = false,
            length = 50)
    public RequestType requestType;

    @Enumerated(EnumType.STRING)
    @Column(
            nullable = false,
            length = 50)
    public RequestStatus status;

    @Column(
            name = "requester_email",
            nullable = false,
            length = 255)
    public String requesterEmail;

    @Column(
            name = "subject_email",
            length = 255)
    public String subjectEmail; // Email of the data subject (if different from requester)

    @Column(
            name = "subject_identifier_hash",
            length = 64)
    public String subjectIdentifierHash; // SHA-256 hash of customer ID or other identifier

    @Column(
            columnDefinition = "TEXT")
    public String reason;

    @Column(
            name = "ticket_number",
            length = 100)
    public String ticketNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            columnDefinition = "jsonb")
    public String parameters; // Request-specific parameters (date ranges, data categories, etc.)

    @Column(
            name = "approval_notes",
            columnDefinition = "TEXT")
    public String approvalNotes;

    @Column(
            name = "approved_by_email",
            length = 255)
    public String approvedByEmail;

    @Column(
            name = "approved_at")
    public OffsetDateTime approvedAt;

    @Column(
            name = "completed_at")
    public OffsetDateTime completedAt;

    @Column(
            name = "result_url",
            columnDefinition = "TEXT")
    public String resultUrl; // Signed URL for export downloads

    @Column(
            name = "error_message",
            columnDefinition = "TEXT")
    public String errorMessage;

    @ManyToOne
    @JoinColumn(
            name = "platform_command_id")
    public PlatformCommand platformCommand; // Links to audit trail

    @Column(
            name = "created_at",
            nullable = false)
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
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = RequestStatus.PENDING_REVIEW;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
