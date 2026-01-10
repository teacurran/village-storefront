package villagecompute.storefront.notifications;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Service for filtering email recipients based on domain whitelist (staging environment).
 *
 * <p>
 * In staging environments, prevents sending emails to real customer addresses by enforcing a domain whitelist. Only
 * emails to {@code @villagecompute.com} and {@code @example.com} domains are allowed when filtering is enabled.
 *
 * <p>
 * Usage example:
 *
 * <pre>{@code
 * if (!domainFilterService.isAllowed("user@gmail.com")) {
 *     LOG.warnf("Email blocked by domain filter: user@gmail.com");
 *     return;
 * }
 * mailer.send(Mail.withHtml("user@gmail.com", subject, body));
 * }</pre>
 *
 * <p>
 * Configuration:
 *
 * <pre>
 * # Enable domain filtering (staging only)
 * %staging.notifications.domain-filter.enabled=true
 * %staging.notifications.domain-filter.allowed=villagecompute.com,example.com
 * </pre>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T5: Order email templates with environment domain filtering</li>
 * <li>Architecture: Email notification system - staging safety controls</li>
 * </ul>
 */
@ApplicationScoped
public class DomainFilterService {

    private static final Logger LOG = Logger.getLogger(DomainFilterService.class);

    @ConfigProperty(
            name = "notifications.domain-filter.enabled",
            defaultValue = "false")
    boolean filterEnabled;

    @ConfigProperty(
            name = "notifications.domain-filter.allowed")
    Optional<List<String>> allowedDomains;

    /**
     * Check if the given email address is allowed based on domain filtering rules.
     *
     * <p>
     * Returns {@code true} if:
     * <ul>
     * <li>Domain filtering is disabled (production), or</li>
     * <li>The email domain is in the allowed list (staging)</li>
     * </ul>
     *
     * <p>
     * Returns {@code false} if domain filtering is enabled and the email domain is not whitelisted.
     *
     * @param email
     *            email address to validate
     * @return {@code true} if email is allowed, {@code false} if blocked by filter
     */
    public boolean isAllowed(String email) {
        if (email == null || email.isBlank()) {
            LOG.warnf("Domain filter received null or blank email address");
            return false;
        }

        // If filtering is disabled (production), allow all emails
        if (!filterEnabled) {
            return true;
        }

        // Extract domain from email
        int atIndex = email.indexOf('@');
        if (atIndex == -1) {
            LOG.warnf("Domain filter received invalid email format: %s", email);
            return false;
        }

        String domain = email.substring(atIndex + 1).toLowerCase();

        // Check if domain is in allowed list
        if (allowedDomains.isEmpty()) {
            LOG.warnf(
                    "Domain filtering is enabled but no allowed domains configured - blocking all emails. Set notifications.domain-filter.allowed");
            return false;
        }

        List<String> allowed = allowedDomains.get();
        boolean isAllowed = allowed.stream().anyMatch(allowedDomain -> allowedDomain.equalsIgnoreCase(domain));

        if (!isAllowed) {
            LOG.warnf("Email blocked by domain filter - recipient=%s, domain=%s, allowedDomains=%s", email, domain,
                    allowed);
        }

        return isAllowed;
    }

    /**
     * Check if domain filtering is currently enabled.
     *
     * @return {@code true} if filtering is enabled (staging), {@code false} otherwise (production)
     */
    public boolean isFilterEnabled() {
        return filterEnabled;
    }

    /**
     * Get the list of allowed domains (for logging/debugging).
     *
     * @return list of allowed domains if configured, empty list otherwise
     */
    public List<String> getAllowedDomains() {
        return allowedDomains.orElse(List.of());
    }
}
