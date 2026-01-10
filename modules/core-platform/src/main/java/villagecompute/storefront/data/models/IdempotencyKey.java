package villagecompute.storefront.data.models;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Idempotency key entity for preventing duplicate request processing.
 *
 * <p>
 * Implements idempotency semantics per ADR-003 checkout saga requirements. Each key tracks the processing state and
 * stores the result for duplicate requests. Keys expire after 24 hours.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-003: Checkout saga decision (Section 3: Idempotency keys)</li>
 * <li>Task I3.T1: Cart + Checkout services with idempotency support</li>
 * <li>Architecture: Preventing duplicate checkout submissions</li>
 * </ul>
 */
@Entity
@Table(
        name = "idempotency_keys",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_idempotency_keys_tenant_key",
                columnNames = {"tenant_id", "idempotency_key"}))
public class IdempotencyKey extends PanacheEntityBase {

    /**
     * Client-provided idempotency key (UUID format recommended).
     */
    @Id
    @Column(
            name = "idempotency_key",
            nullable = false,
            length = 255)
    public String idempotencyKey;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false)
    @JoinColumn(
            name = "tenant_id",
            nullable = false)
    public Tenant tenant;

    /**
     * Current processing status of this idempotent operation.
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 20)
    public Status status = Status.PENDING;

    /**
     * Operation type identifier (e.g., "checkout", "payment", "refund").
     */
    @Column(
            name = "operation_type",
            nullable = false,
            length = 50)
    public String operationType;

    /**
     * Cached result from successful operation (JSON-encoded).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "result",
            columnDefinition = "jsonb")
    public String result;

    /**
     * Error details if operation failed (JSON-encoded).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "error",
            columnDefinition = "jsonb")
    public String error;

    /**
     * HTTP status code returned to client.
     */
    @Column(
            name = "response_code")
    public Integer responseCode;

    /**
     * Timestamp when this key was created.
     */
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false)
    public OffsetDateTime createdAt;

    /**
     * Timestamp when this key expires (24 hours from creation).
     */
    @Column(
            name = "expires_at",
            nullable = false)
    public OffsetDateTime expiresAt;

    /**
     * Timestamp when processing completed (success or failure).
     */
    @Column(
            name = "completed_at")
    public OffsetDateTime completedAt;

    @PrePersist
    public void prePersist() {
        if (tenant == null && TenantContext.hasContext()) {
            UUID tenantId = TenantContext.getCurrentTenantId();
            tenant = Tenant.findById(tenantId);
        }
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;

        // Default expiry to 24 hours from creation
        if (expiresAt == null) {
            expiresAt = now.plusHours(24);
        }
    }

    /**
     * Check if this idempotency key is expired.
     *
     * @return true if current time is past expiration
     */
    public boolean isExpired() {
        return OffsetDateTime.now().isAfter(expiresAt);
    }

    /**
     * Check if operation is still in progress.
     *
     * @return true if status is PENDING
     */
    public boolean isPending() {
        return status == Status.PENDING;
    }

    /**
     * Check if operation completed successfully.
     *
     * @return true if status is SUCCESS
     */
    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    /**
     * Check if operation failed.
     *
     * @return true if status is FAILED
     */
    public boolean isFailed() {
        return status == Status.FAILED;
    }

    /**
     * Idempotency key processing status.
     */
    public enum Status {
        PENDING, // Operation in progress
        SUCCESS, // Operation completed successfully
        FAILED // Operation failed
    }
}
