package villagecompute.storefront.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import villagecompute.storefront.data.models.CustomDomain;
import villagecompute.storefront.data.models.CustomDomain.DomainStatus;
import villagecompute.storefront.data.models.Tenant;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Unit tests for DomainValidationJob. Verifies DNS verification logic, retry backoff, and state transitions.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T4: Custom Domain SSL Automation</li>
 * <li>Migration: V20260132__custom_domain_ssl_automation.sql</li>
 * </ul>
 *
 * <p>
 * <strong>Run with:</strong> {@code mvn test -Dtest=DomainValidationJobTest}
 */
@QuarkusTest
public class DomainValidationJobTest {

    @Inject
    DomainValidationJob domainValidationJob;

    @Inject
    EntityManager entityManager;

    @Inject
    ObjectMapper objectMapper;

    private UUID testTenantId;
    private UUID testDomainId;

    @BeforeEach
    @Transactional
    public void setupTestData() {
        // Clean up existing test data
        entityManager.createQuery("DELETE FROM CustomDomain").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();
        entityManager.flush();

        // Create test tenant
        Tenant tenant = new Tenant();
        tenant.subdomain = "testshop";
        tenant.name = "Test Shop";
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();
        testTenantId = tenant.id;

        // Create test custom domain in PENDING state
        CustomDomain domain = new CustomDomain();
        domain.tenant = tenant;
        domain.domain = "shop.example.com";
        domain.verified = false;
        domain.verificationToken = "test-verification-token-12345";
        domain.status = DomainStatus.PENDING;
        domain.createdAt = OffsetDateTime.now();
        domain.updatedAt = OffsetDateTime.now();

        // Create DNS challenge JSON
        ObjectNode dnsChallenge = objectMapper.createObjectNode();
        dnsChallenge.put("type", "TXT");
        dnsChallenge.put("name", "_acme-challenge.shop.example.com");
        dnsChallenge.put("value", "test-verification-token-12345");
        domain.dnsChallenge = dnsChallenge;

        entityManager.persist(domain);
        entityManager.flush();
        testDomainId = domain.id;
    }

    @Test
    @Transactional
    public void testVerifyDomain_DnsRecordNotFound_MarksFailedWithRetry() {
        // Given: Domain with PENDING status (DNS record doesn't exist in test)
        CustomDomain domain = entityManager.find(CustomDomain.class, testDomainId);
        assertNotNull(domain);
        assertEquals(DomainStatus.PENDING, domain.status);

        // When: Job attempts verification
        domainValidationJob.verifyDomain(domain);
        entityManager.flush();
        entityManager.clear();

        // Then: Domain should be marked with verification error (DNS not found or query failed)
        domain = entityManager.find(CustomDomain.class, testDomainId);
        assertNotNull(domain.verificationErrorMessage, "Verification error message should be set");
        // DNS error message may vary depending on test environment
        assertTrue(
                domain.verificationErrorMessage.contains("DNS") || domain.verificationErrorMessage.contains("not found")
                        || domain.verificationErrorMessage.contains("query failed"),
                "Error message should indicate DNS issue. Actual: " + domain.verificationErrorMessage);
        assertEquals(1, domain.verificationRetryCount);
        assertNotNull(domain.lastVerificationAttempt);
    }

    @Test
    @Transactional
    public void testVerifyDomain_RetryCountIncrementsOnFailure() {
        // Given: Domain with existing retry count
        CustomDomain domain = entityManager.find(CustomDomain.class, testDomainId);
        domain.verificationRetryCount = 2;
        domain.lastVerificationAttempt = OffsetDateTime.now().minusHours(5);
        entityManager.persist(domain);
        entityManager.flush();

        // When: Job attempts verification again
        domainValidationJob.verifyDomain(domain);
        entityManager.flush();
        entityManager.clear();

        // Then: Retry count should increment
        domain = entityManager.find(CustomDomain.class, testDomainId);
        assertEquals(3, domain.verificationRetryCount);
    }

    @Test
    @Transactional
    public void testVerifyDomain_MissingDnsChallengeData_ReturnsError() {
        // Given: Domain with malformed DNS challenge
        CustomDomain domain = entityManager.find(CustomDomain.class, testDomainId);
        domain.dnsChallenge = null; // Simulate missing challenge data
        entityManager.persist(domain);
        entityManager.flush();

        // When: Job attempts verification
        domainValidationJob.verifyDomain(domain);
        entityManager.flush();
        entityManager.clear();

        // Then: Should fail with descriptive error
        domain = entityManager.find(CustomDomain.class, testDomainId);
        assertNotNull(domain.verificationErrorMessage);
        assertTrue(domain.verificationErrorMessage.contains("DNS challenge data missing"));
        assertEquals(1, domain.verificationRetryCount);
        assertEquals(DomainStatus.FAILED, domain.status);
    }

    @Test
    @Transactional
    public void testVerifyDomain_UpdatesLastVerificationAttemptTimestamp() {
        // Given: Domain with no previous attempts
        CustomDomain domain = entityManager.find(CustomDomain.class, testDomainId);
        assertNull(domain.lastVerificationAttempt);

        OffsetDateTime beforeVerification = OffsetDateTime.now();

        // When: Job attempts verification
        domainValidationJob.verifyDomain(domain);
        entityManager.flush();
        entityManager.clear();

        // Then: Timestamp should be set
        domain = entityManager.find(CustomDomain.class, testDomainId);
        assertNotNull(domain.lastVerificationAttempt);
        assertTrue(domain.lastVerificationAttempt.isAfter(beforeVerification));
    }

    @Test
    @Transactional
    public void testVerifyDomains_SkipsPendingDomainsWithNoRetryNeeded() {
        // Given: FAILED domain that was just attempted (not eligible for retry)
        CustomDomain domain = entityManager.find(CustomDomain.class, testDomainId);
        domain.status = DomainStatus.FAILED;
        domain.lastVerificationAttempt = OffsetDateTime.now(); // Just attempted
        domain.verificationRetryCount = 1;
        entityManager.persist(domain);
        entityManager.flush();

        int initialRetryCount = domain.verificationRetryCount;

        // When: Job runs (should skip this domain due to backoff)
        domainValidationJob.verifyDomains();
        entityManager.flush();
        entityManager.clear();

        // Then: Retry count should NOT increment (domain was skipped)
        domain = entityManager.find(CustomDomain.class, testDomainId);
        assertEquals(initialRetryCount, domain.verificationRetryCount);
    }

    @Test
    @Transactional
    public void testVerifyDomains_ProcessesEligibleFailedDomains() {
        // Given: FAILED domain eligible for retry (last attempt > 1 hour ago)
        CustomDomain domain = entityManager.find(CustomDomain.class, testDomainId);
        domain.status = DomainStatus.FAILED;
        domain.lastVerificationAttempt = OffsetDateTime.now().minusHours(2); // Eligible for retry
        domain.verificationRetryCount = 0;
        entityManager.persist(domain);
        entityManager.flush();

        // When: Job runs
        domainValidationJob.verifyDomains();
        entityManager.flush();
        entityManager.clear();

        // Then: Domain should be processed (retry count incremented)
        domain = entityManager.find(CustomDomain.class, testDomainId);
        assertEquals(1, domain.verificationRetryCount);
    }

    /**
     * Integration test placeholder: Successful verification flow requires mocking DNS resolver or running against test
     * DNS infrastructure.
     *
     * <p>
     * Full integration test would:
     * <ol>
     * <li>Set up test DNS zone with TXT record</li>
     * <li>Trigger verification job</li>
     * <li>Assert domain status transitions to ACTIVE</li>
     * <li>Verify CustomDomainVerified event is fired</li>
     * </ol>
     */
    @Test
    public void testVerifyDomain_SuccessfulVerification_MarksActive() {
        // TODO: Implement integration test with DNS mocking or test infrastructure
        // See: https://github.com/dnsjava/dnsjava for DNS mocking utilities
    }
}
