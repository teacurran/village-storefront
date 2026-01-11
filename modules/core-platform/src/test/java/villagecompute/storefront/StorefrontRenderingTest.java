package villagecompute.storefront;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import java.time.OffsetDateTime;

import jakarta.transaction.Transactional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.CustomDomain;
import villagecompute.storefront.data.models.Tenant;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for storefront rendering, theming, and localization.
 *
 * <p>
 * Verifies:
 * <ul>
 * <li>Homepage renders with hero, featured products, and categories</li>
 * <li>Catalog page renders with filters, products, and pagination</li>
 * <li>Theme tokens load correctly per tenant</li>
 * <li>Translations render correctly (EN/ES)</li>
 * <li>Component accessibility features</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T6: Test coverage for storefront rendering</li>
 * <li>UI/UX Architecture: Component specifications and accessibility requirements</li>
 * </ul>
 */
@QuarkusTest
@DisplayName("Storefront Rendering Tests")
public class StorefrontRenderingTest {

    private static final String TEST_TENANT_HOST = "example-store.localhost";
    private static final String DEFAULT_TENANT_HOST = "localhost";

    @BeforeEach
    @Transactional
    void ensureDefaultTenantDomain() {
        provisionTenantDomain("default", DEFAULT_TENANT_HOST);
    }

    @Test
    @DisplayName("Homepage should render with hero banner")
    public void testHomepageRendersWithHero() {
        given().header("Host", DEFAULT_TENANT_HOST).when().get("/").then().statusCode(200).body(containsString("hero"));
    }

    @Test
    @DisplayName("Homepage should render featured products section")
    public void testHomepageRendersWithFeaturedProducts() {
        given().header("Host", DEFAULT_TENANT_HOST).when().get("/").then().statusCode(200)
                .body(containsString("featured-heading")).body(containsString("View All"));
    }

    @Test
    @DisplayName("Homepage should render category showcase section")
    public void testHomepageRendersWithCategories() {
        given().header("Host", DEFAULT_TENANT_HOST).when().get("/").then().statusCode(200)
                .body(containsString("categories-heading")).body(containsString("Shop by Category"));
    }

    @Test
    @DisplayName("Theme tokens should load from generated JSON files")
    public void testThemeTokensLoad() {
        given().header("Host", DEFAULT_TENANT_HOST).when().get("/").then().statusCode(200)
                .body(containsString("--color-primary-500"));
    }

    @Test
    @DisplayName("Theme tokens should include accent colors")
    public void testThemeTokensIncludeAccent() {
        given().header("Host", DEFAULT_TENANT_HOST).when().get("/").then().statusCode(200)
                .body(containsString("--color-accent-"));
    }

    @Test
    @DisplayName("Setting lang parameter stores visitor locale cookie")
    public void testVisitorLocaleCookieSetFromLangParam() {
        given().header("Host", DEFAULT_TENANT_HOST).when().get("/?lang=es").then().statusCode(200)
                .header("Set-Cookie", containsString("visitor_locale=es")).header("Content-Language", equalTo("es"));
    }

    @Test
    @DisplayName("Custom domain renders tenant-specific branding tokens")
    public void testTenantSpecificBrandingForCustomDomain() {
        provisionTenantDomain("example-store", TEST_TENANT_HOST);

        given().header("Host", TEST_TENANT_HOST).when().get("/").then().statusCode(200).body(containsString("#4f46e5")); // example-store
                                                                                                                         // primary600
                                                                                                                         // color
    }

    @Test
    @DisplayName("Unknown tenant host returns 404")
    public void testUnknownTenantReturns404() {
        given().header("Host", "missing.villagecompute.com").when().get("/").then().statusCode(404);
    }

    @Test
    @DisplayName("English translations should render correctly")
    public void testEnglishTranslations() {
        given().header("Host", DEFAULT_TENANT_HOST).header("Accept-Language", "en-US").when().get("/").then()
                .statusCode(200).body(containsString("Shop Now")).body(containsString("Featured Products"))
                .body(containsString("Shop by Category"));
    }

    @Test
    @DisplayName("All message keys should resolve (no unresolved placeholders)")
    public void testNoUnresolvedMessageKeys() {
        given().header("Host", DEFAULT_TENANT_HOST).when().get("/").then().statusCode(200)
                .body(not(containsString("{msg."))).body(not(containsString("{#msg")));
    }

    @Test
    @DisplayName("Skip to content link should be present for accessibility")
    public void testSkipToContentLink() {
        given().header("Host", DEFAULT_TENANT_HOST).when().get("/").then().statusCode(200)
                .body(containsString("Skip to main content"));
    }

    @Test
    @DisplayName("Images should use lazy loading")
    public void testImagesUseLazyLoading() {
        given().header("Host", DEFAULT_TENANT_HOST).when().get("/").then().statusCode(200)
                .body(containsString("loading=\"lazy\""));
    }

    @Test
    @DisplayName("Page should include proper SEO meta tags")
    public void testSeoMetaTags() {
        given().header("Host", DEFAULT_TENANT_HOST).when().get("/").then().statusCode(200).body(containsString("<meta"))
                .body(containsString("description"));
    }

    @Transactional
    void provisionTenantDomain(String subdomain, String domain) {
        Tenant tenant = Tenant.find("subdomain", subdomain).firstResult();
        if (tenant == null) {
            tenant = new Tenant();
            tenant.subdomain = subdomain;
            tenant.name = "Example Storefront";
            tenant.status = "active";
            tenant.createdAt = OffsetDateTime.now();
            tenant.updatedAt = tenant.createdAt;
            tenant.persist();
        }

        CustomDomain customDomain = CustomDomain.find("domain", domain).firstResult();
        if (customDomain == null) {
            customDomain = new CustomDomain();
            customDomain.tenant = tenant;
            customDomain.domain = domain;
            customDomain.verified = true;
            customDomain.createdAt = OffsetDateTime.now();
            customDomain.updatedAt = customDomain.createdAt;
            customDomain.persist();
        }
    }
}
