package villagecompute.storefront.compliance.api.types;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * DTO for privacy request responses.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T6: Compliance automation (API contracts)</li>
 * </ul>
 */
public record PrivacyRequestDto(UUID id, String requestType, String status, String requesterEmail, String subjectEmail,
        String reason, String ticketNumber, String approvedByEmail, OffsetDateTime approvedAt,
        OffsetDateTime completedAt, String resultUrl, String errorMessage, OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
