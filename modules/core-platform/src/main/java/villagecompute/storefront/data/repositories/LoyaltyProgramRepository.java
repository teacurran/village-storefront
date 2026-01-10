package villagecompute.storefront.data.repositories;

import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.LoyaltyProgram;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for LoyaltyProgram entity with tenant-aware queries.
 *
 * <p>
 * All queries automatically scope to the current tenant from {@link TenantContext}. This prevents cross-tenant data
 * leakage and enforces the multi-tenancy model defined in ADR-001.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001: Repository-Level Tenant Enforcement</li>
 * <li>Entity: {@link LoyaltyProgram}</li>
 * <li>Task I4.T4: Loyalty program configuration repository</li>
 * </ul>
 */
@ApplicationScoped
public class LoyaltyProgramRepository implements PanacheRepositoryBase<LoyaltyProgram, UUID> {

    private static final String QUERY_FIND_BY_TENANT = "tenant.id = :tenantId";
    private static final String QUERY_FIND_ACTIVE_BY_TENANT = "tenant.id = :tenantId and enabled = true";

    /**
     * Find the active loyalty program for the current tenant.
     *
     * @return active program if found
     */
    public Optional<LoyaltyProgram> findActiveProgramForCurrentTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_ACTIVE_BY_TENANT, Parameters.with("tenantId", tenantId)).firstResultOptional();
    }

    /**
     * Find loyalty program by ID within current tenant.
     *
     * @param programId
     *            program UUID
     * @return program if found
     */
    public Optional<LoyaltyProgram> findByIdForCurrentTenant(UUID programId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LoyaltyProgram program = findById(programId);
        if (program != null && program.tenant.id.equals(tenantId)) {
            return Optional.of(program);
        }
        return Optional.empty();
    }

    /**
     * Find any loyalty program configuration for the current tenant (enabled or disabled).
     *
     * @return program if configured
     */
    public Optional<LoyaltyProgram> findProgramForCurrentTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT, Parameters.with("tenantId", tenantId)).firstResultOptional();
    }
}
