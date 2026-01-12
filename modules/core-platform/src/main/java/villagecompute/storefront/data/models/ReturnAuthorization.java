package villagecompute.storefront.data.models;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Return authorization entity tracking product return requests and restock decisions.
 *
 * <p>
 * Captures per-item return reasons, approval workflow, and refund linkage. Supports multi-tenant isolation and audit
 * trail requirements.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T9: Refund and return implementation</li>
 * <li>ERD: ReturnAuthorization table design</li>
 * <li>ADR-003: Order lifecycle and compensating transactions</li>
 * </ul>
 */
@Entity
@Table(
        name = "return_authorizations",
        indexes = {@Index(
                name = "idx_return_auth_tenant_id",
                columnList = "tenant_id"),
                @Index(
                        name = "idx_return_auth_order_id",
                        columnList = "order_id"),
                @Index(
                        name = "idx_return_auth_status",
                        columnList = "status")})
public class ReturnAuthorization extends PanacheEntityBase {

    @Id
    @GeneratedValue(
            strategy = GenerationType.IDENTITY)
    public Long id;

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
            name = "order_id",
            nullable = false)
    public Order order;

    @Column(
            name = "items",
            columnDefinition = "TEXT",
            nullable = false)
    public String items; // JSON array of return items with reason codes

    @Column(
            name = "reason_codes",
            columnDefinition = "TEXT")
    public String reasonCodes; // JSON array of reason codes

    @Column(
            name = "status",
            nullable = false,
            length = 50)
    @Enumerated(EnumType.STRING)
    public ReturnStatus status;

    @Column(
            name = "approved_by")
    public UUID approvedBy; // User ID who approved the return

    @Column(
            name = "received_at")
    public Instant receivedAt; // When returned items physically received

    @Column(
            name = "restock_decision",
            length = 50)
    @Enumerated(EnumType.STRING)
    public RestockDecision restockDecision;

    @Column(
            name = "refund_id")
    public Long refundId; // Link to Refund entity

    @Column(
            name = "notes",
            columnDefinition = "TEXT")
    public String notes; // JSON notes and metadata

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public Instant updatedAt;

    @Version
    @Column(
            name = "version")
    public Long version;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (tenant == null && TenantContext.hasContext()) {
            tenant = Tenant.findById(TenantContext.getCurrentTenantId());
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Return authorization status lifecycle.
     */
    public enum ReturnStatus {
        REQUESTED, // Initial state
        APPROVED, // Admin approved return
        RECEIVED, // Items physically received
        RESTOCKED, // Items added back to inventory
        REJECTED, // Return request rejected
        COMPLETED // Fully processed (refund issued if applicable)
    }

    /**
     * Restock decision enum.
     */
    public enum RestockDecision {
        RESTOCK, // Items in good condition, restore inventory
        DISCARD, // Items damaged/unsellable
        PENDING_INSPECTION, // Awaiting physical inspection
        NO_RESTOCK // Return without inventory restoration
    }

    /**
     * Find return authorizations by order ID.
     *
     * @param tenantId
     *            Tenant identifier
     * @param orderId
     *            Order identifier
     * @return List of return authorizations
     */
    public static List<ReturnAuthorization> findByOrderId(UUID tenantId, UUID orderId) {
        return list("order.id = ?1 and tenant.id = ?2", orderId, tenantId);
    }

    /**
     * Find return authorizations by status.
     *
     * @param tenantId
     *            Tenant identifier
     * @param status
     *            Return status
     * @return List of return authorizations
     */
    public static List<ReturnAuthorization> findByStatus(UUID tenantId, ReturnStatus status) {
        return list("status = ?1 and tenant.id = ?2", status, tenantId);
    }

    /**
     * Find return authorization by ID and tenant.
     *
     * @param tenantId
     *            Tenant identifier
     * @param id
     *            Return authorization ID
     * @return Return authorization or null
     */
    public static ReturnAuthorization findByIdAndTenant(UUID tenantId, Long id) {
        return find("id = ?1 and tenant.id = ?2", id, tenantId).firstResult();
    }
}
