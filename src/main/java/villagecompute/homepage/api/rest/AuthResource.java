package villagecompute.homepage.api.rest;

import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.OAuthUrlResponseType;
import villagecompute.homepage.services.AuthIdentityService;
import villagecompute.homepage.services.OAuthService;
import villagecompute.homepage.data.models.User;

import java.net.URI;
import java.util.Objects;

/**
 * REST endpoints for authentication bootstrap flows.
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>{@code POST /api/auth/anonymous} – issue {@code vu_anon_id} cookie</li>
 * <li>{@code GET /api/auth/login/{provider}} – rate-limited OAuth initiation</li>
 * <li>{@code GET /api/auth/bootstrap} – guarded bootstrap landing page</li>
 * <li>{@code POST /api/auth/bootstrap} – finalize first superuser creation + JWT session issuance</li>
 * </ul>
 */
@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(
        name = "Authentication",
        description = "OAuth login flows, session management, bootstrap admin creation")
public class AuthResource {

    private static final Logger LOG = Logger.getLogger(AuthResource.class);

    @Inject
    AuthIdentityService authService;

    @Inject
    OAuthService oauthService;

    @POST
    @Path("/anonymous")
    @Operation(
            summary = "Create anonymous session",
            description = "Issues an anonymous cookie (vu_anon_id) for personalization without login. "
                    + "Allows anonymous users to customize widgets and preferences.")
    @APIResponses({@APIResponse(
            responseCode = "200",
            description = "Anonymous cookie issued successfully",
            content = @Content(
                    schema = @Schema(
                            implementation = AnonymousResponse.class))),
            @APIResponse(
                    responseCode = "500",
                    description = "Internal server error")})
    public Response issueAnonymousCookie() {
        NewCookie cookie = authService.issueAnonymousCookie();
        AnonymousResponse body = new AnonymousResponse("Anonymous account created", cookie.getValue());
        return Response.ok(body).cookie(cookie).build();
    }

    @GET
    @Path("/login/{provider}")
    @Operation(
            summary = "Initiate OAuth login flow",
            description = "Redirects to the specified OAuth provider's authorization endpoint. "
                    + "Supports Google, Facebook, and Apple. Rate limited per IP to prevent abuse. "
                    + "Set bootstrap=true query parameter for first superuser creation flow.")
    @APIResponses({@APIResponse(
            responseCode = "303",
            description = "Redirect to OAuth provider authorization URL"),
            @APIResponse(
                    responseCode = "404",
                    description = "Unsupported OAuth provider",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "429",
                    description = "Too many login attempts - rate limit exceeded",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "500",
                    description = "Internal server error")})
    public Response login(@Parameter(
            description = "OAuth provider (google, facebook, apple)",
            required = true) @PathParam("provider") String provider,
            @Parameter(
                    description = "Set to true for bootstrap superuser creation flow") @QueryParam("bootstrap") boolean bootstrapFlow,
            @Context UriInfo uriInfo, @Context HttpHeaders headers, @Context HttpServerRequest request) {

        String normalizedProvider = provider == null ? null : provider.toLowerCase();
        if (!authService.isProviderSupported(normalizedProvider)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorResponse("Unsupported provider: " + provider)).build();
        }

        String ip = resolveClientIp(headers, request);
        if (!authService.checkLoginRateLimit(normalizedProvider, ip)) {
            return Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .entity(new ErrorResponse("Too many login attempts. Please wait and try again.")).build();
        }

        URI redirect = authService.buildLoginRedirectUri(normalizedProvider, uriInfo);
        if (bootstrapFlow) {
            redirect = appendBootstrapFlag(redirect);
        }

        LOG.infof("Redirecting OAuth login for %s (ip=%s, bootstrapFlow=%s)", normalizedProvider, ip, bootstrapFlow);
        return Response.seeOther(redirect).build();
    }

    @GET
    @Path("/bootstrap")
    @Produces(MediaType.TEXT_HTML)
    @Operation(
            summary = "Bootstrap landing page",
            description = "HTML page for creating the first superuser account. "
                    + "Requires valid bootstrap token from environment configuration. "
                    + "Rate limited per IP. Only accessible when no superuser exists.")
    @APIResponses({@APIResponse(
            responseCode = "200",
            description = "Bootstrap page with OAuth provider links",
            content = @Content(
                    mediaType = MediaType.TEXT_HTML)),
            @APIResponse(
                    responseCode = "400",
                    description = "Missing or invalid bootstrap token",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "403",
                    description = "Bootstrap token invalid or superuser already exists",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "429",
                    description = "Too many bootstrap attempts - rate limit exceeded",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "500",
                    description = "Bootstrap token not configured",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class)))})
    public Response bootstrap(@Parameter(
            description = "Bootstrap token from environment configuration",
            required = true) @QueryParam("token") String token, @Context HttpHeaders headers,
            @Context HttpServerRequest request, @Context UriInfo uriInfo) {

        String clientIp = resolveClientIp(headers, request);
        Response failure = mapBootstrapValidation(token, clientIp);
        if (failure != null) {
            return failure;
        }

        String html = """
                <!DOCTYPE html>
                <html lang="en">
                <head><title>Village Homepage Bootstrap</title></head>
                <body>
                    <h1>Create the first superuser</h1>
                    <p>Choose an OAuth provider to finish secure bootstrap. Rate limited per Policy P1.</p>
                    <ul>
                        <li><a href="%s?bootstrap=true">Login with Google</a></li>
                        <li><a href="%s?bootstrap=true">Login with Facebook</a></li>
                        <li><a href="%s?bootstrap=true">Login with Apple</a></li>
                    </ul>
                </body>
                </html>
                """.formatted(uriInfo.getBaseUriBuilder().path("api/auth/login/google").build(),
                uriInfo.getBaseUriBuilder().path("api/auth/login/facebook").build(),
                uriInfo.getBaseUriBuilder().path("api/auth/login/apple").build());
        return Response.ok(html).build();
    }

    @POST
    @Path("/bootstrap")
    @Operation(
            summary = "Complete bootstrap superuser creation",
            description = "Finalizes first superuser account creation after OAuth authentication. "
                    + "Requires valid bootstrap token and OAuth provider details. "
                    + "Issues JWT session token for the newly created superuser.")
    @APIResponses({@APIResponse(
            responseCode = "200",
            description = "Superuser created successfully with JWT session",
            content = @Content(
                    schema = @Schema(
                            implementation = BootstrapResponse.class))),
            @APIResponse(
                    responseCode = "400",
                    description = "Missing required fields or unsupported provider",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "403",
                    description = "Bootstrap token invalid or superuser already exists",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "429",
                    description = "Too many bootstrap attempts - rate limit exceeded",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "500",
                    description = "Bootstrap token not configured or internal error",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class)))})
    public Response completeBootstrap(@Valid BootstrapRequest request, @Context HttpHeaders headers,
            @Context HttpServerRequest httpRequest) {

        Objects.requireNonNull(request, "Bootstrap request body required");

        String clientIp = resolveClientIp(headers, httpRequest);
        Response failure = mapBootstrapValidation(request.token(), clientIp);
        if (failure != null) {
            return failure;
        }

        String provider = request.provider() == null ? null : request.provider().toLowerCase();
        if (!authService.isProviderSupported(provider)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("Unsupported provider: " + request.provider())).build();
        }

        AuthIdentityService.BootstrapSession session = authService.createSuperuser(request.email(), provider,
                request.providerUserId());
        BootstrapResponse response = new BootstrapResponse("Superuser created", session.jwt(), session.expiresAt());
        return Response.ok(response).build();
    }

    private Response mapBootstrapValidation(String token, String clientIp) {
        if (token == null || token.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse("Bootstrap token is required"))
                    .build();
        }

        return switch (authService.validateBootstrapToken(token, clientIp)) {
            case SUCCESS -> null;
            case ADMIN_EXISTS -> Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse("Superuser already created")).build();
            case INVALID_TOKEN ->
                Response.status(Response.Status.FORBIDDEN).entity(new ErrorResponse("Invalid bootstrap token")).build();
            case TOKEN_NOT_CONFIGURED -> Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Bootstrap token not configured")).build();
            case RATE_LIMITED -> Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .entity(new ErrorResponse("Too many bootstrap attempts. Try again later.")).build();
        };
    }

    private String resolveClientIp(HttpHeaders headers, HttpServerRequest request) {
        if (headers != null) {
            String forwarded = headers.getHeaderString("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        if (request != null && request.remoteAddress() != null) {
            return request.remoteAddress().host();
        }
        return "unknown";
    }

    private URI appendBootstrapFlag(URI loginUri) {
        String base = loginUri.toString();
        if (base.contains("bootstrap=true")) {
            return loginUri;
        }
        String separator = base.contains("?") ? "&" : "?";
        return URI.create(base + separator + "bootstrap=true");
    }

    public record AnonymousResponse(String message, String anonymousId) {
    }

    public record ErrorResponse(String error) {
    }

    public record BootstrapResponse(String message, String jwt, java.time.Instant expiresAt) {
    }

    public record BootstrapRequest(@NotBlank String token, @NotBlank String email, @NotBlank String provider,
            @NotBlank String providerUserId) {
    }

    /**
     * Logout endpoint - terminates authenticated session and clears cookies.
     *
     * <p>
     * Clears both authenticated session cookie (vu_session) and anonymous cookie (vu_anon_id). Optionally revokes OAuth
     * tokens with providers (not implemented in v1).
     *
     * <p>
     * Security Notes:
     * <ul>
     * <li>Returns 204 No Content (not 200 OK) per REST best practices</li>
     * <li>Sets cookie Max-Age=0 to delete cookies (not expired date)</li>
     * <li>No redirect - client handles post-logout navigation</li>
     * </ul>
     *
     * @param headers
     *            HTTP headers containing cookies
     * @return 204 No Content with cookie deletion headers
     */
    @POST
    @Path("/logout")
    @Operation(
            summary = "Terminate user session",
            description = "Logs out the current user by clearing session and anonymous cookies. "
                    + "Returns 204 No Content per REST best practices. " + "Client handles post-logout navigation.")
    @APIResponses({@APIResponse(
            responseCode = "204",
            description = "Logout successful - cookies cleared"),
            @APIResponse(
                    responseCode = "500",
                    description = "Internal server error")})
    public Response logout(@Context HttpHeaders headers) {
        LOG.infof("User logout requested");

        // Create expired cookies to delete existing cookies
        NewCookie sessionCookie = authService.buildSecureCookie(authService.getSessionCookieName(), "", 0);
        NewCookie anonCookie = authService.buildSecureCookie(authService.getAnonymousCookieName(), "", 0);

        LOG.infof("User logged out successfully");

        return Response.noContent().cookie(sessionCookie, anonCookie).build();
    }

    /**
     * Initiate Google OAuth login flow.
     *
     * <p>
     * Generates authorization URL with CSRF state token. The state token is stored in the database with a 5-minute
     * expiration and validated during the callback.
     *
     * @param uriInfo
     *            URI context for building redirect URI
     * @param headers
     *            HTTP headers for extracting session cookie
     * @return OAuth URL response with authorization URL and state token
     */
    @GET
    @Path("/google/login")
    @Operation(
            summary = "Initiate Google OAuth login",
            description = "Generates Google OAuth authorization URL with CSRF state token. "
                    + "The state token is stored in the database with a 5-minute expiration "
                    + "and validated during the callback to prevent CSRF attacks.")
    @APIResponses({@APIResponse(
            responseCode = "200",
            description = "OAuth URL generated successfully",
            content = @Content(
                    schema = @Schema(
                            implementation = OAuthUrlResponseType.class))),
            @APIResponse(
                    responseCode = "500",
                    description = "Internal server error")})
    public Response googleLogin(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
        // Extract or generate session ID (anonymous cookie or new UUID)
        String sessionId = extractSessionId(headers);

        // Build redirect URI for OAuth callback
        String redirectUri = uriInfo.getBaseUriBuilder().path("api/auth/google/callback").build().toString();

        OAuthUrlResponseType response = oauthService.initiateGoogleLogin(sessionId, redirectUri);
        LOG.infof("Initiated Google OAuth login: sessionId=%s, state=%s", sessionId, response.state());

        return Response.ok(response).build();
    }

    /**
     * Handle Google OAuth callback.
     *
     * <p>
     * Validates state token, exchanges authorization code for access token, retrieves user profile, and creates or
     * links user account. On success, issues JWT session token and redirects to homepage.
     *
     * @param code
     *            authorization code from Google
     * @param state
     *            CSRF state token
     * @param error
     *            error code (if user cancelled or error occurred)
     * @param errorDescription
     *            human-readable error description
     * @param uriInfo
     *            URI context for building redirect URI
     * @return redirect to homepage with JWT token on success, error response on failure
     */
    @GET
    @Path("/google/callback")
    @Operation(
            summary = "Handle Google OAuth callback",
            description = "OAuth callback endpoint invoked by Google after user authorization. "
                    + "Validates CSRF state token, exchanges authorization code for access token, "
                    + "retrieves user profile, creates or links account, and issues JWT session. "
                    + "On success, redirects to homepage with JWT token in query parameter.")
    @APIResponses({@APIResponse(
            responseCode = "303",
            description = "Authentication successful - redirecting to homepage with JWT token"),
            @APIResponse(
                    responseCode = "400",
                    description = "Missing authorization code or state parameter, or user cancelled authentication",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "403",
                    description = "Invalid or expired state token (CSRF protection)",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "409",
                    description = "Email already exists with different OAuth provider",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "500",
                    description = "Authentication failed - network error, API error, or internal error",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class)))})
    public Response googleCallback(@Parameter(
            description = "Authorization code from Google",
            required = true) @QueryParam("code") String code,
            @Parameter(
                    description = "CSRF state token",
                    required = true) @QueryParam("state") String state,
            @Parameter(
                    description = "Error code if user cancelled or authorization failed") @QueryParam("error") String error,
            @Parameter(
                    description = "Human-readable error description") @QueryParam("error_description") String errorDescription,
            @Context UriInfo uriInfo) {

        // Handle OAuth errors (user cancellation, permission denial, etc.)
        if (error != null) {
            LOG.warnf("Google OAuth error: error=%s, description=%s", error, errorDescription);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("Authentication cancelled or failed: " + error)).build();
        }

        // Validate required parameters
        if (code == null || code.isBlank()) {
            LOG.warn("Google OAuth callback missing code parameter");
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse("Missing authorization code"))
                    .build();
        }

        if (state == null || state.isBlank()) {
            LOG.warn("Google OAuth callback missing state parameter");
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse("Missing state parameter"))
                    .build();
        }

        // Build redirect URI (must match initiation request)
        String redirectUri = uriInfo.getBaseUriBuilder().path("api/auth/google/callback").build().toString();

        try {
            // Complete OAuth flow
            User user = oauthService.handleGoogleCallback(code, state, redirectUri);

            // Create JWT session (reuse bootstrap logic)
            AuthIdentityService.BootstrapSession session = authService.createSessionForUser(user);

            // Redirect to homepage with JWT token in query parameter
            // TODO (I3.T6): Use secure HttpOnly cookie instead of query parameter
            URI homepageUri = uriInfo.getBaseUriBuilder().path("/").queryParam("token", session.jwt()).build();

            LOG.infof("Google OAuth success: userId=%s, email=%s, redirecting to homepage", user.id, user.email);
            return Response.seeOther(homepageUri).build();

        } catch (SecurityException e) {
            // Invalid or expired state token
            LOG.errorf(e, "Google OAuth callback security exception: %s", e.getMessage());
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse("Invalid or expired state token")).build();

        } catch (IllegalStateException e) {
            // Email already exists with different provider
            LOG.errorf(e, "Google OAuth callback state exception: %s", e.getMessage());
            return Response.status(Response.Status.CONFLICT).entity(new ErrorResponse(e.getMessage())).build();

        } catch (Exception e) {
            // Unexpected error (network, API, etc.)
            LOG.errorf(e, "Google OAuth callback failed: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Authentication failed. Please try again.")).build();
        }
    }

    /**
     * Initiate Facebook OAuth login flow.
     *
     * <p>
     * Generates authorization URL with CSRF state token. The state token is stored in the database with a 5-minute
     * expiration and validated during the callback.
     *
     * @param uriInfo
     *            URI context for building redirect URI
     * @param headers
     *            HTTP headers for extracting session cookie
     * @return OAuth URL response with authorization URL and state token
     */
    @GET
    @Path("/facebook/login")
    @Operation(
            summary = "Initiate Facebook OAuth login",
            description = "Generates Facebook OAuth authorization URL with CSRF state token. "
                    + "The state token is stored in the database with a 5-minute expiration "
                    + "and validated during the callback to prevent CSRF attacks.")
    @APIResponses({@APIResponse(
            responseCode = "200",
            description = "OAuth URL generated successfully",
            content = @Content(
                    schema = @Schema(
                            implementation = OAuthUrlResponseType.class))),
            @APIResponse(
                    responseCode = "500",
                    description = "Internal server error")})
    public Response facebookLogin(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
        // Extract or generate session ID (anonymous cookie or new UUID)
        String sessionId = extractSessionId(headers);

        // Build redirect URI for OAuth callback
        String redirectUri = uriInfo.getBaseUriBuilder().path("api/auth/facebook/callback").build().toString();

        OAuthUrlResponseType response = oauthService.initiateFacebookLogin(sessionId, redirectUri);
        LOG.infof("Initiated Facebook OAuth login: sessionId=%s, state=%s", sessionId, response.state());

        return Response.ok(response).build();
    }

    /**
     * Handle Facebook OAuth callback.
     *
     * <p>
     * Validates state token, exchanges authorization code for access token, retrieves user profile, and creates or
     * links user account. On success, issues JWT session token and redirects to homepage.
     *
     * @param code
     *            authorization code from Facebook
     * @param state
     *            CSRF state token
     * @param error
     *            error code (if user cancelled or error occurred)
     * @param errorDescription
     *            human-readable error description
     * @param uriInfo
     *            URI context for building redirect URI
     * @return redirect to homepage with JWT token on success, error response on failure
     */
    @GET
    @Path("/facebook/callback")
    @Operation(
            summary = "Handle Facebook OAuth callback",
            description = "OAuth callback endpoint invoked by Facebook after user authorization. "
                    + "Validates CSRF state token, exchanges authorization code for access token, "
                    + "retrieves user profile, creates or links account, and issues JWT session. "
                    + "On success, redirects to homepage with JWT token in query parameter.")
    @APIResponses({@APIResponse(
            responseCode = "303",
            description = "Authentication successful - redirecting to homepage with JWT token"),
            @APIResponse(
                    responseCode = "400",
                    description = "Missing authorization code or state parameter, or user cancelled authentication",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "403",
                    description = "Invalid or expired state token (CSRF protection)",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "409",
                    description = "Email already exists with different OAuth provider or email permission denied",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "500",
                    description = "Authentication failed - network error, API error, or internal error",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class)))})
    public Response facebookCallback(@Parameter(
            description = "Authorization code from Facebook",
            required = true) @QueryParam("code") String code,
            @Parameter(
                    description = "CSRF state token",
                    required = true) @QueryParam("state") String state,
            @Parameter(
                    description = "Error code if user cancelled or authorization failed") @QueryParam("error") String error,
            @Parameter(
                    description = "Human-readable error description") @QueryParam("error_description") String errorDescription,
            @Context UriInfo uriInfo) {

        // Handle OAuth errors (user cancellation, permission denial, etc.)
        if (error != null) {
            LOG.warnf("Facebook OAuth error: error=%s, description=%s", error, errorDescription);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("Authentication cancelled or failed: " + error)).build();
        }

        // Validate required parameters
        if (code == null || code.isBlank()) {
            LOG.warn("Facebook OAuth callback missing code parameter");
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse("Missing authorization code"))
                    .build();
        }

        if (state == null || state.isBlank()) {
            LOG.warn("Facebook OAuth callback missing state parameter");
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse("Missing state parameter"))
                    .build();
        }

        // Build redirect URI (must match initiation request)
        String redirectUri = uriInfo.getBaseUriBuilder().path("api/auth/facebook/callback").build().toString();

        try {
            // Complete OAuth flow
            User user = oauthService.handleFacebookCallback(code, state, redirectUri);

            // Create JWT session (reuse bootstrap logic)
            AuthIdentityService.BootstrapSession session = authService.createSessionForUser(user);

            // Redirect to homepage with JWT token in query parameter
            // TODO (I3.T6): Use secure HttpOnly cookie instead of query parameter
            URI homepageUri = uriInfo.getBaseUriBuilder().path("/").queryParam("token", session.jwt()).build();

            LOG.infof("Facebook OAuth success: userId=%s, email=%s, redirecting to homepage", user.id, user.email);
            return Response.seeOther(homepageUri).build();

        } catch (SecurityException e) {
            // Invalid or expired state token
            LOG.errorf(e, "Facebook OAuth callback security exception: %s", e.getMessage());
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse("Invalid or expired state token")).build();

        } catch (IllegalStateException e) {
            // Email already exists with different provider OR email permission denied
            LOG.errorf(e, "Facebook OAuth callback state exception: %s", e.getMessage());
            return Response.status(Response.Status.CONFLICT).entity(new ErrorResponse(e.getMessage())).build();

        } catch (Exception e) {
            // Unexpected error (network, API, etc.)
            LOG.errorf(e, "Facebook OAuth callback failed: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Authentication failed. Please try again.")).build();
        }
    }

    /**
     * Initiate Apple OAuth login flow.
     *
     * <p>
     * Generates authorization URL with CSRF state token. The state token is stored in the database with a 5-minute
     * expiration and validated during the callback.
     *
     * @param uriInfo
     *            URI context for building redirect URI
     * @param headers
     *            HTTP headers for extracting session cookie
     * @return OAuth URL response with authorization URL and state token
     */
    @GET
    @Path("/apple/login")
    @Operation(
            summary = "Initiate Apple OAuth login",
            description = "Generates Apple OAuth authorization URL with CSRF state token. "
                    + "The state token is stored in the database with a 5-minute expiration "
                    + "and validated during the callback to prevent CSRF attacks.")
    @APIResponses({@APIResponse(
            responseCode = "200",
            description = "OAuth URL generated successfully",
            content = @Content(
                    schema = @Schema(
                            implementation = OAuthUrlResponseType.class))),
            @APIResponse(
                    responseCode = "500",
                    description = "Internal server error")})
    public Response appleLogin(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
        // Extract or generate session ID (anonymous cookie or new UUID)
        String sessionId = extractSessionId(headers);

        // Build redirect URI for OAuth callback
        String redirectUri = uriInfo.getBaseUriBuilder().path("api/auth/apple/callback").build().toString();

        OAuthUrlResponseType response = oauthService.initiateAppleLogin(sessionId, redirectUri);
        LOG.infof("Initiated Apple OAuth login: sessionId=%s, state=%s", sessionId, response.state());

        return Response.ok(response).build();
    }

    /**
     * Handle Apple OAuth callback.
     *
     * <p>
     * Validates state token, exchanges authorization code for access token, parses ID token to extract user profile,
     * and creates or links user account. On success, issues JWT session token and redirects to homepage.
     *
     * <p>
     * Apple-specific: This endpoint accepts POST with form parameters (response_mode=form_post) instead of GET with
     * query parameters. This is more secure as credentials are not exposed in browser history.
     *
     * @param code
     *            authorization code from Apple
     * @param state
     *            CSRF state token
     * @param error
     *            error code (if user cancelled or error occurred)
     * @param uriInfo
     *            URI context for building redirect URI
     * @return redirect to homepage with JWT token on success, error response on failure
     */
    @POST
    @Path("/apple/callback")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(
            summary = "Handle Apple OAuth callback",
            description = "OAuth callback endpoint invoked by Apple after user authorization. "
                    + "Validates CSRF state token, exchanges authorization code for access token, "
                    + "parses ID token to extract user profile, creates or links account, and issues JWT session. "
                    + "On success, redirects to homepage with JWT token in query parameter. "
                    + "Apple-specific: Uses POST with form parameters (response_mode=form_post) for enhanced security.")
    @APIResponses({@APIResponse(
            responseCode = "303",
            description = "Authentication successful - redirecting to homepage with JWT token"),
            @APIResponse(
                    responseCode = "400",
                    description = "Missing authorization code or state parameter, or user cancelled authentication",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "403",
                    description = "Invalid or expired state token (CSRF protection)",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "409",
                    description = "Email already exists with different OAuth provider",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "500",
                    description = "Authentication failed - network error, API error, JWT parsing error, or internal error",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class)))})
    public Response appleCallback(@Parameter(
            description = "Authorization code from Apple",
            required = true) @FormParam("code") String code,
            @Parameter(
                    description = "CSRF state token",
                    required = true) @FormParam("state") String state,
            @Parameter(
                    description = "Error code if user cancelled or authorization failed") @FormParam("error") String error,
            @Context UriInfo uriInfo) {

        // Handle OAuth errors (user cancellation, permission denial, etc.)
        if (error != null) {
            LOG.warnf("Apple OAuth error: error=%s", error);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("Authentication cancelled or failed: " + error)).build();
        }

        // Validate required parameters
        if (code == null || code.isBlank()) {
            LOG.warn("Apple OAuth callback missing code parameter");
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse("Missing authorization code"))
                    .build();
        }

        if (state == null || state.isBlank()) {
            LOG.warn("Apple OAuth callback missing state parameter");
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse("Missing state parameter"))
                    .build();
        }

        // Build redirect URI (must match initiation request)
        String redirectUri = uriInfo.getBaseUriBuilder().path("api/auth/apple/callback").build().toString();

        try {
            // Complete OAuth flow
            User user = oauthService.handleAppleCallback(code, state, redirectUri);

            // Create JWT session (reuse bootstrap logic)
            AuthIdentityService.BootstrapSession session = authService.createSessionForUser(user);

            // Redirect to homepage with JWT token in query parameter
            // TODO (I3.T6): Use secure HttpOnly cookie instead of query parameter
            URI homepageUri = uriInfo.getBaseUriBuilder().path("/").queryParam("token", session.jwt()).build();

            LOG.infof("Apple OAuth success: userId=%s, email=%s, redirecting to homepage", user.id, user.email);
            return Response.seeOther(homepageUri).build();

        } catch (SecurityException e) {
            // Invalid or expired state token
            LOG.errorf(e, "Apple OAuth callback security exception: %s", e.getMessage());
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse("Invalid or expired state token")).build();

        } catch (IllegalStateException e) {
            // Email already exists with different provider
            LOG.errorf(e, "Apple OAuth callback state exception: %s", e.getMessage());
            return Response.status(Response.Status.CONFLICT).entity(new ErrorResponse(e.getMessage())).build();

        } catch (Exception e) {
            // Unexpected error (network, API, JWT parsing, etc.)
            LOG.errorf(e, "Apple OAuth callback failed: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Authentication failed. Please try again.")).build();
        }
    }

    /**
     * Extract session ID from anonymous cookie or generate new UUID.
     *
     * @param headers
     *            HTTP headers containing cookies
     * @return session ID (anonymous cookie value or new UUID)
     */
    private String extractSessionId(HttpHeaders headers) {
        // Look for vu_anon_id cookie
        if (headers != null && headers.getCookies() != null) {
            var anonCookie = headers.getCookies().get("vu_anon_id");
            if (anonCookie != null && anonCookie.getValue() != null && !anonCookie.getValue().isBlank()) {
                return anonCookie.getValue();
            }
        }

        // Generate new session ID if no cookie found
        return java.util.UUID.randomUUID().toString();
    }
}
