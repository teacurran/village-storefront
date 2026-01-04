# Gift Cards & Store Credit

> **Task I4.T6** - Gift card/store credit modules with checkout/POS integration
> **Version:** 1.0
> **Last Updated:** 2026-01-09

## Overview

Village Storefront supports gift cards and store credit as alternative tender types alongside traditional card payments. Both features integrate with the checkout orchestrator to enable multi-tender transactions (e.g., partial gift card + card payment) and work seamlessly with POS offline flows.

### Key Features

- **Gift Cards**
  - Secure code generation with SHA-256 hashing
  - Partial redemption support
  - Expiration dates and lifecycle management
  - Purchasable via orders or issued by admins
  - Public balance checking without authentication

- **Store Credit**
  - One account per user per tenant
  - Issued via refunds, gift card conversions, or manual adjustments
  - Ledger-based transaction history
  - Admin adjustments with audit trails

- **Integration Points**
  - Multi-tender checkout flows
  - POS offline redemption with sync
  - Reporting/analytics via `ReportingProjectionService`
  - Loyalty program gift card rewards

---

## Architecture

### Database Schema

#### Gift Cards

```sql
gift_cards (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    code VARCHAR(32) NOT NULL,              -- Display code (XXXX-XXXX-XXXX-XXXX)
    code_hash VARCHAR(128) NOT NULL UNIQUE, -- SHA-256 hash for secure lookups
    initial_balance NUMERIC(12,2),
    current_balance NUMERIC(12,2),
    currency VARCHAR(3) DEFAULT 'USD',
    status VARCHAR(20),                     -- active, redeemed, expired, cancelled
    expires_at TIMESTAMPTZ,
    ...
)
```

> **Implementation note:** The production code paths use `CheckoutTenderService` to create `payment_tenders` rows for every gift card or store credit redemption, so multi-tender splits remain consistent across checkout, POS, and reporting.

**Indexes:**
- `idx_gift_cards_code_hash` - Fast lookups via hash (prevents enumeration attacks)
- `idx_gift_cards_tenant_id` - Tenant isolation
- `idx_gift_cards_status` - Filtered index for active cards

#### Gift Card Transactions

```sql
gift_card_transactions (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL,
    gift_card_id BIGINT NOT NULL REFERENCES gift_cards(id),
    order_id BIGINT,                        -- Order where redeemed
    amount NUMERIC(12,2),                   -- Positive for load, negative for redemption
    transaction_type VARCHAR(20),           -- issued, redeemed, refunded, adjusted, expired
    balance_after NUMERIC(12,2),
    idempotency_key VARCHAR(255) UNIQUE,    -- Prevents duplicate redemptions
    pos_device_id BIGINT,                   -- POS offline support
    ...
)
```

#### Store Credit

```sql
store_credit_accounts (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id),
    balance NUMERIC(12,2) DEFAULT 0.00,
    currency VARCHAR(3) DEFAULT 'USD',
    status VARCHAR(20),                     -- active, suspended, closed
    UNIQUE (tenant_id, user_id)
)

store_credit_transactions (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES store_credit_accounts(id),
    gift_card_id BIGINT,                    -- If converted from gift card
    amount NUMERIC(12,2),
    transaction_type VARCHAR(20),           -- issued, redeemed, refunded, adjusted, converted
    balance_after NUMERIC(12,2),
    idempotency_key VARCHAR(255) UNIQUE,
    ...
)
```

### Security Design

#### Gift Card Code Security

1. **Code Generation**
   - Cryptographically secure random alphanumeric strings (16-20 chars)
   - Formatted for readability: `ABCD-EFGH-IJKL-MNOP`
   - Never stored in plain text in logs or analytics

2. **Secure Lookups**
   - All lookups use SHA-256 `code_hash` instead of raw code
   - Prevents database enumeration attacks
   - Timing-safe comparisons prevent side-channel leaks

3. **Balance Checking**
   - Public endpoint (no auth required) for customer convenience
   - Rate-limited to 10 requests/min per IP to prevent scraping
   - Returns minimal info (balance, status) - no PII

---

## Gift Card Lifecycle

### 1. Issuance

Gift cards can be created via three paths:

#### A. Purchase via Order

When a customer buys a physical or digital gift card product:

```java
// Checkout completes successfully
Order order = checkoutService.captureOrder(orderId);

IssueGiftCardRequest request = new IssueGiftCardRequest();
request.initialBalance = lineItem.getAmount();
request.currency = order.currency;
request.recipientEmail = recipientEmail;
request.recipientName = recipientName;
request.personalMessage = personalMessage;
request.sourceOrderId = order.id;

GiftCard giftCard = giftCardService.issueGiftCard(request);
notificationService.sendGiftCardEmail(giftCard);
```

#### B. Admin Manual Issuance

Store admins can issue gift cards for promotions or customer service:

```http
POST /api/v1/admin/gift-cards
Authorization: Bearer {admin_jwt}

{
  "initialBalance": 50.00,
  "currency": "USD",
  "recipientEmail": "customer@example.com",
  "recipientName": "Jane Doe",
  "personalMessage": "Enjoy your gift!",
  "expiresAt": "2027-01-01T00:00:00Z"
}
```

#### C. Promotional Campaigns

Bulk issuance for marketing campaigns:

```java
for (int i = 0; i < 100; i++) {
    IssueGiftCardRequest request = new IssueGiftCardRequest();
    request.initialBalance = BigDecimal.valueOf(25);
    request.currency = "USD";
    request.personalMessage = "Summer 2026 promo";
    giftCardService.issueGiftCard(request);
}
```

### 2. Activation

Gift cards are **pre-activated** (status = `active`) upon issuance. The `activated_at` timestamp is set on first redemption to track usage patterns.

### 3. Balance Checking

Customers can check balances without authentication:

```http
POST /api/v1/gift-cards/check-balance
Content-Type: application/json

{
  "code": "ABCD-EFGH-IJKL-MNOP"
}

# Response
{
  "currentBalance": 42.50,
  "currency": "USD",
  "status": "active",
  "expiresAt": "2027-01-01T00:00:00Z"
}
```

### 4. Redemption

#### Single-Tender Redemption (Full Amount)

```http
POST /api/v1/gift-cards/redeem
X-Idempotency-Key: checkout_12345_giftcard_abcd
Content-Type: application/json

{
  "code": "ABCD-EFGH-IJKL-MNOP",
  "amount": 42.50,
  "orderId": 12345
}
```

#### Multi-Tender Redemption (Partial + Card)

When order total exceeds gift card balance:

```java
// Checkout orchestrator saga
checkoutSaga.execute(orderId, steps -> {
    // Step 1: Apply gift card (partial)
    GiftCardTransaction gcTx = giftCardService.redeem(
        giftCardCode,
        giftCardBalance,  // e.g., $42.50
        orderId,
        idempotencyKey
    );

    // Step 2: Charge remaining to credit card
    BigDecimal remainingAmount = orderTotal.subtract(gcTx.amount.abs());
    PaymentIntent pi = paymentService.createPaymentIntent(
        remainingAmount,  // e.g., $57.50
        currency,
        orderId,
        true,  // captureImmediately
        idempotencyKey
    );

    // Step 3: Record multi-tender split
    paymentTenderService.recordTenders(orderId, List.of(gcTx, pi));
});
```

**Idempotency Guarantees:**
- Duplicate redemption requests with same `idempotency_key` return existing transaction
- Prevents double-charging during checkout retries
- Keys expire after 24 hours

### 5. Refunds

When an order is refunded, gift card redemptions are reversed:

```java
// Order refund flow
void refundOrder(Long orderId) {
    List<PaymentTender> tenders = paymentTenderService.findByOrder(orderId);

    for (PaymentTender tender : tenders) {
        if (tender.tenderType == TenderType.GIFT_CARD) {
            giftCardService.refund(
                tender.giftCardId,
                tender.amount,
                "Order #" + orderId + " refund",
                orderId
            );
        } else if (tender.tenderType == TenderType.CARD) {
            paymentService.refundPayment(tender.paymentIntentId, ...);
        }
    }
}
```

### 6. Expiration

Scheduled job expires cards past their `expires_at` timestamp:

```java
@Scheduled(cron = "0 0 2 * * ?")  // 2 AM daily
void expireGiftCards() {
    List<GiftCard> expiring = GiftCard.find(
        "status = 'active' AND expires_at < ?1",
        OffsetDateTime.now()
    ).list();

    for (GiftCard card : expiring) {
        if (card.currentBalance.compareTo(BigDecimal.ZERO) > 0) {
            // Create expiration transaction
            GiftCardTransaction tx = new GiftCardTransaction();
            tx.amount = card.currentBalance.negate();
            tx.transactionType = "expired";
            tx.balanceAfter = BigDecimal.ZERO;
            tx.persist();

            // Update card status
            card.status = "expired";
            card.currentBalance = BigDecimal.ZERO;
            card.persist();

            meterRegistry.counter("giftcard.expired", "tenant", tenantId).increment();
        }
    }
}
```

---

## Store Credit Lifecycle

### 1. Issuance

Store credit can be added to customer accounts via:

#### A. Order Refunds

When an order is refunded to store credit instead of original payment method:

```java
void refundToStoreCredit(Long orderId, UUID userId, BigDecimal amount, String reason) {
    StoreCreditAccount account = StoreCreditAccount.findOrCreateByUser(userId);

    StoreCreditTransaction tx = new StoreCreditTransaction();
    tx.account = account;
    tx.orderId = orderId;
    tx.amount = amount;  // Positive for credit
    tx.transactionType = "refunded";
    tx.reason = reason;
    tx.balanceAfter = account.balance.add(amount);
    tx.persist();

    account.balance = tx.balanceAfter;
    account.persist();

    reportingProjectionService.recordStoreCreditLedgerEvent(tx);
}
```

#### B. Gift Card Conversion

Admins can convert remaining gift card balance to store credit:

```http
POST /api/v1/admin/store-credit/convert-gift-card
Authorization: Bearer {admin_jwt}

{
  "giftCardId": 456,
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "reason": "Customer requested conversion"
}
```

Atomically:
1. Debit gift card balance → 0
2. Credit store credit account
3. Link transactions via `gift_card_id` foreign key

#### C. Manual Adjustments

For customer service or promotional credits:

```http
POST /api/v1/admin/store-credit/adjust/{userId}
Authorization: Bearer {admin_jwt}

{
  "amount": 10.00,
  "reason": "Apology for shipping delay"
}
```

### 2. Redemption

Store credit redemption follows same multi-tender pattern as gift cards:

```http
POST /api/v1/store-credit/redeem/{userId}
X-Idempotency-Key: checkout_12345_storecredit
Content-Type: application/json

{
  "amount": 15.00,
  "orderId": 12345
}
```

**Validations:**
- Account must be `active` status
- Amount must not exceed current balance
- Idempotency key required

### 3. Expiration

Store credit **does not expire** by default. Tenants can configure expiration policies via feature flags if required by local regulations.

---

## POS Offline Integration

### Offline Redemption Flow

When POS devices lose connectivity, gift cards and store credit can still be redeemed locally:

1. **POS device maintains local cache** of active gift card codes and store credit balances
2. **Redemption is optimistically recorded** in local SQLite database
3. **When connectivity restores**, transactions sync to server

#### Sync Protocol

```java
// POS sync endpoint
@POST
@Path("/api/v1/pos/sync-offline-transactions")
void syncOfflineTransactions(@HeaderParam("X-POS-Device-ID") Long deviceId,
                              List<OfflineTransaction> transactions) {
    for (OfflineTransaction tx : transactions) {
        try {
            if (tx.tenderType == TenderType.GIFT_CARD) {
                giftCardService.redeemOffline(
                    tx.code,
                    tx.amount,
                    tx.orderId,
                    deviceId,
                    tx.localTimestamp,
                    tx.idempotencyKey
                );
            } else if (tx.tenderType == TenderType.STORE_CREDIT) {
                storeCreditService.redeemOffline(
                    tx.userId,
                    tx.amount,
                    tx.orderId,
                    deviceId,
                    tx.localTimestamp,
                    tx.idempotencyKey
                );
            }
        } catch (InsufficientBalanceException e) {
            // Mark transaction for manual review
            offlineReconciliationService.flagForReview(tx, e.getMessage());
        }
    }
}
```

**Conflict Resolution:**
- **Insufficient balance**: Flag for manual review, refund customer or adjust account
- **Duplicate idempotency key**: Ignore (already synced)
- **Expired card**: Flag for review, may honor based on offline timestamp

---

## Multi-Tender Checkout Flow

### Payment Tender Split Tracking

The `payment_tenders` table tracks all tender types used in an order:

```sql
INSERT INTO payment_tenders (
    tenant_id, order_id, tender_type, amount, currency,
    gift_card_id, store_credit_account_id, payment_intent_id, status
) VALUES
(tenant_uuid, 12345, 'gift_card', 42.50, 'USD', 456, NULL, NULL, 'captured'),
(tenant_uuid, 12345, 'store_credit', 10.00, 'USD', NULL, 789, NULL, 'captured'),
(tenant_uuid, 12345, 'card', 47.50, 'USD', NULL, NULL, 111, 'captured');
```

### Checkout Saga Example

```java
public class CheckoutSaga {

    public void executeCheckout(Long orderId, CheckoutRequest request) {
        Order order = Order.findById(orderId);
        BigDecimal remainingAmount = order.total;
        List<PaymentTender> tenders = new ArrayList<>();

        // Step 1: Apply gift cards (in request order)
        for (GiftCardPayment gcp : request.giftCardPayments) {
            BigDecimal redeemAmount = gcp.amount.min(remainingAmount);
            GiftCardTransaction tx = giftCardService.redeem(
                gcp.code, redeemAmount, orderId, gcp.idempotencyKey
            );
            tenders.add(createTender(order, tx));
            remainingAmount = remainingAmount.subtract(redeemAmount);
            if (remainingAmount.compareTo(BigDecimal.ZERO) <= 0) break;
        }

        // Step 2: Apply store credit
        if (request.useStoreCredit && remainingAmount.compareTo(BigDecimal.ZERO) > 0) {
            StoreCreditAccount account = StoreCreditAccount.findByUser(request.userId);
            if (account != null && account.balance.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal creditAmount = account.balance.min(remainingAmount);
                StoreCreditTransaction tx = storeCreditService.redeem(
                    request.userId, creditAmount, orderId, request.idempotencyKey
                );
                tenders.add(createTender(order, tx));
                remainingAmount = remainingAmount.subtract(creditAmount);
            }
        }

        // Step 3: Charge remaining to credit card (if needed)
        if (remainingAmount.compareTo(BigDecimal.ZERO) > 0) {
            PaymentIntent pi = paymentService.createPaymentIntent(
                remainingAmount, order.currency, orderId, true, request.idempotencyKey
            );
            tenders.add(createTender(order, pi));
        }

        // Step 4: Persist tender records
        paymentTenderRepository.persistAll(tenders);

        // Step 5: Mark order as paid
        order.status = "paid";
        order.paidAt = OffsetDateTime.now();
        order.persist();
    }
}
```

---

## API Reference

### Gift Cards

| Endpoint | Method | Auth | Description |
|----------|--------|------|-------------|
| `/gift-cards/check-balance` | POST | None | Check gift card balance (rate-limited) |
| `/gift-cards/redeem` | POST | Yes | Redeem gift card during checkout |
| `/gift-cards/transactions/{id}` | GET | Yes | List gift card transaction history |
| `/admin/gift-cards` | GET | Admin | List all gift cards with filters |
| `/admin/gift-cards` | POST | Admin | Issue new gift card |
| `/admin/gift-cards/{id}` | GET | Admin | Get gift card details |
| `/admin/gift-cards/{id}` | PUT | Admin | Update gift card status/balance |

**POS Offline Support:** The redemption endpoints accept optional `posDeviceId` and `offlineSyncedAt` fields so POS devices can apply balances while offline and sync ledger events back to the platform once connectivity is restored. These values bubble through to transaction history for reconciliation.

### Store Credit

| Endpoint | Method | Auth | Description |
|----------|--------|------|-------------|
| `/store-credit/balance/{userId}` | GET | Yes | Get store credit balance |
| `/store-credit/redeem/{userId}` | POST | Yes | Redeem store credit during checkout |
| `/store-credit/transactions/{userId}` | GET | Yes | List transaction history |
| `/admin/store-credit/accounts` | GET | Admin | List all store credit accounts |
| `/admin/store-credit/adjust/{userId}` | POST | Admin | Manually adjust balance |
| `/admin/store-credit/convert-gift-card` | POST | Admin | Convert gift card → store credit |

See `api/v1/openapi.yaml` for complete schema definitions.

---

## Observability

### Metrics

Gift card and store credit services emit Micrometer metrics:

```java
// Gift cards
meterRegistry.counter("giftcard.issued", "tenant", tenantId).increment();
meterRegistry.counter("giftcard.redeemed", "tenant", tenantId, "amount_bucket", bucket).increment();
meterRegistry.counter("giftcard.expired", "tenant", tenantId).increment();
meterRegistry.timer("giftcard.redemption.duration", "tenant", tenantId).record(duration);

// Store credit
meterRegistry.counter("storecredit.issued", "tenant", tenantId, "source", source).increment();
meterRegistry.counter("storecredit.redeemed", "tenant", tenantId).increment();
meterRegistry.gauge("storecredit.total_balance", account.balance, "tenant", tenantId);
```

### Logging

Structured logging for audit and troubleshooting:

```java
LOG.infof("[Tenant: %s] Gift card redeemed - code=%s, amount=%s, orderId=%s, balance_after=%s",
    tenantId, maskCode(code), amount, orderId, balanceAfter);

LOG.warnf("[Tenant: %s] Gift card redemption failed - code=%s, reason=insufficient_balance",
    tenantId, maskCode(code));
```

**Code Masking**: Only last 4 chars shown in logs (e.g., `****-****-****-MNOP`)

### Reporting Integration

All gift card and store credit transactions feed into `ReportingProjectionService`:

```java
reportingProjectionService.recordGiftCardLedgerEvent(giftCardTransaction);
reportingProjectionService.recordStoreCreditLedgerEvent(storeCreditTransaction);
```

Enables dashboards for:
- Outstanding gift card liability
- Store credit liability by tenant
- Redemption rate trends
- Average gift card purchase amount
- POS offline reconciliation reports

---

## Security & Fraud Prevention

### Rate Limiting

- **Balance checks**: 10 requests/min per IP (public endpoint)
- **Redemptions**: 20 requests/min per user (idempotency prevents duplicates)

### Suspicious Activity Detection

Monitor for:
- **Rapid sequential balance checks** (code enumeration attempts)
- **Multiple failed redemptions** (invalid codes or insufficient balance)
- **Unusual redemption patterns** (e.g., 50 gift cards on one order)

```java
@Scheduled(every = "5m")
void detectSuspiciousActivity() {
    // Flag accounts with >100 failed redemptions in 1 hour
    List<Alert> alerts = fraudDetectionService.detectGiftCardAbuse(
        Duration.ofHours(1),
        100  // threshold
    );

    for (Alert alert : alerts) {
        notificationService.alertAdmins(alert);
        // Optionally auto-suspend account or enable CAPTCHA
    }
}
```

### PCI DSS Considerations

Gift card codes are **not considered PCI data** (not credit card numbers), but should still be protected:
- Never log full codes (mask in logs/UI)
- Use HTTPS for all API calls
- Encrypt codes at rest (database-level encryption recommended)

---

## Configuration

### Feature Flags

Control gift card/store credit features per tenant:

```java
@ConfigProperty(name = "storefront.giftcards.enabled", defaultValue = "true")
boolean giftCardsEnabled;

@ConfigProperty(name = "storefront.giftcards.code-length", defaultValue = "16")
int giftCardCodeLength;

@ConfigProperty(name = "storefront.giftcards.default-expiration-days", defaultValue = "365")
int defaultExpirationDays;

@ConfigProperty(name = "storefront.storecredit.enabled", defaultValue = "true")
boolean storeCreditEnabled;

@ConfigProperty(name = "storefront.storecredit.auto-convert-refunds", defaultValue = "false")
boolean autoConvertRefundsToCredit;
```

### Per-Tenant Settings

Tenants can configure via admin dashboard:
- Enable/disable gift card purchases
- Set minimum/maximum gift card amounts
- Configure expiration policies
- Allow/disallow partial redemptions
- Enable automatic refund → store credit conversion

---

## Testing Guidance

### Unit Tests

Key test scenarios:

```java
@Test
void testGiftCardRedemption_PartialAmount() {
    GiftCard card = createTestGiftCard(BigDecimal.valueOf(100));
    GiftCardTransaction tx = giftCardService.redeem(
        card.code, BigDecimal.valueOf(42.50), 123L, "test-key"
    );

    assertEquals(BigDecimal.valueOf(-42.50), tx.amount);
    assertEquals(BigDecimal.valueOf(57.50), tx.balanceAfter);
    assertEquals("active", card.status);
}

@Test
void testGiftCardRedemption_Idempotency() {
    GiftCard card = createTestGiftCard(BigDecimal.valueOf(100));

    // First redemption
    GiftCardTransaction tx1 = giftCardService.redeem(
        card.code, BigDecimal.valueOf(50), 123L, "dup-key"
    );

    // Duplicate request with same idempotency key
    GiftCardTransaction tx2 = giftCardService.redeem(
        card.code, BigDecimal.valueOf(50), 123L, "dup-key"
    );

    assertEquals(tx1.id, tx2.id);  // Returns same transaction
    assertEquals(BigDecimal.valueOf(50), card.currentBalance);  // Not double-charged
}
```

### Integration Tests

Multi-tender checkout scenarios:

```java
@QuarkusTest
@TestTransaction
class MultiTenderCheckoutTest {

    @Test
    void testCheckout_GiftCardPlusCard() {
        // Setup
        Order order = createTestOrder(BigDecimal.valueOf(100));
        GiftCard card = createTestGiftCard(BigDecimal.valueOf(60));

        // Execute checkout with multi-tender
        CheckoutRequest request = new CheckoutRequest();
        request.giftCardPayments = List.of(
            new GiftCardPayment(card.code, card.currentBalance, "key-1")
        );
        request.cardPaymentMethodId = "pm_test_visa";

        checkoutSaga.executeCheckout(order.id, request);

        // Verify
        List<PaymentTender> tenders = PaymentTender.findByOrder(order.id);
        assertEquals(2, tenders.size());

        PaymentTender gcTender = tenders.stream()
            .filter(t -> t.tenderType == TenderType.GIFT_CARD)
            .findFirst().orElseThrow();
        assertEquals(BigDecimal.valueOf(60), gcTender.amount);

        PaymentTender cardTender = tenders.stream()
            .filter(t -> t.tenderType == TenderType.CARD)
            .findFirst().orElseThrow();
        assertEquals(BigDecimal.valueOf(40), cardTender.amount);

        assertEquals("paid", order.status);
    }
}
```

### Load Tests

Gift card balance check endpoint (rate limiting):

```bash
# Simulate 100 concurrent balance checks
ab -n 1000 -c 100 -p giftcard-check.json \
   -T application/json \
   https://storename.villagecompute.com/api/v1/gift-cards/check-balance
```

Expected: 429 Too Many Requests after rate limit threshold

---

## References

- **OpenAPI Spec**: `api/v1/openapi.yaml` (gift card/store credit schemas)
- **Database Migration**: `src/main/resources/db/migrations/V20260109__gift_card_store_credit.sql`
- **Architecture Blueprint**: `.codemachine/artifacts/architecture/01_Blueprint_Foundation.md` (Section 4)
- **ERD**: `.codemachine/artifacts/architecture/02_System_Structure_and_Data.md`
- **Task Spec**: `.codemachine/artifacts/plan/02_Iteration_I4.md` (Task I4.T6)
- **Java Standards**: `docs/java-project-standards.adoc`

---

## Runbook

### Common Operations

#### Issue Gift Card

```bash
curl -X POST https://storename.villagecompute.com/api/v1/admin/gift-cards \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{
    "initialBalance": 50.00,
    "recipientEmail": "customer@example.com",
    "expiresAt": "2027-01-01T00:00:00Z"
  }'
```

#### Check Gift Card Balance

```bash
curl -X POST https://storename.villagecompute.com/api/v1/gift-cards/check-balance \
  -H "Content-Type: application/json" \
  -d '{"code": "ABCD-EFGH-IJKL-MNOP"}'
```

#### Adjust Store Credit

```bash
curl -X POST https://storename.villagecompute.com/api/v1/admin/store-credit/adjust/$USER_ID \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{
    "amount": 10.00,
    "reason": "Customer service credit"
  }'
```

#### Convert Gift Card to Store Credit

```bash
curl -X POST https://storename.villagecompute.com/api/v1/admin/store-credit/convert-gift-card \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{
    "giftCardId": 456,
    "userId": "550e8400-e29b-41d4-a716-446655440000",
    "reason": "Customer request"
  }'
```

### Troubleshooting

#### Gift Card Not Found

**Symptom**: `404 Not Found` when redeeming gift card

**Causes**:
1. Code entered incorrectly (typo)
2. Card belongs to different tenant
3. Card expired/cancelled

**Resolution**:
```sql
-- Check if code exists (as admin)
SELECT id, code, status, current_balance, expires_at
FROM gift_cards
WHERE code = 'XXXX-XXXX-XXXX-XXXX';

-- If expired, manually reactivate if within policy
UPDATE gift_cards
SET status = 'active', expires_at = NOW() + INTERVAL '1 year'
WHERE id = 456;
```

#### POS Offline Transactions Not Syncing

**Symptom**: POS reports successful redemption but server shows no record

**Resolution**:
1. Check POS device connectivity
2. Verify `X-POS-Device-ID` header matches registered device
3. Check idempotency key collisions:

```sql
SELECT * FROM gift_card_transactions
WHERE idempotency_key = 'pos_offline_12345';
```

4. Manually reconcile if needed via admin adjustment

---

## Future Enhancements

- [ ] **Digital gift card delivery** - Email/SMS with code + PDF attachment
- [ ] **Gift card balance transfers** - Move balance between cards
- [ ] **Scheduled gift card delivery** - Send on specific date
- [ ] **Loyalty points → gift card conversion**
- [ ] **Tiered gift card pricing** - Bonus for higher denominations (e.g., spend $100 get $110 card)
- [ ] **Store credit autopay** - Automatically apply to future orders
- [ ] **Gift card analytics dashboard** - Redemption rates, popular amounts, expiration tracking
