package villagecompute.storefront.data.repositories;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.LoyaltyRedemptionReservation;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for LoyaltyRedemptionReservation entity with tenant-aware queries.
 *
 * <p>
 * All queries automatically scope to the current tenant from {@link TenantContext}. Supports lookup by member, cart,
 * and expiration for reservation management and cleanup operations.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001: Repository-Level Tenant Enforcement</li>
 * <li>Entity: {@link LoyaltyRedemptionReservation}</li>
 * <li>Task I4.T3: Loyalty redemption reservation repository</li>
 * </ul>
 */
@ApplicationScoped
public class LoyaltyRedemptionReservationRepository
        implements PanacheRepositoryBase<LoyaltyRedemptionReservation, UUID> {

    private static final String QUERY_FIND_ACTIVE_BY_MEMBER = "tenant.id = :tenantId and member.id = :memberId and status = 'active' and expiresAt > :now";
    private static final String QUERY_FIND_BY_CART = "tenant.id = :tenantId and cartId = :cartId";
    private static final String QUERY_FIND_ACTIVE_BY_CART = "tenant.id = :tenantId and cartId = :cartId and status = 'active' and expiresAt > :now";
    private static final String QUERY_FIND_EXPIRED = "tenant.id = :tenantId and status = 'active' and expiresAt <= :cutoffDate";
    private static final String QUERY_FIND_BY_IDEMPOTENCY_KEY = "tenant.id = :tenantId and idempotencyKey = :idempotencyKey";

    /**
     * Find all active reservations for a member within current tenant.
     *
     * @param memberId
     *            member UUID
     * @return list of active reservations
     */
    public List<LoyaltyRedemptionReservation> findActiveByMember(UUID memberId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        OffsetDateTime now = OffsetDateTime.now();
        return list(QUERY_FIND_ACTIVE_BY_MEMBER,
                Parameters.with("tenantId", tenantId).and("memberId", memberId).and("now", now));
    }

    /**
     * Find all reservations for a cart within current tenant.
     *
     * @param cartId
     *            cart UUID
     * @return list of reservations
     */
    public List<LoyaltyRedemptionReservation> findByCart(UUID cartId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_CART, Parameters.with("tenantId", tenantId).and("cartId", cartId));
    }

    /**
     * Find active reservation for a cart within current tenant.
     *
     * @param cartId
     *            cart UUID
     * @return active reservation if exists
     */
    public Optional<LoyaltyRedemptionReservation> findActiveByCart(UUID cartId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        OffsetDateTime now = OffsetDateTime.now();
        return find(QUERY_FIND_ACTIVE_BY_CART,
                Parameters.with("tenantId", tenantId).and("cartId", cartId).and("now", now)).firstResultOptional();
    }

    /**
     * Find expired reservations within current tenant.
     *
     * @param cutoffDate
     *            expiration cutoff timestamp
     * @param offset
     *            pagination offset
     * @param limit
     *            page size
     * @return list of expired reservations
     */
    public List<LoyaltyRedemptionReservation> findExpired(OffsetDateTime cutoffDate, int offset, int limit) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_EXPIRED, Parameters.with("tenantId", tenantId).and("cutoffDate", cutoffDate))
                .range(offset, offset + limit - 1).list();
    }

    /**
     * Find reservation by idempotency key within current tenant.
     *
     * @param idempotencyKey
     *            idempotency key
     * @return reservation if found
     */
    public Optional<LoyaltyRedemptionReservation> findByIdempotencyKey(String idempotencyKey) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_IDEMPOTENCY_KEY,
                Parameters.with("tenantId", tenantId).and("idempotencyKey", idempotencyKey)).firstResultOptional();
    }

    /**
     * Calculate total active reserved points for a member.
     *
     * @param memberId
     *            member UUID
     * @return total reserved points
     */
    public int calculateReservedPoints(UUID memberId) {
        List<LoyaltyRedemptionReservation> activeReservations = findActiveByMember(memberId);
        return activeReservations.stream().mapToInt(r -> r.pointsReserved != null ? r.pointsReserved : 0).sum();
    }
}
