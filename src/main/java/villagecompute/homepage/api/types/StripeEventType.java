package villagecompute.homepage.api.types;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Type for Stripe webhook event payload parsing.
 *
 * <p>
 * Stripe webhook events follow this structure:
 *
 * <pre>
 * {
 *   "id": "evt_...",
 *   "type": "payment_intent.succeeded",
 *   "data": {
 *     "object": { ... Payment Intent or Refund object ... }
 *   }
 * }
 * </pre>
 *
 * <p>
 * <b>Event Types of Interest:</b>
 * <ul>
 * <li>payment_intent.succeeded: Payment completed successfully</li>
 * <li>payment_intent.payment_failed: Payment failed</li>
 * <li>charge.refunded: Refund processed</li>
 * <li>charge.dispute.created: Chargeback initiated</li>
 * </ul>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P3: Marketplace payment & fraud policy (webhook processing)</li>
 * </ul>
 *
 * @param id
 *            Stripe event ID (e.g., evt_...)
 * @param type
 *            event type (e.g., "payment_intent.succeeded")
 * @param data
 *            event data containing the Stripe object (Payment Intent, Refund, etc.)
 */
public record StripeEventType(String id, String type, JsonNode data) {
}
