package villagecompute.storefront.data.repositories;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.User;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;

/**
 * Repository helpers for {@link User} entities.
 *
 * <p>
 * Provides convenience lookups for tenant-scoped user queries so downstream services do not duplicate query strings
 * when resolving customer records by email/ID.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T6 - Compliance automation (privacy exports/deletions)</li>
 * <li>ADR-001 - Tenant data isolation</li>
 * </ul>
 */
@ApplicationScoped
public class UserRepository implements PanacheRepositoryBase<User, UUID> {

    /**
     * Find user by tenant and email (case-insensitive).
     *
     * @param tenantId
     *            tenant identifier
     * @param email
     *            email address to match
     * @return {@link User} or {@code null} when not found
     */
    public User findByTenantAndEmail(UUID tenantId, String email) {
        if (tenantId == null || email == null) {
            return null;
        }

        return find("tenant.id = ?1 AND lower(email) = lower(?2)", tenantId, email).firstResult();
    }
}
