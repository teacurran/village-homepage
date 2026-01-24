package villagecompute.homepage.api.rest;

import java.util.List;
import java.util.UUID;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import villagecompute.homepage.api.types.StockWidgetType;
import villagecompute.homepage.api.types.UserPreferencesType;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.services.RateLimitService;
import villagecompute.homepage.services.StockService;
import villagecompute.homepage.services.UserPreferenceService;

/**
 * REST endpoint for stock market widget.
 *
 * <p>
 * Provides stock quotes for user's watchlist with authentication and rate limiting. Follows the same pattern as
 * WeatherWidgetResource.
 */
@Path("/api/widgets/stocks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(
        name = "Widgets",
        description = "Homepage widget data operations")
public class StockWidgetResource {

    private static final Logger LOG = Logger.getLogger(StockWidgetResource.class);

    private static final int MAX_WATCHLIST_SIZE = 20;

    @Inject
    StockService stockService;

    @Inject
    UserPreferenceService userPreferenceService;

    @Inject
    RateLimitService rateLimitService;

    /**
     * Get stock quotes for user's watchlist.
     *
     * @param securityContext
     *            security context with authenticated user
     * @return StockWidgetType with quotes and metadata
     */
    @GET
    @Operation(
            summary = "Get stock widget data",
            description = "Retrieve stock quotes for user's watchlist with market status")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Stock data returned successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = StockWidgetType.class))),
                    @APIResponse(
                            responseCode = "401",
                            description = "Authentication required",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "429",
                            description = "Rate limit exceeded",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "500",
                            description = "Server error",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    @SecurityRequirement(
            name = "bearerAuth")
    public Response getStocks(@Context SecurityContext securityContext) {
        // Extract user ID from security context
        UUID userId = extractUserId(securityContext);

        LOG.debugf("Fetching stock quotes for user %s", userId);

        // Check rate limit
        RateLimitService.Tier tier = determineTier(securityContext);
        RateLimitService.RateLimitResult rateLimitResult = rateLimitService.checkLimit(null, null, "stock_widget_view",
                tier, "/api/widgets/stocks");

        if (!rateLimitResult.allowed()) {
            LOG.warnf("Rate limit exceeded for user %s on stock_widget_view", userId);
            return Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .header("X-RateLimit-Remaining", String.valueOf(rateLimitResult.remaining()))
                    .entity("Rate limit exceeded. Please try again later.").build();
        }

        // Get user preferences
        UserPreferencesType preferences = userPreferenceService.getPreferences(userId);
        List<String> watchlist = preferences.watchlist();

        if (watchlist.isEmpty()) {
            // Return empty widget with market status
            LOG.debugf("User %s has empty watchlist", userId);
            return Response.ok(new StockWidgetType(List.of(), stockService.getMarketStatus(),
                    java.time.Instant.now().toString(), false, false)).build();
        }

        // Fetch quotes for watchlist
        StockWidgetType widget = stockService.getQuotes(watchlist);

        // Add cache headers
        String cacheControl = stockService.isMarketOpen() ? "max-age=300" : "max-age=3600"; // 5min open, 1hr closed
        return Response.ok(widget).header("Cache-Control", cacheControl).build();
    }

    /**
     * Update user's stock watchlist.
     *
     * @param symbols
     *            list of ticker symbols (max 20)
     * @param securityContext
     *            security context with authenticated user
     * @return success response
     */
    @PUT
    @Path("/watchlist")
    @Transactional
    @Operation(
            summary = "Update stock watchlist",
            description = "Update user's stock watchlist (maximum 20 symbols)")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Watchlist updated successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "400",
                            description = "Invalid watchlist (too many symbols or invalid format)",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "401",
                            description = "Authentication required",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "500",
                            description = "Server error",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    @SecurityRequirement(
            name = "bearerAuth")
    public Response updateWatchlist(@Valid @NotNull @Size(
            max = MAX_WATCHLIST_SIZE) List<String> symbols, @Context SecurityContext securityContext) {

        UUID userId = extractUserId(securityContext);

        LOG.infof("Updating watchlist for user %s with %d symbols", userId, symbols.size());

        // Validate watchlist size
        if (symbols.size() > MAX_WATCHLIST_SIZE) {
            LOG.warnf("User %s attempted to set watchlist with %d symbols (max %d)", userId, symbols.size(),
                    MAX_WATCHLIST_SIZE);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Watchlist limited to " + MAX_WATCHLIST_SIZE + " symbols").build();
        }

        // Validate symbol format (uppercase, alphanumeric + '^')
        for (String symbol : symbols) {
            if (!symbol.matches("^[A-Z0-9^]+$")) {
                LOG.warnf("User %s provided invalid symbol: %s", userId, symbol);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("Invalid symbol format: " + symbol + " (must be uppercase alphanumeric)").build();
            }
        }

        // Update preferences
        UserPreferencesType preferences = userPreferenceService.getPreferences(userId);
        UserPreferencesType updated = new UserPreferencesType(preferences.schemaVersion(), preferences.layout(),
                preferences.newsTopics(), symbols, // Replace watchlist
                preferences.weatherLocations(), preferences.theme(), preferences.widgetConfigs());

        userPreferenceService.updatePreferences(userId, updated);

        LOG.infof("Successfully updated watchlist for user %s", userId);

        return Response.ok().entity(Map.of("message", "Watchlist updated successfully", "symbols", symbols)).build();
    }

    /**
     * Extract user ID from security context.
     *
     * @param securityContext
     *            security context
     * @return user ID
     */
    private UUID extractUserId(SecurityContext securityContext) {
        if (securityContext.getUserPrincipal() == null) {
            throw new IllegalStateException("No authenticated user");
        }

        String principalName = securityContext.getUserPrincipal().getName();

        // Try parsing as UUID first
        try {
            return UUID.fromString(principalName);
        } catch (IllegalArgumentException e) {
            // Principal name might be email or username - look up user
            User user = User.findByEmail(principalName)
                    .orElseThrow(() -> new IllegalStateException("User not found: " + principalName));
            return user.id;
        }
    }

    /**
     * Determines user tier for rate limiting based on security context.
     *
     * <p>
     * TODO: Fetch user entity and determine tier from karma once integration is complete.
     *
     * @param securityContext
     *            security context with principal
     * @return user tier for rate limiting
     */
    private RateLimitService.Tier determineTier(SecurityContext securityContext) {
        if (securityContext == null || securityContext.getUserPrincipal() == null) {
            return RateLimitService.Tier.ANONYMOUS;
        }

        return RateLimitService.Tier.LOGGED_IN;
    }

    /**
     * Helper class for JSON response.
     */
    private record Map(String message, List<String> symbols) {
        static Map of(String key1, String value1, String key2, List<String> value2) {
            return new Map(value1, value2);
        }
    }
}
