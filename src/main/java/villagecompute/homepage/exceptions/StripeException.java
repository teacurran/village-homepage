package villagecompute.homepage.exceptions;

/**
 * Exception thrown when Stripe API operations fail.
 *
 * <p>
 * Wraps HTTP errors, authentication failures, rate limits, and API validation errors from Stripe payment processing.
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P3: Marketplace payment & fraud policy</li>
 * <li>F12.8: Listing fees & monetization</li>
 * </ul>
 */
public class StripeException extends RuntimeException {

    public StripeException(String message) {
        super(message);
    }

    public StripeException(String message, Throwable cause) {
        super(message, cause);
    }
}
