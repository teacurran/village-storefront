package villagecompute.storefront.jobs;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Hashtable;
import java.util.List;

import javax.naming.Context;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;

import villagecompute.storefront.data.models.CustomDomain;
import villagecompute.storefront.tenant.CustomDomainVerified;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.scheduler.Scheduled;

/**
 * Scheduled job for DNS verification of custom domains.
 *
 * <p>
 * Runs every hour to verify PENDING or retry-eligible FAILED domains. For each domain, queries the DNS TXT record at
 * _acme-challenge.domain to validate the verification token. On success, updates status to ACTIVE and fires
 * CustomDomainVerified event to trigger certificate provisioning.
 *
 * <p>
 * <strong>Retry Logic:</strong>
 * <ul>
 * <li>Retry 0: Immediate (at job creation time)</li>
 * <li>Retry 1: 1 hour after first failure</li>
 * <li>Retry 2: 4 hours after retry 1</li>
 * <li>Retry 3: 12 hours after retry 2</li>
 * <li>Retry 4+: 24 hours after previous attempt (indefinite)</li>
 * </ul>
 *
 * <p>
 * <strong>Error Handling:</strong>
 * <ul>
 * <li>Transient errors (DNS timeout, NXDOMAIN) → increment retry count, preserve PENDING status</li>
 * <li>Permanent errors (wrong token, malformed record) → set status=FAILED with actionable message</li>
 * <li>Per-tenant failures are logged but do not halt the entire job run</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T4: Custom Domain SSL Automation</li>
 * <li>Migration: V20260132__custom_domain_ssl_automation.sql</li>
 * <li>Architecture: docs/architecture/tenant_isolation.md (Custom Domain Access)</li>
 * <li>Runbook: docs/operations/runbook.md (Custom Domain Troubleshooting)</li>
 * </ul>
 */
@ApplicationScoped
public class DomainValidationJob {

    private static final Logger LOG = Logger.getLogger(DomainValidationJob.class);

    // Exponential backoff schedule (in hours): 1h, 4h, 12h, 24h (then 24h indefinitely)
    private static final Duration[] RETRY_BACKOFF_SCHEDULE = new Duration[]{Duration.ofHours(1), Duration.ofHours(4),
            Duration.ofHours(12), Duration.ofHours(24)};

    @Inject
    Event<CustomDomainVerified> domainVerifiedEvent;

    /**
     * Verify custom domains for all tenants.
     *
     * <p>
     * Runs every hour (configurable via domain.validation.cron). Queries all PENDING domains plus FAILED domains that
     * are eligible for retry based on exponential backoff schedule.
     */
    @Scheduled(
            cron = "${domain.validation.cron:0 0 * * * ?}",
            identity = "verify-custom-domains")
    public void verifyDomains() {
        LOG.info("Starting custom domain verification job");

        int verifiedCount = 0;
        int failedCount = 0;
        int skippedCount = 0;
        int totalProcessed = 0;

        try {
            // Query domains needing verification (PENDING + retry-eligible FAILED)
            List<CustomDomain> domainsToVerify = CustomDomain
                    .find("status = 'PENDING' OR (status = 'FAILED' AND lastVerificationAttempt IS NOT NULL)").list();

            LOG.infof("Found %d custom domains to verify", domainsToVerify.size());

            for (CustomDomain domain : domainsToVerify) {
                try {
                    // Check if FAILED domain is eligible for retry (exponential backoff)
                    if (domain.status == CustomDomain.DomainStatus.FAILED) {
                        if (!isRetryEligible(domain)) {
                            skippedCount++;
                            continue;
                        }
                    }

                    // Seed tenant context for logging/metrics
                    TenantContext.setCurrentTenantId(domain.tenant.id);

                    VerificationResult result = verifyDomain(domain);
                    totalProcessed++;

                    if (result.success) {
                        verifiedCount++;
                        LOG.infof("Domain verified successfully - domain=%s, tenantId=%s", domain.domain,
                                domain.tenant.id);
                    } else {
                        failedCount++;
                        LOG.warnf("Domain verification failed - domain=%s, tenantId=%s, error=%s", domain.domain,
                                domain.tenant.id, result.errorMessage);
                    }

                } catch (Exception e) {
                    failedCount++;
                    LOG.errorf(e, "Unexpected error verifying domain %s for tenant %s", domain.domain,
                            domain.tenant.id);
                } finally {
                    TenantContext.clear();
                }
            }

            LOG.infof("Domain verification job completed - processed=%d, verified=%d, failed=%d, skipped=%d (backoff)",
                    totalProcessed, verifiedCount, failedCount, skippedCount);

        } catch (Exception e) {
            LOG.error("Failed to execute domain verification job", e);
        }
    }

    /**
     * Check if a FAILED domain is eligible for retry based on exponential backoff.
     *
     * @param domain
     *            domain to check
     * @return true if enough time has passed since last attempt
     */
    private boolean isRetryEligible(CustomDomain domain) {
        if (domain.lastVerificationAttempt == null) {
            return true; // Never attempted, always eligible
        }

        int retryCount = Math.max(1, domain.verificationRetryCount);
        int scheduleIndex = Math.min(retryCount - 1, RETRY_BACKOFF_SCHEDULE.length - 1);
        Duration backoffDuration = RETRY_BACKOFF_SCHEDULE[scheduleIndex];

        OffsetDateTime nextRetryTime = domain.lastVerificationAttempt.plus(backoffDuration);
        return OffsetDateTime.now().isAfter(nextRetryTime);
    }

    /**
     * Verify a single domain by querying DNS TXT record.
     *
     * <p>
     * This method performs the following steps:
     * <ol>
     * <li>Extract expected TXT record name/value from domain.dnsChallenge JSON</li>
     * <li>Query DNS TXT record for _acme-challenge.{domain}</li>
     * <li>Compare actual vs expected verification token</li>
     * <li>On success: Update status=ACTIVE, fire CustomDomainVerified event</li>
     * <li>On failure: Increment retry count, set error message</li>
     * </ol>
     *
     * @param domain
     *            domain to verify
     * @return VerificationResult indicating success/failure and error details
     */
    @Transactional
    public VerificationResult verifyDomain(CustomDomain domain) {
        LOG.debugf("Verifying domain %s (id=%s)", domain.domain, domain.id);

        // Update attempt timestamp
        domain.lastVerificationAttempt = OffsetDateTime.now();

        try {
            // Parse DNS challenge from JSONB field
            JsonNode dnsChallenge = domain.dnsChallenge;
            if (dnsChallenge == null || !dnsChallenge.has("value") || !dnsChallenge.has("name")) {
                return fail(domain, "DNS challenge data missing or malformed");
            }

            String expectedToken = dnsChallenge.get("value").asText();
            String txtRecordName = dnsChallenge.get("name").asText();

            // Query DNS TXT record
            String actualToken = queryDnsTxtRecord(txtRecordName);

            if (actualToken == null) {
                return fail(domain,
                        "DNS TXT record not found. Ensure record exists: " + txtRecordName + " TXT " + expectedToken);
            }

            // Validate token match
            if (!actualToken.trim().equals(expectedToken.trim())) {
                return fail(domain,
                        "DNS TXT record value mismatch. Expected: " + expectedToken + ", Found: " + actualToken);
            }

            // SUCCESS: Mark domain as verified
            domain.status = CustomDomain.DomainStatus.ACTIVE;
            domain.verified = true;
            domain.verificationErrorMessage = null;
            domain.verificationRetryCount = 0;
            domain.updatedAt = OffsetDateTime.now();
            domain.persist();

            // Fire CDI event to trigger certificate provisioning
            domainVerifiedEvent.fire(new CustomDomainVerified(domain));

            return new VerificationResult(true, null);

        } catch (javax.naming.NameNotFoundException e) {
            // DNS record doesn't exist yet (transient failure - DNS propagation delay)
            return fail(domain, "DNS TXT record not found (may be propagating). Retry scheduled.");
        } catch (javax.naming.NamingException e) {
            // DNS query failure (transient - network/resolver issue)
            LOG.warnf(e, "DNS query failed for domain %s", domain.domain);
            return fail(domain, "DNS query failed: " + e.getMessage() + ". Retry scheduled.");
        } catch (Exception e) {
            // Unexpected error
            LOG.errorf(e, "Unexpected error verifying domain %s", domain.domain);
            return fail(domain, "Internal error: " + e.getMessage());
        }
    }

    /**
     * Mark domain verification as failed and persist error details.
     *
     * @param domain
     *            domain that failed verification
     * @param errorMessage
     *            human-readable error message for UI display
     * @return VerificationResult with failure flag
     */
    private VerificationResult fail(CustomDomain domain, String errorMessage) {
        domain.verificationRetryCount++;
        domain.verificationErrorMessage = errorMessage;
        domain.status = CustomDomain.DomainStatus.FAILED;
        domain.updatedAt = OffsetDateTime.now();
        domain.persist();

        return new VerificationResult(false, errorMessage);
    }

    /**
     * Query DNS TXT record using JNDI DirContext.
     *
     * @param recordName
     *            fully qualified TXT record name (e.g., _acme-challenge.example.com)
     * @return TXT record value (first record if multiple), or null if not found
     * @throws javax.naming.NamingException
     *             if DNS query fails
     */
    private String queryDnsTxtRecord(String recordName) throws javax.naming.NamingException {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        env.put(Context.PROVIDER_URL, "dns:"); // Use system DNS resolvers

        DirContext ctx = new InitialDirContext(env);
        try {
            Attributes attrs = ctx.getAttributes(recordName, new String[]{"TXT"});
            Attribute txtAttr = attrs.get("TXT");

            if (txtAttr != null && txtAttr.size() > 0) {
                // TXT records are often quoted, strip quotes if present
                String value = txtAttr.get(0).toString();
                return value.replaceAll("^\"|\"$", "");
            }

            return null;
        } finally {
            ctx.close();
        }
    }

    /** Verification result container */
    private record VerificationResult(boolean success, String errorMessage) {
    }
}
