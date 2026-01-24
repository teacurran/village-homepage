package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.UUID;

/**
 * Contact information for marketplace listings stored as JSONB (Policy P6).
 *
 * <p>
 * Marketplace listings use masked email relay to protect seller privacy. When a user creates a listing, their real
 * email and optional phone are stored, along with a generated masked email address for public display and relay.
 *
 * <p>
 * <b>Email Relay Flow:</b>
 * <ol>
 * <li>User creates listing with real email (e.g., "seller@example.com")</li>
 * <li>System generates masked email (e.g., "listing-{uuid}@villagecompute.com")</li>
 * <li>Public listing displays masked email only</li>
 * <li>Buyer sends inquiry to masked email</li>
 * <li>Inbound email job (I4.T7) relays message to seller's real email</li>
 * <li>Seller replies via normal email, system relays back to buyer</li>
 * </ol>
 *
 * <p>
 * <b>JSONB Structure in Database:</b>
 *
 * <pre>
 * {
 *   "email": "seller@example.com",
 *   "phone": "+1-555-1234",
 *   "masked_email": "listing-a3f8b9c0-1234-5678-abcd-1234567890ab@villagecompute.com"
 * }
 * </pre>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>F12.6: Email masking for contact relay</li>
 * <li>P1: GDPR compliance - seller controls their contact info</li>
 * <li>P6: Privacy via masked email relay</li>
 * </ul>
 *
 * @param email
 *            seller's real email (never displayed publicly)
 * @param phone
 *            optional seller phone number
 * @param maskedEmail
 *            generated relay email (displayed publicly, format: listing-{uuid}@villagecompute.com)
 */
@Schema(
        description = "Contact information with masked email relay for privacy protection")
public record ContactInfoType(@Schema(
        description = "Seller's real email address (never displayed publicly)",
        example = "seller@example.com",
        required = true) @NotNull @Email String email,

        @Schema(
                description = "Optional seller phone number",
                example = "+1-802-555-1234",
                nullable = true) String phone,

        @Schema(
                description = "Generated masked email for public display and relay",
                example = "listing-a3f8b9c0-1234-5678-abcd-1234567890ab@villagecompute.com",
                required = true) @JsonProperty("masked_email") @NotNull String maskedEmail) {

    /**
     * Factory method for creating contact info with auto-generated masked email.
     *
     * <p>
     * Generates a unique masked email address for the listing. The masked email follows the pattern:
     * {@code listing-{uuid}@villagecompute.com}
     *
     * @param email
     *            seller's real email address
     * @param phone
     *            optional seller phone number (can be null)
     * @return ContactInfoType with generated masked email
     */
    public static ContactInfoType forListing(String email, String phone) {
        String maskedEmail = "listing-" + UUID.randomUUID() + "@villagecompute.com";
        return new ContactInfoType(email, phone, maskedEmail);
    }

    /**
     * Factory method for creating contact info with explicit masked email.
     *
     * <p>
     * Used when reconstructing from database JSONB or testing with specific masked email.
     *
     * @param email
     *            seller's real email address
     * @param phone
     *            optional seller phone number (can be null)
     * @param maskedEmail
     *            pre-existing masked email address
     * @return ContactInfoType with specified masked email
     */
    public static ContactInfoType of(String email, String phone, String maskedEmail) {
        return new ContactInfoType(email, phone, maskedEmail);
    }
}
