package villagecompute.storefront.security.sessions;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for tenant-scoped queries against the {@code session_log} table.
 */
@ApplicationScoped
public class SessionLogRepository implements PanacheRepositoryBase<SessionLogEntry, UUID> {

    private static final String BASE_QUERY = "tenant.id = :tenantId AND userId = :userId";

    public List<SessionLogEntry> findRecentSessionsForUser(UUID tenantId, UUID userId, OffsetDateTime since,
            int limit) {
        int pageSize = limit > 0 ? limit : 50;
        return find(
                BASE_QUERY + " AND (lastActivityAt >= :since OR loginAt >= :since) ORDER BY "
                        + "COALESCE(lastActivityAt, loginAt) DESC",
                Parameters.with("tenantId", tenantId).and("userId", userId).and("since", since))
                .page(Page.of(0, pageSize)).list();
    }

    public List<SessionLogEntry> findArchivedSessionsForUser(UUID tenantId, UUID userId, OffsetDateTime before,
            int limit) {
        int pageSize = limit > 0 ? limit : 200;
        return find(BASE_QUERY + " AND loginAt < :before ORDER BY loginAt DESC",
                Parameters.with("tenantId", tenantId).and("userId", userId).and("before", before))
                .page(Page.of(0, pageSize)).list();
    }

    public SessionLogEntry findByTenantAndId(UUID tenantId, UUID userId, UUID sessionId) {
        return find(BASE_QUERY + " AND id = :sessionId",
                Parameters.with("tenantId", tenantId).and("userId", userId).and("sessionId", sessionId)).firstResult();
    }
}
