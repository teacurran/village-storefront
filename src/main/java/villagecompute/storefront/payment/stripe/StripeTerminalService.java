package villagecompute.storefront.payment.stripe;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.terminal.ConnectionToken;
import com.stripe.param.terminal.ConnectionTokenCreateParams;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Stripe Terminal helper that issues connection tokens for POS devices.
 *
 * <p>
 * Tokens are requested by devices during pairing to allow the Stripe Terminal SDK to connect to readers in sandbox
 * mode. Tokens are scoped per tenant for observability.
 */
@ApplicationScoped
public class StripeTerminalService {

    private static final Logger LOG = Logger.getLogger(StripeTerminalService.class);

    @Inject
    StripeConfig stripeConfig;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Issue a short-lived Stripe Terminal connection token for the provided device.
     */
    public String createConnectionToken(UUID tenantId, Long deviceId) {
        try {
            Stripe.apiKey = stripeConfig.apiSecretKey();
            ConnectionTokenCreateParams params = ConnectionTokenCreateParams.builder().build();
            ConnectionToken token = ConnectionToken.create(params);

            meterRegistry.counter("stripe.terminal.connection_token.issued", "tenant", tenantId.toString()).increment();
            LOG.infof("[Tenant: %s] Issued Stripe Terminal connection token for device %d", tenantId, deviceId);
            return token.getSecret();
        } catch (StripeException e) {
            LOG.errorf(e, "[Tenant: %s] Failed to create Stripe Terminal connection token for device %d", tenantId,
                    deviceId);
            throw new IllegalStateException("Unable to create Stripe Terminal connection token: " + e.getMessage(), e);
        }
    }
}
