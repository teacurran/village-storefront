package villagecompute.storefront.platformops.security;

import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;

import org.jboss.logging.Logger;

import villagecompute.storefront.platformops.data.models.PlatformAdminRole;
import villagecompute.storefront.platformops.data.repositories.PlatformAdminRoleRepository;

import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.json.JsonArray;

/**
 * Helper service that maps {@link SecurityIdentity} instances to platform admin roles/permissions.
 *
 * <p>
 * Platform console endpoints call this service to enforce RBAC and to obtain the actor metadata required for audit
 * logging.
 */
@ApplicationScoped
public class PlatformAdminAuthorizationService {

    private static final Logger LOG = Logger.getLogger(PlatformAdminAuthorizationService.class);

    @Inject
    PlatformAdminRoleRepository roleRepository;

    /**
     * Validate that the current identity is a platform admin and has the required permission set.
     *
     * @param identity
     *            security identity resolved by Quarkus
     * @param requiredPermissions
     *            permissions that must be granted
     * @return resolved admin principal (never {@code null})
     */
    public PlatformAdminPrincipal requirePermissions(SecurityIdentity identity, String... requiredPermissions) {
        PlatformAdminRole role = resolveActiveRole(identity)
                .orElseThrow(() -> new ForbiddenException("Platform admin authentication required"));

        Set<String> permissions = extractPermissions(role);
        if (requiredPermissions != null) {
            for (String permission : requiredPermissions) {
                if (permission == null || permission.isBlank()) {
                    continue;
                }
                if (!permissions.contains(permission)) {
                    LOG.warnf("Platform admin %s missing permission %s", role.email, permission);
                    throw new ForbiddenException("Missing platform admin permission: " + permission);
                }
            }
        }

        return new PlatformAdminPrincipal(role.id, role.email, permissions);
    }

    private Optional<PlatformAdminRole> resolveActiveRole(SecurityIdentity identity) {
        if (identity == null || identity.getPrincipal() == null) {
            return Optional.empty();
        }
        String email = identity.getPrincipal().getName();
        return roleRepository.findActiveByEmail(email);
    }

    private Set<String> extractPermissions(PlatformAdminRole role) {
        if (role.permissions == null || role.permissions.isBlank()) {
            return Collections.emptySet();
        }
        try {
            return new HashSet<>(new JsonArray(role.permissions).<String>getList());
        } catch (Exception ex) {
            LOG.warnf(ex, "Failed to parse permissions JSON for admin %s", role.email);
            return Collections.emptySet();
        }
    }

    /**
     * Simple immutable view of the acting platform admin.
     *
     * @param id
     *            platform_admin_roles.id
     * @param email
     *            email (also SecurityIdentity principal name)
     * @param permissions
     *            resolved permission set
     */
    public record PlatformAdminPrincipal(java.util.UUID id, String email, Set<String> permissions) {
    }
}
