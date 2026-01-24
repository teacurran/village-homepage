package villagecompute.homepage.api.rest.admin;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.RefundActionRequestType;
import villagecompute.homepage.api.types.RefundType;
import villagecompute.homepage.data.models.PaymentRefund;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.exceptions.StripeException;
import villagecompute.homepage.integration.payments.StripeClient;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin REST resource for payment refund management.
 *
 * <p>
 * Provides endpoints for:
 * <ul>
 * <li>Listing pending refund requests</li>
 * <li>Viewing refund details</li>
 * <li>Approving manual refund requests (user_request reason)</li>
 * <li>Rejecting manual refund requests</li>
 * </ul>
 *
 * <p>
 * <b>Security:</b> All endpoints restricted to super_admin role.
 *
 * <p>
 * <b>Refund Workflow:</b>
 * <ol>
 * <li>User requests refund after 24h window (or automatic refund within 24h)</li>
 * <li>Refund record created with status=pending (manual) or status=processed (automatic)</li>
 * <li>Admin reviews pending refunds via GET /admin/api/marketplace/refunds</li>
 * <li>Admin approves or rejects via POST /admin/api/marketplace/refunds/{id}/approve or /reject</li>
 * <li>On approval, Stripe API processes refund and status transitions to processed</li>
 * </ol>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P3: Marketplace payment & fraud policy (refund workflows)</li>
 * </ul>
 */
@Path("/admin/api/marketplace/refunds")
@Tag(
        name = "Admin - Payments",
        description = "Admin endpoints for payment refund management (requires super_admin role)")
@SecurityRequirement(
        name = "bearerAuth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("super_admin")
public class PaymentAdminResource {

    private static final Logger LOG = Logger.getLogger(PaymentAdminResource.class);

    @Inject
    StripeClient stripeClient;

    /**
     * Lists all pending refund requests awaiting admin review.
     *
     * <p>
     * Returns refunds with status=pending, ordered by creation date (oldest first).
     *
     * <p>
     * <b>Example Response:</b>
     *
     * <pre>
     * [
     *   {
     *     "id": "550e8400-e29b-41d4-a716-446655440000",
     *     "stripe_payment_intent_id": "pi_1Ab2Cd3Ef4Gh5678",
     *     "stripe_refund_id": null,
     *     "listing_id": "660f9500-f3ac-52e5-b827-557766551111",
     *     "user_id": "770f9600-f3bd-63f6-c938-668877662222",
     *     "amount_cents": 500,
     *     "reason": "user_request",
     *     "status": "pending",
     *     "reviewed_by_user_id": null,
     *     "reviewed_at": null,
     *     "notes": "User requested refund due to accidental purchase",
     *     "created_at": "2025-01-10T15:30:00Z",
     *     "updated_at": "2025-01-10T15:30:00Z"
     *   }
     * ]
     * </pre>
     *
     * @return List of pending refunds
     */
    @GET
    @Operation(
            summary = "List pending refunds",
            description = "Returns all pending refund requests awaiting admin review. Requires super_admin role.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Success",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = RefundType.class))),
                    @APIResponse(
                            responseCode = "401",
                            description = "Unauthorized - missing or invalid authentication"),
                    @APIResponse(
                            responseCode = "403",
                            description = "Forbidden - insufficient permissions (requires super_admin role)"),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error")})
    public Response listPendingRefunds() {
        LOG.info("Listing pending refunds");

        List<PaymentRefund> refunds = PaymentRefund.findPending();
        List<RefundType> response = refunds.stream().map(this::toRefundType).collect(Collectors.toList());

        return Response.ok(response).build();
    }

    /**
     * Retrieves details for a specific refund.
     *
     * @param refundId
     *            refund UUID
     * @return Refund details
     * @throws WebApplicationException
     *             404 if refund not found
     */
    @GET
    @Path("/{id}")
    @Operation(
            summary = "Get refund details",
            description = "Returns details for a specific refund. Requires super_admin role.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Success",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = RefundType.class))),
                    @APIResponse(
                            responseCode = "401",
                            description = "Unauthorized - missing or invalid authentication"),
                    @APIResponse(
                            responseCode = "403",
                            description = "Forbidden - insufficient permissions (requires super_admin role)"),
                    @APIResponse(
                            responseCode = "404",
                            description = "Refund not found"),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error")})
    public Response getRefund(@Parameter(
            description = "Refund UUID",
            required = true) @PathParam("id") UUID refundId) {
        LOG.infof("Getting refund: id=%s", refundId);

        PaymentRefund refund = PaymentRefund.findById(refundId);
        if (refund == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Refund not found").build();
        }

        return Response.ok(toRefundType(refund)).build();
    }

    /**
     * Approves a pending refund request.
     *
     * <p>
     * Transitions refund status to 'approved', then processes refund via Stripe API and transitions to 'processed'.
     *
     * <p>
     * <b>Example Request:</b>
     *
     * <pre>
     * POST /admin/api/marketplace/refunds/550e8400-e29b-41d4-a716-446655440000/approve
     * Authorization: Bearer {admin_jwt_token}
     * Content-Type: application/json
     *
     * {
     *   "notes": "Approved due to exceptional circumstances"
     * }
     * </pre>
     *
     * @param refundId
     *            refund UUID
     * @param request
     *            optional admin notes
     * @param securityContext
     *            injected security context for admin user identification
     * @return Updated refund details
     * @throws WebApplicationException
     *             404 if refund not found, 400 if not pending, 500 if Stripe API fails
     */
    @POST
    @Path("/{id}/approve")
    @Operation(
            summary = "Approve refund request",
            description = "Approves a pending refund and processes it via Stripe API. Requires super_admin role.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Success - refund approved and processed",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = RefundType.class))),
                    @APIResponse(
                            responseCode = "400",
                            description = "Bad request - refund not pending"),
                    @APIResponse(
                            responseCode = "401",
                            description = "Unauthorized - missing or invalid authentication"),
                    @APIResponse(
                            responseCode = "403",
                            description = "Forbidden - insufficient permissions (requires super_admin role)"),
                    @APIResponse(
                            responseCode = "404",
                            description = "Refund not found"),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error - Stripe API failure")})
    public Response approveRefund(@Parameter(
            description = "Refund UUID",
            required = true) @PathParam("id") UUID refundId, @Valid RefundActionRequestType request,
            @Context SecurityContext securityContext) {
        String adminEmail = securityContext.getUserPrincipal().getName();
        LOG.infof("Approving refund: id=%s, adminEmail=%s", refundId, adminEmail);

        try {
            // Load admin user
            User adminUser = User.findByEmail(adminEmail)
                    .orElseThrow(() -> new WebApplicationException("Admin not found", Response.Status.UNAUTHORIZED));

            // Load refund
            PaymentRefund refund = PaymentRefund.findById(refundId);
            if (refund == null) {
                return Response.status(Response.Status.NOT_FOUND).entity("Refund not found").build();
            }

            if (!"pending".equals(refund.status)) {
                return Response.status(Response.Status.BAD_REQUEST).entity("Refund is not pending").build();
            }

            // Approve refund
            PaymentRefund.approve(refundId, adminUser.id, request != null ? request.notes() : null);

            // Process refund via Stripe
            com.fasterxml.jackson.databind.JsonNode stripeRefund = stripeClient
                    .createRefund(refund.stripePaymentIntentId, refund.reason);
            String stripeRefundId = stripeRefund.get("id").asText();

            // Mark as processed
            PaymentRefund.markProcessed(refundId, stripeRefundId);

            // Return updated refund
            PaymentRefund updated = PaymentRefund.findById(refundId);
            return Response.ok(toRefundType(updated)).build();

        } catch (StripeException e) {
            LOG.errorf(e, "Stripe error during refund approval: refundId=%s", refundId);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Failed to process refund via Stripe")
                    .build();
        }
    }

    /**
     * Rejects a pending refund request.
     *
     * <p>
     * Transitions refund status to 'rejected'. No Stripe API call is made.
     *
     * <p>
     * <b>Example Request:</b>
     *
     * <pre>
     * POST /admin/api/marketplace/refunds/550e8400-e29b-41d4-a716-446655440000/reject
     * Authorization: Bearer {admin_jwt_token}
     * Content-Type: application/json
     *
     * {
     *   "notes": "Refund request outside policy window and no exceptional circumstances"
     * }
     * </pre>
     *
     * @param refundId
     *            refund UUID
     * @param request
     *            required admin notes explaining rejection
     * @param securityContext
     *            injected security context
     * @return Updated refund details
     * @throws WebApplicationException
     *             404 if refund not found, 400 if not pending
     */
    @POST
    @Path("/{id}/reject")
    @Operation(
            summary = "Reject refund request",
            description = "Rejects a pending refund request. Requires super_admin role.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Success - refund rejected",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = RefundType.class))),
                    @APIResponse(
                            responseCode = "400",
                            description = "Bad request - refund not pending"),
                    @APIResponse(
                            responseCode = "401",
                            description = "Unauthorized - missing or invalid authentication"),
                    @APIResponse(
                            responseCode = "403",
                            description = "Forbidden - insufficient permissions (requires super_admin role)"),
                    @APIResponse(
                            responseCode = "404",
                            description = "Refund not found"),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error")})
    public Response rejectRefund(@Parameter(
            description = "Refund UUID",
            required = true) @PathParam("id") UUID refundId, @Valid RefundActionRequestType request,
            @Context SecurityContext securityContext) {
        String adminEmail = securityContext.getUserPrincipal().getName();
        LOG.infof("Rejecting refund: id=%s, adminEmail=%s", refundId, adminEmail);

        // Load admin user
        User adminUser = User.findByEmail(adminEmail)
                .orElseThrow(() -> new WebApplicationException("Admin not found", Response.Status.UNAUTHORIZED));

        // Load refund
        PaymentRefund refund = PaymentRefund.findById(refundId);
        if (refund == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Refund not found").build();
        }

        if (!"pending".equals(refund.status)) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Refund is not pending").build();
        }

        // Reject refund
        PaymentRefund.reject(refundId, adminUser.id, request != null ? request.notes() : "Rejected by admin");

        // Return updated refund
        PaymentRefund updated = PaymentRefund.findById(refundId);
        return Response.ok(toRefundType(updated)).build();
    }

    /**
     * Converts PaymentRefund entity to RefundType DTO.
     */
    private RefundType toRefundType(PaymentRefund refund) {
        return new RefundType(refund.id, refund.stripePaymentIntentId, refund.stripeRefundId, refund.listingId,
                refund.userId, refund.amountCents, refund.reason, refund.status, refund.reviewedByUserId,
                refund.reviewedAt != null ? refund.reviewedAt.toString() : null, refund.notes,
                refund.createdAt.toString(), refund.updatedAt.toString());
    }
}
