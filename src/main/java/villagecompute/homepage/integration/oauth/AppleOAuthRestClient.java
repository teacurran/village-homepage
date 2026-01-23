package villagecompute.homepage.integration.oauth;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import villagecompute.homepage.api.types.AppleTokenResponseType;

/**
 * REST client for Apple Sign-In OAuth 2.0 APIs.
 *
 * <p>
 * This client calls Apple's token exchange endpoint using Quarkus REST Client (reactive). The base URL is configured in
 * application.yaml as quarkus.rest-client.apple-oauth.url.
 *
 * <p>
 * Apple Sign-In differs from Google/Facebook in two critical ways:
 *
 * <ul>
 * <li>Client secret is a JWT signed with ES256 (not a static string)</li>
 * <li>User info is in ID token JWT (no separate userinfo endpoint)</li>
 * </ul>
 *
 * <p>
 * See: https://developer.apple.com/documentation/sign_in_with_apple/sign_in_with_apple_rest_api
 */
@RegisterRestClient(
        configKey = "apple-oauth")
@Path("/")
public interface AppleOAuthRestClient {

    /**
     * Exchange authorization code for access token and ID token.
     *
     * <p>
     * Calls POST https://appleid.apple.com/auth/token with form-encoded parameters per OAuth 2.0 RFC 6749.
     *
     * <p>
     * Critical: The {@code clientSecret} parameter is NOT a static string - it MUST be a JWT signed with ES256 using
     * Apple's private key. The JWT includes these claims:
     *
     * <ul>
     * <li>iss: Apple Team ID</li>
     * <li>iat: Current timestamp</li>
     * <li>exp: 10 minutes from iat</li>
     * <li>aud: https://appleid.apple.com</li>
     * <li>sub: Apple Service ID (client_id)</li>
     * </ul>
     *
     * @param grantType
     *            always "authorization_code"
     * @param code
     *            the authorization code from OAuth callback
     * @param redirectUri
     *            the redirect URI used in authorization request (must match exactly)
     * @param clientId
     *            Apple Service ID (e.g., com.villagecompute.homepage.service)
     * @param clientSecret
     *            JWT token signed with ES256 using Apple private key
     * @return token response with access_token, expires_in, refresh_token, id_token (user info in ID token JWT)
     */
    @POST
    @Path("/auth/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    AppleTokenResponseType exchangeToken(@FormParam("grant_type") String grantType, @FormParam("code") String code,
            @FormParam("redirect_uri") String redirectUri, @FormParam("client_id") String clientId,
            @FormParam("client_secret") String clientSecret);

    /**
     * Refresh Apple access token using refresh token.
     *
     * <p>
     * Calls POST https://appleid.apple.com/auth/token with grant_type=refresh_token.
     *
     * <p>
     * Critical: The {@code clientSecret} parameter MUST be a freshly generated JWT (same as initial exchange). Apple
     * requires regenerating the client secret JWT for EVERY refresh request - you cannot reuse a cached JWT.
     *
     * @param grantType
     *            always "refresh_token"
     * @param refreshToken
     *            the refresh token from initial token exchange (expires after 6 months)
     * @param clientId
     *            Apple Service ID (e.g., com.villagecompute.homepage.service)
     * @param clientSecret
     *            Freshly generated JWT token signed with ES256 using Apple private key
     * @return token response with new access_token, expires_in, and possibly rotated refresh_token
     */
    @POST
    @Path("/auth/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    AppleTokenResponseType refreshToken(@FormParam("grant_type") String grantType,
            @FormParam("refresh_token") String refreshToken, @FormParam("client_id") String clientId,
            @FormParam("client_secret") String clientSecret);
}
