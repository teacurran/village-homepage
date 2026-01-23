package villagecompute.homepage.integration.oauth;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import villagecompute.homepage.api.types.GoogleTokenResponseType;
import villagecompute.homepage.api.types.GoogleUserInfoType;

/**
 * REST client for Google OAuth 2.0 APIs.
 *
 * <p>
 * This client calls Google's token exchange and user info endpoints using Quarkus REST Client (reactive). The base URL
 * is configured in application.yaml as quarkus.rest-client.google-oauth.url.
 *
 * <p>
 * See: https://developers.google.com/identity/protocols/oauth2/web-server
 */
@RegisterRestClient(
        configKey = "google-oauth")
@Path("/")
public interface GoogleOAuthRestClient {

    /**
     * Exchange authorization code for access token.
     *
     * <p>
     * Calls POST https://oauth2.googleapis.com/token with form-encoded parameters per RFC 6749 Section 4.1.3.
     *
     * @param grantType
     *            always "authorization_code"
     * @param code
     *            the authorization code from OAuth callback
     * @param redirectUri
     *            the redirect URI used in authorization request (must match exactly)
     * @param clientId
     *            Google OAuth client ID
     * @param clientSecret
     *            Google OAuth client secret
     * @return token response with access_token, expires_in, refresh_token, id_token
     */
    @POST
    @Path("/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    GoogleTokenResponseType exchangeToken(@FormParam("grant_type") String grantType, @FormParam("code") String code,
            @FormParam("redirect_uri") String redirectUri, @FormParam("client_id") String clientId,
            @FormParam("client_secret") String clientSecret);

    /**
     * Get authenticated user's profile information.
     *
     * <p>
     * Calls GET https://www.googleapis.com/oauth2/v2/userinfo with Bearer token in Authorization header.
     *
     * @param authorization
     *            Bearer token (format: "Bearer {access_token}")
     * @return user info with sub (Google ID), email, name, picture, etc.
     */
    @GET
    @Path("/oauth2/v2/userinfo")
    @Produces(MediaType.APPLICATION_JSON)
    GoogleUserInfoType getUserInfo(@HeaderParam("Authorization") String authorization);
}
