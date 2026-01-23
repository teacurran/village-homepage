package villagecompute.homepage.integration.oauth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.UriBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import villagecompute.homepage.api.types.FacebookTokenResponseType;
import villagecompute.homepage.api.types.FacebookUserInfoType;

/**
 * Client for Facebook OAuth 2.0 authorization code flow.
 *
 * <p>
 * Provides methods for:
 *
 * <ul>
 * <li>Generating authorization URLs for user consent
 * <li>Exchanging authorization codes for access tokens
 * <li>Retrieving user profile information via Graph API
 * </ul>
 *
 * <p>
 * Configuration properties:
 *
 * <ul>
 * <li>quarkus.oidc.facebook.client-id (Facebook App ID)
 * <li>quarkus.oidc.facebook.credentials.secret (Facebook App Secret)
 * </ul>
 *
 * <p>
 * Key differences from Google OAuth:
 *
 * <ul>
 * <li>API version required in all URLs (v18.0)
 * <li>No refresh_token (uses long-lived access tokens instead)
 * <li>Token expiration must be tracked via expires_in field
 * <li>Picture URL is nested: picture.data.url
 * <li>Uses fields parameter instead of scopes for user info
 * </ul>
 *
 * <p>
 * See: https://developers.facebook.com/docs/facebook-login/guides/advanced/manual-flow
 */
@ApplicationScoped
public class FacebookOAuthClient {

    @ConfigProperty(
            name = "quarkus.oidc.facebook.client-id")
    String clientId;

    @ConfigProperty(
            name = "quarkus.oidc.facebook.credentials.secret")
    String clientSecret;

    @Inject
    @RestClient
    FacebookOAuthRestClient restClient;

    /**
     * Generate Facebook OAuth authorization URL.
     *
     * <p>
     * Builds the authorization URL with the following parameters:
     *
     * <ul>
     * <li>client_id: Facebook App ID
     * <li>redirect_uri: Callback URL for this application
     * <li>response_type: "code" (authorization code flow)
     * <li>scope: "email public_profile" (user identity and basic profile)
     * <li>state: CSRF token (caller-provided)
     * </ul>
     *
     * <p>
     * Note: Unlike Google OAuth, Facebook does not use access_type or prompt parameters. Long-lived tokens are obtained
     * via separate token exchange (I3.T5).
     *
     * @param redirectUri
     *            the callback URL (must match Facebook App configuration)
     * @param state
     *            CSRF state token (UUID v4)
     * @return full authorization URL to redirect user to
     */
    public String getAuthorizationUrl(String redirectUri, String state) {
        return UriBuilder.fromUri("https://www.facebook.com/v18.0/dialog/oauth").queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri).queryParam("response_type", "code")
                .queryParam("scope", "email public_profile").queryParam("state", state).build().toString();
    }

    /**
     * Exchange authorization code for access token.
     *
     * <p>
     * Calls POST https://graph.facebook.com/v18.0/oauth/access_token with form-encoded parameters.
     *
     * <p>
     * The response includes expires_in field (typically 5183944 seconds = 60 days) which MUST be stored for token
     * refresh logic (I3.T5).
     *
     * @param code
     *            the authorization code from OAuth callback
     * @param redirectUri
     *            the redirect URI used in authorization request (must match exactly)
     * @return token response with access_token, token_type, expires_in
     */
    public FacebookTokenResponseType exchangeCodeForToken(String code, String redirectUri) {
        return restClient.exchangeToken("authorization_code", code, redirectUri, clientId, clientSecret);
    }

    /**
     * Retrieve user profile information using access token.
     *
     * <p>
     * Calls GET https://graph.facebook.com/v18.0/me?fields=id,name,email,picture with Bearer token.
     *
     * <p>
     * Note: Email may be null if user denied email permission during consent. Caller must validate email presence.
     *
     * @param accessToken
     *            the access token from token exchange
     * @return user info with id (Facebook ID), name, email, picture.data.url
     */
    public FacebookUserInfoType getUserProfile(String accessToken) {
        return restClient.getUserInfo("Bearer " + accessToken, "id,name,email,picture");
    }
}
