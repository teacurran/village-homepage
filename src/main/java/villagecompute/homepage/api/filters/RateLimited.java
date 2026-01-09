package villagecompute.homepage.api.filters;

import jakarta.ws.rs.NameBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * JAX-RS name binding annotation for rate limit enforcement.
 *
 * <p>
 * Apply this annotation to REST resource methods to enable automatic rate limiting with tier-aware rules and HTTP
 * header injection.
 *
 * <p>
 * <b>Usage Example:</b>
 *
 * <pre>
 * &#64;POST
 * &#64;Path("/search")
 * &#64;RateLimited(
 *         action = "search")
 * public Response search(SearchRequest request) {
 *     // Implementation
 * }
 * </pre>
 *
 * <p>
 * <b>Behavior:</b>
 * <ul>
 * <li>User tier determined from session/authentication context</li>
 * <li>Rate limit config loaded from database (cached)</li>
 * <li>Sliding window checked, violations logged asynchronously</li>
 * <li>Response headers injected: X-RateLimit-Limit, X-RateLimit-Remaining, Retry-After</li>
 * <li>429 Too Many Requests returned when limit exceeded</li>
 * </ul>
 *
 * @see RateLimitFilter for enforcement implementation
 */
@NameBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RateLimited {

    /**
     * The action type identifier (e.g., "search", "vote", "submission").
     *
     * <p>
     * Must match an action_type in the rate_limit_config table.
     *
     * @return action type string
     */
    String action();
}
