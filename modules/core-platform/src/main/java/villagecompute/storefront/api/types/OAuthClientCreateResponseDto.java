package villagecompute.storefront.api.types;

import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response DTO for OAuth client creation.
 *
 * <p>
 * <strong>Security Critical:</strong> This is the ONLY response that includes the plaintext client secret. The secret
 * is returned once during creation and cannot be retrieved later. If lost, it must be regenerated.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T4: OAuth client management</li>
 * <li>Architecture: Section 5 Contract Patterns (OAuth scopes)</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OAuthClientCreateResponseDto {

    public UUID id;

    public String clientId;

    public String clientSecret;

    public String name;

    public Set<String> scopes;

    public int rateLimitPerMinute;
}
