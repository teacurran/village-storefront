package villagecompute.storefront.api.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

import java.time.OffsetDateTime;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.Tenant;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class HealthResourceTest {

    @Inject
    EntityManager entityManager;

    @BeforeEach
    @Transactional
    void setupTenant() {
        entityManager.createQuery("DELETE FROM CartItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Cart").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryLevel").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductVariant").executeUpdate();
        entityManager.createQuery("DELETE FROM Product").executeUpdate();
        entityManager.createQuery("DELETE FROM Category").executeUpdate();
        entityManager.createQuery("DELETE FROM CustomDomain").executeUpdate();
        entityManager.createQuery("DELETE FROM User").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();
        entityManager.flush();

        Tenant tenant = new Tenant();
        tenant.subdomain = "healthtest";
        tenant.name = "Health Test Store";
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
    }

    @Test
    void testHealthEndpoint() {
        given().header("Host", "healthtest.villagecompute.com").when().get("/api/v1/health").then().statusCode(200)
                .body("status", is("ok"));
    }
}
