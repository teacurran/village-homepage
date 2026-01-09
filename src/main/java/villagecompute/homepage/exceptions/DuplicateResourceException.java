package villagecompute.homepage.exceptions;

/**
 * Exception thrown when attempting to create a resource that already exists (e.g., duplicate URL, email).
 *
 * <p>
 * Extends RuntimeException per project standards. Typically mapped to HTTP 409 Conflict in REST resources.
 */
public class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String message) {
        super(message);
    }

    public DuplicateResourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
