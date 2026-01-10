package villagecompute.storefront.data.models;

import java.math.BigDecimal;
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
 * Refund entity tracking payment refund lifecycle. Links to PaymentIntent and stores provider-specific refund details.
 *
 * Supports full and partial refunds with reason codes and status tracking.
 */
@Entity
@Table(
        name = "refunds",
        indexes = {@Index(
                name = "idx_refund_provider_id",
                columnList = "provider_refund_id"),
                @Index(
                        name = "idx_refund_tenant_id",
                        columnList = "tenant_id"),
                @Index(
                        name = "idx_refund_payment_intent_id",
                        columnList = "payment_intent_id"),
                @Index(
                        name = "idx_refund_order_id",
                        columnList = "order_id")})
public class Refund extends PanacheEntityBase {

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
            name = "payment_intent_id",
            nullable = false)
    public PaymentIntent paymentIntent;

    @Column(
            name = "provider",
            nullable = false,
            length = 50)
    public String provider; // "stripe", "paypal", etc.

    @Column(
            name = "provider_refund_id",
            nullable = false,
            unique = true,
            length = 255)
    public String providerRefundId; // Provider's refund ID

    @Column(
            name = "order_id")
    public UUID orderId; // Link to Order aggregate

    @Column(
            name = "amount",
            nullable = false,
            precision = 19,
            scale = 4)
    public BigDecimal amount;

    @Column(
            name = "currency",
            nullable = false,
            length = 3)
    public String currency;

    @Column(
            name = "status",
            nullable = false,
            length = 50)
    @Enumerated(EnumType.STRING)
    public RefundStatus status;

    @Column(
            name = "reason",
            length = 100)
    public String reason; // "duplicate", "fraudulent", "requested_by_customer"

    @Column(
            name = "idempotency_key",
            length = 255)
    public String idempotencyKey;

    @Column(
            name = "metadata",
            columnDefinition = "TEXT")
    public String metadata; // JSON metadata

    @Column(
            name = "failure_reason",
            length = 500)
    public String failureReason; // Reason for failure if status = FAILED

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
     * Refund lifecycle status.
     */
    public enum RefundStatus {
        PENDING, SUCCEEDED, FAILED, CANCELLED
    }

    /**
     * Find refund by provider refund ID.
     *
     * @param tenantId
     *            Tenant identifier
     * @param providerRefundId
     *            Provider's refund identifier
     * @return Refund or null
     */
    public static Refund findByProviderRefundId(UUID tenantId, String providerRefundId) {
        return find("providerRefundId = ?1 and tenant.id = ?2", providerRefundId, tenantId).firstResult();
    }

    /**
     * Find refunds by payment intent.
     *
     * @param tenantId
     *            Tenant identifier
     * @param paymentIntentId
     *            Payment intent ID
     * @return List of refunds
     */
    public static List<Refund> findByPaymentIntent(UUID tenantId, Long paymentIntentId) {
        return list("paymentIntent.id = ?1 and tenant.id = ?2", paymentIntentId, tenantId);
    }

    /**
     * Find refunds by order ID.
     *
     * @param tenantId
     *            Tenant identifier
     * @param orderId
     *            Order identifier
     * @return List of refunds
     */
    public static List<Refund> findByOrderId(UUID tenantId, UUID orderId) {
        return list("orderId = ?1 and tenant.id = ?2", orderId, tenantId);
    }
}
