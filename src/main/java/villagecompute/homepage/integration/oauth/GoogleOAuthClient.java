package villagecompute.homepage.integration.oauth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.UriBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import villagecompute.homepage.api.types.GoogleTokenResponseType;
import villagecompute.homepage.api.types.GoogleUserInfoType;

/**
 * Client for Google OAuth 2.0 authorization code flow.
 *
 * <p>
 * Provides methods for:
 *
 * <ul>
 * <li>Generating authorization URLs for user consent
 * <li>Exchanging authorization codes for access tokens
 * <li>Retrieving user profile information
 * </ul>
 *
 * <p>
 * Configuration properties:
 *
 * <ul>
 * <li>quarkus.oidc.google.client-id
 * <li>quarkus.oidc.google.credentials.secret
 * </ul>
 *
 * <p>
 * See: https://developers.google.com/identity/protocols/oauth2/web-server
 */
@ApplicationScoped
public class GoogleOAuthClient {

    @ConfigProperty(
            name = "quarkus.oidc.google.client-id")
    String clientId;

    @ConfigProperty(
            name = "quarkus.oidc.google.credentials.secret")
    String clientSecret;

    @Inject
    @RestClient
    GoogleOAuthRestClient restClient;

    /**
     * Generate Google OAuth authorization URL.
     *
     * <p>
     * Builds the authorization URL with the following parameters:
     *
     * <ul>
     * <li>client_id: Google OAuth client ID
     * <li>redirect_uri: Callback URL for this application
     * <li>response_type: "code" (authorization code flow)
     * <li>scope: "openid email profile" (user identity and basic profile)
     * <li>state: CSRF token (caller-provided)
     * <li>access_type: "offline" (request refresh token)
     * <li>prompt: "consent" (force consent screen for refresh token)
     * </ul>
     *
     * @param redirectUri
     *            the callback URL (must match Google Console configuration)
     * @param state
     *            CSRF state token (UUID v4)
     * @return full authorization URL to redirect user to
     */
    public String getAuthorizationUrl(String redirectUri, String state) {
        return UriBuilder.fromUri("https://accounts.google.com/o/oauth2/v2/auth").queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri).queryParam("response_type", "code")
                .queryParam("scope", "openid email profile").queryParam("state", state)
                .queryParam("access_type", "offline").queryParam("prompt", "consent").build().toString();
    }

    /**
     * Exchange authorization code for access token.
     *
     * <p>
     * Calls POST https://oauth2.googleapis.com/token with form-encoded parameters.
     *
     * @param code
     *            the authorization code from OAuth callback
     * @param redirectUri
     *            the redirect URI used in authorization request (must match exactly)
     * @return token response with access_token, refresh_token, id_token, etc.
     */
    public GoogleTokenResponseType exchangeCodeForToken(String code, String redirectUri) {
        return restClient.exchangeToken("authorization_code", code, redirectUri, clientId, clientSecret);
    }

    /**
     * Retrieve user profile information using access token.
     *
     * <p>
     * Calls GET https://www.googleapis.com/oauth2/v2/userinfo with Bearer token.
     *
     * @param accessToken
     *            the access token from token exchange
     * @return user info with sub (Google ID), email, name, picture, etc.
     */
    public GoogleUserInfoType getUserProfile(String accessToken) {
        return restClient.getUserInfo("Bearer " + accessToken);
    }
}
