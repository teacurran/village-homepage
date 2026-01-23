package villagecompute.homepage.integration.oauth;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import villagecompute.homepage.api.types.FacebookTokenResponseType;
import villagecompute.homepage.api.types.FacebookUserInfoType;

/**
 * REST client for Facebook Graph API OAuth 2.0.
 *
 * <p>
 * This client calls Facebook's token exchange and user info endpoints using Quarkus REST Client (reactive). The base
 * URL is configured in application.yaml as quarkus.rest-client.facebook-oauth.url (https://graph.facebook.com/v18.0).
 *
 * <p>
 * Key differences from Google OAuth:
 *
 * <ul>
 * <li>API version required in base URL (v18.0)
 * <li>No refresh_token in response (long-lived tokens instead)
 * <li>No id_token (Facebook doesn't use OpenID Connect)
 * <li>User info requires fields query parameter
 * </ul>
 *
 * <p>
 * See: https://developers.facebook.com/docs/facebook-login/guides/advanced/manual-flow
 */
@RegisterRestClient(
        configKey = "facebook-oauth")
@Path("/")
public interface FacebookOAuthRestClient {

    /**
     * Exchange authorization code for access token.
     *
     * <p>
     * Calls POST https://graph.facebook.com/v18.0/oauth/access_token with form-encoded parameters per OAuth 2.0 spec.
     *
     * @param grantType
     *            always "authorization_code"
     * @param code
     *            the authorization code from OAuth callback
     * @param redirectUri
     *            the redirect URI used in authorization request (must match exactly)
     * @param clientId
     *            Facebook App ID
     * @param clientSecret
     *            Facebook App Secret
     * @return token response with access_token, token_type, expires_in
     */
    @POST
    @Path("/oauth/access_token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    FacebookTokenResponseType exchangeToken(@FormParam("grant_type") String grantType, @FormParam("code") String code,
            @FormParam("redirect_uri") String redirectUri, @FormParam("client_id") String clientId,
            @FormParam("client_secret") String clientSecret);

    /**
     * Get authenticated user's profile information.
     *
     * <p>
     * Calls GET https://graph.facebook.com/v18.0/me?fields=id,name,email,picture with Bearer token in Authorization
     * header.
     *
     * <p>
     * The fields parameter controls what data is returned. Picture is requested with nested data structure containing
     * URL.
     *
     * @param authorization
     *            Bearer token (format: "Bearer {access_token}")
     * @param fields
     *            comma-separated list of fields to retrieve (default: id,name,email,picture)
     * @return user info with id (Facebook ID), name, email, picture.data.url
     */
    @GET
    @Path("/me")
    @Produces(MediaType.APPLICATION_JSON)
    FacebookUserInfoType getUserInfo(@HeaderParam("Authorization") String authorization,
            @QueryParam("fields") @DefaultValue("id,name,email,picture") String fields);
}
