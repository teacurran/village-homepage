package villagecompute.homepage.api.types;

/**
 * Request type for OAuth callback parameters.
 *
 * @param code
 *            the authorization code (present on success)
 * @param state
 *            the CSRF state token (always present)
 * @param error
 *            the error code (present on failure, e.g., "access_denied")
 * @param errorDescription
 *            the human-readable error description
 */
public record OAuthCallbackRequestType(String code, String state, String error, String errorDescription) {
}
