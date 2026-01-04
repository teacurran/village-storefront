package villagecompute.storefront.compliance.api.rest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

import org.jboss.logging.Logger;

import villagecompute.storefront.compliance.ComplianceService;
import villagecompute.storefront.compliance.api.types.ApprovePrivacyRequestRequest;
import villagecompute.storefront.compliance.api.types.MarketingConsentDto;
import villagecompute.storefront.compliance.api.types.PrivacyRequestDto;
import villagecompute.storefront.compliance.api.types.RecordConsentRequest;
import villagecompute.storefront.compliance.api.types.SubmitPrivacyRequestRequest;
import villagecompute.storefront.compliance.data.models.MarketingConsent;
import villagecompute.storefront.compliance.data.models.PrivacyRequest;
import villagecompute.storefront.compliance.data.models.PrivacyRequest.RequestStatus;
import villagecompute.storefront.compliance.data.models.PrivacyRequest.RequestType;
import villagecompute.storefront.compliance.data.repositories.MarketingConsentRepository;
import villagecompute.storefront.compliance.data.repositories.PrivacyRequestRepository;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.User;
import villagecompute.storefront.platformops.data.models.PlatformAdminRole;
import villagecompute.storefront.platformops.security.PlatformAdminAuthorizationService;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;

/**
 * Compliance REST resource for privacy requests and consent management.
 *
 * <p>
 * Provides endpoints for:
 * <ul>
 * <li>Submitting and approving privacy export/delete requests</li>
 * <li>Managing marketing consent records</li>
 * <li>Viewing compliance workflow status</li>
 * </ul>
 *
 * <p>
 * RBAC: Requires 'platform_admin' role for approval operations.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T6: Compliance automation</li>
 * <li>Architecture: 04_Operational_Architecture.md Section 3.15</li>
 * </ul>
 */
@Path("/api/v1/platform/compliance")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class ComplianceResource {

    private static final Logger LOG = Logger.getLogger(ComplianceResource.class);

    @Inject
    ComplianceService complianceService;

    @Inject
    PrivacyRequestRepository privacyRequestRepo;

    @Inject
    MarketingConsentRepository consentRepo;

    @Inject
    PlatformAdminAuthorizationService authorizationService;

    @Inject
    SecurityIdentity securityIdentity;

    /**
     * Submit a privacy export request.
     *
     * @param request
     *            export request details
     * @return privacy request ID
     */
    @POST
    @Path("/privacy-requests/export")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response submitExportRequest(SubmitPrivacyRequestRequest request) {
        authorizationService.requirePermissions(securityIdentity, PlatformAdminRole.PERMISSION_VIEW_STORES);

        LOG.infof("POST /compliance/privacy-requests/export - requester=%s, subject=%s", request.requesterEmail(),
                request.subjectEmail());

        UUID requestId = complianceService.submitExportRequest(request.requesterEmail(), request.subjectEmail(),
                request.reason(), request.ticketNumber());

        Map<String, Object> response = new HashMap<>();
        response.put("requestId", requestId);
        response.put("status", "pending_review");

        return Response.status(Response.Status.CREATED).entity(response).build();
    }

    /**
     * Submit a privacy delete request.
     *
     * @param request
     *            delete request details
     * @return privacy request ID
     */
    @POST
    @Path("/privacy-requests/delete")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response submitDeleteRequest(SubmitPrivacyRequestRequest request) {
        authorizationService.requirePermissions(securityIdentity, PlatformAdminRole.PERMISSION_VIEW_STORES);

        LOG.infof("POST /compliance/privacy-requests/delete - requester=%s, subject=%s", request.requesterEmail(),
                request.subjectEmail());

        UUID requestId = complianceService.submitDeleteRequest(request.requesterEmail(), request.subjectEmail(),
                request.reason(), request.ticketNumber());

        Map<String, Object> response = new HashMap<>();
        response.put("requestId", requestId);
        response.put("status", "pending_review");

        return Response.status(Response.Status.CREATED).entity(response).build();
    }

    /**
     * Approve a privacy request (export or delete).
     *
     * @param requestId
     *            privacy request ID
     * @param approvalRequest
     *            approval details
     * @return job ID
     */
    @POST
    @Path("/privacy-requests/{requestId}/approve")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response approvePrivacyRequest(@PathParam("requestId") UUID requestId,
            ApprovePrivacyRequestRequest approvalRequest) {

        authorizationService.requirePermissions(securityIdentity, PlatformAdminRole.PERMISSION_VIEW_STORES);

        LOG.infof("POST /compliance/privacy-requests/%s/approve - approver=%s", requestId,
                approvalRequest.approverEmail());

        PrivacyRequest request = PrivacyRequest.findById(requestId);
        if (request == null) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "Request not found")).build();
        }

        UUID jobId;
        if (request.requestType == RequestType.EXPORT) {
            jobId = complianceService.approveExportRequest(requestId, approvalRequest.approverEmail(),
                    approvalRequest.approvalNotes());
        } else {
            jobId = complianceService.approveDeleteRequest(requestId, approvalRequest.approverEmail(),
                    approvalRequest.approvalNotes());
        }

        Map<String, Object> response = new HashMap<>();
        response.put("jobId", jobId);
        response.put("status", "approved");

        return Response.ok(response).build();
    }

    /**
     * List privacy requests with optional filters.
     *
     * @param status
     *            filter by status
     * @param type
     *            filter by type (export/delete)
     * @return list of privacy requests
     */
    @GET
    @Path("/privacy-requests")
    public Response listPrivacyRequests(@QueryParam("status") String status, @QueryParam("type") String type) {

        authorizationService.requirePermissions(securityIdentity, PlatformAdminRole.PERMISSION_VIEW_AUDIT);

        LOG.infof("GET /compliance/privacy-requests - status=%s, type=%s", status, type);

        List<PrivacyRequest> requests;

        if (status != null) {
            RequestStatus requestStatus = RequestStatus.valueOf(status.toUpperCase());
            requests = privacyRequestRepo.findByStatus(requestStatus);
        } else if (type != null) {
            RequestType requestType = RequestType.valueOf(type.toUpperCase());
            requests = privacyRequestRepo.findByType(requestType);
        } else {
            requests = privacyRequestRepo.findByCurrentTenant();
        }

        List<PrivacyRequestDto> dtos = new ArrayList<>();
        for (PrivacyRequest req : requests) {
            dtos.add(mapToDto(req));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("requests", dtos);
        response.put("total", dtos.size());

        return Response.ok(response).build();
    }

    /**
     * Get privacy request by ID.
     *
     * @param requestId
     *            privacy request ID
     * @return request details
     */
    @GET
    @Path("/privacy-requests/{requestId}")
    public Response getPrivacyRequest(@PathParam("requestId") UUID requestId) {

        authorizationService.requirePermissions(securityIdentity, PlatformAdminRole.PERMISSION_VIEW_AUDIT);

        LOG.infof("GET /compliance/privacy-requests/%s", requestId);

        PrivacyRequest request = PrivacyRequest.findById(requestId);
        if (request == null) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "Request not found")).build();
        }

        return Response.ok(mapToDto(request)).build();
    }

    /**
     * Record marketing consent for a customer.
     *
     * @param consentRequest
     *            consent details
     * @return consent ID
     */
    @POST
    @Path("/marketing-consents")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response recordConsent(RecordConsentRequest consentRequest) {

        LOG.infof("POST /compliance/marketing-consents - customer=%s, channel=%s, consented=%s",
                consentRequest.customerId(), consentRequest.channel(), consentRequest.consented());

        UUID tenantId = TenantContext.getCurrentTenantId();
        User user = User.findById(consentRequest.customerId());
        if (user == null) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "User not found")).build();
        }

        if (!user.tenant.id.equals(tenantId)) {
            return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", "User not in tenant")).build();
        }

        MarketingConsent consent = new MarketingConsent();
        consent.tenant = Tenant.findById(tenantId);
        consent.user = user;
        consent.channel = consentRequest.channel();
        consent.consented = consentRequest.consented();
        consent.consentSource = consentRequest.consentSource();
        consent.consentMethod = consentRequest.consentMethod();
        consent.ipAddress = consentRequest.ipAddress();
        consent.userAgent = consentRequest.userAgent();
        consent.notes = consentRequest.notes();

        consentRepo.persist(consent);

        Map<String, Object> response = new HashMap<>();
        response.put("consentId", consent.id);

        return Response.status(Response.Status.CREATED).entity(response).build();
    }

    /**
     * Get consent history for a customer.
     *
     * @param customerId
     *            customer ID
     * @return list of consent records
     */
    @GET
    @Path("/marketing-consents/customer/{customerId}")
    public Response getCustomerConsents(@PathParam("customerId") UUID customerId) {

        LOG.infof("GET /compliance/marketing-consents/customer/%s", customerId);

        List<MarketingConsent> consents = consentRepo.findByCustomer(customerId);

        List<MarketingConsentDto> dtos = new ArrayList<>();
        for (MarketingConsent consent : consents) {
            dtos.add(new MarketingConsentDto(consent.id, consent.user.id, consent.channel, consent.consented,
                    consent.consentSource, consent.consentMethod, consent.consentedAt));
        }

        return Response.ok(Map.of("consents", dtos)).build();
    }

    /**
     * Get compliance metrics summary.
     *
     * @return metrics
     */
    @GET
    @Path("/metrics")
    public Response getMetrics() {

        authorizationService.requirePermissions(securityIdentity, PlatformAdminRole.PERMISSION_VIEW_AUDIT);

        LOG.info("GET /compliance/metrics");

        long pendingExports = privacyRequestRepo.count("requestType = ?1 AND status = ?2", RequestType.EXPORT,
                RequestStatus.PENDING_REVIEW);
        long pendingDeletes = privacyRequestRepo.count("requestType = ?1 AND status = ?2", RequestType.DELETE,
                RequestStatus.PENDING_REVIEW);
        long inProgress = privacyRequestRepo.countByStatus(RequestStatus.IN_PROGRESS);
        long completed = privacyRequestRepo.countByStatus(RequestStatus.COMPLETED);
        long failed = privacyRequestRepo.countByStatus(RequestStatus.FAILED);

        int exportQueueDepth = complianceService.getExportQueueDepth();
        int deleteQueueDepth = complianceService.getDeleteQueueDepth();

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("pendingExports", pendingExports);
        metrics.put("pendingDeletes", pendingDeletes);
        metrics.put("inProgress", inProgress);
        metrics.put("completed", completed);
        metrics.put("failed", failed);
        metrics.put("exportQueueDepth", exportQueueDepth);
        metrics.put("deleteQueueDepth", deleteQueueDepth);

        return Response.ok(metrics).build();
    }

    // --- Helper Methods ---

    private PrivacyRequestDto mapToDto(PrivacyRequest req) {
        return new PrivacyRequestDto(req.id, req.requestType.name(), req.status.name(), req.requesterEmail,
                req.subjectEmail, req.reason, req.ticketNumber, req.approvedByEmail, req.approvedAt, req.completedAt,
                req.resultUrl, req.errorMessage, req.createdAt, req.updatedAt);
    }
}
