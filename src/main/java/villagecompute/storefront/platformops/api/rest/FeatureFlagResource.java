package villagecompute.storefront.platformops.api.rest;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import villagecompute.storefront.data.models.FeatureFlag;
import villagecompute.storefront.platformops.api.types.FeatureFlagDto;
import villagecompute.storefront.platformops.api.types.StaleFlagReport;
import villagecompute.storefront.platformops.api.types.UpdateFeatureFlagRequest;
import villagecompute.storefront.platformops.data.models.PlatformAdminRole;
import villagecompute.storefront.platformops.data.models.PlatformCommand;
import villagecompute.storefront.platformops.security.PlatformAdminAuthorizationService;
import villagecompute.storefront.platformops.security.PlatformAdminAuthorizationService.PlatformAdminPrincipal;
import villagecompute.storefront.services.FeatureToggle;

import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.json.JsonObject;

/**
 * Platform admin resource for feature flag governance and management.
 *
 * <p>
 * Provides endpoints for:
 * <ul>
 * <li>Listing all feature flags with governance metadata</li>
 * <li>Updating flag state and governance fields</li>
 * <li>Detecting stale flags (expired or past review date)</li>
 * <li>Viewing flag change history</li>
 * </ul>
 *
 * <p>
 * All operations require {@code PERMISSION_MANAGE_FEATURE_FLAGS} and create audit records via {@link PlatformCommand}.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T7: Feature flag governance + release process</li>
 * <li>Architecture: 05_Rationale_and_Future.md Section 4.1.12</li>
 * <li>UI: 06_UI_UX_Architecture.md Section 4.9 (Feature Flag Exposure)</li>
 * </ul>
 */
@Path("/api/v1/platform/feature-flags")
@RequestScoped
@RolesAllowed(PlatformAdminRole.PERMISSION_MANAGE_FEATURE_FLAGS)
public class FeatureFlagResource {

    private static final Logger LOG = Logger.getLogger(FeatureFlagResource.class.getName());

    @Inject
    EntityManager entityManager;

    @Inject
    FeatureToggle featureToggle;

    @Inject
    PlatformAdminAuthorizationService authorizationService;

    @Inject
    SecurityIdentity securityIdentity;

    /**
     * List all feature flags with optional filtering.
     *
     * @param tenantId
     *            filter by tenant (null for global flags)
     * @param staleOnly
     *            only return stale flags
     * @return list of feature flag DTOs
     */
    @GET
    public Response listFlags(@QueryParam("tenant_id") UUID tenantId, @QueryParam("stale_only") boolean staleOnly) {
        requireFeatureFlagPermission();

        OffsetDateTime now = OffsetDateTime.now();
        String query = (tenantId != null)
                ? "SELECT ff FROM FeatureFlag ff WHERE ff.tenant.id = :tenantId ORDER BY ff.flagKey"
                : "SELECT ff FROM FeatureFlag ff WHERE ff.tenant IS NULL ORDER BY ff.flagKey";

        var typedQuery = entityManager.createQuery(query, FeatureFlag.class);
        if (tenantId != null) {
            typedQuery.setParameter("tenantId", tenantId);
        }

        List<FeatureFlag> flags = typedQuery.getResultList();
        if (staleOnly) {
            flags = flags.stream().filter(flag -> isStale(flag, now)).collect(Collectors.toList());
        }

        List<FeatureFlagDto> dtos = flags.stream().map(flag -> toDto(flag, now)).collect(Collectors.toList());
        return Response.ok(dtos).build();
    }

    /**
     * Get single feature flag by ID.
     *
     * @param id
     *            flag UUID
     * @return feature flag DTO
     */
    @GET
    @Path("/{id}")
    public Response getFlag(@PathParam("id") UUID id) {
        requireFeatureFlagPermission();

        FeatureFlag flag = entityManager.find(FeatureFlag.class, id);
        if (flag == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("{\"error\": \"Flag not found\"}").build();
        }
        return Response.ok(toDto(flag, OffsetDateTime.now())).build();
    }

    /**
     * Get stale flag report for dashboards and alerts.
     *
     * @return report with counts and flag details
     */
    @GET
    @Path("/stale-report")
    public Response getStaleFlagReport() {
        requireFeatureFlagPermission();

        OffsetDateTime now = OffsetDateTime.now();

        List<FeatureFlag> allFlags = entityManager.createQuery("SELECT ff FROM FeatureFlag ff", FeatureFlag.class)
                .getResultList();

        List<FeatureFlag> expired = allFlags.stream().filter(flag -> isExpired(flag, now)).collect(Collectors.toList());
        List<FeatureFlag> needsReview = allFlags.stream().filter(flag -> isReviewOverdue(flag, now))
                .collect(Collectors.toList());

        StaleFlagReport report = new StaleFlagReport();
        report.expiredCount = expired.size();
        report.expiredFlags = expired.stream().map(flag -> toDto(flag, now)).collect(Collectors.toList());
        report.needsReviewCount = needsReview.size();
        report.needsReviewFlags = needsReview.stream().map(flag -> toDto(flag, now)).collect(Collectors.toList());
        report.generatedAt = now;

        return Response.ok(report).build();
    }

    /**
     * Update feature flag state and governance metadata.
     *
     * @param id
     *            flag UUID
     * @param request
     *            update payload
     * @return updated flag DTO
     */
    @PUT
    @Path("/{id}")
    @Transactional
    public Response updateFlag(@PathParam("id") UUID id, UpdateFeatureFlagRequest request) {
        PlatformAdminPrincipal actor = requireFeatureFlagPermission();

        FeatureFlag flag = entityManager.find(FeatureFlag.class, id);
        if (flag == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("{\"error\": \"Flag not found\"}").build();
        }

        boolean changed = false;
        StringBuilder changeLog = new StringBuilder();

        if (request.enabled != null && !request.enabled.equals(flag.enabled)) {
            changeLog.append("enabled: ").append(flag.enabled).append(" → ").append(request.enabled).append("; ");
            flag.enabled = request.enabled;
            changed = true;
        }

        if (request.owner != null && !request.owner.equals(flag.owner)) {
            changeLog.append("owner: ").append(flag.owner).append(" → ").append(request.owner).append("; ");
            flag.owner = request.owner;
            changed = true;
        }

        if (request.riskLevel != null && !request.riskLevel.equals(flag.riskLevel)) {
            changeLog.append("riskLevel: ").append(flag.riskLevel).append(" → ").append(request.riskLevel).append("; ");
            flag.riskLevel = request.riskLevel;
            changed = true;
        }

        if (request.reviewCadenceDays != null && !request.reviewCadenceDays.equals(flag.reviewCadenceDays)) {
            changeLog.append("reviewCadenceDays: ").append(flag.reviewCadenceDays).append(" → ")
                    .append(request.reviewCadenceDays).append("; ");
            flag.reviewCadenceDays = request.reviewCadenceDays;
            changed = true;
        }

        if (request.expiryDate != null && !request.expiryDate.equals(flag.expiryDate)) {
            changeLog.append("expiryDate: ").append(flag.expiryDate).append(" → ").append(request.expiryDate)
                    .append("; ");
            flag.expiryDate = request.expiryDate;
            changed = true;
        }

        if (request.description != null && !request.description.equals(flag.description)) {
            changeLog.append("description updated; ");
            flag.description = request.description;
            changed = true;
        }

        if (request.rollbackInstructions != null && !request.rollbackInstructions.equals(flag.rollbackInstructions)) {
            changeLog.append("rollbackInstructions updated; ");
            flag.rollbackInstructions = request.rollbackInstructions;
            changed = true;
        }

        if (request.markReviewed != null && request.markReviewed) {
            flag.lastReviewedAt = OffsetDateTime.now();
            changeLog.append("reviewed at ").append(flag.lastReviewedAt).append("; ");
            changed = true;
        }

        if (changed) {
            if (request.reason == null || request.reason.isBlank()) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Reason is required when updating a feature flag");
                return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
            }

            flag.updatedAt = OffsetDateTime.now();
            entityManager.persist(flag);

            invalidateFlagCache(flag);

            JsonObject metadata = new JsonObject().put("changes", changeLog.toString().trim());
            recordAudit(actor, "update_feature_flag", flag, request.reason, metadata);

            LOG.log(Level.INFO, "Updated feature flag {0}: {1}",
                    new Object[]{flag.flagKey, changeLog.toString().trim()});
        }

        return Response.ok(toDto(flag, OffsetDateTime.now())).build();
    }

    /**
     * Delete feature flag (soft delete - marks as disabled).
     *
     * @param id
     *            flag UUID
     * @return no content
     */
    @DELETE
    @Path("/{id}")
    @Transactional
    public Response deleteFlag(@PathParam("id") UUID id) {
        PlatformAdminPrincipal actor = requireFeatureFlagPermission();

        FeatureFlag flag = entityManager.find(FeatureFlag.class, id);
        if (flag == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("{\"error\": \"Flag not found\"}").build();
        }

        flag.enabled = false;
        flag.updatedAt = OffsetDateTime.now();
        entityManager.persist(flag);

        invalidateFlagCache(flag);

        JsonObject metadata = new JsonObject().put("flagKey", flag.flagKey);
        recordAudit(actor, "delete_feature_flag", flag, "Flag disabled via delete endpoint", metadata);

        LOG.log(Level.INFO, "Deleted feature flag {0}", flag.flagKey);

        return Response.noContent().build();
    }

    /**
     * Get change history for a flag.
     *
     * @param id
     *            flag UUID
     * @return list of audit log entries
     */
    @GET
    @Path("/{id}/history")
    public Response getFlagHistory(@PathParam("id") UUID id) {
        requireFeatureFlagPermission();

        List<PlatformCommand> history = entityManager.createQuery(
                "SELECT pc FROM PlatformCommand pc WHERE pc.targetType = 'feature_flag' AND pc.targetId = :id ORDER BY pc.occurredAt DESC",
                PlatformCommand.class).setParameter("id", id).getResultList();

        return Response.ok(history).build();
    }

    /**
     * Convert entity to DTO.
     */
    private FeatureFlagDto toDto(FeatureFlag flag, OffsetDateTime referenceTime) {
        FeatureFlagDto dto = new FeatureFlagDto();
        dto.id = flag.id;
        dto.tenantId = flag.tenant != null ? flag.tenant.id : null;
        dto.flagKey = flag.flagKey;
        dto.enabled = flag.enabled;
        dto.config = flag.config;
        dto.owner = flag.owner;
        dto.riskLevel = flag.riskLevel;
        dto.reviewCadenceDays = flag.reviewCadenceDays;
        dto.expiryDate = flag.expiryDate;
        dto.lastReviewedAt = flag.lastReviewedAt;
        dto.description = flag.description;
        dto.rollbackInstructions = flag.rollbackInstructions;
        dto.createdAt = flag.createdAt;
        dto.updatedAt = flag.updatedAt;

        String staleReason = computeStaleReason(flag, referenceTime);
        if (staleReason != null) {
            dto.stale = true;
            dto.staleReason = staleReason;
        } else {
            dto.stale = false;
        }

        return dto;
    }

    private String computeStaleReason(FeatureFlag flag, OffsetDateTime now) {
        if (isExpired(flag, now)) {
            return "Expired on " + flag.expiryDate;
        }
        if (isReviewOverdue(flag, now)) {
            OffsetDateTime nextReview = flag.lastReviewedAt != null
                    ? flag.lastReviewedAt.plusDays(flag.reviewCadenceDays)
                    : null;
            return nextReview != null ? "Review overdue since " + nextReview : "Review overdue";
        }
        return null;
    }

    private boolean isExpired(FeatureFlag flag, OffsetDateTime now) {
        return flag.expiryDate != null && flag.expiryDate.isBefore(now);
    }

    private boolean isReviewOverdue(FeatureFlag flag, OffsetDateTime now) {
        if (flag.reviewCadenceDays == null) {
            return false;
        }

        if (flag.lastReviewedAt == null) {
            // Never reviewed since creation - treat as stale once cadence passed from creation date
            return flag.createdAt != null && flag.createdAt.plusDays(flag.reviewCadenceDays).isBefore(now);
        }

        return flag.lastReviewedAt.plusDays(flag.reviewCadenceDays).isBefore(now);
    }

    private boolean isStale(FeatureFlag flag, OffsetDateTime now) {
        return isExpired(flag, now) || isReviewOverdue(flag, now);
    }

    private void invalidateFlagCache(FeatureFlag flag) {
        if (flag.tenant != null) {
            featureToggle.invalidate(flag.tenant.id, flag.flagKey);
        } else {
            featureToggle.invalidateAll();
        }
    }

    private PlatformAdminPrincipal requireFeatureFlagPermission() {
        return authorizationService.requirePermissions(securityIdentity,
                PlatformAdminRole.PERMISSION_MANAGE_FEATURE_FLAGS);
    }

    private void recordAudit(PlatformAdminPrincipal actor, String action, FeatureFlag flag, String reason,
            JsonObject metadata) {
        PlatformCommand audit = new PlatformCommand();
        audit.actorType = "platform_admin";
        audit.actorId = actor.id();
        audit.actorEmail = actor.email();
        audit.action = action;
        audit.targetType = "feature_flag";
        audit.targetId = flag.id;
        audit.reason = reason;
        audit.metadata = metadata != null ? metadata.encode() : null;
        entityManager.persist(audit);
    }
}
