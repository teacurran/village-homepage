package villagecompute.homepage.api.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.StripeEventType;
import villagecompute.homepage.integration.payments.StripeWebhookVerifier;
import villagecompute.homepage.observability.ObservabilityMetrics;
import villagecompute.homepage.services.PaymentService;

/**
 * REST resource for Stripe webhook event processing.
 *
 * <p>
 * Receives and processes webhook events from Stripe for payment lifecycle management:
 * <ul>
 * <li>payment_intent.succeeded - Payment completed, activate listing or create promotion</li>
 * <li>payment_intent.payment_failed - Payment failed, log and increment metrics</li>
 * <li>charge.refunded - Refund processed, update refund record</li>
 * <li>charge.dispute.created - Chargeback initiated, record and check ban threshold</li>
 * </ul>
 *
 * <p>
 * <b>Security:</b> All webhook payloads MUST be verified using HMAC-SHA256 signature to prevent spoofing attacks.
 * Invalid signatures are rejected with HTTP 400.
 *
 * <p>
 * <b>Idempotency:</b> Webhook handlers are idempotent to handle duplicate events from Stripe retry logic. Uses Payment
 * Intent ID and Refund ID as idempotency keys.
 *
 * <p>
 * <b>Rate Limiting:</b> No rate limiting applied (Stripe controls retry behavior).
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P3: Marketplace payment & fraud policy (webhook security, chargeback handling)</li>
 * </ul>
 *
 * @see <a href="https://stripe.com/docs/webhooks">Stripe Webhooks Documentation</a>
 */
@Path("/webhooks/stripe")
public class StripeWebhookResource {

    private static final Logger LOG = Logger.getLogger(StripeWebhookResource.class);

    @Inject
    StripeWebhookVerifier webhookVerifier;

    @Inject
    PaymentService paymentService;

    @Inject
    ObservabilityMetrics metrics;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Handles Stripe webhook events.
     *
     * <p>
     * Verifies webhook signature, parses event payload, and delegates to appropriate handler based on event type.
     *
     * <p>
     * <b>Example Webhook Payload:</b>
     *
     * <pre>
     * {
     *   "id": "evt_1Ab2Cd3Ef4Gh5678",
     *   "type": "payment_intent.succeeded",
     *   "data": {
     *     "object": {
     *       "id": "pi_1Ab2Cd3Ef4Gh5678",
     *       "amount": 500,
     *       "currency": "usd",
     *       "metadata": {
     *         "listing_id": "550e8400-e29b-41d4-a716-446655440000",
     *         "user_id": "660f9500-f3ac-52e5-b827-557766551111",
     *         "payment_type": "posting_fee"
     *       }
     *     }
     *   }
     * }
     * </pre>
     *
     * @param signature
     *            Stripe-Signature header value
     * @param payload
     *            raw webhook payload (JSON string)
     * @return HTTP 200 if processed successfully, HTTP 400 if signature invalid
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handleWebhook(@HeaderParam("Stripe-Signature") String signature, String payload) {
        LOG.debugf("Received Stripe webhook, signature=%s, payload length=%d bytes", signature,
                payload != null ? payload.length() : 0);

        // Verify webhook signature
        if (!webhookVerifier.verifySignature(payload, signature)) {
            LOG.warnf("Invalid Stripe webhook signature, rejecting");
            return Response.status(Response.Status.BAD_REQUEST).entity("Invalid signature").build();
        }

        try {
            // Parse event
            StripeEventType event = objectMapper.readValue(payload, StripeEventType.class);
            LOG.infof("Processing Stripe webhook: eventId=%s, type=%s", event.id(), event.type());

            // Increment metrics
            metrics.incrementWebhookReceived(event.type());

            // Handle event by type
            switch (event.type()) {
                case "payment_intent.succeeded" :
                    paymentService.handlePaymentSuccess(event.data().get("object"));
                    break;

                case "payment_intent.payment_failed" :
                    paymentService.handlePaymentFailure(event.data().get("object"));
                    break;

                case "charge.refunded" :
                    paymentService.handleRefund(event.data().get("object"));
                    break;

                case "charge.dispute.created" :
                    // Extract payment_intent from dispute object
                    String paymentIntentId = event.data().get("object").get("payment_intent").asText();
                    paymentService.processChargeback(paymentIntentId);
                    break;

                default :
                    LOG.debugf("Unhandled webhook event type: %s", event.type());
            }

            return Response.ok().build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to process Stripe webhook");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Webhook processing failed").build();
        }
    }
}
