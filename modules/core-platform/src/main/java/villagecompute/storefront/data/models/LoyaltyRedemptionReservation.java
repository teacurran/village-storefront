package villagecompute.storefront.data.models;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
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
 * LoyaltyRedemptionReservation entity representing a temporary hold on loyalty points during checkout.
 *
 * <p>
 * Prevents double-spending of loyalty points by reserving them while a cart is in checkout. Reservations expire
 * automatically after a configurable TTL (default 15 minutes). Uses optimistic locking via @Version to prevent
 * concurrent redemption conflicts.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T3: Loyalty redemption reservations with optimistic locking</li>
 * <li>ADR-001: Multi-tenant data isolation via tenant_id</li>
 * <li>ADR-003: Idempotent redemption operations</li>
 * <li>Architecture: Section 3.6 - LoyaltyLedgerEntry data model</li>
 * </ul>
 */
@Entity
@Table(
        name = "loyalty_redemption_reservations",
        indexes = {@Index(
                name = "idx_loyalty_reservations_member",
                columnList = "member_id"),
                @Index(
                        name = "idx_loyalty_reservations_cart",
                        columnList = "cart_id"),
                @Index(
                        name = "idx_loyalty_reservations_expires",
                        columnList = "expires_at")})
public class LoyaltyRedemptionReservation extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Version
    @Column(
            nullable = false)
    public Integer version;

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
            name = "member_id",
            nullable = false)
    public LoyaltyMember member;

    /**
     * Associated cart ID for this reservation.
     */
    @Column(
            name = "cart_id",
            nullable = false)
    public UUID cartId;

    /**
     * Points reserved for redemption.
     */
    @Column(
            name = "points_reserved",
            nullable = false)
    public Integer pointsReserved;

    /**
     * Reservation status: active, consumed, expired, released.
     */
    @Column(
            nullable = false,
            length = 20)
    public String status = "active";

    /**
     * Expiration timestamp for this reservation (typically 15 minutes from creation).
     */
    @Column(
            name = "expires_at",
            nullable = false)
    public OffsetDateTime expiresAt;

    /**
     * Idempotency key for reservation operations (prevents duplicate reservations).
     */
    @Column(
            name = "idempotency_key",
            length = 255)
    public String idempotencyKey;

    /**
     * Order ID if reservation was consumed during checkout.
     */
    @Column(
            name = "order_id")
    public UUID orderId;

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
        if (tenant == null && TenantContext.hasContext()) {
            UUID tenantId = TenantContext.getCurrentTenantId();
            tenant = Tenant.findById(tenantId);
        }
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (expiresAt == null) {
            // Default 15-minute expiration
            expiresAt = now.plusMinutes(15);
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
