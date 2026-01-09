package villagecompute.homepage.exceptions;

/**
 * Exception thrown when an external API rate limit is exceeded.
 *
 * <p>
 * This exception signals that a service (e.g., Alpha Vantage) has returned a rate limit error, and the system should
 * fall back to serving stale cached data if available.
 *
 * <p>
 * All exceptions in this project extend RuntimeException per project standards.
 */
public class RateLimitException extends RuntimeException {

    public RateLimitException(String message) {
        super(message);
    }

    public RateLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
