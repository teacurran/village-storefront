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

import villagecompute.storefront.api.types.IssueGiftCardRequest;
import villagecompute.storefront.api.types.RedeemGiftCardRequest;
import villagecompute.storefront.api.types.ResendGiftCardRequest;
import villagecompute.storefront.api.types.UpdateGiftCardRequest;
import villagecompute.storefront.data.models.GiftCard;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.User;
import villagecompute.storefront.giftcard.GiftCardService;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;

/**
 * Integration tests for {@link GiftCardResource}.
 *
 * <p>
 * Tests cover HTTP endpoints for gift card issuance, redemption, balance checks, admin operations, and audit trails.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T4: Gift card subsystem with secure hashing, redemption, and resend flows</li>
 * <li>OpenAPI: /api/v1/gift-cards and /api/v1/admin/gift-cards endpoints</li>
 * </ul>
 */
@QuarkusTest
class GiftCardResourceIT {

    @Inject
    EntityManager entityManager;

    @Inject
    GiftCardService giftCardService;

    private UUID tenantId;
    private UUID userId;
    private String giftCardCode;
    private Long giftCardId;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up existing data
        entityManager.createQuery("DELETE FROM GiftCardTransaction").executeUpdate();
        entityManager.createQuery("DELETE FROM GiftCard").executeUpdate();
        entityManager.createQuery("DELETE FROM StoreCreditTransaction").executeUpdate();
        entityManager.createQuery("DELETE FROM StoreCreditAccount").executeUpdate();
        entityManager.createQuery("DELETE FROM User").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();

        // Create test tenant
        Tenant tenant = new Tenant();
        tenant.subdomain = "giftcard-it";
        tenant.name = "Gift Card IT Tenant";
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
        user.email = "giftcard-it@example.com";
        user.status = "active";
        user.emailVerified = true;
        user.createdAt = OffsetDateTime.now();
        user.updatedAt = OffsetDateTime.now();
        entityManager.persist(user);
        entityManager.flush();
        userId = user.id;

        // Create test gift card
        IssueGiftCardRequest request = new IssueGiftCardRequest();
        request.initialBalance = new BigDecimal("100.00");
        request.currency = "USD";
        request.recipientEmail = "recipient@example.com";
        GiftCard card = giftCardService.issueGiftCard(request);
        giftCardCode = card.code;
        giftCardId = card.id;
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void checkBalanceReturnsGiftCardDetails() {
        given().header("X-Tenant-ID", tenantId.toString()).contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\": \"" + giftCardCode + "\"}").when().post("/api/v1/gift-cards/check-balance").then()
                .statusCode(Response.Status.OK.getStatusCode()).body("balance", equalTo("100.00"))
                .body("currency", equalTo("USD")).body("status", equalTo("active"));
    }

    @Test
    void checkBalanceReturns404ForInvalidCode() {
        given().header("X-Tenant-ID", tenantId.toString()).contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\": \"INVALID-CODE-9999\"}").when().post("/api/v1/gift-cards/check-balance").then()
                .statusCode(Response.Status.NOT_FOUND.getStatusCode());
    }

    @Test
    void redeemGiftCardDeductsBalance() {
        RedeemGiftCardRequest request = new RedeemGiftCardRequest();
        request.code = giftCardCode;
        request.amount = new BigDecimal("30.00");
        request.orderId = UUID.randomUUID();
        request.idempotencyKey = "redeem-test-" + UUID.randomUUID();

        given().header("X-Tenant-ID", tenantId.toString()).header("X-Idempotency-Key", request.idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).body(request).when().post("/api/v1/gift-cards/redeem").then()
                .statusCode(Response.Status.OK.getStatusCode()).body("amount", equalTo("-30.00"))
                .body("balanceAfter", equalTo("70.00")).body("transactionType", equalTo("redeemed"));
    }

    @Test
    void redeemIsIdempotent() {
        String idempotencyKey = "idempotent-redeem-" + UUID.randomUUID();
        RedeemGiftCardRequest request = new RedeemGiftCardRequest();
        request.code = giftCardCode;
        request.amount = new BigDecimal("25.00");
        request.orderId = UUID.randomUUID();
        request.idempotencyKey = idempotencyKey;

        // First redemption
        given().header("X-Tenant-ID", tenantId.toString()).header("X-Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).body(request).when().post("/api/v1/gift-cards/redeem").then()
                .statusCode(Response.Status.OK.getStatusCode());

        // Second redemption with same idempotency key
        given().header("X-Tenant-ID", tenantId.toString()).header("X-Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).body(request).when().post("/api/v1/gift-cards/redeem").then()
                .statusCode(Response.Status.OK.getStatusCode()).body("balanceAfter", equalTo("75.00")); // Should still
                                                                                                        // be 75, not 50
    }

    @Test
    void redeemRequiresIdempotencyKey() {
        RedeemGiftCardRequest request = new RedeemGiftCardRequest();
        request.code = giftCardCode;
        request.amount = new BigDecimal("10.00");
        request.orderId = UUID.randomUUID();

        given().header("X-Tenant-ID", tenantId.toString()).contentType(MediaType.APPLICATION_JSON).body(request).when()
                .post("/api/v1/gift-cards/redeem").then().statusCode(Response.Status.BAD_REQUEST.getStatusCode());
    }

    @Test
    void listTransactionsReturnsLedgerEntries() {
        given().header("X-Tenant-ID", tenantId.toString()).queryParam("page", 0).queryParam("size", 10).when()
                .get("/api/v1/gift-cards/transactions/" + giftCardId).then()
                .statusCode(Response.Status.OK.getStatusCode()).body("size()", greaterThan(0));
    }

    @Test
    @TestSecurity(
            user = "admin@example.com",
            roles = {"admin"})
    void adminCanListGiftCards() {
        given().header("X-Tenant-ID", tenantId.toString()).queryParam("page", 0).queryParam("size", 20).when()
                .get("/api/v1/admin/gift-cards").then().statusCode(Response.Status.OK.getStatusCode())
                .body("data.size()", greaterThan(0)).body("pagination.total", greaterThan(0));
    }

    @Test
    @TestSecurity(
            user = "admin@example.com",
            roles = {"admin"})
    void adminCanIssueGiftCard() {
        IssueGiftCardRequest request = new IssueGiftCardRequest();
        request.initialBalance = new BigDecimal("250.00");
        request.currency = "USD";
        request.recipientEmail = "admin-issued@example.com";
        request.recipientName = "Test Recipient";

        given().header("X-Tenant-ID", tenantId.toString()).contentType(MediaType.APPLICATION_JSON).body(request).when()
                .post("/api/v1/admin/gift-cards").then().statusCode(Response.Status.CREATED.getStatusCode())
                .body("id", notNullValue()).body("code", notNullValue()).body("initialBalance", equalTo("250.00"))
                .body("currentBalance", equalTo("250.00")).body("status", equalTo("active"));
    }

    @Test
    @TestSecurity(
            user = "admin@example.com",
            roles = {"admin"})
    void adminCanGetGiftCard() {
        given().header("X-Tenant-ID", tenantId.toString()).when().get("/api/v1/admin/gift-cards/" + giftCardId).then()
                .statusCode(Response.Status.OK.getStatusCode()).body("id", equalTo(giftCardId.intValue()))
                .body("currentBalance", equalTo("100.00"));
    }

    @Test
    @TestSecurity(
            user = "admin@example.com",
            roles = {"admin"})
    void adminCanUpdateGiftCardStatus() {
        UpdateGiftCardRequest request = new UpdateGiftCardRequest();
        request.status = "cancelled";
        request.reason = "Admin cancelled for testing";

        given().header("X-Tenant-ID", tenantId.toString()).contentType(MediaType.APPLICATION_JSON).body(request).when()
                .put("/api/v1/admin/gift-cards/" + giftCardId).then().statusCode(Response.Status.OK.getStatusCode())
                .body("status", equalTo("cancelled")).body("currentBalance", equalTo("0.00")); // Balance cleared on
                                                                                               // cancellation
    }

    @Test
    @TestSecurity(
            user = "admin@example.com",
            roles = {"admin"})
    void adminCanResendGiftCard() {
        ResendGiftCardRequest request = new ResendGiftCardRequest();
        request.recipientEmail = "resend-test@example.com";

        given().header("X-Tenant-ID", tenantId.toString()).contentType(MediaType.APPLICATION_JSON).body(request).when()
                .post("/api/v1/admin/gift-cards/" + giftCardId + "/resend").then()
                .statusCode(Response.Status.OK.getStatusCode()).body("id", equalTo(giftCardId.intValue()));
    }

    @Test
    @TestSecurity(
            user = "admin@example.com",
            roles = {"admin"})
    void adminCanFilterGiftCardsByStatus() {
        given().header("X-Tenant-ID", tenantId.toString()).queryParam("status", "active").queryParam("page", 0)
                .queryParam("size", 20).when().get("/api/v1/admin/gift-cards").then()
                .statusCode(Response.Status.OK.getStatusCode()).body("data.size()", greaterThan(0));
    }
}
