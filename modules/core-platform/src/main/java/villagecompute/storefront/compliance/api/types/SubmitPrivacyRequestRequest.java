package villagecompute.storefront.compliance.api.types;

/**
 * Request DTO for submitting privacy requests.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T6: Compliance automation (API contracts)</li>
 * </ul>
 */
public record SubmitPrivacyRequestRequest(String requestType, String requesterEmail, String subjectEmail, String reason,
        String ticketNumber) {
}
