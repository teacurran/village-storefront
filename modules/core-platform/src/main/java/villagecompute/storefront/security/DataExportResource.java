package villagecompute.storefront.security;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;

import villagecompute.storefront.compliance.ComplianceService;
import villagecompute.storefront.compliance.data.models.PrivacyRequest;
import villagecompute.storefront.compliance.data.models.PrivacyRequest.RequestStatus;
import villagecompute.storefront.compliance.data.models.PrivacyRequest.RequestType;
import villagecompute.storefront.compliance.data.repositories.PrivacyRequestRepository;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;

/**
 * Customer-facing REST resource for privacy data export and deletion requests.
 *
 * <p>
 * Provides self-service endpoints for GDPR/CCPA compliance:
 * <ul>
 * <li>Request data export (download personal data)</li>
 * <li>Request account deletion (right to erasure)</li>
 * <li>Poll request status and download export files</li>
 * </ul>
 *
 * <p>
 * All requests are tenant-scoped and require authentication. Export files are delivered via pre-signed URLs with
 * 72-hour expiry.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T8: Security/privacy features finalization</li>
 * <li>Architecture: 01_Blueprint_Foundation.md Section 6.0 (Safety Net - GDPR/CCPA flows)</li>
 * <li>UI/UX: 06_UI_UX_Architecture.md Section 4.15 (Data Privacy UX)</li>
 * </ul>
 */
@Path("/api/v1/privacy")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class DataExportResource {

    private static final Logger LOG = Logger.getLogger(DataExportResource.class);

    @Inject
    ComplianceService complianceService;

    @Inject
    PrivacyRequestRepository privacyRequestRepo;

    @Inject
    SecurityIdentity securityIdentity;

    /**
     * Request a data export for the authenticated user.
     *
     * <p>
     * Creates a privacy export request that will be processed asynchronously. User receives all personal data (orders,
     * profile, sessions, consent history) in JSONL/CSV format via downloadable ZIP file.
     *
     * @return request ID and status
     */
    @POST
    @Path("/export")
    public Response requestDataExport() {
        // Verify tenant context
        if (!TenantContext.hasContext()) {
            LOG.error("Data export failed: No tenant context available");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ProblemDetail.forStatus(500)
                    .withTitle("Internal Error").withDetail("Tenant context not available").build()).build();
        }

        String userEmail = securityIdentity.getPrincipal().getName();
        UUID tenantId = TenantContext.getCurrentTenantId();

        LOG.infof("POST /privacy/export - tenantId=%s, user=%s", tenantId, userEmail);

        // Check for existing pending/in-progress requests
        List<PrivacyRequest> existingRequests = privacyRequestRepo.findBySubjectAndStatus(userEmail,
                List.of(RequestStatus.PENDING_REVIEW, RequestStatus.APPROVED, RequestStatus.IN_PROGRESS));

        if (!existingRequests.isEmpty()) {
            PrivacyRequest existing = existingRequests.get(0);
            LOG.warnf("User %s already has pending export request: %s", userEmail, existing.id);

            return Response.status(Response.Status.CONFLICT).entity(ProblemDetail.forStatus(409)
                    .withTitle("Request Already Exists")
                    .withDetail("You already have a pending data export request. Please wait for it to complete.")
                    .withExtension("requestId", existing.id.toString()).withExtension("status", existing.status)
                    .build()).build();
        }

        // Submit export request
        UUID requestId = complianceService.submitExportRequest(userEmail, userEmail, "Self-service data export request",
                null);

        // Auto-approve for self-service requests (no manual review required)
        UUID jobId = complianceService.approveExportRequest(requestId, "system", "Auto-approved self-service request");

        Map<String, Object> response = new HashMap<>();
        response.put("requestId", requestId);
        response.put("jobId", jobId);
        response.put("status", "processing");
        response.put("message", "Your data export is being prepared. This may take several minutes.");

        LOG.infof("Data export request created and auto-approved - requestId=%s, jobId=%s", requestId, jobId);

        return Response.status(Response.Status.ACCEPTED).entity(response).build();
    }

    /**
     * Get status of a data export request.
     *
     * @param requestId
     *            privacy request ID
     * @return request status and download URL (if completed)
     */
    @GET
    @Path("/export/{requestId}")
    public Response getExportStatus(@PathParam("requestId") UUID requestId) {
        if (!TenantContext.hasContext()) {
            LOG.error("Export status check failed: No tenant context available");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ProblemDetail.forStatus(500)
                    .withTitle("Internal Error").withDetail("Tenant context not available").build()).build();
        }

        String userEmail = securityIdentity.getPrincipal().getName();

        LOG.infof("GET /privacy/export/%s - user=%s", requestId, userEmail);

        PrivacyRequest request = PrivacyRequest.findById(requestId);
        if (request == null) {
            return Response.status(Response.Status.NOT_FOUND).entity(
                    ProblemDetail.forStatus(404).withTitle("Not Found").withDetail("Export request not found").build())
                    .build();
        }

        // Verify user owns this request
        if (!request.subjectEmail.equals(userEmail)) {
            LOG.warnf("User %s attempted to access export request %s owned by %s", userEmail, requestId,
                    request.subjectEmail);
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(ProblemDetail.forbidden("You do not have permission to access this request")).build();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("requestId", request.id);
        response.put("status", request.status.name().toLowerCase());
        response.put("createdAt", request.createdAt);
        response.put("updatedAt", request.updatedAt);

        if (request.status == RequestStatus.COMPLETED && request.resultUrl != null) {
            response.put("downloadUrl", request.resultUrl);
            response.put("message", "Your data export is ready. Download link expires in 72 hours.");
        } else if (request.status == RequestStatus.IN_PROGRESS) {
            response.put("message", "Your data export is being processed. Please check back shortly.");
        } else if (request.status == RequestStatus.FAILED) {
            response.put("message", "Data export failed. Please contact support.");
            response.put("error", request.errorMessage);
        } else {
            response.put("message", "Your data export request is queued for processing.");
        }

        return Response.ok(response).build();
    }

    /**
     * List all export requests for the authenticated user.
     *
     * @return list of export requests
     */
    @GET
    @Path("/exports")
    public Response listExportRequests() {
        if (!TenantContext.hasContext()) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ProblemDetail.forStatus(500)
                    .withTitle("Internal Error").withDetail("Tenant context not available").build()).build();
        }

        String userEmail = securityIdentity.getPrincipal().getName();

        LOG.infof("GET /privacy/exports - user=%s", userEmail);

        List<PrivacyRequest> requests = privacyRequestRepo.findBySubjectEmail(userEmail, RequestType.EXPORT);

        List<Map<String, Object>> exportList = requests.stream().map(req -> {
            Map<String, Object> item = new HashMap<>();
            item.put("requestId", req.id);
            item.put("status", req.status.name().toLowerCase());
            item.put("createdAt", req.createdAt);
            item.put("completedAt", req.completedAt);

            if (req.status == RequestStatus.COMPLETED && req.resultUrl != null) {
                item.put("downloadAvailable", true);
            } else {
                item.put("downloadAvailable", false);
            }

            return item;
        }).collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("exports", exportList);
        response.put("total", exportList.size());

        return Response.ok(response).build();
    }

    /**
     * Request account deletion for the authenticated user.
     *
     * <p>
     * Initiates two-phase deletion: immediate soft-delete (data hidden from UI) followed by permanent purge after
     * 90-day retention period. Audit logs are preserved indefinitely per compliance requirements.
     *
     * @return request ID and status
     */
    @POST
    @Path("/delete")
    public Response requestAccountDeletion() {
        if (!TenantContext.hasContext()) {
            LOG.error("Account deletion failed: No tenant context available");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ProblemDetail.forStatus(500)
                    .withTitle("Internal Error").withDetail("Tenant context not available").build()).build();
        }

        String userEmail = securityIdentity.getPrincipal().getName();
        UUID tenantId = TenantContext.getCurrentTenantId();

        LOG.infof("POST /privacy/delete - tenantId=%s, user=%s", tenantId, userEmail);

        // Check for existing pending deletion requests
        List<PrivacyRequest> existingRequests = privacyRequestRepo.findBySubjectAndStatus(userEmail,
                List.of(RequestStatus.PENDING_REVIEW, RequestStatus.APPROVED, RequestStatus.IN_PROGRESS,
                        RequestStatus.AWAITING_PURGE));

        if (!existingRequests.isEmpty()) {
            PrivacyRequest existing = existingRequests.get(0);
            LOG.warnf("User %s already has pending deletion request: %s", userEmail, existing.id);

            return Response.status(Response.Status.CONFLICT)
                    .entity(ProblemDetail.forStatus(409).withTitle("Request Already Exists").withDetail(
                            "You already have a pending account deletion request. Contact support if you need assistance.")
                            .withExtension("requestId", existing.id.toString()).withExtension("status", existing.status)
                            .build())
                    .build();
        }

        // Submit deletion request
        UUID requestId = complianceService.submitDeleteRequest(userEmail, userEmail,
                "Self-service account deletion request", null);

        // Auto-approve for self-service requests
        UUID jobId = complianceService.approveDeleteRequest(requestId, "system", "Auto-approved self-service request");

        Map<String, Object> response = new HashMap<>();
        response.put("requestId", requestId);
        response.put("jobId", jobId);
        response.put("status", "processing");
        response.put("message",
                "Your account deletion request has been submitted. Your account will be deactivated immediately and permanently deleted after 90 days.");

        LOG.infof("Account deletion request created and auto-approved - requestId=%s, jobId=%s", requestId, jobId);

        return Response.status(Response.Status.ACCEPTED).entity(response).build();
    }

    /**
     * Get status of an account deletion request.
     *
     * @param requestId
     *            privacy request ID
     * @return request status
     */
    @GET
    @Path("/delete/{requestId}")
    public Response getDeletionStatus(@PathParam("requestId") UUID requestId) {
        if (!TenantContext.hasContext()) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ProblemDetail.forStatus(500)
                    .withTitle("Internal Error").withDetail("Tenant context not available").build()).build();
        }

        String userEmail = securityIdentity.getPrincipal().getName();

        LOG.infof("GET /privacy/delete/%s - user=%s", requestId, userEmail);

        PrivacyRequest request = PrivacyRequest.findById(requestId);
        if (request == null) {
            return Response.status(Response.Status.NOT_FOUND).entity(ProblemDetail.forStatus(404).withTitle("Not Found")
                    .withDetail("Deletion request not found").build()).build();
        }

        // Verify user owns this request
        if (!request.subjectEmail.equals(userEmail)) {
            LOG.warnf("User %s attempted to access deletion request %s owned by %s", userEmail, requestId,
                    request.subjectEmail);
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(ProblemDetail.forbidden("You do not have permission to access this request")).build();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("requestId", request.id);
        response.put("status", request.status.name().toLowerCase());
        response.put("createdAt", request.createdAt);
        response.put("updatedAt", request.updatedAt);

        if (request.status == RequestStatus.COMPLETED) {
            response.put("message", "Your account has been permanently deleted.");
        } else if (request.status == RequestStatus.AWAITING_PURGE) {
            response.put("message",
                    "Your account has been deactivated and will be permanently deleted after the retention period (90 days).");
        } else if (request.status == RequestStatus.IN_PROGRESS) {
            response.put("message", "Your account deletion is being processed.");
        } else if (request.status == RequestStatus.FAILED) {
            response.put("message", "Account deletion failed. Please contact support.");
            response.put("error", request.errorMessage);
        }

        return Response.ok(response).build();
    }
}
