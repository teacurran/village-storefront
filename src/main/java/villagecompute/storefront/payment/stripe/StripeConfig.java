package villagecompute.storefront.payment.stripe;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Stripe configuration mapped from application properties. Secrets are injected via MicroProfile Config and should be
 * stored in Kubernetes Secrets.
 */
@ConfigMapping(
        prefix = "stripe")
public interface StripeConfig {

    /**
     * Stripe API secret key (sk_test_... or sk_live_...).
     */
    @WithName("api.secret-key")
    String apiSecretKey();

    /**
     * Stripe publishable key (pk_test_... or pk_live_...).
     */
    @WithName("api.publishable-key")
    String apiPublishableKey();

    /**
     * Webhook signing secret for verifying webhook signatures.
     */
    @WithName("webhook.signing-secret")
    String webhookSigningSecret();

    /**
     * Allow skipping webhook signature verification (tests only).
     */
    @WithName("webhook.skip-verification")
    @WithDefault("false")
    boolean webhookSkipVerification();

    /**
     * Stripe API version to use.
     */
    @WithName("api.version")
    @WithDefault("2023-10-16")
    String apiVersion();

    /**
     * Enable Stripe Connect features.
     */
    @WithName("connect.enabled")
    @WithDefault("true")
    boolean connectEnabled();

    /**
     * Maximum number of retry attempts for Stripe API calls.
     */
    @WithName("api.max-retries")
    @WithDefault("3")
    int maxRetries();

    /**
     * Request timeout in milliseconds.
     */
    @WithName("api.timeout-ms")
    @WithDefault("30000")
    int timeoutMs();

    /**
     * Enable test mode (uses test API keys and sandbox).
     */
    @WithName("test-mode")
    @WithDefault("false")
    boolean testMode();
}
