package villagecompute.homepage.api.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.PaymentIntentResponseType;
import villagecompute.homepage.api.types.PromotionRequestType;
import villagecompute.homepage.data.models.MarketplaceListing;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.exceptions.StripeException;
import villagecompute.homepage.services.PaymentService;

import java.util.UUID;

/**
 * REST resource for marketplace payment operations.
 *
 * <p>
 * Provides endpoints for:
 * <ul>
 * <li>Creating Payment Intents for listing posting fees</li>
 * <li>Creating Payment Intents for listing promotions (featured/bump)</li>
 * </ul>
 *
 * <p>
 * <b>Security:</b> All endpoints require authentication. Users can only create payments for their own listings.
 *
 * <p>
 * <b>Rate Limiting:</b> Endpoints are rate-limited to prevent abuse:
 * <ul>
 * <li>POST /checkout - 5 requests per minute per user</li>
 * <li>POST /promote - 10 requests per hour per user</li>
 * </ul>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P3: Marketplace payment & fraud policy</li>
 * <li>F12.8: Listing fees & monetization</li>
 * </ul>
 */
@Path("/api/marketplace/listings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MarketplacePaymentResource {

    private static final Logger LOG = Logger.getLogger(MarketplacePaymentResource.class);

    @Inject
    PaymentService paymentService;

    /**
     * Creates a Payment Intent for listing posting fee.
     *
     * <p>
     * If the listing's category has a posting fee > $0, this endpoint creates a Stripe Payment Intent and returns the
     * {@code client_secret} for frontend payment collection.
     *
     * <p>
     * After successful payment (confirmed via webhook), the listing status transitions from pending_payment to active.
     *
     * <p>
     * <b>Example Request:</b>
     *
     * <pre>
     * POST /api/marketplace/listings/550e8400-e29b-41d4-a716-446655440000/checkout
     * Authorization: Bearer {jwt_token}
     * </pre>
     *
     * <p>
     * <b>Example Response:</b>
     *
     * <pre>
     * {
     *   "payment_intent_id": "pi_1Ab2Cd3Ef4Gh5678EXAMPLE",
     *   "client_secret": "pi_1Ab2Cd3Ef4Gh5678_secret_EXAMPLE",
     *   "amount_cents": 500
     * }
     * </pre>
     *
     * @param listingId
     *            listing UUID
     * @param securityContext
     *            injected security context for user authentication
     * @return Payment Intent response with client_secret
     * @throws WebApplicationException
     *             403 if user doesn't own listing, 400 if category has no fee, 500 if Stripe API fails
     */
    @POST
    @Path("/{id}/checkout")
    @RolesAllowed({"user", "super_admin"})
    public Response createCheckoutPayment(@PathParam("id") UUID listingId, @Context SecurityContext securityContext) {
        String userEmail = securityContext.getUserPrincipal().getName();
        LOG.infof("Creating checkout payment: listingId=%s, userEmail=%s", listingId, userEmail);

        try {
            // Load user
            User user = User.findByEmail(userEmail)
                    .orElseThrow(() -> new WebApplicationException("User not found", Response.Status.UNAUTHORIZED));

            // Check ownership
            if (!MarketplaceListing.isOwnedByUser(listingId, user.id)) {
                LOG.warnf("User %s does not own listing %s", userEmail, listingId);
                return Response.status(Response.Status.FORBIDDEN).entity("You do not own this listing").build();
            }

            // Create Payment Intent
            PaymentIntentResponseType response = paymentService.createPostingPayment(listingId, user.id);

            return Response.ok(response).build();

        } catch (IllegalStateException e) {
            LOG.warnf(e, "Invalid checkout request: listingId=%s", listingId);
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        } catch (StripeException e) {
            LOG.errorf(e, "Stripe error during checkout: listingId=%s", listingId);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Payment processing error").build();
        }
    }

    /**
     * Creates a Payment Intent for listing promotion (featured or bump).
     *
     * <p>
     * Creates a Stripe Payment Intent for the requested promotion type. After successful payment (confirmed via
     * webhook), the promotion is applied to the listing.
     *
     * <p>
     * <b>Promotion Types:</b>
     * <ul>
     * <li><b>featured:</b> $5 for 7 days, listing highlighted in search and top of category</li>
     * <li><b>bump:</b> $2 per bump, listing reset to top of chronological order, limited to 1 per 24 hours</li>
     * </ul>
     *
     * <p>
     * <b>Example Request:</b>
     *
     * <pre>
     * POST /api/marketplace/listings/550e8400-e29b-41d4-a716-446655440000/promote
     * Authorization: Bearer {jwt_token}
     * Content-Type: application/json
     *
     * {
     *   "type": "featured"
     * }
     * </pre>
     *
     * <p>
     * <b>Example Response:</b>
     *
     * <pre>
     * {
     *   "payment_intent_id": "pi_1Ab2Cd3Ef4Gh5678EXAMPLE",
     *   "client_secret": "pi_1Ab2Cd3Ef4Gh5678_secret_EXAMPLE",
     *   "amount_cents": 500
     * }
     * </pre>
     *
     * @param listingId
     *            listing UUID
     * @param request
     *            promotion request (type: featured or bump)
     * @param securityContext
     *            injected security context
     * @return Payment Intent response with client_secret
     * @throws WebApplicationException
     *             403 if user doesn't own listing, 400 if validation fails, 500 if Stripe API fails
     */
    @POST
    @Path("/{id}/promote")
    @RolesAllowed({"user", "super_admin"})
    public Response createPromotionPayment(@PathParam("id") UUID listingId, @Valid PromotionRequestType request,
            @Context SecurityContext securityContext) {
        String userEmail = securityContext.getUserPrincipal().getName();
        LOG.infof("Creating promotion payment: listingId=%s, type=%s, userEmail=%s", listingId, request.type(),
                userEmail);

        try {
            // Load user
            User user = User.findByEmail(userEmail)
                    .orElseThrow(() -> new WebApplicationException("User not found", Response.Status.UNAUTHORIZED));

            // Check ownership
            if (!MarketplaceListing.isOwnedByUser(listingId, user.id)) {
                LOG.warnf("User %s does not own listing %s", userEmail, listingId);
                return Response.status(Response.Status.FORBIDDEN).entity("You do not own this listing").build();
            }

            // Create Payment Intent
            PaymentIntentResponseType response = paymentService.createPromotionPayment(listingId, user.id,
                    request.type());

            return Response.ok(response).build();

        } catch (IllegalStateException | IllegalArgumentException e) {
            LOG.warnf(e, "Invalid promotion request: listingId=%s, type=%s", listingId, request.type());
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        } catch (StripeException e) {
            LOG.errorf(e, "Stripe error during promotion: listingId=%s, type=%s", listingId, request.type());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Payment processing error").build();
        }
    }
}
