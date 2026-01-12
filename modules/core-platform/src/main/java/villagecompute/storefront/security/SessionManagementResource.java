package villagecompute.storefront.security;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import villagecompute.storefront.data.models.User;
import villagecompute.storefront.data.repositories.UserRepository;
import villagecompute.storefront.platformops.data.models.ImpersonationSession;
import villagecompute.storefront.platformops.data.repositories.ImpersonationSessionRepository;
import villagecompute.storefront.security.sessions.SessionLogEntry;
import villagecompute.storefront.security.sessions.SessionLogRepository;
import villagecompute.storefront.services.AuditLogService;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;

/**
 * REST resource for session management (customer-facing).
 */
@Path("/api/v1/sessions")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class SessionManagementResource {

    private static final Logger LOG = Logger.getLogger(SessionManagementResource.class);
    private static final int MAX_SESSION_RESULTS = 25;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    ImpersonationSessionRepository impersonationSessionRepo;

    @Inject
    SessionLogRepository sessionLogRepository;

    @Inject
    UserRepository userRepository;

    @Inject
    AuditLogService auditLogService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    @CacheName("revoked-tokens")
    Cache revokedTokensCache;

    @GET
    public Response listActiveSessions(@Context HttpHeaders headers) {
        if (!TenantContext.hasContext()) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ProblemDetail.forStatus(500)
                    .withTitle("Internal Error").withDetail("Tenant context not available").build()).build();
        }

        String userEmail = securityIdentity.getPrincipal().getName();
        UUID tenantId = TenantContext.getCurrentTenantId();

        LOG.infof("GET /sessions - tenantId=%s, user=%s", tenantId, userEmail);

        User user = userRepository.findByTenantAndEmail(tenantId, userEmail);
        if (user == null) {
            LOG.warnf("No user found for email=%s, tenantId=%s. Returning empty session list.", userEmail, tenantId);
            return Response.ok(buildEmptySessionResponse()).build();
        }

        List<Map<String, Object>> sessions = new ArrayList<>(buildHotSessions(tenantId, user, headers));
        sessions.addAll(buildImpersonationSessions(userEmail));

        Map<String, Object> response = new HashMap<>();
        response.put("sessions", sessions);
        response.put("total", sessions.size());
        response.put("message",
                "Active sessions from the last 7 days plus any live impersonation sessions. Revoke unrecognized devices immediately.");

        return Response.ok(response).build();
    }

    @DELETE
    @Path("/{sessionId}")
    public Response revokeSession(@PathParam("sessionId") String sessionId) {
        if (!TenantContext.hasContext()) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ProblemDetail.forStatus(500)
                    .withTitle("Internal Error").withDetail("Tenant context not available").build()).build();
        }

        String userEmail = securityIdentity.getPrincipal().getName();
        UUID tenantId = TenantContext.getCurrentTenantId();
        User user = userRepository.findByTenantAndEmail(tenantId, userEmail);

        LOG.infof("DELETE /sessions/%s - tenantId=%s, user=%s", sessionId, tenantId, userEmail);

        if (user != null) {
            try {
                UUID sessionUuid = UUID.fromString(sessionId);
                SessionLogEntry entry = sessionLogRepository.findByTenantAndId(tenantId, user.id, sessionUuid);
                if (entry != null) {
                    markSessionRevoked(entry, "revoked_by_user");
                    recordAudit("session_revoked", "SessionLogEntry", entry.id, Map.of("source", "self_service"));
                    addTokenToRevocationCache(sessionId);
                    return Response.ok(buildRevocationResponse(sessionId)).build();
                }
            } catch (IllegalArgumentException ignored) {
                // continue to impersonation/JWT handling
            }
        }

        Optional<Response> impersonationResponse = revokeImpersonationSession(sessionId, userEmail);
        if (impersonationResponse.isPresent()) {
            return impersonationResponse.get();
        }

        addTokenToRevocationCache(sessionId);
        LOG.infof("Session %s added to revocation blacklist by %s", sessionId, userEmail);
        return Response.ok(buildRevocationResponse(sessionId)).build();
    }

    @POST
    @Path("/{sessionId}/report")
    public Response reportSuspiciousSession(@PathParam("sessionId") String sessionId,
            Map<String, String> reportDetails) {
        if (!TenantContext.hasContext()) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ProblemDetail.forStatus(500)
                    .withTitle("Internal Error").withDetail("Tenant context not available").build()).build();
        }

        String userEmail = securityIdentity.getPrincipal().getName();
        String description = reportDetails != null
                ? reportDetails.getOrDefault("description", "No description provided")
                : "No description provided";

        LOG.warnf("Suspicious session reported - tenantId=%s, user=%s, session=%s", TenantContext.getCurrentTenantId(),
                userEmail, sessionId);

        UUID sessionUuid = null;
        try {
            sessionUuid = UUID.fromString(sessionId);
        } catch (IllegalArgumentException ignored) {
        }

        recordAudit("session_reported", "SessionLogEntry", sessionUuid,
                Map.of("description", description, "reporter", userEmail));

        Map<String, Object> response = new HashMap<>();
        response.put("message",
                "Thank you for reporting this session. Our security team will investigate and contact you if needed.");
        response.put("sessionId", sessionId);
        response.put("reportId", UUID.randomUUID().toString());

        return Response.status(Response.Status.CREATED).entity(response).build();
    }

    @POST
    @Path("/revoke-all")
    public Response revokeAllOtherSessions() {
        if (!TenantContext.hasContext()) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ProblemDetail.forStatus(500)
                    .withTitle("Internal Error").withDetail("Tenant context not available").build()).build();
        }

        String userEmail = securityIdentity.getPrincipal().getName();
        UUID tenantId = TenantContext.getCurrentTenantId();

        LOG.infof("POST /sessions/revoke-all - tenantId=%s, user=%s", tenantId, userEmail);

        User user = userRepository.findByTenantAndEmail(tenantId, userEmail);
        if (user == null) {
            return Response.status(Response.Status.NOT_FOUND).entity(ProblemDetail.forStatus(404).withTitle("Not Found")
                    .withDetail("User not found for session revocation").build()).build();
        }

        List<SessionLogEntry> hotSessions = sessionLogRepository.findRecentSessionsForUser(tenantId, user.id,
                OffsetDateTime.now().minusDays(90), MAX_SESSION_RESULTS);
        int revokedCount = 0;
        boolean skippedMostRecent = false;

        for (SessionLogEntry entry : hotSessions) {
            if (!skippedMostRecent) {
                skippedMostRecent = true; // assume the first entry (latest activity) represents current session
                continue;
            }
            markSessionRevoked(entry, "bulk_revoke");
            addTokenToRevocationCache(entry.id.toString());
            revokedCount++;
        }

        List<ImpersonationSession> impersonationSessions = impersonationSessionRepo.findActiveByTargetEmail(userEmail);
        for (ImpersonationSession impSession : impersonationSessions) {
            impSession.endedAt = OffsetDateTime.now();
            impSession.persist();
            recordAudit("impersonation_revoked", "ImpersonationSession", impSession.id,
                    Map.of("adminEmail", impSession.platformAdminEmail));
            revokedCount++;
        }

        Map<String, Object> response = new HashMap<>();
        response.put("message", "All other sessions have been revoked. You will remain logged in on this device.");
        response.put("sessionsRevoked", revokedCount);
        return Response.ok(response).build();
    }

    private List<Map<String, Object>> buildHotSessions(UUID tenantId, User user, HttpHeaders headers) {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(7);
        List<SessionLogEntry> entries = sessionLogRepository.findRecentSessionsForUser(tenantId, user.id, cutoff,
                MAX_SESSION_RESULTS);

        List<Map<String, Object>> sessions = new ArrayList<>();
        for (SessionLogEntry entry : entries) {
            Map<String, Object> sessionInfo = new HashMap<>();
            sessionInfo.put("sessionId", entry.id.toString());
            sessionInfo.put("deviceName", parseDeviceName(entry.userAgent));
            sessionInfo.put("browser", parseBrowser(entry.userAgent));
            sessionInfo.put("ipAddress", maskIp(entry.ipAddress));
            sessionInfo.put("approximateLocation", deriveLocation(entry.ipAddress));
            sessionInfo.put("lastActivityAt", entry.lastActivityAt != null ? entry.lastActivityAt : entry.loginAt);
            sessionInfo.put("impersonated", entry.impersonationContext != null);
            sessionInfo.put("impersonatedBy", resolveImpersonator(entry.impersonationContext));
            sessionInfo.put("isCurrentSession", isCurrentSession(entry, headers));
            sessions.add(sessionInfo);
        }
        return sessions;
    }

    private List<Map<String, Object>> buildImpersonationSessions(String targetEmail) {
        List<Map<String, Object>> sessions = new ArrayList<>();
        List<ImpersonationSession> impersonationSessions = impersonationSessionRepo
                .findActiveByTargetEmail(targetEmail);
        for (ImpersonationSession session : impersonationSessions) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("sessionId", session.id.toString());
            payload.put("deviceName", "Platform Admin");
            payload.put("browser", "Admin Console");
            payload.put("ipAddress", "masked");
            payload.put("approximateLocation", "Unknown");
            payload.put("lastActivityAt", session.endedAt != null ? session.endedAt : session.startedAt);
            payload.put("isCurrentSession", false);
            payload.put("impersonated", true);
            payload.put("impersonatedBy", session.platformAdminEmail);
            sessions.add(payload);
        }
        return sessions;
    }

    private Optional<Response> revokeImpersonationSession(String sessionId, String userEmail) {
        try {
            UUID sessionUuid = UUID.fromString(sessionId);
            ImpersonationSession impersonationSession = ImpersonationSession.findById(sessionUuid);
            if (impersonationSession != null && userEmail.equals(impersonationSession.targetUserEmail)) {
                impersonationSession.endedAt = OffsetDateTime.now();
                impersonationSession.persist();
                recordAudit("impersonation_revoked", "ImpersonationSession", impersonationSession.id,
                        Map.of("adminEmail", impersonationSession.platformAdminEmail));
                return Optional.of(Response.ok(buildRevocationResponse(sessionId)).build());
            }
        } catch (IllegalArgumentException ignored) {
        }
        return Optional.empty();
    }

    private void markSessionRevoked(SessionLogEntry entry, String reason) {
        entry.logoutReason = reason;
        entry.lastActivityAt = OffsetDateTime.now();
        entry.persist();
        LOG.infof("Session %s marked revoked (%s)", entry.id, reason);
    }

    private void recordAudit(String action, String entityType, UUID entityId, Map<String, Object> metadata) {
        auditLogService.recordSecurityAction(action, entityType, entityId, metadata);
    }

    private Map<String, Object> buildEmptySessionResponse() {
        Map<String, Object> response = new HashMap<>();
        response.put("sessions", List.of());
        response.put("total", 0);
        response.put("message", "No active sessions found.");
        return response;
    }

    private Map<String, Object> buildRevocationResponse(String sessionId) {
        Map<String, Object> response = new HashMap<>();
        response.put("message", "Session revoked successfully. The device will be logged out on its next request.");
        response.put("sessionId", sessionId);
        return response;
    }

    private void addTokenToRevocationCache(String sessionId) {
        revokedTokensCache.as(io.quarkus.cache.CaffeineCache.class).put(sessionId,
                CompletableFuture.completedFuture(Boolean.TRUE));
    }

    private boolean isCurrentSession(SessionLogEntry entry, HttpHeaders headers) {
        String currentUserAgent = headers != null ? headers.getHeaderString("User-Agent") : null;
        return currentUserAgent != null && currentUserAgent.equals(entry.userAgent) && entry.logoutReason == null;
    }

    private String deriveLocation(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return "Unknown";
        }
        return "IP " + maskIp(ipAddress);
    }

    private String maskIp(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return "masked";
        }
        if (ipAddress.contains(".")) {
            String[] parts = ipAddress.split("\\.");
            if (parts.length == 4) {
                return parts[0] + "." + parts[1] + ".*.*";
            }
        }
        if (ipAddress.contains(":")) {
            return ipAddress.substring(0, ipAddress.indexOf(':')) + "::/64";
        }
        return "masked";
    }

    private String parseDeviceName(String userAgent) {
        if (userAgent == null) {
            return "Unknown Device";
        }
        if (userAgent.contains("iPhone")) {
            return "iPhone";
        } else if (userAgent.contains("iPad")) {
            return "iPad";
        } else if (userAgent.contains("Android")) {
            return "Android Device";
        } else if (userAgent.contains("Mac")) {
            return "Mac";
        } else if (userAgent.contains("Windows")) {
            return "Windows PC";
        } else if (userAgent.contains("Linux")) {
            return "Linux";
        }
        return "Unknown Device";
    }

    private String parseBrowser(String userAgent) {
        if (userAgent == null) {
            return "Unknown Browser";
        }
        if (userAgent.contains("Chrome") && !userAgent.contains("Edg")) {
            return "Chrome";
        } else if (userAgent.contains("Safari") && !userAgent.contains("Chrome")) {
            return "Safari";
        } else if (userAgent.contains("Firefox")) {
            return "Firefox";
        } else if (userAgent.contains("Edg")) {
            return "Edge";
        }
        return "Unknown Browser";
    }

    private String resolveImpersonator(String impersonationContext) {
        if (impersonationContext == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(impersonationContext);
            if (node.has("adminEmail")) {
                return node.get("adminEmail").asText();
            }
        } catch (Exception e) {
            LOG.debug("Unable to parse impersonation context", e);
        }
        return null;
    }
}
