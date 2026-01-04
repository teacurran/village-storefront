package villagecompute.storefront.data.repositories;

import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.ReportJob;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for ReportJob entity with tenant-aware queries.
 *
 * <p>
 * All queries automatically scope to the current tenant from {@link TenantContext}. Provides access to report job
 * tracking and queue management.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I3.T3 - Reporting Projection Service</li>
 * <li>Entity: {@link ReportJob}</li>
 * <li>Architecture: 04_Operational_Architecture.md (Section 3.6)</li>
 * </ul>
 */
@ApplicationScoped
public class ReportJobRepository implements PanacheRepositoryBase<ReportJob, UUID> {

    private static final String QUERY_FIND_BY_TENANT = "tenant.id = :tenantId";
    private static final String QUERY_FIND_PENDING = "tenant.id = :tenantId and status = 'pending'";
    private static final String QUERY_FIND_BY_STATUS = "tenant.id = :tenantId and status = :status";
    private static final String QUERY_FIND_BY_TYPE = "tenant.id = :tenantId and reportType = :reportType";
    private static final String QUERY_COUNT_PENDING = "status = 'pending'";

    /**
     * Find all report jobs for the current tenant ordered by creation date descending.
     *
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return paginated list of report jobs
     */
    public List<ReportJob> findByCurrentTenant(int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT + " order by createdAt desc", Parameters.with("tenantId", tenantId))
                .page(Page.of(page, size)).list();
    }

    /**
     * Find pending report jobs for the current tenant.
     *
     * @return list of pending jobs
     */
    public List<ReportJob> findPendingJobs() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_PENDING + " order by createdAt", Parameters.with("tenantId", tenantId));
    }

    /**
     * Find jobs by status within current tenant.
     *
     * @param status
     *            job status (pending, running, completed, failed)
     * @return list of matching jobs
     */
    public List<ReportJob> findByStatus(String status) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_STATUS + " order by createdAt desc",
                Parameters.with("tenantId", tenantId).and("status", status));
    }

    /**
     * Find jobs by report type within current tenant.
     *
     * @param reportType
     *            report type identifier
     * @return list of matching jobs
     */
    public List<ReportJob> findByReportType(String reportType) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_TYPE + " order by createdAt desc",
                Parameters.with("tenantId", tenantId).and("reportType", reportType));
    }

    /**
     * Count all pending jobs across all tenants (for queue depth metrics).
     *
     * @return total pending job count
     */
    public long countAllPendingJobs() {
        return count(QUERY_COUNT_PENDING);
    }

    /**
     * Count jobs for current tenant.
     *
     * @return job count
     */
    public long countByCurrentTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return count(QUERY_FIND_BY_TENANT, Parameters.with("tenantId", tenantId));
    }
}
