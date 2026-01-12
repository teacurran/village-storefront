package villagecompute.storefront.platformops.api.types;

/**
 * Request to change a tenant's subscription plan.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T1: Platform admin backend (plan management)</li>
 * <li>Architecture: 01_Blueprint_Foundation.md Section 5 (Data Governance)</li>
 * </ul>
 */
public class PlanChangeRequest {

    public String newPlan; // e.g., 'basic', 'professional', 'enterprise'
    public String reason; // Required justification
    public String ticketNumber; // Optional support ticket reference

    public PlanChangeRequest() {
    }

    public PlanChangeRequest(String newPlan, String reason, String ticketNumber) {
        this.newPlan = newPlan;
        this.reason = reason;
        this.ticketNumber = ticketNumber;
    }
}
