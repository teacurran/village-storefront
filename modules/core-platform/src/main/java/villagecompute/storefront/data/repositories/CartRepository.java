package villagecompute.storefront.data.repositories;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.Cart;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for Cart entity with tenant-aware queries.
 *
 * <p>
 * All queries automatically scope to the current tenant from {@link TenantContext}. This prevents cross-tenant data
 * leakage and enforces the multi-tenancy model defined in ADR-001.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001: Repository-Level Tenant Enforcement</li>
 * <li>Entity: {@link Cart}</li>
 * <li>Task I2.T4: Cart repository with tenant isolation</li>
 * </ul>
 */
@ApplicationScoped
public class CartRepository implements PanacheRepositoryBase<Cart, UUID> {

    private static final String QUERY_FIND_BY_TENANT = "tenant.id = :tenantId";
    private static final String QUERY_FIND_BY_TENANT_AND_USER = "tenant.id = :tenantId and user.id = :userId";
    private static final String QUERY_FIND_BY_TENANT_AND_SESSION = "tenant.id = :tenantId and sessionId = :sessionId";
    private static final String QUERY_FIND_ACTIVE_BY_TENANT_AND_USER = "tenant.id = :tenantId and user.id = :userId and expiresAt > :now";
    private static final String QUERY_FIND_ACTIVE_BY_TENANT_AND_SESSION = "tenant.id = :tenantId and sessionId = :sessionId and expiresAt > :now";
    private static final String QUERY_FIND_EXPIRED = "tenant.id = :tenantId and expiresAt <= :now";

    /**
     * Find all carts for the current tenant.
     *
     * @return list of carts
     */
    public List<Cart> findByCurrentTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_TENANT, Parameters.with("tenantId", tenantId));
    }

    /**
     * Find cart by user ID within current tenant.
     *
     * @param userId
     *            user UUID
     * @return cart if found
     */
    public Optional<Cart> findByUser(UUID userId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT_AND_USER, Parameters.with("tenantId", tenantId).and("userId", userId))
                .firstResultOptional();
    }

    /**
     * Find active (non-expired) cart by user ID within current tenant.
     *
     * @param userId
     *            user UUID
     * @return active cart if found
     */
    public Optional<Cart> findActiveByUser(UUID userId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_ACTIVE_BY_TENANT_AND_USER,
                Parameters.with("tenantId", tenantId).and("userId", userId).and("now", OffsetDateTime.now()))
                .firstResultOptional();
    }

    /**
     * Find cart by session ID within current tenant.
     *
     * @param sessionId
     *            session identifier
     * @return cart if found
     */
    public Optional<Cart> findBySession(String sessionId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT_AND_SESSION, Parameters.with("tenantId", tenantId).and("sessionId", sessionId))
                .firstResultOptional();
    }

    /**
     * Find active (non-expired) cart by session ID within current tenant.
     *
     * @param sessionId
     *            session identifier
     * @return active cart if found
     */
    public Optional<Cart> findActiveBySession(String sessionId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_ACTIVE_BY_TENANT_AND_SESSION,
                Parameters.with("tenantId", tenantId).and("sessionId", sessionId).and("now", OffsetDateTime.now()))
                .firstResultOptional();
    }

    /**
     * Find expired carts for cleanup within current tenant.
     *
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return paginated list of expired carts
     */
    public List<Cart> findExpired(int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_EXPIRED, Parameters.with("tenantId", tenantId).and("now", OffsetDateTime.now()))
                .page(Page.of(page, size)).list();
    }

    /**
     * Count all carts for the current tenant.
     *
     * @return total cart count
     */
    public long countByCurrentTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return count(QUERY_FIND_BY_TENANT, Parameters.with("tenantId", tenantId));
    }

    /**
     * Count expired carts for the current tenant.
     *
     * @return expired cart count
     */
    public long countExpired() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return count(QUERY_FIND_EXPIRED, Parameters.with("tenantId", tenantId).and("now", OffsetDateTime.now()));
    }
}
