package villagecompute.homepage.api.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.SubmitFlagRequestType;
import villagecompute.homepage.data.models.ListingFlag;
import villagecompute.homepage.exceptions.RateLimitException;
import villagecompute.homepage.services.ModerationService;

import java.util.UUID;

/**
 * REST resource for user flag submissions on marketplace listings.
 *
 * <p>
 * Allows authenticated users to flag listings for policy violations. Flags are submitted to the moderation queue for
 * admin review. Rate limited to 5 flags per day per user to prevent abuse.
 *
 * <p>
 * <b>Flag Reasons:</b>
 * <ul>
 * <li><b>spam:</b> Duplicate or irrelevant content</li>
 * <li><b>prohibited_item:</b> Weapons, drugs, adult content, counterfeit goods</li>
 * <li><b>fraud:</b> Scam indicators, too-good-to-be-true prices, suspicious payment methods</li>
 * <li><b>duplicate:</b> Same listing posted multiple times</li>
 * <li><b>misleading:</b> False claims, misrepresented items</li>
 * <li><b>inappropriate:</b> Offensive or abusive content</li>
 * <li><b>other:</b> Other violations (requires details)</li>
 * </ul>
 *
 * <p>
 * <b>Auto-Hide Threshold:</b> Listings are automatically hidden when they receive 3 pending flags (status transitions
 * from 'active' to 'flagged').
 *
 * <p>
 * <b>Security:</b> All endpoints require authentication. Anonymous users cannot submit flags. Banned users are blocked
 * from flagging.
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>F12.9: Moderation & fraud detection (auto-hide threshold, rate limiting)</li>
 * </ul>
 *
 * @see ModerationService
 * @see ListingFlag
 */
@Path("/api/marketplace/listings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ListingFlagResource {

    private static final Logger LOG = Logger.getLogger(ListingFlagResource.class);

    @Inject
    ModerationService moderationService;

    /**
     * Submits a flag for a marketplace listing.
     *
     * <p>
     * Creates a flag record with status=pending and increments the listing's flag_count. If AI fraud detection is
     * requested, analyzes listing content and stores fraud score. Rate limited to 5 flags per day per user.
     *
     * <p>
     * <b>Request Body:</b>
     *
     * <pre>
     * {
     *   "reason": "fraud",
     *   "details": "Price is suspiciously low and seller requests wire transfer"
     * }
     * </pre>
     *
     * <p>
     * <b>Response:</b>
     *
     * <pre>
     * HTTP 202 Accepted
     * {
     *   "message": "Flag submitted successfully. Our moderation team will review it shortly.",
     *   "flag_id": "550e8400-e29b-41d4-a716-446655440000"
     * }
     * </pre>
     *
     * @param listingId
     *            the listing UUID to flag
     * @param request
     *            flag submission request with reason and details
     * @return 202 Accepted if flag submitted, 429 if rate limit exceeded, 403 if user banned
     */
    @POST
    @Path("/{listingId}/flag")
    @RolesAllowed({"user", "admin"})
    public Response submitFlag(@PathParam("listingId") UUID listingId, @Valid SubmitFlagRequestType request) {
        LOG.infof("Flag submission request: listingId=%s, reason=%s", listingId, request.reason());

        // Validate request
        try {
            request.validate();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity("{\"error\": \"" + e.getMessage() + "\"}")
                    .build();
        }

        // Note: In production, extract user ID from SecurityContext
        // For now, using a placeholder
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        try {
            // Submit flag with fraud analysis enabled
            ListingFlag flag = moderationService.submitFlag(listingId, userId, request.reason(), request.details(), true // Run
                                                                                                                         // AI
                                                                                                                         // fraud
                                                                                                                         // detection
            );

            String responseMessage = String
                    .format("Flag submitted successfully. Our moderation team will review it shortly.");

            return Response.status(Response.Status.ACCEPTED)
                    .entity(new FlagSubmissionResponse(responseMessage, flag.id)).build();

        } catch (RateLimitException e) {
            LOG.warnf("Rate limit exceeded for user %s: %s", userId, e.getMessage());
            return Response.status(429) // Too Many Requests
                    .entity("{\"error\": \"" + e.getMessage() + "\"}").build();

        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND).entity("{\"error\": \"" + e.getMessage() + "\"}").build();

        } catch (IllegalStateException e) {
            LOG.warnf("Banned user attempted to submit flag: userId=%s", userId);
            return Response.status(Response.Status.FORBIDDEN).entity("{\"error\": \"" + e.getMessage() + "\"}").build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to submit flag: listingId=%s, userId=%s", listingId, userId);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"An error occurred while submitting your flag. Please try again later.\"}")
                    .build();
        }
    }

    /**
     * Response type for flag submission.
     */
    public record FlagSubmissionResponse(String message, UUID flagId) {
    }
}
