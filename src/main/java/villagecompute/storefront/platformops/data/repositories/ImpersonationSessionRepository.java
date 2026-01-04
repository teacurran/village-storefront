package villagecompute.storefront.platformops.data.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.platformops.data.models.ImpersonationSession;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for impersonation sessions.
 *
 * <p>
 * Provides queries for active sessions, historical sessions by tenant/user, and session lifecycle management.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T2: Platform admin console (impersonation control)</li>
 * <li>Architecture: 04_Operational_Architecture.md Section 3.8.1 (Impersonation)</li>
 * </ul>
 *
 * @see ImpersonationSession
 */
@ApplicationScoped
public class ImpersonationSessionRepository implements PanacheRepositoryBase<ImpersonationSession, UUID> {

    /**
     * Find active impersonation sessions for a platform admin.
     *
     * @param platformAdminId
     *            platform admin user ID
     * @return list of active sessions
     */
    public List<ImpersonationSession> findActiveByAdmin(UUID platformAdminId) {
        return find("platformAdminId = :adminId AND endedAt IS NULL ORDER BY startedAt DESC",
                Parameters.with("adminId", platformAdminId)).list();
    }

    /**
     * Find any active impersonation session for a platform admin (typically 0 or 1).
     *
     * @param platformAdminId
     *            platform admin user ID
     * @return active session if exists
     */
    public Optional<ImpersonationSession> findCurrentSession(UUID platformAdminId) {
        return find("platformAdminId = :adminId AND endedAt IS NULL ORDER BY startedAt DESC",
                Parameters.with("adminId", platformAdminId)).firstResultOptional();
    }

    /**
     * Find impersonation sessions targeting a specific tenant.
     *
     * @param targetTenantId
     *            tenant being impersonated
     * @param page
     *            page number (0-indexed)
     * @param pageSize
     *            number of results per page
     * @return list of sessions
     */
    public List<ImpersonationSession> findByTargetTenant(UUID targetTenantId, int page, int pageSize) {
        return find("targetTenant.id = :tenantId ORDER BY startedAt DESC", Parameters.with("tenantId", targetTenantId))
                .page(page, pageSize).list();
    }

    /**
     * Find impersonation sessions targeting a specific user.
     *
     * @param targetUserId
     *            user being impersonated
     * @param page
     *            page number (0-indexed)
     * @param pageSize
     *            number of results per page
     * @return list of sessions
     */
    public List<ImpersonationSession> findByTargetUser(UUID targetUserId, int page, int pageSize) {
        return find("targetUserId = :userId ORDER BY startedAt DESC", Parameters.with("userId", targetUserId))
                .page(page, pageSize).list();
    }

    /**
     * Count currently active impersonation sessions across all platform admins.
     *
     * @return count of active sessions
     */
    public long countActiveSessions() {
        return count("endedAt IS NULL");
    }

    /**
     * Find all currently active impersonation sessions.
     *
     * @return list of active sessions
     */
    public List<ImpersonationSession> findAllActiveSessions() {
        return find("endedAt IS NULL ORDER BY startedAt DESC").list();
    }

    /**
     * Find sessions by ticket number (for support tracking).
     *
     * @param ticketNumber
     *            support ticket reference
     * @return list of sessions
     */
    public List<ImpersonationSession> findByTicket(String ticketNumber) {
        return find("ticketNumber = :ticket ORDER BY startedAt DESC", Parameters.with("ticket", ticketNumber)).list();
    }
}
