package villagecompute.homepage.api.types;

/**
 * Response type for OAuth authorization URL generation.
 *
 * @param authorizationUrl
 *            the full OAuth provider authorization URL with query parameters
 * @param state
 *            the CSRF state token (client should store for validation)
 */
public record OAuthUrlResponseType(String authorizationUrl, String state) {
}
