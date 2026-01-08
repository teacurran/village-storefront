package villagecompute.storefront.platformops.data.models;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Platform admin role entity. Defines RBAC for platform administrators (cross-tenant permissions).
 *
 * <p>
 * Platform admins operate above the tenant level and have permissions like impersonation, store suspension, and audit
 * log access. This is separate from tenant-level user roles which are stored in {@code users.metadata}.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T2: Platform admin console</li>
 * <li>Architecture: 04_Operational_Architecture.md Section 3.8.1 (RBAC)</li>
 * <li>Migration: V20260111__platform_admin_tables.sql</li>
 * </ul>
 */
@Entity
@Table(
        name = "platform_admin_roles")
public class PlatformAdminRole extends PanacheEntityBase {

    public static final String QUERY_FIND_BY_EMAIL = "PlatformAdminRole.findByEmail";
    public static final String QUERY_FIND_ACTIVE = "PlatformAdminRole.findActive";

    public static final String ROLE_SUPER_ADMIN = "super_admin";
    public static final String ROLE_SUPPORT = "support";
    public static final String ROLE_OPS = "ops";
    public static final String ROLE_READ_ONLY = "read_only";

    public static final String PERMISSION_IMPERSONATE = "impersonate";
    public static final String PERMISSION_SUSPEND_TENANT = "suspend_tenant";
    public static final String PERMISSION_VIEW_AUDIT = "view_audit";
    public static final String PERMISSION_MANAGE_FEATURE_FLAGS = "manage_feature_flags";
    public static final String PERMISSION_VIEW_HEALTH = "view_health";
    public static final String PERMISSION_VIEW_STORES = "view_stores";

    @Id
    @GeneratedValue
    public UUID id;

    @Column(
            unique = true,
            nullable = false,
            length = 255)
    public String email; // Platform admin email (not linked to tenant users)

    @Column(
            nullable = false,
            length = 50)
    public String role; // 'super_admin', 'support', 'ops', 'read_only'

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            nullable = false,
            columnDefinition = "jsonb")
    public String permissions = "[]"; // Array of permission strings

    @Column(
            name = "mfa_enforced",
            nullable = false)
    public Boolean mfaEnforced = true;

    @Column(
            nullable = false,
            length = 20)
    public String status = "active"; // 'active', 'suspended', 'deleted'

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false)
    public OffsetDateTime createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public OffsetDateTime updatedAt;

    @Column(
            name = "created_by")
    public UUID createdBy;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    /**
     * Check if this admin role is active.
     *
     * @return true if status is 'active'
     */
    public boolean isActive() {
        return "active".equals(status);
    }
}
