package villagecompute.storefront.giftcard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import org.jboss.logging.Logger;

import villagecompute.storefront.api.types.IssueGiftCardRequest;
import villagecompute.storefront.api.types.UpdateGiftCardRequest;
import villagecompute.storefront.data.models.GiftCard;
import villagecompute.storefront.data.models.GiftCardTransaction;
import villagecompute.storefront.data.models.StoreCreditTransaction;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.User;
import villagecompute.storefront.services.CheckoutTenderService;
import villagecompute.storefront.services.ReportingProjectionService;
import villagecompute.storefront.storecredit.StoreCreditService;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Domain service encapsulating gift card issuance, redemption, and lifecycle management.
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>Secure code generation + hashing for lookups</li>
 * <li>Ledger transaction creation with idempotent redemption</li>
 * <li>Checkout tender recording + POS offline metadata</li>
 * <li>Conversion of remaining funds into store credit</li>
 * </ul>
 * </p>
 */
@ApplicationScoped
public class GiftCardService {

    private static final Logger LOG = Logger.getLogger(GiftCardService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> VALID_STATUSES = Set.of("active", "redeemed", "expired", "cancelled");
    private static final int CODE_SEGMENTS = 4;
    private static final int SEGMENT_LENGTH = 4;
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    @Inject
    EntityManager entityManager;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    ReportingProjectionService reportingProjectionService;

    @Inject
    CheckoutTenderService checkoutTenderService;

    @Inject
    StoreCreditService storeCreditService;

    /**
     * Issue a new gift card with a secure code.
     */
    @Transactional
    public GiftCard issueGiftCard(IssueGiftCardRequest request) {
        Objects.requireNonNull(request, "Request is required");
        validateInitialBalance(request.initialBalance);
        UUID tenantId = TenantContext.getCurrentTenantId();

        GiftCard giftCard = new GiftCard();
        giftCard.tenant = Tenant.findById(tenantId);
        giftCard.code = generateUniqueCode();
        giftCard.codeHash = hashCode(giftCard.code);
        giftCard.initialBalance = normalizeAmount(request.initialBalance);
        giftCard.currentBalance = giftCard.initialBalance;
        giftCard.currency = request.currency != null ? request.currency.toUpperCase(Locale.US) : "USD";
        giftCard.purchaserUser = resolveUser(request.purchaserUserId);
        giftCard.purchaserEmail = request.purchaserEmail;
        giftCard.recipientEmail = request.recipientEmail;
        giftCard.recipientName = request.recipientName;
        giftCard.personalMessage = request.personalMessage;
        giftCard.expiresAt = request.expiresAt;
        giftCard.sourceOrderId = request.sourceOrderId;

        giftCard.persist();
        entityManager.flush();

        GiftCardTransaction transaction = new GiftCardTransaction();
        transaction.tenant = giftCard.tenant;
        transaction.giftCard = giftCard;
        transaction.amount = giftCard.initialBalance;
        transaction.transactionType = "issued";
        transaction.balanceAfter = giftCard.currentBalance;
        transaction.reason = "Gift card issued";
        transaction.persist();

        LOG.infof("Issued gift card - tenantId=%s, giftCardId=%s, amount=%s %s", tenantId, giftCard.id,
                giftCard.initialBalance, giftCard.currency);
        meterRegistry.counter("giftcard.issued", "tenant_id", tenantId.toString()).increment();
        reportingProjectionService.recordGiftCardLedgerEvent(transaction);

        return giftCard;
    }

    /**
     * Lookup gift card balance by code (secure hash comparison).
     */
    @Transactional(TxType.SUPPORTS)
    public Optional<GiftCard> findByCode(String code) {
        String normalized = normalizeCode(code);
        String hash = hashCode(normalized);
        return Optional.ofNullable(GiftCard.findByCodeHash(hash));
    }

    /**
     * Redeem amount from a gift card using idempotency to ensure atomic checkout behavior.
     *
     * @return redemption result including actual amount applied and remaining balance
     */
    @Transactional
    public GiftCardRedemptionResult redeem(String code, BigDecimal requestedAmount, Long orderId, String idempotencyKey,
            Long posDeviceId, OffsetDateTime offlineSyncedAt) {
        validateRedemptionInput(code, requestedAmount, idempotencyKey);
        UUID tenantId = TenantContext.getCurrentTenantId();

        GiftCardTransaction existing = GiftCardTransaction.findByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            LOG.infof("Returning existing redemption (idempotency hit) - tenantId=%s, transactionId=%s", tenantId,
                    existing.id);
            return GiftCardRedemptionResult.fromExisting(existing);
        }

        GiftCard giftCard = lockCardByCode(code);
        ensureRedeemable(giftCard);

        BigDecimal amountToRedeem = giftCard.currentBalance.min(normalizeAmount(requestedAmount));
        if (amountToRedeem.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Gift card has no available balance");
        }

        giftCard.currentBalance = giftCard.currentBalance.subtract(amountToRedeem);
        OffsetDateTime now = OffsetDateTime.now();
        if (giftCard.activatedAt == null) {
            giftCard.activatedAt = now;
        }
        if (giftCard.currentBalance.compareTo(BigDecimal.ZERO) == 0) {
            giftCard.status = "redeemed";
            giftCard.fullyRedeemedAt = now;
        }

        GiftCardTransaction transaction = new GiftCardTransaction();
        transaction.tenant = giftCard.tenant;
        transaction.giftCard = giftCard;
        transaction.amount = amountToRedeem.negate();
        transaction.transactionType = "redeemed";
        transaction.balanceAfter = giftCard.currentBalance;
        transaction.orderId = orderId;
        transaction.idempotencyKey = idempotencyKey;
        transaction.posDeviceId = posDeviceId;
        transaction.offlineSyncedAt = offlineSyncedAt;
        transaction.reason = amountToRedeem.compareTo(requestedAmount) < 0 ? "Partial redemption applied"
                : "Checkout redemption";
        transaction.persist();

        checkoutTenderService.recordGiftCardTender(orderId, transaction);
        reportingProjectionService.recordGiftCardLedgerEvent(transaction);

        LOG.infof("Redeemed gift card - tenantId=%s, giftCardId=%s, amount=%s, remaining=%s", tenantId, giftCard.id,
                amountToRedeem, giftCard.currentBalance);
        meterRegistry.counter("giftcard.redeemed", "tenant_id", tenantId.toString()).increment();

        return GiftCardRedemptionResult.fromTransaction(transaction, amountToRedeem, giftCard.currentBalance,
                amountToRedeem.compareTo(requestedAmount) < 0);
    }

    /**
     * Return paged list of gift cards for tenant.
     */
    @Transactional(TxType.SUPPORTS)
    public List<GiftCard> listGiftCards(String status, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 200);

        if (status != null && !status.isBlank()) {
            return GiftCard
                    .find("tenant.id = :tenant and status = :status",
                            io.quarkus.panache.common.Parameters.with("tenant", tenantId).and("status", status))
                    .page(safePage, safeSize).list();
        }

        return GiftCard.find("tenant.id = ?1", tenantId).page(safePage, safeSize).list();
    }

    /**
     * Count gift cards for pagination metadata.
     */
    @Transactional(TxType.SUPPORTS)
    public long countGiftCards(String status) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        if (status != null && !status.isBlank()) {
            return GiftCard.count("tenant.id = ?1 and status = ?2", tenantId, status);
        }
        return GiftCard.count("tenant.id = ?1", tenantId);
    }

    /**
     * List transactions for gift card.
     */
    @Transactional(TxType.SUPPORTS)
    public List<GiftCardTransaction> listTransactions(Long giftCardId, int page, int size) {
        return GiftCardTransaction.findByGiftCard(giftCardId, Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    /**
     * Fetch gift card by id ensuring tenant scoping.
     */
    @Transactional(TxType.SUPPORTS)
    public GiftCard getGiftCard(Long giftCardId) {
        GiftCard card = GiftCard.findByIdAndTenant(giftCardId);
        if (card == null) {
            throw new IllegalArgumentException("Gift card not found: " + giftCardId);
        }
        return card;
    }

    /**
     * Update card status/expiry with optional zero-out adjustments.
     */
    @Transactional
    public GiftCard updateGiftCard(Long giftCardId, UpdateGiftCardRequest request) {
        GiftCard card = lockCardById(giftCardId);

        if (request.status != null && !request.status.isBlank()) {
            String normalizedStatus = request.status.toLowerCase(Locale.US);
            if (!VALID_STATUSES.contains(normalizedStatus)) {
                throw new IllegalArgumentException("Invalid gift card status: " + request.status);
            }
            if (!normalizedStatus.equals(card.status)) {
                handleStatusChange(card, normalizedStatus, request.reason);
            }
        }

        if (request.expiresAt != null) {
            card.expiresAt = request.expiresAt;
        }

        return card;
    }

    /**
     * Convert remaining card balance into store credit for the provided user.
     */
    @Transactional
    public GiftCardConversionResult convertToStoreCredit(Long giftCardId, UUID userId, String reason) {
        GiftCard card = lockCardById(giftCardId);
        ensureRedeemable(card);

        BigDecimal amount = card.currentBalance;
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Gift card has no remaining balance to convert");
        }

        StoreCreditTransaction creditTxn = storeCreditService.creditFromGiftCard(card, userId, amount, reason);

        card.currentBalance = BigDecimal.ZERO;
        OffsetDateTime now = OffsetDateTime.now();
        card.status = "redeemed";
        card.fullyRedeemedAt = now;

        GiftCardTransaction redemption = new GiftCardTransaction();
        redemption.tenant = card.tenant;
        redemption.giftCard = card;
        redemption.amount = amount.negate();
        redemption.transactionType = "redeemed";
        redemption.balanceAfter = BigDecimal.ZERO;
        redemption.reason = reason != null ? reason : "Converted to store credit";
        redemption.persist();

        reportingProjectionService.recordGiftCardLedgerEvent(redemption);

        return new GiftCardConversionResult(redemption, creditTxn);
    }

    private GiftCard lockCardByCode(String code) {
        String normalized = normalizeCode(code);
        String hash = hashCode(normalized);
        UUID tenantId = TenantContext.getCurrentTenantId();
        GiftCard card = entityManager
                .createQuery("select gc from GiftCard gc where gc.codeHash = :hash and gc.tenant.id = :tenant",
                        GiftCard.class)
                .setParameter("hash", hash).setParameter("tenant", tenantId).setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream().findFirst().orElse(null);
        if (card == null) {
            throw new IllegalArgumentException("Gift card not found");
        }
        return card;
    }

    private GiftCard lockCardById(Long giftCardId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        GiftCard card = entityManager
                .createQuery("select gc from GiftCard gc where gc.id = :id and gc.tenant.id = :tenant", GiftCard.class)
                .setParameter("id", giftCardId).setParameter("tenant", tenantId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst().orElse(null);
        if (card == null) {
            throw new IllegalArgumentException("Gift card not found: " + giftCardId);
        }
        return card;
    }

    private void handleStatusChange(GiftCard card, String newStatus, String reason) {
        if (("cancelled".equals(newStatus) || "expired".equals(newStatus))
                && card.currentBalance.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal amountLost = card.currentBalance;
            card.currentBalance = BigDecimal.ZERO;
            GiftCardTransaction adjustment = new GiftCardTransaction();
            adjustment.tenant = card.tenant;
            adjustment.giftCard = card;
            adjustment.amount = amountLost.negate();
            adjustment.transactionType = "adjusted";
            adjustment.balanceAfter = BigDecimal.ZERO;
            adjustment.reason = reason != null ? reason : "Balance cleared due to status change";
            adjustment.persist();
            reportingProjectionService.recordGiftCardLedgerEvent(adjustment);
        }
        card.status = newStatus;
    }

    private void ensureRedeemable(GiftCard giftCard) {
        if (!giftCard.isRedeemable()) {
            throw new IllegalStateException("Gift card is not redeemable");
        }
    }

    private void validateRedemptionInput(String code, BigDecimal amount, String idempotencyKey) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Gift card code is required");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key is required");
        }
    }

    private void validateInitialBalance(BigDecimal amount) {
        if (amount == null || amount.compareTo(new BigDecimal("0.01")) < 0) {
            throw new IllegalArgumentException("Initial balance must be at least 0.01");
        }
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String code = randomCode();
            if (GiftCard.find("codeHash = ?1", hashCode(code)).firstResult() == null) {
                return code;
            }
        }
        throw new IllegalStateException("Unable to generate secure gift card code");
    }

    private String randomCode() {
        StringBuilder builder = new StringBuilder();
        for (int segment = 0; segment < CODE_SEGMENTS; segment++) {
            if (segment > 0) {
                builder.append('-');
            }
            for (int i = 0; i < SEGMENT_LENGTH; i++) {
                int value = RANDOM.nextInt(36);
                char ch = value < 10 ? (char) ('0' + value) : (char) ('A' + (value - 10));
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private String normalizeCode(String code) {
        return code == null ? null : code.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.US);
    }

    private String hashCode(String code) {
        String normalized = normalizeCode(code);
        if (normalized == null) {
            throw new IllegalArgumentException("Code cannot be null");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to hash gift card code", e);
        }
    }

    private BigDecimal normalizeAmount(BigDecimal amount) {
        return amount.setScale(2, MONEY_ROUNDING);
    }

    private User resolveUser(UUID userId) {
        if (userId == null) {
            return null;
        }
        User user = User.findById(userId);
        if (user == null) {
            throw new IllegalArgumentException("User not found: " + userId);
        }
        if (!user.tenant.id.equals(TenantContext.getCurrentTenantId())) {
            throw new IllegalArgumentException("User does not belong to current tenant");
        }
        return user;
    }

    /**
     * Value object describing redemption outcomes (amount applied and transaction reference).
     */
    public record GiftCardRedemptionResult(GiftCardTransaction transaction, BigDecimal amountRedeemed,
            BigDecimal remainingBalance, boolean partial) {

        static GiftCardRedemptionResult fromExisting(GiftCardTransaction existing) {
            BigDecimal redeemed = existing.amount != null ? existing.amount.abs() : BigDecimal.ZERO;
            BigDecimal remaining = existing.balanceAfter != null ? existing.balanceAfter : BigDecimal.ZERO;
            boolean partial = existing.reason != null && existing.reason.contains("Partial");
            return new GiftCardRedemptionResult(existing, redeemed, remaining, partial);
        }

        static GiftCardRedemptionResult fromTransaction(GiftCardTransaction txn, BigDecimal amount,
                BigDecimal remaining, boolean partial) {
            return new GiftCardRedemptionResult(txn, amount, remaining, partial);
        }
    }

    /**
     * Encapsulates conversion flows returning both ledger entries.
     */
    public record GiftCardConversionResult(GiftCardTransaction giftCardTransaction,
            StoreCreditTransaction storeCreditTransaction) {
    }
}
