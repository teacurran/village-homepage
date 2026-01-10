package villagecompute.homepage.api.types;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for marketplace listing contact inquiry (Feature F12.6).
 *
 * <p>
 * Used by {@code POST /api/marketplace/listings/{listingId}/contact} endpoint to send messages from buyers to sellers
 * via masked email relay.
 *
 * <p>
 * <b>Validation Rules:</b>
 * <ul>
 * <li>name: 1-100 characters, required</li>
 * <li>email: Valid email format, required</li>
 * <li>message: 10-10,000 characters, required (prevents spam via tiny messages, DoS via huge messages)</li>
 * </ul>
 *
 * <p>
 * <b>Usage Example:</b>
 *
 * <pre>
 * POST /api/marketplace/listings/a3f8b9c0-1234-5678-abcd-1234567890ab/contact
 * Content-Type: application/json
 *
 * {
 *   "name": "John Doe",
 *   "email": "john@example.com",
 *   "message": "Is this item still available? Can you provide more details?"
 * }
 * </pre>
 *
 * <p>
 * <b>Security Considerations:</b>
 * <ul>
 * <li>Rate limited: 5/hour for anonymous, 20/hour for logged_in, 50/hour for trusted (RateLimitService)</li>
 * <li>Message length capped at 10,000 chars to prevent DoS attacks</li>
 * <li>Email addresses validated but not stored in plaintext logs (privacy)</li>
 * <li>Spam detection via keyword analysis (future AI integration)</li>
 * </ul>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>F12.6: Email masking for contact relay</li>
 * <li>P6: Privacy via masked email relay</li>
 * <li>P14: Rate limiting to prevent spam abuse</li>
 * </ul>
 *
 * @param name
 *            buyer's display name (1-100 chars)
 * @param email
 *            buyer's real email address (validated, relayed to seller via masked address)
 * @param message
 *            inquiry message body (10-10,000 chars, plain text)
 * @see villagecompute.homepage.api.rest.ListingContactResource for endpoint implementation
 * @see villagecompute.homepage.services.MessageRelayService for relay logic
 */
public record ContactInquiryRequest(@NotBlank @Size(
        min = 1,
        max = 100) String name, @NotBlank @Email String email,
        @NotBlank @Size(
                min = 10,
                max = 10_000,
                message = "Message must be between 10 and 10,000 characters") String message) {
}
