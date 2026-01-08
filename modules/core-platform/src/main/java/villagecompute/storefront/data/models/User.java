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
 * User entity representing authenticated users within a tenant (customers, admins, vendors).
 *
 * <p>
 * Users are scoped to a specific tenant and identified by email (unique per tenant). Supports both password-based and
 * OAuth authentication. The metadata field stores custom user attributes as JSONB.
 *
 * <p>
 * References:
 * <ul>
 * <li>ERD: datamodel_erd.puml (users table)</li>
 * <li>ADR-001: Multi-tenant data isolation via tenant_id</li>
 * <li>OpenAPI: User schemas</li>
 * </ul>
 */
@Entity
@Table(
        name = "users",
        uniqueConstraints = {@UniqueConstraint(
                name = "uq_users_tenant_email",
                columnNames = {"tenant_id", "email"})})
public class User extends PanacheEntityBase {

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

    @Column(
            nullable = false,
            length = 255)
    public String email;

    @Column(
            name = "password_hash",
            length = 255)
    public String passwordHash; // Nullable for OAuth-only users

    @Column(
            nullable = false,
            length = 20)
    public String status = "active"; // active, suspended, deleted

    @Column(
            name = "first_name",
            length = 100)
    public String firstName;

    @Column(
            name = "last_name",
            length = 100)
    public String lastName;

    @Column(
            length = 20)
    public String phone;

    @JdbcTypeCode(SqlTypes.JSON)
    public String metadata; // Custom user attributes

    @Column(
            name = "email_verified",
            nullable = false)
    public Boolean emailVerified = false;

    @Column(
            name = "last_login_at")
    public OffsetDateTime lastLoginAt;

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
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
