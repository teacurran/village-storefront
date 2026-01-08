package villagecompute.storefront.data.models;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * LoyaltyMember entity representing a user's enrollment in a loyalty program.
 *
 * <p>
 * Tracks current points balance, tier status, and lifetime statistics. One user can have one membership per tenant's
 * loyalty program. The balance_after column in LoyaltyTransaction provides audit trail for balance changes.
 *
 * <p>
 * References:
 * <ul>
 * <li>ERD: datamodel_erd.puml (loyalty_members table)</li>
 * <li>ADR-001: Multi-tenant data isolation via tenant_id</li>
 * <li>Task I4.T4: Loyalty member enrollment and tier tracking</li>
 * </ul>
 */
@Entity
@Table(
        name = "loyalty_members",
        uniqueConstraints = {@UniqueConstraint(
                name = "uq_loyalty_members_user_program",
                columnNames = {"user_id", "program_id"})})
public class LoyaltyMember extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

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
            name = "user_id",
            nullable = false)
    public User user;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false)
    @JoinColumn(
            name = "program_id",
            nullable = false)
    public LoyaltyProgram program;

    /**
     * Current available points balance.
     */
    @Column(
            name = "points_balance",
            nullable = false)
    public Integer pointsBalance = 0;

    /**
     * Total points earned over lifetime (never decreases).
     */
    @Column(
            name = "lifetime_points_earned",
            nullable = false)
    public Integer lifetimePointsEarned = 0;

    /**
     * Total points redeemed over lifetime.
     */
    @Column(
            name = "lifetime_points_redeemed",
            nullable = false)
    public Integer lifetimePointsRedeemed = 0;

    /**
     * Current tier name (e.g., "Bronze", "Silver", "Gold").
     */
    @Column(
            name = "current_tier",
            length = 50)
    public String currentTier;

    /**
     * Timestamp of last tier recalculation.
     */
    @Column(
            name = "tier_updated_at")
    public OffsetDateTime tierUpdatedAt;

    /**
     * Member status: active, suspended, inactive.
     */
    @Column(
            nullable = false,
            length = 20)
    public String status = "active";

    /**
     * Additional member attributes as JSON (preferences, marketing opt-ins, etc.).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    public String metadata;

    @Column(
            name = "enrolled_at",
            nullable = false,
            updatable = false)
    public OffsetDateTime enrolledAt;

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
        if (enrolledAt == null) {
            enrolledAt = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
