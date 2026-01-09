package villagecompute.homepage.integration.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import villagecompute.homepage.exceptions.StripeException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * HTTP client for Stripe Payment API integration.
 *
 * <p>
 * Provides methods for:
 * <ul>
 * <li>Creating Payment Intents for listing fees and promotions</li>
 * <li>Creating refunds for cancelled or rejected listings</li>
 * <li>Retrieving Payment Intent details for webhook validation</li>
 * </ul>
 *
 * <p>
 * <b>Authentication:</b> Uses Stripe secret key via Bearer token authentication. Secret key configured via
 * {@code stripe.secret-key} property (sourced from Kubernetes secret in production).
 *
 * <p>
 * <b>API Format:</b> Stripe API uses application/x-www-form-urlencoded for POST requests. Nested parameters are encoded
 * with bracket notation (e.g., {@code metadata[listing_id]=...}).
 *
 * <p>
 * <b>Error Handling:</b> Wraps HTTP errors and API validation failures in {@link StripeException}. Rate limit errors
 * (HTTP 429) are logged and rethrown with descriptive messages.
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P3: Marketplace payment & fraud policy (refund window, chargeback handling)</li>
 * <li>F12.8: Listing fees & monetization (posting fees, promotions)</li>
 * </ul>
 *
 * @see <a href="https://stripe.com/docs/api">Stripe API Reference</a>
 */
@ApplicationScoped
public class StripeClient {

    private static final Logger LOG = Logger.getLogger(StripeClient.class);

    private static final String BASE_URL = "https://api.stripe.com/v1";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    @ConfigProperty(
            name = "stripe.secret-key")
    String secretKey;

    @Inject
    ObjectMapper objectMapper;

    private final HttpClient httpClient;

    public StripeClient() {
        this.httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    }

    /**
     * Creates a Stripe Payment Intent for listing fee or promotion.
     *
     * <p>
     * Payment Intent is the Stripe object representing a payment from start to finish. Frontend uses the returned
     * {@code client_secret} to collect payment details via Stripe.js.
     *
     * <p>
     * Metadata is used to associate Payment Intent with listing and user for webhook processing.
     *
     * @param amountCents
     *            payment amount in cents (e.g., 500 for $5.00)
     * @param metadata
     *            custom metadata (listing_id, user_id, category_id, promotion_type, etc.)
     * @return JsonNode with Payment Intent data including id and client_secret
     * @throws StripeException
     *             if API call fails
     */
    public JsonNode createPaymentIntent(long amountCents, Map<String, String> metadata) {
        LOG.debugf("Creating Payment Intent: amount=%d cents, metadata=%s", amountCents, metadata);

        try {
            // Build form parameters
            StringBuilder formData = new StringBuilder();
            formData.append("amount=").append(amountCents);
            formData.append("&currency=usd");
            formData.append("&automatic_payment_methods[enabled]=true");

            // Add metadata
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                String encodedKey = URLEncoder.encode("metadata[" + entry.getKey() + "]", StandardCharsets.UTF_8);
                String encodedValue = URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8);
                formData.append("&").append(encodedKey).append("=").append(encodedValue);
            }

            String url = BASE_URL + "/payment_intents";
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(HTTP_TIMEOUT)
                    .header("Authorization", "Bearer " + secretKey)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formData.toString())).build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.errorf("Stripe API error: status=%d, body=%s", response.statusCode(), response.body());
                throw new StripeException(
                        "Stripe API returned status " + response.statusCode() + ": " + response.body());
            }

            JsonNode result = objectMapper.readTree(response.body());
            LOG.infof("Created Payment Intent: id=%s, amount=%d", result.get("id").asText(), amountCents);
            return result;

        } catch (StripeException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to create Payment Intent");
            throw new StripeException("Failed to create Payment Intent", e);
        }
    }

    /**
     * Creates a refund for a Payment Intent.
     *
     * <p>
     * Refunds can be full or partial. This implementation always creates full refunds. Stripe processes refunds
     * asynchronously; funds typically return to customer within 5-10 business days.
     *
     * @param paymentIntentId
     *            Stripe Payment Intent ID (e.g., pi_...)
     * @param reason
     *            refund reason for Stripe records (optional: "requested_by_customer", "duplicate", "fraudulent")
     * @return JsonNode with Refund data including id and status
     * @throws StripeException
     *             if API call fails
     */
    public JsonNode createRefund(String paymentIntentId, String reason) {
        LOG.debugf("Creating refund: payment_intent=%s, reason=%s", paymentIntentId, reason);

        try {
            // Build form parameters
            StringBuilder formData = new StringBuilder();
            formData.append("payment_intent=").append(URLEncoder.encode(paymentIntentId, StandardCharsets.UTF_8));

            if (reason != null && !reason.isBlank()) {
                // Map our reason to Stripe's enum
                String stripeReason = mapRefundReason(reason);
                formData.append("&reason=").append(URLEncoder.encode(stripeReason, StandardCharsets.UTF_8));
            }

            String url = BASE_URL + "/refunds";
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(HTTP_TIMEOUT)
                    .header("Authorization", "Bearer " + secretKey)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formData.toString())).build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.errorf("Stripe refund API error: status=%d, body=%s", response.statusCode(), response.body());
                throw new StripeException(
                        "Stripe API returned status " + response.statusCode() + ": " + response.body());
            }

            JsonNode result = objectMapper.readTree(response.body());
            LOG.infof("Created refund: id=%s, payment_intent=%s, status=%s", result.get("id").asText(), paymentIntentId,
                    result.get("status").asText());
            return result;

        } catch (StripeException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to create refund for payment_intent=%s", paymentIntentId);
            throw new StripeException("Failed to create refund", e);
        }
    }

    /**
     * Retrieves a Payment Intent by ID.
     *
     * <p>
     * Used for webhook validation and debugging. Returns full Payment Intent object including status, amount, and
     * metadata.
     *
     * @param paymentIntentId
     *            Stripe Payment Intent ID
     * @return JsonNode with Payment Intent data
     * @throws StripeException
     *             if API call fails
     */
    public JsonNode retrievePaymentIntent(String paymentIntentId) {
        LOG.debugf("Retrieving Payment Intent: id=%s", paymentIntentId);

        try {
            String url = BASE_URL + "/payment_intents/" + URLEncoder.encode(paymentIntentId, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(HTTP_TIMEOUT)
                    .header("Authorization", "Bearer " + secretKey).GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.errorf("Stripe API error: status=%d, body=%s", response.statusCode(), response.body());
                throw new StripeException(
                        "Stripe API returned status " + response.statusCode() + ": " + response.body());
            }

            return objectMapper.readTree(response.body());

        } catch (StripeException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to retrieve Payment Intent: id=%s", paymentIntentId);
            throw new StripeException("Failed to retrieve Payment Intent", e);
        }
    }

    /**
     * Maps our internal refund reason to Stripe's enum values.
     *
     * @param internalReason
     *            our reason code (technical_failure, moderation_rejection, user_request, chargeback)
     * @return Stripe reason (requested_by_customer, duplicate, fraudulent)
     */
    private String mapRefundReason(String internalReason) {
        return switch (internalReason) {
            case "technical_failure", "moderation_rejection", "user_request" -> "requested_by_customer";
            case "chargeback" -> "fraudulent";
            default -> "requested_by_customer";
        };
    }
}
