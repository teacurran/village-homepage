package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Google OAuth 2.0 token response.
 *
 * <p>
 * Returned by POST https://oauth2.googleapis.com/token during authorization code exchange.
 *
 * @param accessToken
 *            the access token for API requests
 * @param expiresIn
 *            token lifetime in seconds (typically 3600)
 * @param refreshToken
 *            refresh token for offline access (only if access_type=offline)
 * @param scope
 *            the granted scopes (space-separated)
 * @param tokenType
 *            token type (always "Bearer")
 * @param idToken
 *            OpenID Connect ID token (JWT)
 */
public record GoogleTokenResponseType(@JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") int expiresIn, @JsonProperty("refresh_token") String refreshToken, String scope,
        @JsonProperty("token_type") String tokenType, @JsonProperty("id_token") String idToken) {
}
