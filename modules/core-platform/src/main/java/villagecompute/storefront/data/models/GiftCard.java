package villagecompute.storefront.data.models;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.panache.common.Parameters;

/**
 * GiftCard entity representing gift cards issued by tenants.
 *
 * <p>
 * Gift cards can be purchased, issued for promotional purposes, or given as customer service remediation. Each card has
 * a secure code that is hashed for lookups to prevent enumeration attacks. Supports partial redemption and expiration.
 *
 * <p>
 * References:
 * <ul>
 * <li>ERD: datamodel_erd.puml (gift_cards table)</li>
 * <li>ADR-001: Multi-tenant data isolation via tenant_id</li>
 * <li>Task I4.T6: Gift card issuance and redemption with checkout/POS integration</li>
 * </ul>
 */
@Entity
@Table(
        name = "gift_cards")
public class GiftCard extends PanacheEntityBase {

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
    @OnDelete(
            action = OnDeleteAction.CASCADE)
    public Tenant tenant;

    /**
     * Display code shown to customer (e.g., XXXX-XXXX-XXXX-XXXX). Not indexed for security - lookups use code_hash
     * instead.
     */
    @Column(
            name = "code",
            nullable = false,
            length = 32)
    public String code;

    /**
     * SHA-256 hash of code for secure lookups without exposing raw codes.
     */
    @Column(
            name = "code_hash",
            nullable = false,
            length = 128,
            unique = true)
    public String codeHash;

    /**
     * Initial balance when card was issued.
     */
    @Column(
            name = "initial_balance",
            nullable = false,
            precision = 12,
            scale = 2)
    public BigDecimal initialBalance;

    /**
     * Current remaining balance.
     */
    @Column(
            name = "current_balance",
            nullable = false,
            precision = 12,
            scale = 2)
    public BigDecimal currentBalance;

    /**
     * Currency code (ISO 4217).
     */
    @Column(
            name = "currency",
            nullable = false,
            length = 3)
    public String currency = "USD";

    /**
     * Card status: active, redeemed, expired, cancelled.
     */
    @Column(
            name = "status",
            nullable = false,
            length = 20)
    public String status = "active";

    /**
     * User who purchased this gift card.
     */
    @ManyToOne(
            fetch = FetchType.LAZY)
    @JoinColumn(
            name = "purchaser_user_id")
    public User purchaserUser;

    /**
     * Email of purchaser (if not a registered user).
     */
    @Column(
            name = "purchaser_email",
            length = 255)
    public String purchaserEmail;

    /**
     * Recipient email address.
     */
    @Column(
            name = "recipient_email",
            length = 255)
    public String recipientEmail;

    /**
     * Recipient name.
     */
    @Column(
            name = "recipient_name",
            length = 255)
    public String recipientName;

    /**
     * Personal message from purchaser to recipient.
     */
    @Column(
            name = "personal_message",
            columnDefinition = "TEXT")
    public String personalMessage;

    /**
     * Timestamp when card was issued.
     */
    @Column(
            name = "issued_at",
            nullable = false,
            updatable = false)
    public OffsetDateTime issuedAt;

    /**
     * Timestamp when card was first used.
     */
    @Column(
            name = "activated_at")
    public OffsetDateTime activatedAt;

    /**
     * Expiration timestamp (null = never expires).
     */
    @Column(
            name = "expires_at")
    public OffsetDateTime expiresAt;

    /**
     * Timestamp when card was fully redeemed.
     */
    @Column(
            name = "fully_redeemed_at")
    public OffsetDateTime fullyRedeemedAt;

    /**
     * Order that purchased this gift card.
     */
    @Column(
            name = "source_order_id")
    public Long sourceOrderId;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false)
    public OffsetDateTime createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public OffsetDateTime updatedAt;

    @ManyToOne(
            fetch = FetchType.LAZY)
    @JoinColumn(
            name = "created_by")
    public User createdBy;

    @ManyToOne(
            fetch = FetchType.LAZY)
    @JoinColumn(
            name = "updated_by")
    public User updatedBy;

    @PrePersist
    public void prePersist() {
        if (tenant == null && TenantContext.hasContext()) {
            UUID tenantId = TenantContext.getCurrentTenantId();
            tenant = Tenant.findById(tenantId);
        }
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (issuedAt == null) {
            issuedAt = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // ========================================
    // Named Queries
    // ========================================

    public static final String QUERY_FIND_BY_CODE_HASH = "GiftCard.findByCodeHash";
    public static final String QUERY_FIND_ACTIVE_BY_TENANT = "GiftCard.findActiveByTenant";
    public static final String QUERY_FIND_EXPIRING = "GiftCard.findExpiring";

    /**
     * Find gift card by code hash within current tenant.
     *
     * @param codeHash
     *            SHA-256 hash of code
     * @return gift card if found
     */
    public static GiftCard findByCodeHash(String codeHash) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find("codeHash = :codeHash and tenant.id = :tenantId",
                Parameters.with("codeHash", codeHash).and("tenantId", tenantId)).firstResult();
    }

    /**
     * Find gift card by ID within current tenant.
     *
     * @param id
     *            gift card ID
     * @return gift card if found
     */
    public static GiftCard findByIdAndTenant(Long id) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find("id = :id and tenant.id = :tenantId", Parameters.with("id", id).and("tenantId", tenantId))
                .firstResult();
    }

    /**
     * Check if card is redeemable (active, not expired, has balance).
     *
     * @return true if card can be redeemed
     */
    public boolean isRedeemable() {
        if (!"active".equals(status)) {
            return false;
        }
        if (currentBalance.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        if (expiresAt != null && OffsetDateTime.now().isAfter(expiresAt)) {
            return false;
        }
        return true;
    }
}
