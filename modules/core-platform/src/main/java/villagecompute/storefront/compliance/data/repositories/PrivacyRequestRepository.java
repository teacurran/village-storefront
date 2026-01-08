package villagecompute.storefront.compliance.data.repositories;

import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.compliance.data.models.PrivacyRequest;
import villagecompute.storefront.compliance.data.models.PrivacyRequest.RequestStatus;
import villagecompute.storefront.compliance.data.models.PrivacyRequest.RequestType;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for PrivacyRequest entities with tenant-scoped queries.
 *
 * <p>
 * All queries automatically filter by current tenant context to prevent cross-tenant data leakage.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T6: Compliance automation</li>
 * <li>ADR-001: Tenancy Strategy (Repository-Level Enforcement)</li>
 * </ul>
 */
@ApplicationScoped
public class PrivacyRequestRepository implements PanacheRepositoryBase<PrivacyRequest, UUID> {

    /**
     * Find all privacy requests for current tenant.
     *
     * @return list of privacy requests
     */
    public List<PrivacyRequest> findByCurrentTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list("tenant.id = :tenantId ORDER BY createdAt DESC", Parameters.with("tenantId", tenantId));
    }

    /**
     * Find privacy requests by status for current tenant.
     *
     * @param status
     *            request status
     * @return list of matching requests
     */
    public List<PrivacyRequest> findByStatus(RequestStatus status) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list("tenant.id = :tenantId AND status = :status ORDER BY createdAt DESC",
                Parameters.with("tenantId", tenantId).and("status", status));
    }

    /**
     * Find privacy requests by type for current tenant.
     *
     * @param type
     *            request type
     * @return list of matching requests
     */
    public List<PrivacyRequest> findByType(RequestType type) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list("tenant.id = :tenantId AND requestType = :type ORDER BY createdAt DESC",
                Parameters.with("tenantId", tenantId).and("type", type));
    }

    /**
     * Find requests pending approval (for notification/queue processing).
     *
     * @return list of pending requests
     */
    public List<PrivacyRequest> findPendingApproval() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list("tenant.id = :tenantId AND status = :status ORDER BY createdAt ASC",
                Parameters.with("tenantId", tenantId).and("status", RequestStatus.PENDING_REVIEW));
    }

    /**
     * Find approved requests ready for processing.
     *
     * @return list of approved requests
     */
    public List<PrivacyRequest> findApprovedPending() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list("tenant.id = :tenantId AND status = :status ORDER BY approvedAt ASC",
                Parameters.with("tenantId", tenantId).and("status", RequestStatus.APPROVED));
    }

    /**
     * Count requests by status for current tenant.
     *
     * @param status
     *            request status
     * @return count
     */
    public long countByStatus(RequestStatus status) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return count("tenant.id = :tenantId AND status = :status",
                Parameters.with("tenantId", tenantId).and("status", status));
    }
}
