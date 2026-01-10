package villagecompute.homepage.exceptions;

/**
 * Exception thrown when input validation fails (e.g., invalid format, authorization failure).
 *
 * <p>
 * Extends RuntimeException per project standards. Typically mapped to HTTP 400 Bad Request or 403 Forbidden in REST
 * resources depending on the validation type.
 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
