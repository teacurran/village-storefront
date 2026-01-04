package villagecompute.storefront.platformops.services;

import java.net.InetAddress;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.User;
import villagecompute.storefront.platformops.api.types.ImpersonationContext;
import villagecompute.storefront.platformops.data.models.ImpersonationSession;
import villagecompute.storefront.platformops.data.models.PlatformCommand;
import villagecompute.storefront.platformops.data.repositories.ImpersonationSessionRepository;

import io.vertx.core.json.JsonObject;

/**
 * Impersonation service for platform admin impersonation lifecycle.
 *
 * <p>
 * Manages starting, stopping, and querying impersonation sessions with full audit trail. All impersonation operations
 * require:
 * <ul>
 * <li>Valid reason (min 10 characters)</li>
 * <li>Platform admin credentials with 'impersonate' permission</li>
 * <li>Audit logging to platform_commands and impersonation_sessions</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T2: Platform admin console (impersonation control)</li>
 * <li>Architecture: 04_Operational_Architecture.md Section 3.8.1</li>
 * <li>Rationale: 05_Rationale_and_Future.md Section 4.3.7</li>
 * </ul>
 */
@ApplicationScoped
public class ImpersonationService {

    private static final Logger LOG = Logger.getLogger(ImpersonationService.class);

    @Inject
    ImpersonationSessionRepository impersonationSessionRepo;

    /**
     * Start impersonation session.
     *
     * @param platformAdminId
     *            platform admin user ID
     * @param platformAdminEmail
     *            platform admin email
     * @param targetTenantId
     *            tenant to impersonate
     * @param targetUserId
     *            user to impersonate (null = tenant admin mode)
     * @param reason
     *            justification (min 10 chars)
     * @param ticketNumber
     *            optional support ticket reference
     * @param ipAddress
     *            source IP address
     * @param userAgent
     *            client user agent
     * @return impersonation context
     */
    @Transactional
    public ImpersonationContext startImpersonation(UUID platformAdminId, String platformAdminEmail, UUID targetTenantId,
            UUID targetUserId, String reason, String ticketNumber, InetAddress ipAddress, String userAgent) {

        // Validation
        if (reason == null || reason.trim().length() < 10) {
            throw new IllegalArgumentException("Impersonation reason must be at least 10 characters");
        }
        if (ticketNumber == null || ticketNumber.trim().isEmpty()) {
            throw new IllegalArgumentException("Support ticket number is required for impersonation");
        }
        String sanitizedTicket = ticketNumber.trim();

        // Check for existing active session
        Optional<ImpersonationSession> existingSession = impersonationSessionRepo.findCurrentSession(platformAdminId);
        if (existingSession.isPresent()) {
            throw new IllegalStateException(
                    "Platform admin already has an active impersonation session. End current session first.");
        }

        // Verify tenant exists
        Tenant targetTenant = Tenant.findById(targetTenantId);
        if (targetTenant == null) {
            throw new IllegalArgumentException("Target tenant not found: " + targetTenantId);
        }

        // Verify target user if specified
        String targetUserEmail = null;
        if (targetUserId != null) {
            User targetUser = User.findById(targetUserId);
            if (targetUser == null || !targetUser.tenant.id.equals(targetTenantId)) {
                throw new IllegalArgumentException("Target user not found or does not belong to target tenant");
            }
            targetUserEmail = targetUser.email;
        }

        // Log start command
        PlatformCommand startCommand = new PlatformCommand();
        startCommand.actorType = "platform_admin";
        startCommand.actorId = platformAdminId;
        startCommand.actorEmail = platformAdminEmail;
        startCommand.action = "impersonate_start";
        startCommand.targetType = "tenant";
        startCommand.targetId = targetTenantId;
        startCommand.reason = reason;
        startCommand.ticketNumber = sanitizedTicket;
        startCommand.ipAddress = ipAddress;
        startCommand.userAgent = userAgent;
        startCommand.occurredAt = OffsetDateTime.now();
        startCommand.impersonationContext = String.format(
                "{\"session_id\":\"%s\",\"target_tenant_id\":\"%s\",\"target_user_id\":\"%s\"}",
                java.util.UUID.randomUUID(), targetTenantId, targetUserId);
        JsonObject metadata = new JsonObject()
                .put("target_user_id", targetUserId != null ? targetUserId.toString() : null)
                .put("target_user_email", targetUserEmail).put("reason", reason);
        startCommand.metadata = metadata.encode();
        startCommand.persist();

        // Create impersonation session
        ImpersonationSession session = new ImpersonationSession();
        session.platformAdminId = platformAdminId;
        session.platformAdminEmail = platformAdminEmail;
        session.targetTenant = targetTenant;
        session.targetUserId = targetUserId;
        session.targetUserEmail = targetUserEmail;
        session.reason = reason;
        session.ticketNumber = sanitizedTicket;
        session.startedAt = OffsetDateTime.now();
        session.startCommand = startCommand;
        session.ipAddress = ipAddress;
        session.userAgent = userAgent;
        session.persist();

        startCommand.impersonationContext = new JsonObject()
                .put("session_id", session.id != null ? session.id.toString() : null)
                .put("target_tenant_id", targetTenantId != null ? targetTenantId.toString() : null)
                .put("target_user_id", targetUserId != null ? targetUserId.toString() : null).encode();

        LOG.infof("Started impersonation session %s: admin %s -> tenant %s (user %s) - reason: %s", session.id,
                platformAdminEmail, targetTenant.subdomain, targetUserId, reason);

        return mapToContext(session);
    }

    /**
     * End current impersonation session for a platform admin.
     *
     * @param platformAdminId
     *            platform admin user ID
     * @param platformAdminEmail
     *            platform admin email
     * @param ipAddress
     *            source IP address
     * @param userAgent
     *            client user agent
     */
    @Transactional
    public void endImpersonation(UUID platformAdminId, String platformAdminEmail, InetAddress ipAddress,
            String userAgent) {

        Optional<ImpersonationSession> sessionOpt = impersonationSessionRepo.findCurrentSession(platformAdminId);
        if (sessionOpt.isEmpty()) {
            throw new IllegalStateException("No active impersonation session found");
        }

        ImpersonationSession session = sessionOpt.get();

        // Log end command
        PlatformCommand endCommand = new PlatformCommand();
        endCommand.actorType = "platform_admin";
        endCommand.actorId = platformAdminId;
        endCommand.actorEmail = platformAdminEmail;
        endCommand.action = "impersonate_stop";
        endCommand.targetType = "tenant";
        endCommand.targetId = session.targetTenant.id;
        endCommand.reason = "Impersonation session ended";
        endCommand.ipAddress = ipAddress;
        endCommand.userAgent = userAgent;
        endCommand.occurredAt = OffsetDateTime.now();
        endCommand.ticketNumber = session.ticketNumber;
        endCommand.metadata = new JsonObject().put("session_id", session.id != null ? session.id.toString() : null)
                .encode();
        endCommand.impersonationContext = new JsonObject()
                .put("session_id", session.id != null ? session.id.toString() : null)
                .put("target_tenant_id", session.targetTenant != null ? session.targetTenant.id.toString() : null)
                .put("target_user_id", session.targetUserId != null ? session.targetUserId.toString() : null).encode();
        endCommand.persist();

        // End session
        session.end(endCommand);
        session.persist();

        LOG.infof("Ended impersonation session %s: admin %s", session.id, platformAdminEmail);
    }

    /**
     * Get current impersonation context for a platform admin.
     *
     * @param platformAdminId
     *            platform admin user ID
     * @return impersonation context if session active, empty otherwise
     */
    @Transactional
    public Optional<ImpersonationContext> getCurrentImpersonation(UUID platformAdminId) {
        return impersonationSessionRepo.findCurrentSession(platformAdminId).map(this::mapToContext);
    }

    /**
     * Check if platform admin has an active impersonation session.
     *
     * @param platformAdminId
     *            platform admin user ID
     * @return true if impersonation session is active
     */
    @Transactional
    public boolean hasActiveSession(UUID platformAdminId) {
        return impersonationSessionRepo.findCurrentSession(platformAdminId).isPresent();
    }

    // --- Helper Methods ---

    private ImpersonationContext mapToContext(ImpersonationSession session) {
        return new ImpersonationContext(session.id, session.platformAdminId, session.platformAdminEmail,
                session.targetTenant.id, session.targetTenant.name, session.targetUserId, session.targetUserEmail,
                session.reason, session.ticketNumber, session.startedAt);
    }
}
