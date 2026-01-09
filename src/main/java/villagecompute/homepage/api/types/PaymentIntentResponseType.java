package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * Response type for Payment Intent creation endpoints.
 *
 * <p>
 * Contains the Stripe Payment Intent {@code client_secret} that the frontend uses with Stripe.js to collect payment
 * details and confirm the payment.
 *
 * <p>
 * The {@code client_secret} is a sensitive value that should only be transmitted over HTTPS and should not be logged.
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P3: Marketplace payment & fraud policy</li>
 * <li>F12.8: Listing fees & monetization</li>
 * </ul>
 *
 * @param paymentIntentId
 *            Stripe Payment Intent ID (e.g., pi_1Ab2Cd3Ef4Gh5678)
 * @param clientSecret
 *            Stripe client secret for frontend payment confirmation
 * @param amountCents
 *            payment amount in cents
 */
public record PaymentIntentResponseType(@JsonProperty("payment_intent_id") @NotNull String paymentIntentId,
        @JsonProperty("client_secret") @NotNull String clientSecret, @JsonProperty("amount_cents") long amountCents) {
}
