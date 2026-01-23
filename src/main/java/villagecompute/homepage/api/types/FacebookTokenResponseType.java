package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Facebook OAuth 2.0 token response.
 *
 * <p>
 * Returned by POST https://graph.facebook.com/v18.0/oauth/access_token during authorization code exchange.
 *
 * <p>
 * Note: Facebook does not return refresh tokens for web apps. Instead, access tokens are long-lived (typically 60
 * days). Token refresh (I3.T5) will handle exchanging short-lived tokens for long-lived tokens.
 *
 * @param accessToken
 *            the access token for Graph API requests
 * @param tokenType
 *            token type (always "bearer")
 * @param expiresIn
 *            token lifetime in seconds (typically 5183944 = 60 days)
 */
public record FacebookTokenResponseType(@JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType, @JsonProperty("expires_in") int expiresIn) {
}
