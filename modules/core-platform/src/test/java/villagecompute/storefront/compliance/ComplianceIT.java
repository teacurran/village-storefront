package villagecompute.storefront.compliance;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.compliance.api.types.RecordConsentRequest;
import villagecompute.storefront.compliance.api.types.SubmitPrivacyRequestRequest;
import villagecompute.storefront.compliance.data.models.MarketingConsent;
import villagecompute.storefront.compliance.data.models.PrivacyRequest;
import villagecompute.storefront.compliance.data.repositories.MarketingConsentRepository;
import villagecompute.storefront.compliance.data.repositories.PrivacyRequestRepository;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.User;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

/**
 * Integration tests for compliance automation workflows.
 *
 * <p>
 * Tests privacy export/delete workflows, consent management, and platform console integration.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T6: Compliance automation (testing guidance)</li>
 * <li>Architecture: 03_Verification_and_Glossary.md (Integration Tests)</li>
 * </ul>
 */
@QuarkusTest
public class ComplianceIT {

    @Inject
    ComplianceService complianceService;

    @Inject
    PrivacyRequestRepository privacyRequestRepo;

    @Inject
    MarketingConsentRepository consentRepo;

    private UUID testTenantId;
    private UUID testUserId;

    @BeforeEach
    @Transactional
    public void setup() {
        // Create test tenant
        Tenant tenant = new Tenant();
        tenant.subdomain = "compliance-test-" + UUID.randomUUID().toString().substring(0, 8);
        tenant.name = "Compliance Test Store";
        tenant.status = "active";
        tenant.persist();
        testTenantId = tenant.id;

        // Set tenant context
        TenantContext.setCurrentTenantId(testTenantId);

        // Create test user representing the customer
        User user = new User();
        user.tenant = tenant;
        user.email = "testuser@example.com";
        user.firstName = "Test";
        user.lastName = "User";
        user.status = "active";
        user.persist();
        testUserId = user.id;
    }

    @Test
    @TestSecurity(
            user = "admin@platform.com",
            roles = {"platform_admin"})
    @Transactional
    public void testSubmitExportRequest() {
        SubmitPrivacyRequestRequest request = new SubmitPrivacyRequestRequest("export", "admin@platform.com",
                "testuser@example.com", "GDPR data subject request", "TICKET-123");

        given().contentType(ContentType.JSON).body(request).when()
                .post("/api/v1/platform/compliance/privacy-requests/export").then().statusCode(201)
                .body("requestId", notNullValue()).body("status", equalTo("pending_review"));

        // Verify request persisted
        TenantContext.setCurrentTenantId(testTenantId);
        long count = privacyRequestRepo.count("requestType", PrivacyRequest.RequestType.EXPORT);
        assertTrue(count > 0, "Export request should be persisted");
    }

    @Test
    @TestSecurity(
            user = "admin@platform.com",
            roles = {"platform_admin"})
    @Transactional
    public void testSubmitDeleteRequest() {
        SubmitPrivacyRequestRequest request = new SubmitPrivacyRequestRequest("delete", "admin@platform.com",
                "testuser@example.com", "CCPA right to erasure", "TICKET-456");

        given().contentType(ContentType.JSON).body(request).when()
                .post("/api/v1/platform/compliance/privacy-requests/delete").then().statusCode(201)
                .body("requestId", notNullValue()).body("status", equalTo("pending_review"));

        // Verify request persisted
        TenantContext.setCurrentTenantId(testTenantId);
        long count = privacyRequestRepo.count("requestType", PrivacyRequest.RequestType.DELETE);
        assertTrue(count > 0, "Delete request should be persisted");
    }

    @Test
    @Transactional
    public void testApproveExportRequestAndProcessJob() {
        TenantContext.setCurrentTenantId(testTenantId);

        // Submit export request
        UUID requestId = complianceService.submitExportRequest("admin@platform.com", "testuser@example.com",
                "Test export", "TICKET-789");

        // Approve request
        UUID jobId = complianceService.approveExportRequest(requestId, "approver@platform.com",
                "Approved for compliance review");

        assertNotNull(jobId, "Job ID should be returned");

        // Verify request status updated
        PrivacyRequest request = PrivacyRequest.findById(requestId);
        assertEquals(PrivacyRequest.RequestStatus.APPROVED, request.status);
        assertEquals("approver@platform.com", request.approvedByEmail);
        assertNotNull(request.approvedAt);

        // Process export job
        boolean processed = complianceService.processNextExportJob();
        assertTrue(processed, "Export job should be processed");

        // Verify request completed
        request = PrivacyRequest.findById(requestId);
        assertEquals(PrivacyRequest.RequestStatus.COMPLETED, request.status);
        assertNotNull(request.resultUrl, "Signed download URL should be generated");
        assertNotNull(request.completedAt);
    }

    @Test
    @Transactional
    public void testApproveDeleteRequestAndProcessJob() {
        TenantContext.setCurrentTenantId(testTenantId);

        // Submit delete request
        UUID requestId = complianceService.submitDeleteRequest("admin@platform.com", "testuser@example.com",
                "Test deletion", "TICKET-999");

        // Approve request
        UUID jobId = complianceService.approveDeleteRequest(requestId, "approver@platform.com",
                "Approved for deletion");

        assertNotNull(jobId, "Job ID should be returned");

        // Verify request status updated
        PrivacyRequest request = PrivacyRequest.findById(requestId);
        assertEquals(PrivacyRequest.RequestStatus.APPROVED, request.status);

        // Process soft-delete job
        boolean processed = complianceService.processNextDeleteJob();
        assertTrue(processed, "Delete job should be processed");

        // Verify request awaiting purge
        request = PrivacyRequest.findById(requestId);
        assertEquals(PrivacyRequest.RequestStatus.AWAITING_PURGE, request.status);

        // Enqueue and process purge job
        int enqueued = complianceService.enqueueDuePurgeJobs();
        assertTrue(enqueued > 0, "Purge job should be enqueued when retention elapsed");

        boolean purgeProcessed = complianceService.processNextDeleteJob();
        assertTrue(purgeProcessed, "Purge job should be processed");

        request = PrivacyRequest.findById(requestId);
        assertEquals(PrivacyRequest.RequestStatus.COMPLETED, request.status);
        assertNotNull(request.completedAt);
    }

    @Test
    @TestSecurity(
            user = "user@store.com",
            roles = {"store_user"})
    @Transactional
    public void testRecordMarketingConsent() {
        RecordConsentRequest request = new RecordConsentRequest(testUserId, "email", true, "web_form", "opt_in",
                "192.168.1.1", "Mozilla/5.0", "Customer subscribed via homepage form");

        given().contentType(ContentType.JSON).body(request).when()
                .post("/api/v1/platform/compliance/marketing-consents").then().statusCode(201)
                .body("consentId", notNullValue());

        // Verify consent persisted
        TenantContext.setCurrentTenantId(testTenantId);
        MarketingConsent consent = consentRepo.findLatestByCustomerAndChannel(testUserId, "email");
        assertNotNull(consent, "Consent should be persisted");
        assertTrue(consent.consented);
        assertEquals("web_form", consent.consentSource);
        assertEquals("opt_in", consent.consentMethod);
    }

    @Test
    @TestSecurity(
            user = "user@store.com",
            roles = {"store_user"})
    @Transactional
    public void testGetCustomerConsents() {
        TenantContext.setCurrentTenantId(testTenantId);

        // Create consent records
        User customer = User.findById(testUserId);
        Tenant tenant = Tenant.findById(testTenantId);

        MarketingConsent consent1 = new MarketingConsent();
        consent1.tenant = tenant;
        consent1.user = customer;
        consent1.channel = "email";
        consent1.consented = true;
        consent1.consentSource = "web_form";
        consent1.consentMethod = "opt_in";
        consentRepo.persist(consent1);

        MarketingConsent consent2 = new MarketingConsent();
        consent2.tenant = tenant;
        consent2.user = customer;
        consent2.channel = "sms";
        consent2.consented = false;
        consent2.consentSource = "customer_service";
        consent2.consentMethod = "opt_out";
        consentRepo.persist(consent2);

        // Retrieve via API
        given().when().get("/api/v1/platform/compliance/marketing-consents/customer/" + testUserId).then()
                .statusCode(200).body("consents", hasSize(2)).body("consents[0].channel", isOneOf("email", "sms"));
    }

    @Test
    @TestSecurity(
            user = "admin@platform.com",
            roles = {"platform_admin"})
    @Transactional
    public void testListPrivacyRequests() {
        TenantContext.setCurrentTenantId(testTenantId);

        // Create test requests
        complianceService.submitExportRequest("admin1@test.com", "user1@test.com", "Export 1", "T1");
        complianceService.submitDeleteRequest("admin2@test.com", "user2@test.com", "Delete 1", "T2");

        given().when().get("/api/v1/platform/compliance/privacy-requests").then().statusCode(200)
                .body("requests", hasSize(greaterThanOrEqualTo(2))).body("total", greaterThanOrEqualTo(2));
    }

    @Test
    @TestSecurity(
            user = "admin@platform.com",
            roles = {"platform_admin"})
    @Transactional
    public void testGetComplianceMetrics() {
        TenantContext.setCurrentTenantId(testTenantId);

        // Create test data
        complianceService.submitExportRequest("admin@test.com", "user@test.com", "Test", "T1");
        complianceService.submitDeleteRequest("admin@test.com", "user2@test.com", "Test", "T2");

        given().when().get("/api/v1/platform/compliance/metrics").then().statusCode(200)
                .body("pendingExports", greaterThanOrEqualTo(0)).body("pendingDeletes", greaterThanOrEqualTo(0))
                .body("exportQueueDepth", greaterThanOrEqualTo(0)).body("deleteQueueDepth", greaterThanOrEqualTo(0));
    }

    @Test
    @Transactional
    public void testExportGeneratesZippedJsonl() {
        TenantContext.setCurrentTenantId(testTenantId);

        // Submit and approve export
        UUID requestId = complianceService.submitExportRequest("admin@test.com", "testuser@example.com",
                "Test export format", "T123");
        complianceService.approveExportRequest(requestId, "approver@test.com", "Approved");

        // Process export job
        complianceService.processNextExportJob();

        // Verify request completed with signed URL
        PrivacyRequest request = PrivacyRequest.findById(requestId);
        assertNotNull(request.resultUrl, "Export should generate signed URL");
        assertTrue(request.resultUrl.contains("privacy-exports"), "URL should reference exports path");
    }

    @Test
    @Transactional
    public void testQueueMetrics() {
        TenantContext.setCurrentTenantId(testTenantId);

        int initialExportDepth = complianceService.getExportQueueDepth();
        int initialDeleteDepth = complianceService.getDeleteQueueDepth();

        // Enqueue jobs
        UUID exportReqId = complianceService.submitExportRequest("admin@test.com", "user@test.com", "Test", "T1");
        complianceService.approveExportRequest(exportReqId, "approver@test.com", "OK");

        UUID deleteReqId = complianceService.submitDeleteRequest("admin@test.com", "user2@test.com", "Test", "T2");
        complianceService.approveDeleteRequest(deleteReqId, "approver@test.com", "OK");

        // Verify queue depth increased
        int newExportDepth = complianceService.getExportQueueDepth();
        int newDeleteDepth = complianceService.getDeleteQueueDepth();

        assertTrue(newExportDepth > initialExportDepth, "Export queue should have jobs");
        assertTrue(newDeleteDepth > initialDeleteDepth, "Delete queue should have jobs");
    }
}
