package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Apple Sign-In OAuth 2.0 token response.
 *
 * <p>
 * Returned by POST https://appleid.apple.com/auth/token during authorization code exchange.
 *
 * <p>
 * Apple returns user information in the ID token JWT, not via a separate API call. The ID token must be decoded to
 * extract the user's email, unique identifier, and optional name (only provided on first sign-in).
 *
 * @param accessToken
 *            the access token (not used for user info - use ID token instead)
 * @param tokenType
 *            token type (always "Bearer")
 * @param expiresIn
 *            token lifetime in seconds (typically 3600)
 * @param refreshToken
 *            refresh token for offline access
 * @param idToken
 *            OpenID Connect ID token (JWT) containing user information
 */
public record AppleTokenResponseType(@JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType, @JsonProperty("expires_in") int expiresIn,
        @JsonProperty("refresh_token") String refreshToken, @JsonProperty("id_token") String idToken) {
}
