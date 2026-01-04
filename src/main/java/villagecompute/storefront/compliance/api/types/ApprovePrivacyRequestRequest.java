package villagecompute.storefront.compliance.api.types;

/**
 * Request DTO for approving privacy requests.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T6: Compliance automation (API contracts)</li>
 * </ul>
 */
public record ApprovePrivacyRequestRequest(String approverEmail, String approvalNotes) {
}
