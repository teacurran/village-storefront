package villagecompute.storefront.data.repositories;

import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.OAuthClient;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for OAuth client operations.
 *
 * <p>
 * Provides data access methods for OAuth client credentials, including lookup by client ID and tenant-scoped queries.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T7: Headless API OAuth authentication</li>
 * <li>Standards: Named queries use QUERY_ prefix constants</li>
 * </ul>
 */
@ApplicationScoped
public class OAuthClientRepository implements PanacheRepository<OAuthClient> {

    private static final String QUERY_FIND_BY_CLIENT_ID = "SELECT oc FROM OAuthClient oc "
            + "WHERE oc.clientId = :clientId";

    private static final String QUERY_FIND_ACTIVE_BY_CLIENT_ID = "SELECT oc FROM OAuthClient oc "
            + "WHERE oc.clientId = :clientId AND oc.active = true";

    private static final String QUERY_FIND_BY_TENANT = "SELECT oc FROM OAuthClient oc "
            + "WHERE oc.tenant.id = :tenantId ORDER BY oc.createdAt DESC";

    /**
     * Find OAuth client by client ID.
     *
     * @param clientId
     *            client identifier
     * @return OAuth client if found
     */
    public Optional<OAuthClient> findByClientId(String clientId) {
        return find(QUERY_FIND_BY_CLIENT_ID, Parameters.with("clientId", clientId)).firstResultOptional();
    }

    /**
     * Find active OAuth client by client ID.
     *
     * @param clientId
     *            client identifier
     * @return active OAuth client if found
     */
    public Optional<OAuthClient> findActiveByClientId(String clientId) {
        return find(QUERY_FIND_ACTIVE_BY_CLIENT_ID, Parameters.with("clientId", clientId)).firstResultOptional();
    }

    /**
     * Find all OAuth clients for a tenant.
     *
     * @param tenantId
     *            tenant UUID
     * @return list of OAuth clients
     */
    public java.util.List<OAuthClient> findByTenant(UUID tenantId) {
        return list(QUERY_FIND_BY_TENANT, Parameters.with("tenantId", tenantId));
    }
}
