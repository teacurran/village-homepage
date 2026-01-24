/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.api.rest;

import java.security.Principal;
import java.util.*;
import java.util.stream.Collectors;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import villagecompute.homepage.api.types.AiTagsType;
import villagecompute.homepage.api.types.FeedItemType;
import villagecompute.homepage.api.types.NewsWidgetType;
import villagecompute.homepage.api.types.UserPreferencesType;
import villagecompute.homepage.data.models.FeedItem;
import villagecompute.homepage.data.models.UserFeedSubscription;
import villagecompute.homepage.services.FeedAggregationService;
import villagecompute.homepage.services.RateLimitService;
import villagecompute.homepage.services.UserPreferenceService;

/**
 * REST endpoint for news widget data.
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>{@code GET /api/widgets/news} – retrieve personalized news feed with AI tags and pagination</li>
 * </ul>
 *
 * <p>
 * <b>Security:</b> Requires authentication. User ID is extracted from JWT token in SecurityContext. Users can only
 * access their own personalized feed based on subscriptions and topic preferences.
 *
 * <p>
 * <b>Rate Limiting:</b> Enforces tier-based rate limits:
 * <ul>
 * <li>Anonymous: 20 reads/hour</li>
 * <li>Logged-in: 100 reads/hour</li>
 * <li>Trusted: 500 reads/hour</li>
 * </ul>
 *
 * <p>
 * <b>Personalization:</b> Feed items are filtered based on:
 * <ul>
 * <li>User's active RSS source subscriptions</li>
 * <li>User's topic preferences (matched against AI tags when available)</li>
 * <li>Optional category and topics query parameters for additional filtering</li>
 * </ul>
 *
 * <p>
 * <b>Caching:</b> News feed data is cached with 5-minute TTL. Response includes {@code Cache-Control} headers:
 * {@code max-age=300, must-revalidate}. Cache varies by Authorization header (per-user caching).
 *
 * <p>
 * <b>Response Format:</b>
 *
 * <pre>
 * {
 *   "items": [
 *     {
 *       "id": "uuid",
 *       "source_id": "uuid",
 *       "title": "Article Title",
 *       "url": "https://example.com/article",
 *       "description": "Summary...",
 *       "author": "Author Name",
 *       "published_at": "2025-01-09T10:00:00Z",
 *       "ai_tags": {
 *         "topics": ["technology", "ai"],
 *         "sentiment": "positive",
 *         "categories": ["Tech"],
 *         "confidence": 0.95
 *       },
 *       "ai_tagged": true,
 *       "fetched_at": "2025-01-09T10:30:00Z"
 *     }
 *   ],
 *   "total_count": 150,
 *   "limit": 20,
 *   "offset": 0,
 *   "metadata": {
 *     "click_tracking_enabled": true,
 *     "click_tracking_base_url": "/track/click",
 *     "feature_flags": {
 *       "ai_tags_enabled": true,
 *       "personalization_enabled": true
 *     }
 *   }
 * }
 * </pre>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P1 (GDPR/CCPA): Feed subscriptions are personal data, secured per-user</li>
 * <li>P2/P10 (AI Budget): AI tags included when available within budget limits</li>
 * <li>P14 (Rate Limiting): All operations are rate-limited to prevent abuse</li>
 * </ul>
 */
@Path("/api/widgets/news")
@Produces(MediaType.APPLICATION_JSON)
@Tag(
        name = "Widgets",
        description = "Homepage widget data operations")
public class NewsWidgetResource {

    private static final Logger LOG = Logger.getLogger(NewsWidgetResource.class);

    private static final int MAX_LIMIT = 100;
    private static final int DEFAULT_LIMIT = 20;
    private static final int CACHE_TTL_SECONDS = 300; // 5 minutes

    @Inject
    FeedAggregationService feedAggregationService;

    @Inject
    UserPreferenceService userPreferenceService;

    @Inject
    RateLimitService rateLimitService;

    /**
     * Retrieves personalized news feed for authenticated user.
     *
     * <p>
     * Supports pagination and filtering by category/topics. Items are sorted by published_at descending. Includes AI
     * tags when available for topic matching and personalization.
     *
     * <p>
     * Rate limits: 100 reads/hour for logged_in tier. Cache: 5 minutes (max-age=300).
     *
     * @param securityContext
     *            injected security context with JWT principal
     * @param limit
     *            max items per page (default 20, max 100)
     * @param offset
     *            pagination offset (default 0)
     * @param category
     *            optional RSS source category filter
     * @param topics
     *            optional comma-separated topics for AI tag matching
     * @return 200 OK with NewsWidgetType, 401 if not authenticated, 429 if rate limited
     */
    @GET
    @Operation(
            summary = "Get news widget data",
            description = "Retrieve personalized news feed with AI tags and pagination. 5-minute cache TTL.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "News feed returned successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = NewsWidgetType.class))),
                    @APIResponse(
                            responseCode = "400",
                            description = "Invalid pagination parameters",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
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
                            description = "Failed to retrieve news feed",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    @SecurityRequirement(
            name = "bearerAuth")
    public Response getNews(@Context SecurityContext securityContext, @Parameter(
            description = "Maximum items per page (1-100)",
            example = "20") @QueryParam("limit") @DefaultValue("20") int limit,
            @Parameter(
                    description = "Pagination offset",
                    example = "0") @QueryParam("offset") @DefaultValue("0") int offset,
            @Parameter(
                    description = "Optional RSS source category filter") @QueryParam("category") String category,
            @Parameter(
                    description = "Optional comma-separated topics for AI tag matching") @QueryParam("topics") String topics) {

        // Extract user ID from security context
        UUID userId = extractUserId(securityContext);
        if (userId == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity(new ErrorResponse("Authentication required"))
                    .build();
        }

        // Validate pagination parameters
        if (limit <= 0 || limit > MAX_LIMIT) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("limit must be between 1 and " + MAX_LIMIT)).build();
        }
        if (offset < 0) {
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse("offset must be >= 0"))
                    .build();
        }

        // Check rate limit
        RateLimitService.Tier tier = determineTier(securityContext);
        RateLimitService.RateLimitResult rateLimitResult = rateLimitService.checkLimit(null, null, "news_read", tier,
                "/api/widgets/news");

        if (!rateLimitResult.allowed()) {
            LOG.warnf("Rate limit exceeded for user %s on news_read", userId);
            return Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .entity(new ErrorResponse("Rate limit exceeded. Try again later."))
                    .header("X-RateLimit-Limit", rateLimitResult.limitCount())
                    .header("X-RateLimit-Remaining", rateLimitResult.remaining())
                    .header("X-RateLimit-Window", rateLimitResult.windowSeconds()).build();
        }

        try {
            // Fetch user preferences and active subscriptions
            UserPreferencesType preferences = userPreferenceService.getPreferences(userId);
            List<UserFeedSubscription> subscriptions = feedAggregationService.getActiveSubscriptions(userId);

            if (subscriptions.isEmpty()) {
                LOG.debugf("User %s has no active feed subscriptions", userId);
                // Return empty result with metadata
                NewsWidgetType emptyWidget = buildEmptyWidget(preferences);
                return buildSuccessResponse(emptyWidget, rateLimitResult);
            }

            // Extract subscribed source IDs
            Set<UUID> subscribedSourceIds = subscriptions.stream().map(s -> s.sourceId).collect(Collectors.toSet());

            // Get user's topic preferences
            Set<String> preferredTopics = new HashSet<>(preferences.newsTopics());

            // Parse additional topics from query parameter
            Set<String> queryTopics = new HashSet<>();
            if (topics != null && !topics.isBlank()) {
                queryTopics.addAll(Arrays.stream(topics.split(",")).map(String::trim).filter(t -> !t.isEmpty())
                        .collect(Collectors.toSet()));
            }

            // Fetch recent items (3x limit for filtering headroom)
            int fetchLimit = Math.min(limit * 3, 300);
            List<FeedItem> allItems = feedAggregationService.getRecentFeedItems(fetchLimit);

            // Filter and score items
            List<FeedItem> filteredItems = allItems.stream()
                    // Filter by subscribed sources
                    .filter(item -> subscribedSourceIds.contains(item.sourceId))
                    // Filter by category if specified
                    .filter(item -> {
                        if (category == null || category.isBlank()) {
                            return true;
                        }
                        // TODO: Fetch source and match category - requires join or service enhancement
                        return true;
                    })
                    // Filter by topics if user has preferences or query param provided
                    .filter(item -> {
                        Set<String> allTopics = new HashSet<>();
                        allTopics.addAll(preferredTopics);
                        allTopics.addAll(queryTopics);

                        if (allTopics.isEmpty()) {
                            return true; // No topic filter
                        }

                        // If item is AI-tagged, match topics
                        if (item.aiTagged && item.aiTags != null) {
                            AiTagsType aiTags = item.aiTags;
                            return aiTags.topics().stream().anyMatch(allTopics::contains);
                        }

                        // If not tagged yet, include items from subscribed sources
                        // (tagging is async, don't exclude untagged items)
                        return preferredTopics.isEmpty() && queryTopics.isEmpty();
                    }).collect(Collectors.toList());

            // Apply pagination
            int totalCount = filteredItems.size();
            List<FeedItem> paginatedItems = filteredItems.stream().skip(offset).limit(limit)
                    .collect(Collectors.toList());

            // Convert to DTOs
            List<FeedItemType> feedItemDTOs = paginatedItems.stream().map(FeedItemType::fromEntity)
                    .collect(Collectors.toList());

            // Build metadata
            Map<String, Object> metadata = buildMetadata(preferences, !preferredTopics.isEmpty());

            // Build response
            NewsWidgetType newsWidget = new NewsWidgetType(feedItemDTOs, totalCount, limit, offset, metadata);

            return buildSuccessResponse(newsWidget, rateLimitResult);

        } catch (IllegalArgumentException e) {
            LOG.errorf(e, "Failed to retrieve news for user %s", userId);
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse(e.getMessage())).build();
        } catch (Exception e) {
            LOG.errorf(e, "Unexpected error retrieving news for user %s", userId);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Failed to retrieve news feed")).build();
        }
    }

    /**
     * Builds empty widget response when user has no subscriptions.
     */
    private NewsWidgetType buildEmptyWidget(UserPreferencesType preferences) {
        Map<String, Object> metadata = buildMetadata(preferences, false);
        return new NewsWidgetType(List.of(), 0, DEFAULT_LIMIT, 0, metadata);
    }

    /**
     * Builds metadata map with feature flags and click tracking configuration.
     */
    private Map<String, Object> buildMetadata(UserPreferencesType preferences, boolean personalizationActive) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("click_tracking_enabled", true);
        metadata.put("click_tracking_base_url", "/track/click");

        Map<String, Object> featureFlags = new HashMap<>();
        featureFlags.put("ai_tags_enabled", true);
        featureFlags.put("personalization_enabled", personalizationActive);

        metadata.put("feature_flags", featureFlags);

        return metadata;
    }

    /**
     * Builds successful response with caching headers and rate limit info.
     */
    private Response buildSuccessResponse(NewsWidgetType newsWidget, RateLimitService.RateLimitResult rateLimitResult) {
        return Response.ok(newsWidget).header("Cache-Control", "max-age=" + CACHE_TTL_SECONDS + ", must-revalidate")
                .header("Vary", "Authorization").header("X-RateLimit-Limit", rateLimitResult.limitCount())
                .header("X-RateLimit-Remaining", rateLimitResult.remaining())
                .header("X-RateLimit-Window", rateLimitResult.windowSeconds()).build();
    }

    /**
     * Extracts user ID from JWT principal in security context.
     * <p>
     * TODO: Replace with actual JWT claim extraction once OAuth integration lands in I2.T1.
     *
     * @param securityContext
     *            security context with principal
     * @return user UUID if authenticated, null otherwise
     */
    private UUID extractUserId(SecurityContext securityContext) {
        if (securityContext == null) {
            return null;
        }

        Principal principal = securityContext.getUserPrincipal();
        if (principal == null) {
            return null;
        }

        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException e) {
            LOG.warnf("Failed to parse user ID from principal: %s", principal.getName());
            return null;
        }
    }

    /**
     * Determines user tier for rate limiting based on security context.
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
     * Simple error response record for API errors.
     */
    public record ErrorResponse(String error) {
    }
}
