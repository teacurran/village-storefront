package villagecompute.storefront.api.vendor;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.ConsignmentItem;
import villagecompute.storefront.data.models.Consignor;
import villagecompute.storefront.data.models.PayoutBatch;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.AttributeType;
import io.quarkus.test.security.SecurityAttribute;
import io.quarkus.test.security.TestSecurity;
import io.restassured.specification.RequestSpecification;

/**
 * Integration tests for {@link VendorPortalResource}.
 */
@QuarkusTest
class VendorPortalResourceTest {

    private static final String CONSIGNOR_ID = "11111111-2222-3333-4444-555555555555";

    @Inject
    EntityManager entityManager;

    private String tenantSubdomain;

    @BeforeEach
    @Transactional
    void setUp() {
        entityManager.createQuery("DELETE FROM PayoutLineItem").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutBatch").executeUpdate();
        entityManager.createQuery("DELETE FROM ConsignmentItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Consignor").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductVariant").executeUpdate();
        entityManager.createQuery("DELETE FROM Product").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();

        Tenant tenant = new Tenant();
        tenant.subdomain = "vendorportal";
        tenant.name = "Vendor Portal Tenant";
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();
        tenantSubdomain = tenant.subdomain;

        TenantContext.setCurrentTenant(new TenantInfo(tenant.id, tenant.subdomain, tenant.name, tenant.status));

        insertConsignorRow(tenant);
        Consignor consignor = entityManager.find(Consignor.class, UUID.fromString(CONSIGNOR_ID));

        Product product = new Product();
        product.tenant = tenant;
        product.sku = "PORTAL-1";
        product.name = "Portal Product";
        product.status = "active";
        product.type = "physical";
        product.createdAt = OffsetDateTime.now();
        product.updatedAt = OffsetDateTime.now();
        entityManager.persist(product);

        ConsignmentItem item = new ConsignmentItem();
        item.tenant = tenant;
        item.product = product;
        item.consignor = consignor;
        item.commissionRate = new BigDecimal("10.00");
        item.status = "active";
        item.createdAt = OffsetDateTime.now();
        item.updatedAt = OffsetDateTime.now();
        entityManager.persist(item);

        PayoutBatch batch = new PayoutBatch();
        batch.tenant = tenant;
        batch.consignor = consignor;
        batch.periodStart = LocalDate.now().minusMonths(1);
        batch.periodEnd = LocalDate.now();
        batch.totalAmount = BigDecimal.ZERO;
        batch.currency = "USD";
        batch.status = "pending";
        batch.createdAt = OffsetDateTime.now();
        batch.updatedAt = OffsetDateTime.now();
        entityManager.persist(batch);

        entityManager.flush();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @TestSecurity(
            user = "vendor-user",
            roles = {"vendor"},
            attributes = {@SecurityAttribute(
                    key = "consignor_id",
                    value = CONSIGNOR_ID,
                    type = AttributeType.STRING)})
    void testGetProfile() {
        vendorRequest().when().get("/api/v1/vendor/portal/profile").then().statusCode(200)
                .body("id", equalTo(CONSIGNOR_ID)).body("name", equalTo("Portal Vendor"));
    }

    @Test
    @TestSecurity(
            user = "vendor-user",
            roles = {"vendor"},
            attributes = {@SecurityAttribute(
                    key = "consignor_id",
                    value = CONSIGNOR_ID,
                    type = AttributeType.STRING)})
    void testListItems() {
        vendorRequest().queryParam("page", 0).queryParam("size", 10).when().get("/api/v1/vendor/portal/items").then()
                .statusCode(200).body("$", notNullValue());
    }

    @Test
    @TestSecurity(
            user = "vendor-user",
            roles = {"vendor"})
    void testMissingConsignorClaimReturnsForbidden() {
        vendorRequest().when().get("/api/v1/vendor/portal/profile").then().statusCode(403).body("title",
                equalTo("Forbidden"));
    }

    @Test
    @TestSecurity(
            user = "customer-user",
            roles = {"customer"},
            attributes = {@SecurityAttribute(
                    key = "consignor_id",
                    value = CONSIGNOR_ID,
                    type = AttributeType.STRING)})
    void testRequiresVendorRole() {
        vendorRequest().when().get("/api/v1/vendor/portal/profile").then().statusCode(403);
    }

    private void insertConsignorRow(Tenant tenant) {
        OffsetDateTime now = OffsetDateTime.now();
        entityManager.createNativeQuery(
                "INSERT INTO consignors (id, tenant_id, name, contact_info, payout_settings, status, version, created_at, updated_at) "
                        + "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)")
                .setParameter(1, UUID.fromString(CONSIGNOR_ID)).setParameter(2, tenant.id)
                .setParameter(3, "Portal Vendor").setParameter(4, "{\"email\":\"portal@example.com\"}")
                .setParameter(5, "{\"default_commission_rate\":15}").setParameter(6, "active").setParameter(7, 0L)
                .setParameter(8, now).setParameter(9, now).executeUpdate();
    }

    private RequestSpecification vendorRequest() {
        return given().header("Host", tenantSubdomain + ".villagecompute.com");
    }
}
