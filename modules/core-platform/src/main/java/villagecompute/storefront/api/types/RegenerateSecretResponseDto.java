package villagecompute.storefront.api.types;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response DTO for OAuth client secret regeneration.
 *
 * <p>
 * <strong>Security Critical:</strong> Returns the newly generated plaintext client secret. This is the ONLY time the
 * new secret will be visible. If lost, it must be regenerated again.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T4: OAuth client management</li>
 * <li>Architecture: Section 5 Contract Patterns (OAuth scopes)</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RegenerateSecretResponseDto {

    public String clientId;

    public String clientSecret;
}
