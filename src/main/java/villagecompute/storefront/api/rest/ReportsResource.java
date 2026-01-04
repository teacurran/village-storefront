package villagecompute.storefront.api.rest;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.ConsignmentPayoutAggregate;
import villagecompute.storefront.data.models.InventoryAgingAggregate;
import villagecompute.storefront.data.models.ReportJob;
import villagecompute.storefront.data.models.SalesByPeriodAggregate;
import villagecompute.storefront.data.repositories.ConsignmentPayoutAggregateRepository;
import villagecompute.storefront.data.repositories.InventoryAgingAggregateRepository;
import villagecompute.storefront.data.repositories.ReportJobRepository;
import villagecompute.storefront.data.repositories.SalesByPeriodAggregateRepository;
import villagecompute.storefront.services.ReportingJobService;
import villagecompute.storefront.tenant.TenantContext;

/**
 * REST resource for reporting and analytics.
 *
 * <p>
 * Provides endpoints for:
 * <ul>
 * <li>GET /admin/reports/aggregates/sales - Query sales aggregates</li>
 * <li>GET /admin/reports/aggregates/consignment-payouts - Query consignment payout aggregates</li>
 * <li>GET /admin/reports/aggregates/inventory-aging - Query inventory aging aggregates</li>
 * <li>POST /admin/reports/{reportType}/export - Request report export</li>
 * <li>GET /admin/reports/jobs/{jobId} - Get export job status and download URL</li>
 * </ul>
 *
 * <p>
 * All endpoints are tenant-scoped and require admin authentication.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I3.T3 - Reporting Projection Service</li>
 * <li>Architecture: 04_Operational_Architecture.md (Section 3.6)</li>
 * </ul>
 */
@Path("/api/v1/admin/reports")
@Produces(MediaType.APPLICATION_JSON)
public class ReportsResource {

    private static final Logger LOG = Logger.getLogger(ReportsResource.class);

    @Inject
    SalesByPeriodAggregateRepository salesAggregateRepo;

    @Inject
    ConsignmentPayoutAggregateRepository payoutAggregateRepo;

    @Inject
    InventoryAgingAggregateRepository agingAggregateRepo;

    @Inject
    ReportJobRepository reportJobRepository;

    @Inject
    ReportingJobService reportingJobService;

    /**
     * Get sales aggregates for dashboard widgets.
     *
     * @param startDate
     *            optional start date filter (YYYY-MM-DD)
     * @param endDate
     *            optional end date filter (YYYY-MM-DD)
     * @return list of sales aggregates
     */
    @GET
    @Path("/aggregates/sales")
    public Response getSalesAggregates(@QueryParam("startDate") String startDate,
            @QueryParam("endDate") String endDate) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /admin/reports/aggregates/sales - tenantId=%s, startDate=%s, endDate=%s", tenantId, startDate,
                endDate);

        List<SalesByPeriodAggregate> aggregates;

        if (startDate != null && endDate != null) {
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);
            aggregates = salesAggregateRepo.findByPeriodRange(start, end);
        } else {
            aggregates = salesAggregateRepo.findByCurrentTenant();
        }

        LOG.infof("Retrieved %d sales aggregates for tenant %s", aggregates.size(), tenantId);

        return Response.ok(aggregates).build();
    }

    /**
     * Get consignment payout aggregates.
     *
     * @param consignorId
     *            optional consignor filter
     * @param startDate
     *            optional start date filter
     * @param endDate
     *            optional end date filter
     * @return list of payout aggregates
     */
    @GET
    @Path("/aggregates/consignment-payouts")
    public Response getConsignmentPayoutAggregates(@QueryParam("consignorId") UUID consignorId,
            @QueryParam("startDate") String startDate, @QueryParam("endDate") String endDate) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /admin/reports/aggregates/consignment-payouts - tenantId=%s, consignorId=%s", tenantId,
                consignorId);

        List<ConsignmentPayoutAggregate> aggregates;

        if (consignorId != null) {
            aggregates = payoutAggregateRepo.findByConsignor(consignorId);
        } else if (startDate != null && endDate != null) {
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);
            aggregates = payoutAggregateRepo.findByPeriodRange(start, end);
        } else {
            aggregates = payoutAggregateRepo.findByCurrentTenant();
        }

        LOG.infof("Retrieved %d consignment payout aggregates for tenant %s", aggregates.size(), tenantId);

        return Response.ok(aggregates).build();
    }

    /**
     * Get inventory aging aggregates.
     *
     * @param locationId
     *            optional location filter
     * @param minDays
     *            optional minimum days filter for slow movers
     * @return list of aging aggregates
     */
    @GET
    @Path("/aggregates/inventory-aging")
    public Response getInventoryAgingAggregates(@QueryParam("locationId") UUID locationId,
            @QueryParam("minDays") Integer minDays) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /admin/reports/aggregates/inventory-aging - tenantId=%s, locationId=%s, minDays=%d", tenantId,
                locationId, minDays);

        List<InventoryAgingAggregate> aggregates;

        if (locationId != null) {
            aggregates = agingAggregateRepo.findByLocation(locationId);
        } else if (minDays != null) {
            aggregates = agingAggregateRepo.findSlowMovers(minDays);
        } else {
            aggregates = agingAggregateRepo.findByCurrentTenant();
        }

        LOG.infof("Retrieved %d inventory aging aggregates for tenant %s", aggregates.size(), tenantId);

        return Response.ok(aggregates).build();
    }

    /**
     * Request a report export.
     *
     * @param reportType
     *            report type (sales, consignment-payouts, inventory-aging)
     * @param request
     *            export parameters
     * @return export job ID and status
     */
    @POST
    @Path("/{reportType}/export")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response requestExport(@PathParam("reportType") String reportType, Map<String, String> request) {
        UUID tenantId = TenantContext.getCurrentTenantId();

        // Normalize report type (remove hyphens, use underscores for internal format)
        String normalizedType = reportType.replace("-", "_");

        Map<String, String> safeRequest = request != null ? request : Collections.emptyMap();
        String format = safeRequest.getOrDefault("format", "csv");
        String requestedBy = safeRequest.get("requestedBy"); // TODO: Get from auth context

        LOG.infof("POST /admin/reports/%s/export - tenantId=%s, format=%s", reportType, tenantId, format);

        UUID jobId = reportingJobService.enqueueExport(normalizedType, format, safeRequest, requestedBy);

        Map<String, Object> response = new HashMap<>();
        response.put("jobId", jobId);
        response.put("status", "pending");
        response.put("message", "Export job enqueued successfully");

        LOG.infof("Export job created - jobId=%s, reportType=%s, format=%s", jobId, normalizedType, format);

        return Response.status(Status.ACCEPTED).entity(response).build();
    }

    /**
     * Get export job status and download URL.
     *
     * @param jobId
     *            job UUID
     * @return job status and result URL
     */
    @GET
    @Path("/jobs/{jobId}")
    public Response getExportJobStatus(@PathParam("jobId") UUID jobId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /admin/reports/jobs/%s - tenantId=%s", jobId, tenantId);

        Optional<ReportJob> jobOptional = reportJobRepository.findByIdOptional(jobId);

        if (jobOptional.isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Job not found");
            error.put("jobId", jobId);
            return Response.status(Status.NOT_FOUND).entity(error).build();
        }

        ReportJob job = jobOptional.get();

        // Verify tenant ownership
        if (!job.tenant.id.equals(tenantId)) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Unauthorized");
            error.put("message", "Job does not belong to current tenant");
            return Response.status(Status.FORBIDDEN).entity(error).build();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("jobId", job.id);
        response.put("reportType", job.reportType);
        response.put("status", job.status);
        response.put("createdAt", job.createdAt);
        response.put("startedAt", job.startedAt);
        response.put("completedAt", job.completedAt);

        if ("completed".equals(job.status)) {
            response.put("downloadUrl", job.resultUrl);
        }

        if ("failed".equals(job.status)) {
            response.put("error", job.errorMessage);
        }

        LOG.infof("Retrieved job status - jobId=%s, status=%s", jobId, job.status);

        return Response.ok(response).build();
    }

    /**
     * List recent export jobs for current tenant.
     *
     * @param page
     *            page number (default 0)
     * @param size
     *            page size (default 20)
     * @return list of jobs
     */
    @GET
    @Path("/jobs")
    public Response listExportJobs(@QueryParam("page") Integer page, @QueryParam("size") Integer size) {
        UUID tenantId = TenantContext.getCurrentTenantId();

        int pageNum = page != null ? page : 0;
        int pageSize = size != null ? size : 20;

        LOG.infof("GET /admin/reports/jobs - tenantId=%s, page=%d, size=%d", tenantId, pageNum, pageSize);

        List<ReportJob> jobs = reportJobRepository.findByCurrentTenant(pageNum, pageSize);
        long totalCount = reportJobRepository.countByCurrentTenant();

        Map<String, Object> response = new HashMap<>();
        response.put("jobs", jobs);
        response.put("page", pageNum);
        response.put("size", pageSize);
        response.put("totalCount", totalCount);

        return Response.ok(response).build();
    }
}
