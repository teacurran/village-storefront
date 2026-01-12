package villagecompute.storefront.api.types;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for resending gift card code to recipient.
 */
public class ResendGiftCardRequest {
    @NotBlank(
            message = "Recipient email is required")
    @Email(
            message = "Recipient email must be valid")
    public String recipientEmail;
}
