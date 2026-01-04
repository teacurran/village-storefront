package villagecompute.storefront.loyalty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import villagecompute.storefront.data.models.LoyaltyMember;
import villagecompute.storefront.data.models.LoyaltyProgram;
import villagecompute.storefront.data.models.LoyaltyTransaction;
import villagecompute.storefront.data.models.User;
import villagecompute.storefront.data.repositories.LoyaltyMemberRepository;
import villagecompute.storefront.data.repositories.LoyaltyProgramRepository;
import villagecompute.storefront.data.repositories.LoyaltyTransactionRepository;
import villagecompute.storefront.services.ReportingProjectionService;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Service layer for loyalty program operations.
 *
 * <p>
 * Provides business logic for managing loyalty programs, member enrollment, points accrual, redemption, tier
 * calculations, and expiration handling. All operations are tenant-scoped and include structured logging for
 * observability.
 *
 * <p>
 * Redemption operations support idempotency via idempotency keys to prevent duplicate point debits. Tier calculations
 * use lifetime points earned to determine member tiers based on program configuration.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T4: Loyalty service implementation with accrual and redemption</li>
 * <li>ADR-001: Tenant-scoped services</li>
 * <li>ADR-003: Idempotent redemption operations</li>
 * </ul>
 */
@ApplicationScoped
public class LoyaltyService {

    private static final Logger LOG = Logger.getLogger(LoyaltyService.class);
    private static final String EXPIRATION_REASON_TEMPLATE = "Points expired from transaction %s";
    private static final List<LoyaltyTierDefinition> DEFAULT_TIERS = List.of(
            new LoyaltyTierDefinition("Bronze", 0, BigDecimal.ONE),
            new LoyaltyTierDefinition("Silver", 1000, BigDecimal.valueOf(1.25)),
            new LoyaltyTierDefinition("Gold", 5000, BigDecimal.valueOf(1.5)),
            new LoyaltyTierDefinition("Platinum", 10000, BigDecimal.valueOf(2.0)));

    private final ConcurrentMap<UUID, TierCacheEntry> tierConfigCache = new ConcurrentHashMap<>();

    @Inject
    LoyaltyProgramRepository programRepository;

    @Inject
    LoyaltyMemberRepository memberRepository;

    @Inject
    LoyaltyTransactionRepository transactionRepository;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    ReportingProjectionService reportingProjectionService;

    @Inject
    ObjectMapper objectMapper;

    // ========================================
    // Program Operations
    // ========================================

    /**
     * Get active loyalty program for current tenant.
     *
     * @return active program if exists
     */
    @Transactional(TxType.SUPPORTS)
    public Optional<LoyaltyProgram> getActiveProgram() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Fetching active loyalty program - tenantId=%s", tenantId);
        return programRepository.findActiveProgramForCurrentTenant();
    }

    /**
     * Get configured loyalty program for current tenant (enabled or disabled).
     *
     * @return program if configured
     */
    @Transactional(TxType.SUPPORTS)
    public Optional<LoyaltyProgram> getProgramForTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Fetching loyalty program (any status) - tenantId=%s", tenantId);
        return programRepository.findProgramForCurrentTenant();
    }

    /**
     * Create or update loyalty program for current tenant.
     *
     * @param program
     *            program configuration
     * @return persisted program
     */
    @Transactional
    public LoyaltyProgram saveProgram(LoyaltyProgram program) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Saving loyalty program - tenantId=%s, programId=%s, enabled=%s", tenantId, program.id,
                program.enabled);

        programRepository.persist(program);
        programRepository.getEntityManager().flush();
        if (program.id != null) {
            tierConfigCache.remove(program.id);
        }

        LOG.infof("Loyalty program saved - tenantId=%s, programId=%s", tenantId, program.id);
        meterRegistry.counter("loyalty.program.saved", "tenant_id", tenantId.toString()).increment();

        return program;
    }

    // ========================================
    // Member Operations
    // ========================================

    /**
     * Enroll user in loyalty program.
     *
     * @param userId
     *            user UUID
     * @return loyalty member
     */
    @Transactional
    public LoyaltyMember enrollMember(UUID userId) {
        Objects.requireNonNull(userId, "User ID is required");
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Enrolling member in loyalty program - tenantId=%s, userId=%s", tenantId, userId);

        // Get active program
        LoyaltyProgram program = getActiveProgram()
                .orElseThrow(() -> new IllegalStateException("No active loyalty program found"));
        ensureProgramEnabled(program);

        // Check if already enrolled
        Optional<LoyaltyMember> existing = memberRepository.findByUserAndProgram(userId, program.id);
        if (existing.isPresent()) {
            LOG.infof("Member already enrolled - tenantId=%s, userId=%s, memberId=%s", tenantId, userId,
                    existing.get().id);
            return existing.get();
        }

        // Create new member
        LoyaltyMember member = new LoyaltyMember();
        User user = User.findById(userId);
        if (user == null) {
            throw new IllegalArgumentException("User not found: " + userId);
        }
        if (user.tenant == null || !user.tenant.id.equals(tenantId)) {
            throw new IllegalArgumentException("User does not belong to current tenant");
        }
        member.user = user;
        member.program = program;
        member.pointsBalance = 0;
        member.lifetimePointsEarned = 0;
        member.lifetimePointsRedeemed = 0;
        member.status = "active";

        memberRepository.persist(member);

        // Calculate initial tier
        updateMemberTier(member);

        LOG.infof("Member enrolled - tenantId=%s, userId=%s, memberId=%s", tenantId, userId, member.id);
        meterRegistry.counter("loyalty.member.enrolled", "tenant_id", tenantId.toString()).increment();

        return member;
    }

    /**
     * Get loyalty member for user.
     *
     * @param userId
     *            user UUID
     * @return member if enrolled
     */
    @Transactional(TxType.SUPPORTS)
    public Optional<LoyaltyMember> getMemberByUser(UUID userId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Fetching loyalty member - tenantId=%s, userId=%s", tenantId, userId);
        return memberRepository.findByUser(userId);
    }

    // ========================================
    // Points Accrual
    // ========================================

    /**
     * Award points for a purchase.
     *
     * @param userId
     *            user UUID
     * @param purchaseAmount
     *            purchase amount
     * @param orderId
     *            order UUID
     * @return {@link Optional} of the persisted transaction; empty if purchase value does not earn any points
     */
    @Transactional
    public Optional<LoyaltyTransaction> awardPointsForPurchase(UUID userId, BigDecimal purchaseAmount, UUID orderId) {
        Objects.requireNonNull(userId, "User ID is required");
        Objects.requireNonNull(purchaseAmount, "Purchase amount is required");
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Awarding points for purchase - tenantId=%s, userId=%s, orderId=%s, amount=%s", tenantId, userId,
                orderId, purchaseAmount);

        // Get or enroll member
        LoyaltyMember member = getMemberByUser(userId).orElseGet(() -> enrollMember(userId));

        // Calculate points to award
        LoyaltyProgram program = member.program;
        ensureProgramEnabled(program);
        int pointsToAward = calculatePointsForPurchase(purchaseAmount, program, member);

        if (pointsToAward <= 0) {
            LOG.debugf("No points to award - tenantId=%s, userId=%s, orderId=%s, purchaseAmount=%s, pointsToAward=%d",
                    tenantId, userId, orderId, purchaseAmount, pointsToAward);
            return Optional.empty();
        }

        // Create transaction
        LoyaltyTransaction transaction = new LoyaltyTransaction();
        transaction.member = member;
        transaction.orderId = orderId;
        transaction.pointsDelta = pointsToAward;
        transaction.transactionType = "earned";
        transaction.reason = "Purchase #" + (orderId != null ? orderId.toString().substring(0, 8) : "unknown");
        transaction.source = "order";

        // Calculate expiration if configured
        if (program.pointsExpirationDays != null && program.pointsExpirationDays > 0) {
            transaction.expiresAt = OffsetDateTime.now().plusDays(program.pointsExpirationDays);
        }

        // Update member balance
        member.pointsBalance += pointsToAward;
        member.lifetimePointsEarned += pointsToAward;
        transaction.balanceAfter = member.pointsBalance;

        transactionRepository.persist(transaction);
        memberRepository.persist(member);

        // Update tier if needed
        updateMemberTier(member);

        LOG.infof("Points awarded - tenantId=%s, userId=%s, memberId=%s, points=%d, newBalance=%d", tenantId, userId,
                member.id, pointsToAward, member.pointsBalance);
        meterRegistry.counter("loyalty.points.earned", "tenant_id", tenantId.toString(), "tier",
                member.currentTier != null ? member.currentTier : "none").increment(pointsToAward);
        reportingProjectionService.recordLoyaltyLedgerEvent(transaction);

        return Optional.of(transaction);
    }

    /**
     * Award points manually (admin adjustment).
     *
     * @param userId
     *            user UUID
     * @param points
     *            points to award (can be negative for deduction)
     * @param reason
     *            adjustment reason
     * @return transaction record
     */
    @Transactional
    public LoyaltyTransaction adjustPoints(UUID userId, int points, String reason) {
        Objects.requireNonNull(userId, "User ID is required");
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Adjusting points - tenantId=%s, userId=%s, points=%d, reason=%s", tenantId, userId, points, reason);

        // Get member
        LoyaltyMember member = getMemberByUser(userId)
                .orElseThrow(() -> new IllegalStateException("User not enrolled in loyalty program"));

        LoyaltyProgram program = member.program;
        ensureProgramEnabled(program);

        // Create transaction
        LoyaltyTransaction transaction = new LoyaltyTransaction();
        transaction.member = member;
        transaction.pointsDelta = points;
        transaction.transactionType = "adjusted";
        transaction.reason = reason != null ? reason : "Admin adjustment";
        transaction.source = "admin_adjustment";

        // Calculate expiration for positive adjustments
        if (points > 0 && program.pointsExpirationDays != null && program.pointsExpirationDays > 0) {
            transaction.expiresAt = OffsetDateTime.now().plusDays(program.pointsExpirationDays);
        }

        // Update member balance
        int newBalance = member.pointsBalance + points;
        if (newBalance < 0) {
            throw new IllegalArgumentException("Adjustment would result in negative balance");
        }
        member.pointsBalance = newBalance;
        if (points > 0) {
            member.lifetimePointsEarned += points;
        }
        transaction.balanceAfter = member.pointsBalance;

        transactionRepository.persist(transaction);
        memberRepository.persist(member);

        // Update tier if needed
        updateMemberTier(member);

        LOG.infof("Points adjusted - tenantId=%s, userId=%s, memberId=%s, points=%d, newBalance=%d", tenantId, userId,
                member.id, points, member.pointsBalance);
        meterRegistry.counter("loyalty.points.adjusted", "tenant_id", tenantId.toString()).increment(Math.abs(points));
        reportingProjectionService.recordLoyaltyLedgerEvent(transaction);

        return transaction;
    }

    // ========================================
    // Points Redemption
    // ========================================

    /**
     * Redeem points for discount (idempotent).
     *
     * @param userId
     *            user UUID
     * @param pointsToRedeem
     *            points to redeem
     * @param idempotencyKey
     *            idempotency key for duplicate prevention
     * @return transaction record
     */
    @Transactional
    public LoyaltyTransaction redeemPoints(UUID userId, int pointsToRedeem, String idempotencyKey) {
        Objects.requireNonNull(userId, "User ID is required");
        Objects.requireNonNull(idempotencyKey, "Idempotency key is required");
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Redeeming points - tenantId=%s, userId=%s, points=%d, idempotencyKey=%s", tenantId, userId,
                pointsToRedeem, idempotencyKey);

        if (pointsToRedeem <= 0) {
            throw new IllegalArgumentException("Points to redeem must be positive");
        }

        // Check for duplicate redemption
        Optional<LoyaltyTransaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            LOG.infof("Duplicate redemption detected - tenantId=%s, idempotencyKey=%s, transactionId=%s", tenantId,
                    idempotencyKey, existing.get().id);
            return existing.get();
        }

        // Get member
        LoyaltyMember member = getMemberByUser(userId)
                .orElseThrow(() -> new IllegalStateException("User not enrolled in loyalty program"));

        LoyaltyProgram program = member.program;
        ensureProgramEnabled(program);

        // Validate redemption amount
        if (pointsToRedeem < program.minRedemptionPoints) {
            throw new IllegalArgumentException("Minimum redemption is " + program.minRedemptionPoints + " points");
        }

        if (program.maxRedemptionPoints != null && pointsToRedeem > program.maxRedemptionPoints) {
            throw new IllegalArgumentException("Maximum redemption is " + program.maxRedemptionPoints + " points");
        }

        if (pointsToRedeem > member.pointsBalance) {
            throw new IllegalArgumentException("Insufficient points balance");
        }

        // Create transaction
        LoyaltyTransaction transaction = new LoyaltyTransaction();
        transaction.member = member;
        transaction.pointsDelta = -pointsToRedeem;
        transaction.transactionType = "redeemed";
        transaction.reason = "Redemption for discount";
        transaction.source = "checkout";
        transaction.idempotencyKey = idempotencyKey;

        // Update member balance
        member.pointsBalance -= pointsToRedeem;
        member.lifetimePointsRedeemed += pointsToRedeem;
        transaction.balanceAfter = member.pointsBalance;

        transactionRepository.persist(transaction);
        memberRepository.persist(member);

        LOG.infof("Points redeemed - tenantId=%s, userId=%s, memberId=%s, points=%d, newBalance=%d", tenantId, userId,
                member.id, pointsToRedeem, member.pointsBalance);
        meterRegistry.counter("loyalty.points.redeemed", "tenant_id", tenantId.toString(), "tier",
                member.currentTier != null ? member.currentTier : "none").increment(pointsToRedeem);
        reportingProjectionService.recordLoyaltyLedgerEvent(transaction);

        return transaction;
    }

    /**
     * Calculate discount value for redeemed points.
     *
     * @param pointsRedeemed
     *            points redeemed
     * @return discount amount
     */
    @Transactional(TxType.SUPPORTS)
    public BigDecimal calculateRedemptionValue(int pointsRedeemed) {
        LoyaltyProgram program = getActiveProgram()
                .orElseThrow(() -> new IllegalStateException("No active loyalty program found"));
        ensureProgramEnabled(program);

        return program.redemptionValuePerPoint.multiply(BigDecimal.valueOf(pointsRedeemed)).setScale(2,
                RoundingMode.HALF_UP);
    }

    // ========================================
    // Tier Calculations
    // ========================================

    /**
     * Update member tier based on lifetime points earned.
     *
     * @param member
     *            loyalty member
     */
    @Transactional
    public void updateMemberTier(LoyaltyMember member) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String newTier = calculateTier(member);

        if (!Objects.equals(member.currentTier, newTier)) {
            String oldTier = member.currentTier;
            member.currentTier = newTier;
            member.tierUpdatedAt = OffsetDateTime.now();
            memberRepository.persist(member);

            LOG.infof("Member tier updated - tenantId=%s, memberId=%s, oldTier=%s, newTier=%s", tenantId, member.id,
                    oldTier, newTier);
            meterRegistry.counter("loyalty.tier.updated", "tenant_id", tenantId.toString(), "new_tier", newTier)
                    .increment();
        }
    }

    /**
     * Calculate tier for member based on lifetime points.
     *
     * @param member
     *            loyalty member
     * @return tier name
     */
    private String calculateTier(LoyaltyMember member) {
        if (member == null) {
            return DEFAULT_TIERS.get(0).getName();
        }

        List<LoyaltyTierDefinition> tiers = resolveTierDefinitions(member.program);
        int lifetimePoints = member.lifetimePointsEarned != null ? member.lifetimePointsEarned : 0;
        String resolvedTier = tiers.get(0).getName();

        for (LoyaltyTierDefinition tier : tiers) {
            int minPoints = tier.getMinPoints() != null ? tier.getMinPoints() : 0;
            if (lifetimePoints >= minPoints) {
                resolvedTier = tier.getName();
            } else {
                break;
            }
        }

        return resolvedTier;
    }

    // ========================================
    // Expiration Handling
    // ========================================

    /**
     * Expire points for transactions past their expiration date.
     *
     * @param cutoffDate
     *            cutoff timestamp
     * @return number of transactions expired
     */
    @Transactional
    public int expirePoints(OffsetDateTime cutoffDate) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Expiring points - tenantId=%s, cutoffDate=%s", tenantId, cutoffDate);

        List<LoyaltyTransaction> expiringTransactions = transactionRepository.findExpiring(cutoffDate, 0, 1000);
        int expiredCount = 0;

        for (LoyaltyTransaction earnedTransaction : expiringTransactions) {
            if (transactionRepository.hasExpirationEntry(earnedTransaction.id)) {
                continue;
            }

            // Create expiration transaction
            LoyaltyTransaction expirationTransaction = new LoyaltyTransaction();
            expirationTransaction.member = earnedTransaction.member;
            expirationTransaction.pointsDelta = -earnedTransaction.pointsDelta;
            expirationTransaction.transactionType = "expired";
            expirationTransaction.reason = String.format(EXPIRATION_REASON_TEMPLATE, earnedTransaction.id);
            expirationTransaction.source = "expiration_job";

            // Update member balance
            LoyaltyMember member = earnedTransaction.member;
            member.pointsBalance -= earnedTransaction.pointsDelta;
            expirationTransaction.balanceAfter = member.pointsBalance;

            transactionRepository.persist(expirationTransaction);
            memberRepository.persist(member);
            reportingProjectionService.recordLoyaltyLedgerEvent(expirationTransaction);

            expiredCount++;
        }

        LOG.infof("Points expired - tenantId=%s, transactionsExpired=%d", tenantId, expiredCount);
        meterRegistry.counter("loyalty.points.expired", "tenant_id", tenantId.toString()).increment(expiredCount);

        return expiredCount;
    }

    // ========================================
    // Transaction History
    // ========================================

    /**
     * Get transaction history for member.
     *
     * @param userId
     *            user UUID
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return list of transactions
     */
    @Transactional(TxType.SUPPORTS)
    public List<LoyaltyTransaction> getTransactionHistory(UUID userId, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Fetching transaction history - tenantId=%s, userId=%s, page=%d, size=%d", tenantId, userId, page,
                size);

        LoyaltyMember member = getMemberByUser(userId)
                .orElseThrow(() -> new IllegalStateException("User not enrolled in loyalty program"));

        return transactionRepository.findByMember(member.id, page, size);
    }

    // ========================================
    // Helper Methods
    // ========================================

    /**
     * Calculate points for purchase amount.
     *
     * @param amount
     *            purchase amount
     * @param program
     *            loyalty program
     * @return points to award
     */
    private int calculatePointsForPurchase(BigDecimal amount, LoyaltyProgram program, LoyaltyMember member) {
        if (amount == null || program == null) {
            return 0;
        }

        BigDecimal normalizedAmount = amount.max(BigDecimal.ZERO);
        BigDecimal baseRate = program.pointsPerDollar != null ? program.pointsPerDollar : BigDecimal.ZERO;
        BigDecimal multiplier = resolveTierMultiplier(program, member);

        return normalizedAmount.multiply(baseRate).multiply(multiplier).setScale(0, RoundingMode.DOWN).intValue();
    }

    private BigDecimal resolveTierMultiplier(LoyaltyProgram program, LoyaltyMember member) {
        List<LoyaltyTierDefinition> tiers = resolveTierDefinitions(program);
        String targetTier = member != null && member.currentTier != null ? member.currentTier : tiers.get(0).getName();
        return tiers.stream().filter(tier -> tier.getName() != null && tier.getName().equalsIgnoreCase(targetTier))
                .map(LoyaltyTierDefinition::multiplierOrDefault).findFirst().orElse(BigDecimal.ONE);
    }

    private List<LoyaltyTierDefinition> resolveTierDefinitions(LoyaltyProgram program) {
        if (program == null) {
            return DEFAULT_TIERS;
        }

        String rawConfig = program.tierConfig;
        if (rawConfig == null || rawConfig.isBlank()) {
            return DEFAULT_TIERS;
        }

        if (program.id != null) {
            TierCacheEntry cached = tierConfigCache.get(program.id);
            if (cached != null && Objects.equals(cached.rawConfig, rawConfig)) {
                return cached.tiers;
            }
        }

        try {
            List<LoyaltyTierDefinition> tiers = objectMapper.readValue(rawConfig,
                    new TypeReference<List<LoyaltyTierDefinition>>() {
                    });
            if (tiers == null || tiers.isEmpty()) {
                return DEFAULT_TIERS;
            }
            tiers.sort(Comparator.comparingInt(t -> t.getMinPoints() != null ? t.getMinPoints() : 0));
            List<LoyaltyTierDefinition> immutable = List.copyOf(tiers);
            if (program.id != null) {
                tierConfigCache.put(program.id, new TierCacheEntry(rawConfig, immutable));
            }
            return immutable;
        } catch (Exception e) {
            LOG.warnf(e, "Failed to parse tier configuration - programId=%s", program.id);
            return DEFAULT_TIERS;
        }
    }

    private void ensureProgramEnabled(LoyaltyProgram program) {
        if (program == null || !Boolean.TRUE.equals(program.enabled)) {
            throw new IllegalStateException("Loyalty program is disabled for this tenant");
        }
    }

    /**
     * Build loyalty summary for cart responses.
     *
     * @param subtotal
     *            current cart subtotal
     * @param userId
     *            associated user id if authenticated
     * @return projection containing loyalty state
     */
    @Transactional(TxType.SUPPORTS)
    public CartLoyaltyProjection calculateCartSummary(BigDecimal subtotal, UUID userId) {
        CartLoyaltyProjection projection = new CartLoyaltyProjection();
        projection.setDataFreshnessTimestamp(OffsetDateTime.now());

        Optional<LoyaltyProgram> programOpt = getActiveProgram();
        if (programOpt.isEmpty()) {
            return projection;
        }

        LoyaltyProgram program = programOpt.get();
        projection.setProgramId(program.id);
        projection.setDataFreshnessTimestamp(program.updatedAt);

        if (!Boolean.TRUE.equals(program.enabled)) {
            return projection;
        }

        projection.setProgramEnabled(true);

        Optional<LoyaltyMember> memberOpt = userId != null ? memberRepository.findByUser(userId) : Optional.empty();
        LoyaltyMember member = memberOpt.orElse(null);
        if (member != null) {
            projection.setMemberPointsBalance(member.pointsBalance);
            projection.setCurrentTier(member.currentTier);
            if (program.redemptionValuePerPoint != null && member.pointsBalance != null) {
                BigDecimal redeemable = program.redemptionValuePerPoint
                        .multiply(BigDecimal.valueOf(member.pointsBalance));
                projection.setAvailableRedemptionValue(redeemable);
            }
            if (member.updatedAt != null) {
                projection.setDataFreshnessTimestamp(member.updatedAt);
            }
        } else {
            List<LoyaltyTierDefinition> tiers = resolveTierDefinitions(program);
            projection.setCurrentTier(tiers.get(0).getName());
        }

        BigDecimal effectiveSubtotal = subtotal != null ? subtotal : BigDecimal.ZERO;
        int estimatedPoints = calculatePointsForPurchase(effectiveSubtotal, program, member);
        projection.setEstimatedPointsEarned(estimatedPoints);

        if (program.redemptionValuePerPoint != null) {
            BigDecimal estimatedValue = program.redemptionValuePerPoint.multiply(BigDecimal.valueOf(estimatedPoints));
            projection.setEstimatedRewardValue(estimatedValue);
        }

        return projection;
    }

    private static final class TierCacheEntry {
        private final String rawConfig;
        private final List<LoyaltyTierDefinition> tiers;

        private TierCacheEntry(String rawConfig, List<LoyaltyTierDefinition> tiers) {
            this.rawConfig = rawConfig;
            this.tiers = tiers;
        }
    }
}
