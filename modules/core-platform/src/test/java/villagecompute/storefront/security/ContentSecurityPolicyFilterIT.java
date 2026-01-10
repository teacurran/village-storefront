package villagecompute.storefront.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for Content-Security-Policy headers.
 *
 * <p>
 * Verifies that CSP headers are correctly applied to storefront and admin paths to prevent XSS attacks via inline
 * scripts, SVG uploads, and image metadata injection.
 *
 * <p>
 * <strong>Acceptance Criteria (Task I6.T2):</strong>
 * <ul>
 * <li>CSP headers present on storefront paths (/, /products, /cart, /checkout)</li>
 * <li>CSP headers present on admin paths (/admin/*)</li>
 * <li>CSP policy includes report-uri for violation reporting</li>
 * <li>CSP headers NOT applied to API paths (/api/*)</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I6.T2: Security hardening - CSP header implementation</li>
 * <li>Threat Model: docs/security/threat-models/media-pipeline.md (MED-002, MED-004)</li>
 * </ul>
 */
@QuarkusTest
@DisplayName("Content Security Policy Filter Integration Tests")
class ContentSecurityPolicyFilterIT {

    @Test
    @DisplayName("Storefront paths should have CSP headers with Stripe.js allowed")
    void testStorefrontCspHeaders() {
        String cspHeader = given().when().get("/").then().statusCode(200).extract().header("Content-Security-Policy");

        assertThat("CSP header should be present for storefront", cspHeader, notNullValue());
        assertThat("CSP should allow Stripe.js for payment processing", cspHeader,
                containsString("https://js.stripe.com"));
        assertThat("CSP should include report-uri", cspHeader, containsString("report-uri"));
        assertThat("CSP should restrict default sources to self", cspHeader, containsString("default-src 'self'"));
    }

    @Test
    @DisplayName("Product pages should have CSP headers")
    void testProductPageCspHeaders() {
        String cspHeader = given().when().get("/products").then().statusCode(404) // Endpoint may not exist yet
                .extract().header("Content-Security-Policy");

        // Even for 404 responses, CSP headers should be present
        assertThat("CSP header should be present for product pages", cspHeader, notNullValue());
    }

    @Test
    @DisplayName("Admin paths should have strict CSP policy (no inline scripts)")
    void testAdminCspHeaders() {
        String cspHeader = given().when().get("/admin").then().statusCode(200) // Admin SPA should return 200
                .extract().header("Content-Security-Policy");

        assertThat("CSP header should be present for admin", cspHeader, notNullValue());
        assertThat("Admin CSP should restrict script sources to self", cspHeader, containsString("script-src 'self'"));
        assertThat("Admin CSP should include report-uri", cspHeader, containsString("report-uri"));
        assertThat("Admin CSP should NOT allow Stripe.js (not needed in admin)", cspHeader,
                not(containsString("https://js.stripe.com")));
    }

    @Test
    @DisplayName("API endpoints should NOT have CSP headers (not applicable to JSON)")
    void testApiEndpointsNoCspHeaders() {
        String cspHeader = given().when().get("/api/v1/health").then().extract().header("Content-Security-Policy");

        assertThat("API endpoints should NOT have CSP headers (not HTML)", cspHeader, nullValue());
    }

    @Test
    @DisplayName("OpenAPI spec endpoint should NOT have CSP headers")
    void testOpenApiNoCspHeaders() {
        String cspHeader = given().when().get("/openapi").then().extract().header("Content-Security-Policy");

        assertThat("OpenAPI endpoint should NOT have CSP headers", cspHeader, nullValue());
    }

    @Test
    @DisplayName("Health check endpoints should NOT have CSP headers")
    void testHealthCheckNoCspHeaders() {
        String cspHeader = given().when().get("/q/health").then().extract().header("Content-Security-Policy");

        assertThat("Health check endpoint should NOT have CSP headers", cspHeader, nullValue());
    }

    @Test
    @DisplayName("CSP report-uri should be accessible")
    void testCspReportEndpointAccessible() {
        given().contentType("application/json").body("""
                {
                  "csp-report": {
                    "document-uri": "https://example.com/test",
                    "violated-directive": "script-src 'self'",
                    "blocked-uri": "https://evil.com/malicious.js"
                  }
                }
                """).when().post("/api/v1/csp-report").then().statusCode(204);
    }

    @Test
    @DisplayName("CSP report endpoint should reject invalid payloads")
    void testCspReportEndpointValidation() {
        given().contentType("application/json").body("{}").when().post("/api/v1/csp-report").then().statusCode(400);
    }
}
