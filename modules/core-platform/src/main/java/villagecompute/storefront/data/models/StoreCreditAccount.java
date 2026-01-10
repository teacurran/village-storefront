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
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.panache.common.Parameters;

/**
 * StoreCreditAccount entity representing a user's store credit balance.
 *
 * <p>
 * Store credit can be issued via refunds, gift card conversions, loyalty rewards, or promotional campaigns. One account
 * per user per tenant. All balance mutations are tracked via StoreCreditTransaction ledger.
 *
 * <p>
 * References:
 * <ul>
 * <li>ERD: datamodel_erd.puml (store_credit_accounts table)</li>
 * <li>ADR-001: Multi-tenant data isolation via tenant_id</li>
 * <li>Task I4.T6: Store credit ledger with checkout/POS integration</li>
 * </ul>
 */
@Entity
@Table(
        name = "store_credit_accounts",
        uniqueConstraints = {@UniqueConstraint(
                name = "uq_store_credit_tenant_user",
                columnNames = {"tenant_id", "user_id"})})
public class StoreCreditAccount extends PanacheEntityBase {

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

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false)
    @OnDelete(
            action = OnDeleteAction.CASCADE)
    public User user;

    /**
     * Current available balance.
     */
    @Column(
            name = "balance",
            nullable = false,
            precision = 12,
            scale = 2)
    public BigDecimal balance = BigDecimal.ZERO;

    /**
     * Currency code (ISO 4217).
     */
    @Column(
            name = "currency",
            nullable = false,
            length = 3)
    public String currency = "USD";

    /**
     * Account status: active, suspended, closed.
     */
    @Column(
            name = "status",
            nullable = false,
            length = 20)
    public String status = "active";

    /**
     * Admin notes about this account.
     */
    @Column(
            name = "notes",
            columnDefinition = "TEXT")
    public String notes;

    /**
     * Additional metadata as JSON.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    public String metadata;

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
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // ========================================
    // Named Queries
    // ========================================

    public static final String QUERY_FIND_BY_USER = "StoreCreditAccount.findByUser";
    public static final String QUERY_FIND_BY_TENANT_AND_USER = "StoreCreditAccount.findByTenantAndUser";

    /**
     * Find or create store credit account for user in current tenant.
     *
     * @param userId
     *            user UUID
     * @return store credit account
     */
    public static StoreCreditAccount findOrCreateByUser(UUID userId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        StoreCreditAccount account = find("user.id = :userId and tenant.id = :tenantId",
                Parameters.with("userId", userId).and("tenantId", tenantId)).firstResult();

        if (account == null) {
            account = new StoreCreditAccount();
            account.tenant = Tenant.findById(tenantId);
            account.user = User.findById(userId);
            account.persist();
        }

        return account;
    }

    /**
     * Find store credit account by user ID in current tenant.
     *
     * @param userId
     *            user UUID
     * @return account if exists
     */
    public static StoreCreditAccount findByUser(UUID userId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find("user.id = :userId and tenant.id = :tenantId",
                Parameters.with("userId", userId).and("tenantId", tenantId)).firstResult();
    }

    /**
     * Find account by ID within current tenant.
     *
     * @param id
     *            account ID
     * @return account if found
     */
    public static StoreCreditAccount findByIdAndTenant(Long id) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find("id = :id and tenant.id = :tenantId", Parameters.with("id", id).and("tenantId", tenantId))
                .firstResult();
    }

    /**
     * Check if account is usable (active status, positive balance).
     *
     * @return true if account can be used
     */
    public boolean isUsable() {
        return "active".equals(status) && balance.compareTo(BigDecimal.ZERO) > 0;
    }
}
