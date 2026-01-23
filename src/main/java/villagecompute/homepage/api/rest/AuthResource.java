package villagecompute.homepage.api.rest;

import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
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
public class AuthResource {

    private static final Logger LOG = Logger.getLogger(AuthResource.class);

    @Inject
    AuthIdentityService authService;

    @Inject
    OAuthService oauthService;

    @POST
    @Path("/anonymous")
    public Response issueAnonymousCookie() {
        NewCookie cookie = authService.issueAnonymousCookie();
        AnonymousResponse body = new AnonymousResponse("Anonymous account created", cookie.getValue());
        return Response.ok(body).cookie(cookie).build();
    }

    @GET
    @Path("/login/{provider}")
    public Response login(@PathParam("provider") String provider, @QueryParam("bootstrap") boolean bootstrapFlow,
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
    public Response bootstrap(@QueryParam("token") String token, @Context HttpHeaders headers,
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
    public Response googleCallback(@QueryParam("code") String code, @QueryParam("state") String state,
            @QueryParam("error") String error, @QueryParam("error_description") String errorDescription,
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
