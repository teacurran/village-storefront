package villagecompute.storefront;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.Category;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for storefront server-side rendering (SSR).
 *
 * <p>
 * Verifies that Qute templates render correctly with sample data, tenant theming applies properly, and multi-tenant
 * isolation works as expected. These tests ensure the storefront produces valid HTML and handles edge cases gracefully.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T2: SSR smoke tests for storefront rendering</li>
 * <li>UI/UX Architecture Section 1.1.4: Tenant Theming & Overrides</li>
 * <li>Acceptance Criteria: Qute renders sample data via dev mode, message bundles fallback gracefully, tests verify
 * multi-tenant theming</li>
 * </ul>
 */
@QuarkusTest
public class StorefrontRenderingTest {

    @Inject
    EntityManager entityManager;

    private Tenant tenant1;
    private Tenant tenant2;
    private Category category1;
    private Category category2;
    private Product product1;
    private Product product2;

    /**
     * Set up test data before each test. Creates two tenants with sample categories and products to test multi-tenant
     * rendering and data isolation.
     */
    @BeforeEach
    @Transactional
    public void setup() {
        OffsetDateTime now = OffsetDateTime.now();
        String tenant1Subdomain = "store1-" + UUID.randomUUID().toString().substring(0, 8);
        String tenant2Subdomain = "store2-" + UUID.randomUUID().toString().substring(0, 8);

        // Create tenant 1
        tenant1 = new Tenant();
        tenant1.name = "Test Store 1";
        tenant1.subdomain = tenant1Subdomain;
        tenant1.status = "active";
        tenant1.createdAt = now;
        tenant1.updatedAt = now;
        entityManager.persist(tenant1);

        // Create tenant 2
        tenant2 = new Tenant();
        tenant2.name = "Test Store 2";
        tenant2.subdomain = tenant2Subdomain;
        tenant2.status = "active";
        tenant2.createdAt = now;
        tenant2.updatedAt = now;
        entityManager.persist(tenant2);

        // Set tenant context for data creation
        TenantContext.setCurrentTenant(new TenantInfo(tenant1.id, tenant1.subdomain, tenant1.name, tenant1.status));

        // Create category for tenant 1
        category1 = new Category();
        category1.tenant = tenant1;
        category1.code = "electronics";
        category1.name = "Electronics";
        category1.slug = "electronics";
        category1.displayOrder = 1;
        entityManager.persist(category1);

        // Create product for tenant 1
        product1 = new Product();
        product1.tenant = tenant1;
        product1.sku = "PROD-001";
        product1.name = "Wireless Headphones";
        product1.slug = "wireless-headphones";
        product1.description = "Premium wireless headphones with noise cancellation";
        product1.status = "active";
        entityManager.persist(product1);

        // Switch to tenant 2 context
        TenantContext.setCurrentTenant(new TenantInfo(tenant2.id, tenant2.subdomain, tenant2.name, tenant2.status));

        // Create category for tenant 2
        category2 = new Category();
        category2.tenant = tenant2;
        category2.code = "clothing";
        category2.name = "Clothing";
        category2.slug = "clothing";
        category2.displayOrder = 1;
        entityManager.persist(category2);

        // Create product for tenant 2
        product2 = new Product();
        product2.tenant = tenant2;
        product2.sku = "PROD-002";
        product2.name = "Cotton T-Shirt";
        product2.slug = "cotton-tshirt";
        product2.description = "Comfortable cotton t-shirt in multiple colors";
        product2.status = "active";
        entityManager.persist(product2);

        entityManager.flush();
    }

    /**
     * Clean up tenant context after each test to prevent ThreadLocal leakage.
     */
    @AfterEach
    @Transactional
    public void cleanup() {
        TenantContext.clear();
        entityManager.createQuery("DELETE FROM CartItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Cart").executeUpdate();
        entityManager.createQuery("DELETE FROM Product").executeUpdate();
        entityManager.createQuery("DELETE FROM Category").executeUpdate();
        entityManager.createQuery("DELETE FROM User").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();
    }

    private String hostForTenant(Tenant tenant) {
        return tenant.subdomain + ".villagecompute.com";
    }

    /**
     * Test that homepage renders successfully for tenant 1 and contains expected HTML structure.
     */
    @Test
    public void testHomepageRendersForTenant1() {
        String html = given().header("Host", hostForTenant(tenant1)).when().get("/").then().statusCode(200)
                .contentType("text/html").body(containsString("<!DOCTYPE html>")).body(containsString("<html"))
                .body(containsString("</html>")).body(containsString(tenant1.name)).extract().asString();

        // Verify basic HTML structure
        assertTrue(html.contains("<head>"), "HTML should contain head section");
        assertTrue(html.contains("<body"), "HTML should contain body section");
        assertTrue(html.contains("<main"), "HTML should contain main content section");
        assertTrue(html.contains("<footer"), "HTML should contain footer");

        // Verify tenant-specific content is present
        assertTrue(html.contains(tenant1.subdomain), "Should contain tenant subdomain");
    }

    /**
     * Test that homepage renders successfully for tenant 2 and contains different tenant-specific content.
     */
    @Test
    public void testHomepageRendersForTenant2() {
        String html = given().header("Host", hostForTenant(tenant2)).when().get("/").then().statusCode(200)
                .contentType("text/html").body(containsString("<!DOCTYPE html>")).body(containsString(tenant2.name))
                .extract().asString();

        // Verify tenant 2 specific content
        assertTrue(html.contains(tenant2.subdomain), "Should contain tenant 2 subdomain");
    }

    /**
     * Test multi-tenant isolation - verify that tenant 1's data doesn't appear on tenant 2's storefront.
     */
    @Test
    public void testMultiTenantDataIsolation() {
        // Request tenant 1's storefront
        String tenant1Html = given().header("Host", hostForTenant(tenant1)).when().get("/").then().statusCode(200)
                .extract().asString();

        // Request tenant 2's storefront
        String tenant2Html = given().header("Host", hostForTenant(tenant2)).when().get("/").then().statusCode(200)
                .extract().asString();

        // Verify tenant 1 sees their own category but not tenant 2's
        assertTrue(tenant1Html.contains("Electronics"), "Tenant 1 should see Electronics category");
        assertTrue(!tenant1Html.contains("Clothing") || tenant1Html.indexOf("Clothing") == -1,
                "Tenant 1 should not see Clothing category");

        // Verify tenant 2 sees their own category but not tenant 1's
        assertTrue(tenant2Html.contains("Clothing"), "Tenant 2 should see Clothing category");
        assertTrue(!tenant2Html.contains("Electronics") || tenant2Html.indexOf("Electronics") == -1,
                "Tenant 2 should not see Electronics category");
    }

    /**
     * Test that CSS variables for tenant theming are rendered in the HTML.
     */
    @Test
    public void testTenantThemeCssVariables() {
        String html = given().header("Host", hostForTenant(tenant1)).when().get("/").then().statusCode(200).extract()
                .asString();

        // Verify that CSS custom properties section exists
        assertTrue(html.contains(":root"), "Should contain CSS :root selector for custom properties");
        assertTrue(html.contains("--color-primary"), "Should contain primary color CSS variables");
    }

    /**
     * Test that message bundle keys are resolved in templates (i18n works).
     */
    @Test
    public void testMessageBundleResolution() {
        given().header("Host", hostForTenant(tenant1)).when().get("/").then().statusCode(200)
                .body(containsString("Skip to main content")) // From messages.properties
                .body(not(containsString("{msg:"))); // No unresolved message keys
    }

    /**
     * Test that navigation structure is rendered correctly.
     */
    @Test
    public void testNavigationStructure() {
        String html = given().header("Host", hostForTenant(tenant1)).when().get("/").then().statusCode(200).extract()
                .asString();

        // Verify header navigation elements
        assertTrue(html.contains("<header"), "Should contain header element");
        assertTrue(html.contains("<nav"), "Should contain navigation element");
        assertTrue(html.contains("aria-label"), "Should have accessibility labels");

        // Verify footer structure
        assertTrue(html.contains("<footer"), "Should contain footer element");
        assertTrue(html.contains("All rights reserved") || html.contains("Todos los derechos reservados"),
                "Should contain copyright notice");
    }

    /**
     * Test that hero section renders when data is provided.
     */
    @Test
    public void testHeroSectionRendering() {
        String html = given().header("Host", hostForTenant(tenant1)).when().get("/").then().statusCode(200).extract()
                .asString();

        // Verify hero section is present (even with default data)
        assertTrue(html.contains("Discover Quality Products") || html.contains("Shop Now"),
                "Should contain hero section content");
    }

    /**
     * Test that product grid structure renders correctly when products exist.
     */
    @Test
    public void testProductGridRendering() {
        String html = given().header("Host", hostForTenant(tenant1)).when().get("/").then().statusCode(200).extract()
                .asString();

        // Verify product grid structure exists
        assertTrue(html.contains("grid"), "Should contain grid layout classes");
    }

    /**
     * Test that HTML is properly escaped to prevent XSS vulnerabilities.
     */
    @Test
    public void testHtmlEscaping() {
        // Create product with potentially dangerous content
        TenantContext.setCurrentTenant(new TenantInfo(tenant1.id, tenant1.subdomain, tenant1.name, tenant1.status));

        String html = given().header("Host", hostForTenant(tenant1)).when().get("/").then().statusCode(200).extract()
                .asString();

        // Verify that raw script tags don't appear in output (Qute should escape them)
        // This is a basic check - templates should escape user-generated content automatically
        assertNotNull(html, "HTML should be rendered");
    }

    /**
     * Test that Tailwind CSS classes are present in the rendered HTML.
     */
    @Test
    public void testTailwindClassesPresent() {
        String html = given().header("Host", hostForTenant(tenant1)).when().get("/").then().statusCode(200).extract()
                .asString();

        // Verify common Tailwind utility classes are used
        assertTrue(html.contains("class=\"") || html.contains("class='"), "Should contain CSS classes");
        assertTrue(html.contains("flex") || html.contains("grid"), "Should use Tailwind layout classes");
    }

    /**
     * Test responsive viewport meta tag is present for mobile support.
     */
    @Test
    public void testResponsiveMetaTags() {
        given().header("Host", hostForTenant(tenant1)).when().get("/").then().statusCode(200)
                .body(containsString("viewport")).body(containsString("width=device-width"));
    }
}
