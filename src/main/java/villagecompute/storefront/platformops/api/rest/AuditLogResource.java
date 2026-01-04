package villagecompute.storefront.platformops.api.rest;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;

import villagecompute.storefront.platformops.api.types.AuditLogEntry;
import villagecompute.storefront.platformops.data.models.PlatformAdminRole;
import villagecompute.storefront.platformops.data.models.PlatformCommand;
import villagecompute.storefront.platformops.data.repositories.PlatformCommandRepository;
import villagecompute.storefront.platformops.security.PlatformAdminAuthorizationService;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;

/**
 * Audit log viewer REST resource.
 *
 * <p>
 * Provides endpoints for querying platform command audit logs with filtering and pagination.
 *
 * <p>
 * Endpoints:
 * <ul>
 * <li>GET /api/v1/platform/audit - Query audit logs with filters</li>
 * </ul>
 *
 * <p>
 * RBAC: Requires 'platform_admin' role + 'view_audit' permission.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T2: Platform admin console (audit log viewer)</li>
 * <li>Architecture: 04_Operational_Architecture.md Section 3.7</li>
 * </ul>
 */
@Path("/api/v1/platform/audit")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class AuditLogResource {

    private static final Logger LOG = Logger.getLogger(AuditLogResource.class);

    @Inject
    PlatformCommandRepository platformCommandRepo;

    @Inject
    PlatformAdminAuthorizationService authorizationService;

    @Inject
    SecurityIdentity securityIdentity;

    /**
     * Query audit logs with filters and pagination.
     *
     * <p>
     * Supports filtering by:
     * <ul>
     * <li>actorId - Platform admin who performed the action</li>
     * <li>action - Action type (e.g., 'impersonate_start')</li>
     * <li>targetType - Target entity type (e.g., 'tenant')</li>
     * <li>startDate - Date range start (ISO-8601)</li>
     * <li>endDate - Date range end (ISO-8601)</li>
     * </ul>
     *
     * @param actorId
     *            optional actor filter
     * @param action
     *            optional action filter
     * @param targetType
     *            optional target type filter
     * @param startDate
     *            optional date range start (ISO-8601 format)
     * @param endDate
     *            optional date range end (ISO-8601 format)
     * @param page
     *            page number (default 0)
     * @param size
     *            page size (default 50, max 500)
     * @return paginated audit log entries
     */
    @GET
    public Response queryAuditLogs(@QueryParam("actorId") UUID actorId, @QueryParam("action") String action,
            @QueryParam("targetType") String targetType, @QueryParam("startDate") String startDate,
            @QueryParam("endDate") String endDate, @QueryParam("page") Integer page, @QueryParam("size") Integer size) {

        authorizationService.requirePermissions(securityIdentity, PlatformAdminRole.PERMISSION_VIEW_AUDIT);

        int pageNum = page != null ? Math.max(page, 0) : 0;
        int pageSize = size != null ? Math.min(Math.max(size, 1), 500) : 50; // Cap at 500

        LOG.infof("GET /platform/audit - actor=%s, action=%s, targetType=%s, page=%d, size=%d", actorId, action,
                targetType, pageNum, pageSize);

        OffsetDateTime start = parseDate(startDate);
        OffsetDateTime end = parseDate(endDate);
        if (startDate != null && start == null) {
            return badRequest("Invalid startDate format");
        }
        if (endDate != null && end == null) {
            return badRequest("Invalid endDate format");
        }

        // Query with filters
        List<PlatformCommand> commands = platformCommandRepo.findWithFilters(actorId, action, targetType, start, end,
                pageNum, pageSize);

        long totalCount = platformCommandRepo.countWithFilters(actorId, action, targetType, start, end);

        // Map to DTOs
        List<AuditLogEntry> entries = new ArrayList<>();
        for (PlatformCommand cmd : commands) {
            entries.add(mapToEntry(cmd));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("entries", entries);
        response.put("page", pageNum);
        response.put("size", pageSize);
        response.put("totalCount", totalCount);

        LOG.infof("Retrieved %d audit log entries (total: %d)", entries.size(), totalCount);

        return Response.ok(response).build();
    }

    /**
     * Get audit log statistics summary.
     *
     * @param days
     *            number of days to look back (default 7)
     * @return statistics summary
     */
    @GET
    @Path("/stats")
    public Response getAuditStats(@QueryParam("days") Integer days) {
        int lookbackDays = days != null ? days : 7;

        LOG.infof("GET /platform/audit/stats - days=%d", lookbackDays);

        authorizationService.requirePermissions(securityIdentity, PlatformAdminRole.PERMISSION_VIEW_AUDIT);

        List<PlatformCommand> recentCommands = platformCommandRepo.findRecent(lookbackDays, 0, 1000);

        // Calculate stats
        Map<String, Integer> actionCounts = new HashMap<>();
        Map<String, Integer> actorCounts = new HashMap<>();

        for (PlatformCommand cmd : recentCommands) {
            actionCounts.put(cmd.action, actionCounts.getOrDefault(cmd.action, 0) + 1);
            if (cmd.actorEmail != null) {
                actorCounts.put(cmd.actorEmail, actorCounts.getOrDefault(cmd.actorEmail, 0) + 1);
            }
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalCommands", recentCommands.size());
        stats.put("actionBreakdown", actionCounts);
        stats.put("topActors", actorCounts);
        stats.put("lookbackDays", lookbackDays);

        return Response.ok(stats).build();
    }

    // --- Helper Methods ---

    private AuditLogEntry mapToEntry(PlatformCommand cmd) {
        String ipAddress = cmd.ipAddress != null ? cmd.ipAddress.getHostAddress() : null;

        return new AuditLogEntry(cmd.id, cmd.actorType, cmd.actorId, cmd.actorEmail, cmd.action, cmd.targetType,
                cmd.targetId, cmd.reason, cmd.ticketNumber, cmd.occurredAt, ipAddress);
    }

    private OffsetDateTime parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (Exception ex) {
            LOG.warnf("Invalid date value for audit filter: %s", value);
            return null;
        }
    }

    private Response badRequest(String message) {
        Map<String, String> error = new HashMap<>();
        error.put("error", message);
        return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
    }
}
