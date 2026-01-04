package villagecompute.storefront.platformops.services;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.User;
import villagecompute.storefront.platformops.api.types.StoreDirectoryEntry;
import villagecompute.storefront.platformops.data.models.PlatformCommand;
import villagecompute.storefront.platformops.data.repositories.PlatformCommandRepository;

import io.quarkus.panache.common.Parameters;
import io.vertx.core.json.JsonObject;

/**
 * Platform admin service for store directory and tenant management.
 *
 * <p>
 * Provides cross-tenant queries and operations for platform administrators including store listings, statistics, and
 * tenant lifecycle management.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T2: Platform admin console (store directory)</li>
 * <li>Architecture: 01_Blueprint_Foundation.md Section 4.0 (Platform Admin Console Backend)</li>
 * </ul>
 */
@ApplicationScoped
public class PlatformAdminService {

    private static final Logger LOG = Logger.getLogger(PlatformAdminService.class);

    @Inject
    PlatformCommandRepository platformCommandRepo;

    /**
     * Get store directory with pagination and filters.
     *
     * @param status
     *            optional status filter ('active', 'suspended', etc.)
     * @param searchQuery
     *            optional search query (subdomain or name)
     * @param page
     *            page number (0-indexed)
     * @param pageSize
     *            results per page
     * @return list of store directory entries
     */
    @Transactional
    public List<StoreDirectoryEntry> getStoreDirectory(String status, String searchQuery, int page, int pageSize) {
        LOG.infof("Fetching store directory - status=%s, search=%s, page=%d, size=%d", status, searchQuery, page,
                pageSize);

        StringBuilder query = new StringBuilder("1=1");
        Parameters params = new Parameters();

        if (status != null && !status.isBlank()) {
            query.append(" AND status = :status");
            params.and("status", status);
        }

        if (searchQuery != null && !searchQuery.isBlank()) {
            query.append(" AND (LOWER(subdomain) LIKE :search OR LOWER(name) LIKE :search)");
            params.and("search", "%" + searchQuery.toLowerCase() + "%");
        }

        query.append(" ORDER BY createdAt DESC");

        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(pageSize, 1), 200);

        List<Tenant> tenants = Tenant.find(query.toString(), params).page(safePage, safeSize).list();

        List<StoreDirectoryEntry> entries = new ArrayList<>();
        for (Tenant tenant : tenants) {
            StoreDirectoryEntry entry = buildDirectoryEntry(tenant);
            entries.add(entry);
        }

        LOG.infof("Retrieved %d stores for directory", entries.size());
        return entries;
    }

    /**
     * Count stores matching filter criteria.
     *
     * @param status
     *            optional status filter
     * @param searchQuery
     *            optional search query
     * @return count of matching stores
     */
    @Transactional
    public long countStores(String status, String searchQuery) {
        StringBuilder query = new StringBuilder("1=1");
        Parameters params = new Parameters();

        if (status != null && !status.isBlank()) {
            query.append(" AND status = :status");
            params.and("status", status);
        }

        if (searchQuery != null && !searchQuery.isBlank()) {
            query.append(" AND (LOWER(subdomain) LIKE :search OR LOWER(name) LIKE :search)");
            params.and("search", "%" + searchQuery.toLowerCase() + "%");
        }

        return Tenant.count(query.toString(), params);
    }

    /**
     * Get detailed store information by ID.
     *
     * @param tenantId
     *            tenant UUID
     * @return store directory entry with detailed stats
     */
    @Transactional
    public StoreDirectoryEntry getStoreDetails(UUID tenantId) {
        Tenant tenant = Tenant.findById(tenantId);
        if (tenant == null) {
            throw new IllegalArgumentException("Store not found: " + tenantId);
        }

        StoreDirectoryEntry entry = buildDirectoryEntry(tenant);
        LOG.infof("Retrieved store details for tenant %s (%s)", tenant.subdomain, tenantId);
        return entry;
    }

    /**
     * Suspend a tenant store (platform admin action).
     *
     * @param tenantId
     *            tenant to suspend
     * @param reason
     *            suspension reason
     * @param actorId
     *            platform admin performing the action
     * @param actorEmail
     *            platform admin email
     */
    @Transactional
    public void suspendStore(UUID tenantId, String reason, UUID actorId, String actorEmail) {
        Tenant tenant = Tenant.findById(tenantId);
        if (tenant == null) {
            throw new IllegalArgumentException("Store not found: " + tenantId);
        }

        tenant.status = "suspended";
        tenant.updatedAt = OffsetDateTime.now();
        tenant.persist();

        JsonObject metadata = new JsonObject().put("newStatus", tenant.status);
        logPlatformAction(actorId, actorEmail, "suspend_store", "tenant", tenantId, reason, null, metadata);

        LOG.infof("Suspended store %s (tenant %s) by %s - reason: %s", tenant.subdomain, tenantId, actorEmail, reason);
    }

    /**
     * Reactivate a suspended tenant store.
     *
     * @param tenantId
     *            tenant to reactivate
     * @param actorId
     *            platform admin performing the action
     * @param actorEmail
     *            platform admin email
     */
    @Transactional
    public void reactivateStore(UUID tenantId, UUID actorId, String actorEmail) {
        Tenant tenant = Tenant.findById(tenantId);
        if (tenant == null) {
            throw new IllegalArgumentException("Store not found: " + tenantId);
        }

        tenant.status = "active";
        tenant.updatedAt = OffsetDateTime.now();
        tenant.persist();

        JsonObject metadata = new JsonObject().put("newStatus", tenant.status);
        logPlatformAction(actorId, actorEmail, "reactivate_store", "tenant", tenantId, "Store reactivated", null,
                metadata);

        LOG.infof("Reactivated store %s (tenant %s) by %s", tenant.subdomain, tenantId, actorEmail);
    }

    // --- Helper Methods ---

    private StoreDirectoryEntry buildDirectoryEntry(Tenant tenant) {
        // Count users for this tenant
        long userCount = User.count("tenant.id = :tenantId", Parameters.with("tenantId", tenant.id));
        long activeUserCount = User.count("tenant.id = :tenantId AND status = 'active'",
                Parameters.with("tenantId", tenant.id));

        // Find last activity (most recent user login) - simplified
        OffsetDateTime lastActivity = tenant.updatedAt;

        // Extract plan from settings JSON (simplified - would parse JSON properly in production)
        String plan = "basic"; // Default

        // Check if custom domain configured (would query custom_domains table)
        boolean customDomainConfigured = false;

        return new StoreDirectoryEntry(tenant.id, tenant.subdomain, tenant.name, tenant.status, (int) userCount,
                (int) activeUserCount, tenant.createdAt, lastActivity, plan, customDomainConfigured);
    }

    private void logPlatformAction(UUID actorId, String actorEmail, String action, String targetType, UUID targetId,
            String reason, String ticketNumber, JsonObject metadata) {
        PlatformCommand command = new PlatformCommand();
        command.actorType = "platform_admin";
        command.actorId = actorId;
        command.actorEmail = actorEmail;
        command.action = action;
        command.targetType = targetType;
        command.targetId = targetId;
        command.reason = reason;
        command.ticketNumber = ticketNumber;
        command.metadata = metadata != null ? metadata.encode() : null;
        platformCommandRepo.persist(command);
    }
}
