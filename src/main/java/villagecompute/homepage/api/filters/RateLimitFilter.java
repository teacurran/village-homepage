package villagecompute.homepage.api.filters;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;
import villagecompute.homepage.services.RateLimitService;
import villagecompute.homepage.services.RateLimitService.RateLimitResult;
import villagecompute.homepage.services.RateLimitService.Tier;

import java.lang.reflect.Method;

/**
 * JAX-RS filter for tier-aware rate limiting with HTTP header injection (Policy P14/F14.2).
 *
 * <p>
 * Intercepts requests to {@code @RateLimited} annotated endpoints and enforces sliding-window rate limits based on user
 * tier. Violations trigger 429 responses with Retry-After headers. All successful requests receive X-RateLimit-*
 * headers for client visibility.
 *
 * <p>
 * <b>Execution Flow:</b>
 * <ol>
 * <li>Extract user context from session/cookie (user ID, IP address)</li>
 * <li>Determine tier: anonymous (no auth), logged_in (authenticated), trusted (karma >= 10)</li>
 * <li>Load rate limit config from database (cached by RateLimitService)</li>
 * <li>Check sliding window, store result in request property</li>
 * <li>On violation: return 429 with Retry-After header</li>
 * <li>On success: inject X-RateLimit-Limit, X-RateLimit-Remaining headers in response filter</li>
 * </ol>
 *
 * <p>
 * <b>Thread Safety:</b> Filter instances are request-scoped by JAX-RS spec. RateLimitService handles concurrency.
 *
 * <p>
 * <b>Priority:</b> Runs at {@code Priorities.AUTHORIZATION} (2000) to execute after authentication but before business
 * logic.
 *
 * @see RateLimited annotation for usage examples
 * @see RateLimitService for enforcement logic
 */
@Provider
@RateLimited(
        action = "") // Bind to @RateLimited annotation
@Priority(Priorities.AUTHORIZATION)
public class RateLimitFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final Logger LOG = Logger.getLogger(RateLimitFilter.class);

    private static final String RATE_LIMIT_RESULT_PROPERTY = "villagecompute.rateLimit.result";

    @Inject
    RateLimitService rateLimitService;

    @Context
    ResourceInfo resourceInfo;

    /**
     * Request filter: checks rate limit before method execution.
     *
     * <p>
     * Extracts action type from {@code @RateLimited} annotation, determines user tier, and enforces limits. Stores
     * RateLimitResult in request properties for response header injection.
     */
    @Override
    public void filter(ContainerRequestContext requestContext) {
        Method method = resourceInfo.getResourceMethod();
        if (method == null) {
            return;
        }

        RateLimited rateLimited = method.getAnnotation(RateLimited.class);
        if (rateLimited == null) {
            // Fallback to class-level annotation
            rateLimited = resourceInfo.getResourceClass().getAnnotation(RateLimited.class);
        }

        if (rateLimited == null || rateLimited.action().isBlank()) {
            LOG.warnf("@RateLimited annotation missing or empty action on method: %s", method.getName());
            return;
        }

        String actionType = rateLimited.action();
        String endpoint = requestContext.getUriInfo().getPath();
        String ipAddress = extractIpAddress(requestContext);

        // TODO: Extract user ID and tier from authenticated session/JWT
        // For now, assume anonymous tier for all requests
        Long userId = null;
        Tier tier = Tier.ANONYMOUS;

        // Check rate limit
        RateLimitResult result = rateLimitService.checkLimit(userId, ipAddress, actionType, tier, endpoint);

        // Store result for response filter
        requestContext.setProperty(RATE_LIMIT_RESULT_PROPERTY, result);

        if (!result.allowed()) {
            // Rate limit exceeded - abort with 429
            int retryAfterSeconds = result.windowSeconds();

            Response response = Response.status(429) // Too Many Requests
                    .header("X-RateLimit-Limit", result.limitCount()).header("X-RateLimit-Remaining", 0)
                    .header("Retry-After", retryAfterSeconds)
                    .entity(new ErrorResponse("Rate limit exceeded. Retry after " + retryAfterSeconds + " seconds."))
                    .build();

            requestContext.abortWith(response);
            LOG.infof("Rate limit exceeded: action=%s tier=%s ip=%s limit=%d", actionType, tier, ipAddress,
                    result.limitCount());
        }
    }

    /**
     * Response filter: injects rate limit headers into successful responses.
     *
     * <p>
     * Adds X-RateLimit-Limit and X-RateLimit-Remaining headers per API Style Detail 14. Only runs if request filter
     * executed successfully (i.e., rate limit check passed).
     */
    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        Object resultObj = requestContext.getProperty(RATE_LIMIT_RESULT_PROPERTY);
        if (!(resultObj instanceof RateLimitResult result)) {
            return;
        }

        // Inject rate limit headers for visibility
        responseContext.getHeaders().add("X-RateLimit-Limit", result.limitCount());
        responseContext.getHeaders().add("X-RateLimit-Remaining", Math.max(0, result.remaining()));
    }

    /**
     * Extracts client IP address from request headers or connection info.
     *
     * <p>
     * Checks X-Forwarded-For header first (for reverse proxy scenarios), falls back to remote address.
     *
     * @param context
     *            request context
     * @return IP address string
     */
    private String extractIpAddress(ContainerRequestContext context) {
        // Check X-Forwarded-For header (reverse proxy)
        String forwardedFor = context.getHeaderString("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            // Take first IP if multiple (client IP)
            int commaIndex = forwardedFor.indexOf(',');
            return commaIndex > 0 ? forwardedFor.substring(0, commaIndex).trim() : forwardedFor.trim();
        }

        // Fallback: attempt to extract from security context or return placeholder
        // TODO: Integrate with actual request IP extraction once networking layer is finalized
        return "127.0.0.1";
    }

    /**
     * Simple error response record for 429 responses.
     */
    public record ErrorResponse(String error) {
    }
}
