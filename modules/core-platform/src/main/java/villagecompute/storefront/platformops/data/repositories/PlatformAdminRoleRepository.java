package villagecompute.storefront.platformops.data.repositories;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.platformops.data.models.PlatformAdminRole;

import io.quarkus.hibernate.orm.panache.PanacheRepository;

/**
 * Repository for {@link PlatformAdminRole}.
 */
@ApplicationScoped
public class PlatformAdminRoleRepository implements PanacheRepository<PlatformAdminRole> {

    /**
     * Find an active platform admin record by email.
     *
     * @param email
     *            admin email (case-insensitive)
     * @return role if present and active
     */
    public Optional<PlatformAdminRole> findActiveByEmail(String email) {
        if (email == null) {
            return Optional.empty();
        }
        return find("LOWER(email) = LOWER(?1) AND status = 'active'", email).firstResultOptional();
    }
}
