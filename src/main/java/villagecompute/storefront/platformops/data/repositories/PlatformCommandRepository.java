package villagecompute.storefront.platformops.data.repositories;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.platformops.data.models.PlatformCommand;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for platform command audit logs.
 *
 * <p>
 * Provides queries for filtering audit logs by actor, action, target, date range, etc. All queries support pagination
 * for large result sets.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T2: Platform admin console (audit log viewer)</li>
 * <li>Architecture: 04_Operational_Architecture.md Section 3.7 (Observability)</li>
 * </ul>
 *
 * @see PlatformCommand
 */
@ApplicationScoped
public class PlatformCommandRepository implements PanacheRepository<PlatformCommand> {

    /**
     * Find platform commands by actor ID with pagination.
     *
     * @param actorId
     *            platform admin user ID
     * @param page
     *            page number (0-indexed)
     * @param pageSize
     *            number of results per page
     * @return list of commands
     */
    public List<PlatformCommand> findByActor(UUID actorId, int page, int pageSize) {
        return find("actorId = :actorId ORDER BY occurredAt DESC", Parameters.with("actorId", actorId))
                .page(page, pageSize).list();
    }

    /**
     * Find platform commands by action type.
     *
     * @param action
     *            action name (e.g., 'impersonate_start')
     * @param page
     *            page number (0-indexed)
     * @param pageSize
     *            number of results per page
     * @return list of commands
     */
    public List<PlatformCommand> findByAction(String action, int page, int pageSize) {
        return find("action = :action ORDER BY occurredAt DESC", Parameters.with("action", action)).page(page, pageSize)
                .list();
    }

    /**
     * Find platform commands by target entity.
     *
     * @param targetType
     *            target entity type (e.g., 'tenant')
     * @param targetId
     *            target entity ID
     * @param page
     *            page number (0-indexed)
     * @param pageSize
     *            number of results per page
     * @return list of commands
     */
    public List<PlatformCommand> findByTarget(String targetType, UUID targetId, int page, int pageSize) {
        return find("targetType = :targetType AND targetId = :targetId ORDER BY occurredAt DESC",
                Parameters.with("targetType", targetType).and("targetId", targetId)).page(page, pageSize).list();
    }

    /**
     * Find platform commands within a date range.
     *
     * @param startDate
     *            range start (inclusive)
     * @param endDate
     *            range end (inclusive)
     * @param page
     *            page number (0-indexed)
     * @param pageSize
     *            number of results per page
     * @return list of commands
     */
    public List<PlatformCommand> findByDateRange(OffsetDateTime startDate, OffsetDateTime endDate, int page,
            int pageSize) {
        return find("occurredAt >= :start AND occurredAt <= :end ORDER BY occurredAt DESC",
                Parameters.with("start", startDate).and("end", endDate)).page(page, pageSize).list();
    }

    /**
     * Find platform commands with multiple filter criteria.
     *
     * @param actorId
     *            optional actor filter
     * @param action
     *            optional action filter
     * @param targetType
     *            optional target type filter
     * @param startDate
     *            optional date range start
     * @param endDate
     *            optional date range end
     * @param page
     *            page number (0-indexed)
     * @param pageSize
     *            number of results per page
     * @return list of commands
     */
    public List<PlatformCommand> findWithFilters(UUID actorId, String action, String targetType,
            OffsetDateTime startDate, OffsetDateTime endDate, int page, int pageSize) {

        StringBuilder query = new StringBuilder("1=1");
        Parameters params = new Parameters();

        if (actorId != null) {
            query.append(" AND actorId = :actorId");
            params.and("actorId", actorId);
        }
        if (action != null && !action.isBlank()) {
            query.append(" AND action = :action");
            params.and("action", action);
        }
        if (targetType != null && !targetType.isBlank()) {
            query.append(" AND targetType = :targetType");
            params.and("targetType", targetType);
        }
        if (startDate != null) {
            query.append(" AND occurredAt >= :startDate");
            params.and("startDate", startDate);
        }
        if (endDate != null) {
            query.append(" AND occurredAt <= :endDate");
            params.and("endDate", endDate);
        }

        query.append(" ORDER BY occurredAt DESC");

        return find(query.toString(), params).page(page, pageSize).list();
    }

    /**
     * Count platform commands matching filter criteria.
     *
     * @param actorId
     *            optional actor filter
     * @param action
     *            optional action filter
     * @param targetType
     *            optional target type filter
     * @param startDate
     *            optional date range start
     * @param endDate
     *            optional date range end
     * @return count of matching commands
     */
    public long countWithFilters(UUID actorId, String action, String targetType, OffsetDateTime startDate,
            OffsetDateTime endDate) {

        StringBuilder query = new StringBuilder("1=1");
        Parameters params = new Parameters();

        if (actorId != null) {
            query.append(" AND actorId = :actorId");
            params.and("actorId", actorId);
        }
        if (action != null && !action.isBlank()) {
            query.append(" AND action = :action");
            params.and("action", action);
        }
        if (targetType != null && !targetType.isBlank()) {
            query.append(" AND targetType = :targetType");
            params.and("targetType", targetType);
        }
        if (startDate != null) {
            query.append(" AND occurredAt >= :startDate");
            params.and("startDate", startDate);
        }
        if (endDate != null) {
            query.append(" AND occurredAt <= :endDate");
            params.and("endDate", endDate);
        }

        return count(query.toString(), params);
    }

    /**
     * Find recent platform commands (last N days).
     *
     * @param days
     *            number of days to look back
     * @param page
     *            page number (0-indexed)
     * @param pageSize
     *            number of results per page
     * @return list of commands
     */
    public List<PlatformCommand> findRecent(int days, int page, int pageSize) {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(days);
        return find("occurredAt >= :cutoff ORDER BY occurredAt DESC", Parameters.with("cutoff", cutoff))
                .page(page, pageSize).list();
    }
}
