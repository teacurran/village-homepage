package villagecompute.homepage.exceptions;

/**
 * Exception thrown when a requested resource is not found (e.g., user, site, category).
 *
 * <p>
 * Extends RuntimeException per project standards. Typically mapped to HTTP 404 Not Found in REST resources.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public ResourceNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
