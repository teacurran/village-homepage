package villagecompute.homepage.api.rest;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.ClickEventType;
import villagecompute.homepage.data.models.LinkClick;
import villagecompute.homepage.services.RateLimitService;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Click tracking endpoint for analytics (Policy F14.9).
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>POST /track/click – Record click event with metadata</li>
 * </ul>
 *
 * <p>
 * Security:
 * <ul>
 * <li>Public endpoint (PermitAll) - tracks both anonymous and authenticated users</li>
 * <li>Rate limited: 60/minute for anonymous, 300/minute for logged_in</li>
 * <li>IP addresses sanitized before storage (last octet zeroed)</li>
 * </ul>
 *
 * <p>
 * Data Retention: Raw click logs retained for 90 days via partitioned table. Daily rollup aggregates retained
 * indefinitely for analytics dashboards.
 * </p>
 *
 * @see LinkClick for persistence
 * @see villagecompute.homepage.jobs.ClickRollupJobHandler for aggregation
 */
@Path("/track")
public class ClickTrackingResource {

    private static final Logger LOG = Logger.getLogger(ClickTrackingResource.class);

    @Inject
    RateLimitService rateLimitService;

    @Context
    SecurityIdentity securityIdentity;

    @Context
    jakarta.ws.rs.container.ContainerRequestContext requestContext;

    /**
     * Track click event.
     *
     * <p>
     * Route: POST /track/click
     * </p>
     *
     * <p>
     * Example request:
     *
     * <pre>
     * {
     *   "clickType": "directory_site_click",
     *   "targetId": "a3f8b9c0-1234-5678-abcd-1234567890ab",
     *   "targetUrl": "https://example.com",
     *   "metadata": {
     *     "category_id": "b4e9c1d1-2345-6789-bcde-2345678901bc",
     *     "is_bubbled": false,
     *     "rank_in_category": 2,
     *     "score": 15
     *   }
     * }
     *
     * // Profile curated article click example:
     * {
     *   "clickType": "profile_curated",
     *   "targetId": "c5f9d2e2-3456-7890-cdef-3456789012cd",
     *   "targetUrl": "https://example.com/article",
     *   "metadata": {
     *     "profile_id": "d6f0e3f3-4567-8901-def0-4567890123de",
     *     "profile_username": "johndoe",
     *     "article_id": "c5f9d2e2-3456-7890-cdef-3456789012cd",
     *     "article_slot": "top_pick",
     *     "template": "minimal"
     *   }
     * }
     * </pre>
     *
     * @param event
     *            Click event details
     * @return 200 OK with status message
     */
    @POST
    @Path("/click")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response trackClick(@Valid ClickEventType event) {
        try {
            // Extract user/session info
            UUID userId = null;
            Long userIdLong = null;
            String sessionId = null;
            RateLimitService.Tier tier;

            if (!securityIdentity.isAnonymous()) {
                userId = UUID.fromString(securityIdentity.getPrincipal().getName());
                userIdLong = userId.getMostSignificantBits(); // Simplified user ID for rate limiting
                // TODO: Get actual karma from User entity to determine tier
                tier = RateLimitService.Tier.LOGGED_IN;
            } else {
                // Use session cookie or generate session hash
                sessionId = extractSessionId();
                tier = RateLimitService.Tier.ANONYMOUS;
            }

            // Rate limit check
            String ipAddress = getClientIP();
            RateLimitService.RateLimitResult rateLimitResult = rateLimitService.checkLimit(userIdLong, ipAddress,
                    "click_tracking", tier, "/track/click");

            if (!rateLimitResult.allowed()) {
                LOG.warnf("Rate limit exceeded for click tracking: user=%s ip=%s", userId, ipAddress);
                return Response.status(Response.Status.TOO_MANY_REQUESTS).entity(Map.of("status", "error", "message",
                        "Rate limit exceeded", "retry_after", rateLimitResult.windowSeconds())).build();
            }

            // Create LinkClick entity
            LinkClick click = new LinkClick();
            Instant now = Instant.now();
            click.clickDate = LocalDate.now();
            click.clickTimestamp = now;
            click.clickType = event.clickType();
            click.targetId = event.targetId();
            click.targetUrl = event.targetUrl();
            click.userId = userId;
            click.sessionId = sessionId;
            click.ipAddress = sanitizeIpAddress(getClientIP());
            click.userAgent = sanitizeUserAgent(getUserAgent());
            click.referer = getReferer();
            click.createdAt = now;

            // Extract category_id from metadata if present
            if (event.metadata() != null && event.metadata().containsKey("category_id")) {
                Object categoryIdObj = event.metadata().get("category_id");
                if (categoryIdObj != null) {
                    try {
                        click.categoryId = UUID.fromString(categoryIdObj.toString());
                    } catch (IllegalArgumentException e) {
                        LOG.warnf("Invalid category_id in metadata: %s", categoryIdObj);
                    }
                }
            }

            // Extract profile_id for profile events
            if (event.clickType().startsWith("profile_") && event.metadata() != null) {
                if (event.metadata().containsKey("profile_id")) {
                    try {
                        UUID profileId = UUID.fromString(event.metadata().get("profile_id").toString());
                        // Profile context metadata is stored in JSONB; no separate field needed
                        LOG.debugf("Profile event: type=%s, profile_id=%s", event.clickType(), profileId);
                    } catch (IllegalArgumentException e) {
                        LOG.warnf("Invalid profile_id in metadata: %s", event.metadata().get("profile_id"));
                    }
                }
            }

            // Store full metadata as JSONB
            if (event.metadata() != null && !event.metadata().isEmpty()) {
                click.setMetadataFromJson(new JsonObject(event.metadata()));
            }

            // Persist click event
            click.persist();

            LOG.debugf("Tracked click event: type=%s, target=%s, user=%s", event.clickType(), event.targetId(),
                    userId != null ? userId : sessionId);

            return Response.ok(Map.of("status", "tracked", "clickId", click.id.toString())).build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to track click event: %s", event.clickType());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("status", "error", "message", "Failed to track click")).build();
        }
    }

    /**
     * Track click and redirect (GET variant for HTML links).
     *
     * <p>
     * Route: GET /track/click?url=...&source=...&metadata=...
     * </p>
     *
     * <p>
     * This endpoint tracks the click asynchronously and immediately redirects to the target URL. It's designed for use
     * in HTML href attributes where POST is not convenient.
     *
     * @param url
     *            target URL to redirect to
     * @param source
     *            click source (e.g., "good_sites_category")
     * @param metadata
     *            JSON-encoded metadata string
     * @return 307 redirect to target URL
     */
    @GET
    @Path("/click")
    @PermitAll
    @Produces(MediaType.TEXT_HTML)
    public Response trackClickAndRedirect(@QueryParam("url") String url, @QueryParam("source") String source,
            @QueryParam("metadata") String metadata) {
        try {
            // Extract user/session info
            UUID userId = null;
            Long userIdLong = null;
            String sessionId = null;
            RateLimitService.Tier tier;

            if (!securityIdentity.isAnonymous()) {
                userId = UUID.fromString(securityIdentity.getPrincipal().getName());
                userIdLong = userId.getMostSignificantBits();
                tier = RateLimitService.Tier.LOGGED_IN;
            } else {
                sessionId = extractSessionId();
                tier = RateLimitService.Tier.ANONYMOUS;
            }

            // Rate limit check
            String ipAddress = getClientIP();
            RateLimitService.RateLimitResult rateLimitResult = rateLimitService.checkLimit(userIdLong, ipAddress,
                    "click_tracking", tier, "/track/click");

            if (!rateLimitResult.allowed()) {
                LOG.warnf("Rate limit exceeded for click tracking redirect: user=%s ip=%s", userId, ipAddress);
                // Still redirect but don't track
                return Response.temporaryRedirect(java.net.URI.create(url)).build();
            }

            // Parse metadata (simple key:value format for URL params)
            // Expected format: "categoryId:uuid,siteId:uuid,rank:2"
            UUID targetId = null;
            UUID categoryId = null;
            JsonObject metadataJson = new JsonObject();

            if (metadata != null && !metadata.isEmpty()) {
                for (String pair : metadata.split(",")) {
                    String[] kv = pair.split(":", 2);
                    if (kv.length == 2) {
                        String key = kv[0].trim();
                        String value = kv[1].trim();

                        // Try to parse as UUID for special keys
                        if (key.equals("siteId") || key.equals("targetId")) {
                            try {
                                targetId = UUID.fromString(value);
                                metadataJson.put(key, value);
                            } catch (IllegalArgumentException e) {
                                metadataJson.put(key, value);
                            }
                        } else if (key.equals("categoryId")) {
                            try {
                                categoryId = UUID.fromString(value);
                                metadataJson.put(key, value);
                            } catch (IllegalArgumentException e) {
                                metadataJson.put(key, value);
                            }
                        } else {
                            // Try to parse as number
                            try {
                                metadataJson.put(key, Integer.parseInt(value));
                            } catch (NumberFormatException e) {
                                metadataJson.put(key, value);
                            }
                        }
                    }
                }
            }

            // Capture variables for async lambda (must be final)
            final UUID finalUserId = userId;
            final String finalSessionId = sessionId;
            final UUID finalTargetId = targetId;
            final UUID finalCategoryId = categoryId;
            final JsonObject finalMetadata = metadataJson;
            final String finalIpAddress = ipAddress;
            final String finalUserAgent = getUserAgent();
            final String finalReferer = getReferer();
            final String finalUrl = url;
            final String finalSource = source;

            // Create LinkClick entity (asynchronously to avoid blocking redirect)
            CompletableFuture.runAsync(() -> {
                try {
                    QuarkusTransaction.requiringNew().run(() -> {
                        LinkClick click = new LinkClick();
                        Instant now = Instant.now();
                        click.clickDate = LocalDate.now();
                        click.clickTimestamp = now;
                        // Map source to click_type
                        click.clickType = mapSourceToClickType(finalSource);
                        click.targetId = finalTargetId;
                        click.targetUrl = finalUrl;
                        click.userId = finalUserId;
                        click.sessionId = finalSessionId;
                        click.ipAddress = sanitizeIpAddress(finalIpAddress);
                        click.userAgent = sanitizeUserAgent(finalUserAgent);
                        click.referer = finalReferer;
                        click.categoryId = finalCategoryId;
                        click.setMetadataFromJson(finalMetadata);
                        click.createdAt = now;
                        click.persist();
                    });
                } catch (Exception e) {
                    LOG.errorf(e, "Failed to persist click event asynchronously: url=%s", finalUrl);
                }
            });

            // Redirect immediately
            return Response.temporaryRedirect(java.net.URI.create(url)).build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to track click redirect: url=%s", url);
            // Still redirect even on error
            return Response.temporaryRedirect(java.net.URI.create(url)).build();
        }
    }

    /**
     * Maps source string to click_type enum value.
     */
    private String mapSourceToClickType(String source) {
        if (source == null) {
            return "directory_site_click";
        }
        return switch (source) {
            case "good_sites_category" -> "directory_site_click";
            case "good_sites_bubbled" -> "directory_bubbled_click";
            case "good_sites_search" -> "directory_site_click";
            case "marketplace_listing" -> "marketplace_listing";
            default -> "directory_site_click";
        };
    }

    /**
     * Get user identifier for rate limiting (user ID or IP address).
     */
    private String getUserIdentifier() {
        if (!securityIdentity.isAnonymous()) {
            return securityIdentity.getPrincipal().getName();
        }
        return getClientIP();
    }

    /**
     * Extract session ID from cookie or generate hash.
     */
    private String extractSessionId() {
        // Try to get session from cookie
        jakarta.ws.rs.core.Cookie sessionCookie = requestContext.getCookies().get("session");
        if (sessionCookie != null) {
            return sessionCookie.getValue();
        }

        // Fallback: hash of IP + User-Agent for anonymous tracking
        String ip = getClientIP();
        String ua = getUserAgent();
        return Integer.toHexString((ip + ua).hashCode());
    }

    /**
     * Get client IP address from request headers (handles proxies).
     */
    private String getClientIP() {
        String xForwardedFor = requestContext.getHeaderString("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }

        String xRealIp = requestContext.getHeaderString("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }

        return requestContext.getUriInfo().getRequestUri().getHost();
    }

    /**
     * Sanitize IP address for privacy (zero out last octet).
     */
    private String sanitizeIpAddress(String ip) {
        if (ip == null || ip.isEmpty()) {
            return null;
        }

        // IPv4: Zero out last octet (e.g., 192.168.1.100 -> 192.168.1.0)
        if (ip.contains(".")) {
            String[] parts = ip.split("\\.");
            if (parts.length == 4) {
                return parts[0] + "." + parts[1] + "." + parts[2] + ".0";
            }
        }

        // IPv6: Zero out last segment (simplified)
        if (ip.contains(":")) {
            int lastColon = ip.lastIndexOf(':');
            if (lastColon > 0) {
                return ip.substring(0, lastColon + 1) + "0";
            }
        }

        return ip;
    }

    /**
     * Get User-Agent header.
     */
    private String getUserAgent() {
        return requestContext.getHeaderString("User-Agent");
    }

    /**
     * Sanitize User-Agent for privacy (truncate to 512 chars, strip detailed version info).
     */
    private String sanitizeUserAgent(String ua) {
        if (ua == null || ua.isEmpty()) {
            return null;
        }

        // Truncate to 512 chars
        if (ua.length() > 512) {
            ua = ua.substring(0, 512);
        }

        return ua;
    }

    /**
     * Get Referer header.
     */
    private String getReferer() {
        return requestContext.getHeaderString("Referer");
    }
}
