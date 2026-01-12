package villagecompute.storefront.api.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.api.types.AdjustStoreCreditRequest;
import villagecompute.storefront.api.types.ConvertGiftCardRequest;
import villagecompute.storefront.api.types.IssueGiftCardRequest;
import villagecompute.storefront.api.types.RedeemStoreCreditRequest;
import villagecompute.storefront.data.models.GiftCard;
import villagecompute.storefront.data.models.StoreCreditAccount;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.User;
import villagecompute.storefront.giftcard.GiftCardService;
import villagecompute.storefront.storecredit.StoreCreditService;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;

/**
 * Integration tests for {@link StoreCreditResource}.
 *
 * <p>
 * Tests cover HTTP endpoints for store credit balance checks, redemption, admin adjustments, and gift card conversions.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T4: Store credit subsystem with checkout integration and ledger entries</li>
 * <li>OpenAPI: /api/v1/store-credit and /api/v1/admin/store-credit endpoints</li>
 * </ul>
 */
@QuarkusTest
class StoreCreditResourceIT {

    @Inject
    EntityManager entityManager;

    @Inject
    StoreCreditService storeCreditService;

    @Inject
    GiftCardService giftCardService;

    private UUID tenantId;
    private UUID userId;
    private Long storeCreditAccountId;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up existing data
        entityManager.createQuery("DELETE FROM StoreCreditTransaction").executeUpdate();
        entityManager.createQuery("DELETE FROM StoreCreditAccount").executeUpdate();
        entityManager.createQuery("DELETE FROM GiftCardTransaction").executeUpdate();
        entityManager.createQuery("DELETE FROM GiftCard").executeUpdate();
        entityManager.createQuery("DELETE FROM User").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();

        // Create test tenant
        Tenant tenant = new Tenant();
        tenant.subdomain = "storecredit-it";
        tenant.name = "Store Credit IT Tenant";
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();
        tenantId = tenant.id;

        TenantContext.setCurrentTenant(new TenantInfo(tenantId, tenant.subdomain, tenant.name, tenant.status));

        // Create test user
        User user = new User();
        user.tenant = tenant;
        user.email = "storecredit-it@example.com";
        user.status = "active";
        user.emailVerified = true;
        user.createdAt = OffsetDateTime.now();
        user.updatedAt = OffsetDateTime.now();
        entityManager.persist(user);
        entityManager.flush();
        userId = user.id;

        // Create store credit account with balance
        StoreCreditAccount account = storeCreditService.getOrCreateAccount(userId);
        storeCreditService.adjust(userId, new BigDecimal("50.00"), "Initial credit for testing");
        storeCreditAccountId = account.id;
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void getBalanceReturnsAccountDetails() {
        given().header("X-Tenant-ID", tenantId.toString()).when().get("/api/v1/store-credit/balance/" + userId).then()
                .statusCode(Response.Status.OK.getStatusCode()).body("balance", equalTo("50.00"))
                .body("userId", equalTo(userId.toString())).body("status", equalTo("active"));
    }

    @Test
    void getBalanceReturns404ForNonExistentAccount() {
        UUID nonExistentUserId = UUID.randomUUID();
        given().header("X-Tenant-ID", tenantId.toString()).when()
                .get("/api/v1/store-credit/balance/" + nonExistentUserId).then()
                .statusCode(Response.Status.NOT_FOUND.getStatusCode());
    }

    @Test
    void redeemStoreCreditDeductsBalance() {
        RedeemStoreCreditRequest request = new RedeemStoreCreditRequest();
        request.amount = new BigDecimal("20.00");
        request.orderId = UUID.randomUUID();
        request.idempotencyKey = "sc-redeem-" + UUID.randomUUID();

        given().header("X-Tenant-ID", tenantId.toString()).header("X-Idempotency-Key", request.idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).body(request).when()
                .post("/api/v1/store-credit/redeem/" + userId).then().statusCode(Response.Status.OK.getStatusCode())
                .body("amount", equalTo("-20.00")).body("balanceAfter", equalTo("30.00"))
                .body("transactionType", equalTo("redeemed"));
    }

    @Test
    void redeemIsIdempotent() {
        String idempotencyKey = "sc-idempotent-" + UUID.randomUUID();
        RedeemStoreCreditRequest request = new RedeemStoreCreditRequest();
        request.amount = new BigDecimal("15.00");
        request.orderId = UUID.randomUUID();
        request.idempotencyKey = idempotencyKey;

        // First redemption
        given().header("X-Tenant-ID", tenantId.toString()).header("X-Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).body(request).when()
                .post("/api/v1/store-credit/redeem/" + userId).then().statusCode(Response.Status.OK.getStatusCode());

        // Second redemption with same key
        given().header("X-Tenant-ID", tenantId.toString()).header("X-Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).body(request).when()
                .post("/api/v1/store-credit/redeem/" + userId).then().statusCode(Response.Status.OK.getStatusCode())
                .body("balanceAfter", equalTo("35.00")); // Should still be 35, not 20
    }

    @Test
    void redeemRequiresIdempotencyKey() {
        RedeemStoreCreditRequest request = new RedeemStoreCreditRequest();
        request.amount = new BigDecimal("10.00");
        request.orderId = UUID.randomUUID();

        given().header("X-Tenant-ID", tenantId.toString()).contentType(MediaType.APPLICATION_JSON).body(request).when()
                .post("/api/v1/store-credit/redeem/" + userId).then()
                .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
    }

    @Test
    void listTransactionsReturnsLedgerEntries() {
        given().header("X-Tenant-ID", tenantId.toString()).queryParam("page", 0).queryParam("size", 10).when()
                .get("/api/v1/store-credit/transactions/" + userId).then()
                .statusCode(Response.Status.OK.getStatusCode()).body("size()", greaterThan(0));
    }

    @Test
    @TestSecurity(
            user = "admin@example.com",
            roles = {"admin"})
    void adminCanListStoreCreditAccounts() {
        given().header("X-Tenant-ID", tenantId.toString()).queryParam("page", 0).queryParam("size", 20).when()
                .get("/api/v1/admin/store-credit/accounts").then().statusCode(Response.Status.OK.getStatusCode())
                .body("data.size()", greaterThan(0)).body("pagination.total", greaterThan(0));
    }

    @Test
    @TestSecurity(
            user = "admin@example.com",
            roles = {"admin"})
    void adminCanAdjustStoreCredit() {
        AdjustStoreCreditRequest request = new AdjustStoreCreditRequest();
        request.amount = new BigDecimal("25.00");
        request.reason = "Admin adjustment for testing";

        given().header("X-Tenant-ID", tenantId.toString()).contentType(MediaType.APPLICATION_JSON).body(request).when()
                .post("/api/v1/admin/store-credit/adjust/" + userId).then()
                .statusCode(Response.Status.OK.getStatusCode()).body("amount", equalTo("25.00"))
                .body("transactionType", equalTo("adjusted")).body("balanceAfter", equalTo("75.00")); // 50 + 25
    }

    @Test
    @TestSecurity(
            user = "admin@example.com",
            roles = {"admin"})
    void adminCanConvertGiftCardToStoreCredit() {
        // Create a gift card first
        IssueGiftCardRequest giftCardRequest = new IssueGiftCardRequest();
        giftCardRequest.initialBalance = new BigDecimal("100.00");
        giftCardRequest.currency = "USD";
        GiftCard giftCard = giftCardService.issueGiftCard(giftCardRequest);

        // Convert it to store credit
        ConvertGiftCardRequest request = new ConvertGiftCardRequest();
        request.giftCardId = giftCard.id;
        request.userId = userId;
        request.reason = "Customer requested conversion";

        given().header("X-Tenant-ID", tenantId.toString()).contentType(MediaType.APPLICATION_JSON).body(request).when()
                .post("/api/v1/admin/store-credit/convert-gift-card").then()
                .statusCode(Response.Status.OK.getStatusCode()).body("giftCardTransaction", notNullValue())
                .body("storeCreditTransaction", notNullValue()).body("storeCreditTransaction.amount", equalTo("100.00"))
                .body("storeCreditTransaction.transactionType", equalTo("converted"));
    }

    @Test
    @TestSecurity(
            user = "admin@example.com",
            roles = {"admin"})
    void adminCanFilterStoreCreditAccountsByStatus() {
        given().header("X-Tenant-ID", tenantId.toString()).queryParam("status", "active").queryParam("page", 0)
                .queryParam("size", 20).when().get("/api/v1/admin/store-credit/accounts").then()
                .statusCode(Response.Status.OK.getStatusCode()).body("data.size()", greaterThan(0));
    }

    @Test
    void redeemPartialAmountWhenInsufficientBalance() {
        // Account has 50.00
        RedeemStoreCreditRequest request = new RedeemStoreCreditRequest();
        request.amount = new BigDecimal("100.00"); // Try to redeem more
        request.orderId = UUID.randomUUID();
        request.idempotencyKey = "sc-partial-" + UUID.randomUUID();

        given().header("X-Tenant-ID", tenantId.toString()).header("X-Idempotency-Key", request.idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).body(request).when()
                .post("/api/v1/store-credit/redeem/" + userId).then().statusCode(Response.Status.OK.getStatusCode())
                .body("amount", equalTo("-50.00")) // Only redeem what's available
                .body("balanceAfter", equalTo("0.00"));
    }
}
