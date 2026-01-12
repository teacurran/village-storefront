package villagecompute.storefront.platformops.api.types;

/**
 * Request payload for tenant lifecycle actions (suspend/reactivate).
 *
 * <p>
 * Requires a justification reason and external ticket number to satisfy governance guardrails.
 * </p>
 */
public class TenantLifecycleRequest {

    public String reason;
    public String ticketNumber;

    public TenantLifecycleRequest() {
    }

    public TenantLifecycleRequest(String reason, String ticketNumber) {
        this.reason = reason;
        this.ticketNumber = ticketNumber;
    }
}
