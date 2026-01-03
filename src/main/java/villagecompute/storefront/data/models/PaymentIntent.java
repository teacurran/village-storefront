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
 * Payment intent tracking entity for the payment lifecycle. Stores provider-agnostic payment state and links to
 * provider-specific identifiers.
 *
 * Supports multi-step checkout flows (authorize, then capture) and audit trails.
 */
@Entity
@Table(
        name = "payment_intents",
        indexes = {@Index(
                name = "idx_payment_provider_id",
                columnList = "provider_payment_id"),
                @Index(
                        name = "idx_payment_tenant_id",
                        columnList = "tenant_id"),
                @Index(
                        name = "idx_payment_order_id",
                        columnList = "order_id")})
public class PaymentIntent extends PanacheEntityBase {

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

    @Column(
            name = "provider",
            nullable = false,
            length = 50)
    public String provider; // "stripe", "paypal", etc.

    @Column(
            name = "provider_payment_id",
            nullable = false,
            unique = true,
            length = 255)
    public String providerPaymentId; // Provider's payment intent ID

    @Column(
            name = "order_id")
    public Long orderId; // Link to Order entity when available

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
    public PaymentStatus status;

    @Column(
            name = "capture_method",
            nullable = false,
            length = 20)
    @Enumerated(EnumType.STRING)
    public CaptureMethod captureMethod;

    @Column(
            name = "amount_captured",
            precision = 19,
            scale = 4)
    public BigDecimal amountCaptured;

    @Column(
            name = "amount_refunded",
            precision = 19,
            scale = 4)
    public BigDecimal amountRefunded;

    @Column(
            name = "client_secret",
            length = 500)
    public String clientSecret; // For client-side confirmation

    @Column(
            name = "payment_method_id",
            length = 255)
    public String paymentMethodId;

    @Column(
            name = "customer_id",
            length = 255)
    public String customerId;

    @Column(
            name = "idempotency_key",
            length = 255)
    public String idempotencyKey;

    @Column(
            name = "metadata",
            columnDefinition = "TEXT")
    public String metadata; // JSON metadata

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
     * Payment lifecycle status.
     */
    public enum PaymentStatus {
        PENDING, REQUIRES_ACTION, AUTHORIZED, CAPTURED, CANCELLED, FAILED, DISPUTED
    }

    /**
     * Capture method enumeration.
     */
    public enum CaptureMethod {
        AUTOMATIC, // Authorize and capture immediately
        MANUAL // Authorize only, capture later
    }

    /**
     * Find payment intent by provider payment ID.
     *
     * @param providerPaymentId
     *            Provider's payment identifier
     * @return PaymentIntent or null
     */
    public static PaymentIntent findByProviderPaymentId(UUID tenantId, String providerPaymentId) {
        return find("providerPaymentId = ?1 and tenant.id = ?2", providerPaymentId, tenantId).firstResult();
    }

    /**
     * Find payment intents by order ID scoped to tenant.
     *
     * @param orderId
     *            Order identifier
     * @param tenantId
     *            Tenant identifier
     * @return List of payment intents
     */
    public static List<PaymentIntent> findByOrderId(UUID tenantId, Long orderId) {
        return list("orderId = ?1 and tenant.id = ?2", orderId, tenantId);
    }

    /**
     * Find payment intent by id ensuring tenant scoping.
     *
     * @param tenantId
     *            Tenant identifier
     * @param paymentIntentId
     *            Local payment intent id
     * @return PaymentIntent or null
     */
    public static PaymentIntent findByIdAndTenant(UUID tenantId, Long paymentIntentId) {
        return find("id = ?1 and tenant.id = ?2", paymentIntentId, tenantId).firstResult();
    }
}
