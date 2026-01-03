package villagecompute.storefront.data.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.LoyaltyMember;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for LoyaltyMember entity with tenant-aware queries.
 *
 * <p>
 * All queries automatically scope to the current tenant from {@link TenantContext}. This prevents cross-tenant data
 * leakage and enforces the multi-tenancy model defined in ADR-001.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001: Repository-Level Tenant Enforcement</li>
 * <li>Entity: {@link LoyaltyMember}</li>
 * <li>Task I4.T4: Loyalty member repository with tier queries</li>
 * </ul>
 */
@ApplicationScoped
public class LoyaltyMemberRepository implements PanacheRepositoryBase<LoyaltyMember, UUID> {

    private static final String QUERY_FIND_BY_USER = "tenant.id = :tenantId and user.id = :userId";
    private static final String QUERY_FIND_BY_USER_AND_PROGRAM = "tenant.id = :tenantId and user.id = :userId and program.id = :programId";
    private static final String QUERY_FIND_BY_PROGRAM = "tenant.id = :tenantId and program.id = :programId";
    private static final String QUERY_FIND_ACTIVE_BY_PROGRAM = "tenant.id = :tenantId and program.id = :programId and status = 'active'";
    private static final String QUERY_FIND_BY_TIER = "tenant.id = :tenantId and currentTier = :tier and status = 'active'";

    /**
     * Find loyalty member by user ID within current tenant.
     *
     * @param userId
     *            user UUID
     * @return member if found
     */
    public Optional<LoyaltyMember> findByUser(UUID userId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_USER, Parameters.with("tenantId", tenantId).and("userId", userId))
                .firstResultOptional();
    }

    /**
     * Find loyalty member by user and program within current tenant.
     *
     * @param userId
     *            user UUID
     * @param programId
     *            program UUID
     * @return member if found
     */
    public Optional<LoyaltyMember> findByUserAndProgram(UUID userId, UUID programId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_USER_AND_PROGRAM,
                Parameters.with("tenantId", tenantId).and("userId", userId).and("programId", programId))
                .firstResultOptional();
    }

    /**
     * Find all members in a program within current tenant.
     *
     * @param programId
     *            program UUID
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return paginated list of members
     */
    public List<LoyaltyMember> findByProgram(UUID programId, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_PROGRAM, Parameters.with("tenantId", tenantId).and("programId", programId))
                .page(Page.of(page, size)).list();
    }

    /**
     * Find active members in a program within current tenant.
     *
     * @param programId
     *            program UUID
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return paginated list of active members
     */
    public List<LoyaltyMember> findActiveByProgram(UUID programId, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_ACTIVE_BY_PROGRAM, Parameters.with("tenantId", tenantId).and("programId", programId))
                .page(Page.of(page, size)).list();
    }

    /**
     * Find members by tier within current tenant.
     *
     * @param tier
     *            tier name
     * @return list of members in tier
     */
    public List<LoyaltyMember> findByTier(String tier) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_TIER, Parameters.with("tenantId", tenantId).and("tier", tier));
    }

    /**
     * Count members in a program within current tenant.
     *
     * @param programId
     *            program UUID
     * @return member count
     */
    public long countByProgram(UUID programId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return count(QUERY_FIND_BY_PROGRAM, Parameters.with("tenantId", tenantId).and("programId", programId));
    }
}
