package villagecompute.homepage.api.rest.admin;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.FlagType;
import villagecompute.homepage.api.types.ModerationStatsType;
import villagecompute.homepage.data.models.ListingFlag;
import villagecompute.homepage.data.models.MarketplaceListing;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.services.ModerationService;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin REST resource for moderation queue management.
 *
 * <p>
 * Provides endpoints for:
 * <ul>
 * <li>Viewing moderation queue (pending flags)</li>
 * <li>Reviewing individual flags with full context</li>
 * <li>Approving flags (removes listing + issues refund if eligible)</li>
 * <li>Dismissing flags (marks invalid)</li>
 * <li>Viewing moderation analytics (flag counts, approval rates, bans)</li>
 * </ul>
 *
 * <p>
 * <b>Security:</b> All endpoints restricted to super_admin role.
 *
 * <p>
 * <b>Moderation Workflow:</b>
 * <ol>
 * <li>Users submit flags via POST /api/marketplace/listings/{id}/flag</li>
 * <li>Flags appear in moderation queue with status=pending</li>
 * <li>Admin reviews flags via GET /admin/api/moderation/queue</li>
 * <li>Admin approves or dismisses via POST /admin/api/moderation/flags/{id}/approve or /dismiss</li>
 * <li>On approval: listing removed, refund issued if within 24h, user banned if 2+ chargebacks</li>
 * <li>On dismissal: flag marked dismissed, listing flag_count decremented</li>
 * </ol>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P3: Marketplace payment & fraud policy (refund automation, chargeback bans)</li>
 * <li>F12.9: Moderation & fraud detection (auto-hide threshold, AI heuristics)</li>
 * </ul>
 *
 * @see ModerationService
 * @see ListingFlag
 */
@Path("/admin/api/moderation")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("super_admin")
public class ModerationQueueResource {

    private static final Logger LOG = Logger.getLogger(ModerationQueueResource.class);

    @Inject
    ModerationService moderationService;

    /**
     * Lists all pending flags in moderation queue.
     *
     * <p>
     * Returns flags with status=pending, enriched with listing title, status, and user email for context. Ordered by
     * creation date (newest first).
     *
     * <p>
     * <b>Example Response:</b>
     *
     * <pre>
     * [
     *   {
     *     "id": "550e8400-e29b-41d4-a716-446655440000",
     *     "listing_id": "660f9500-f3ac-52e5-b827-557766551111",
     *     "listing_title": "iPhone 13 Pro Max - Great Deal!",
     *     "listing_status": "flagged",
     *     "user_id": "770f9600-f3bd-63f6-c938-668877662222",
     *     "user_email": "john.doe@example.com",
     *     "reason": "fraud",
     *     "details": "Price too good to be true, requests wire transfer",
     *     "status": "pending",
     *     "fraud_score": 0.85,
     *     "fraud_reasons": "{\"reasons\": [\"Suspicious payment method\"], \"prompt_version\": \"v1.0\"}",
     *     "created_at": "2025-01-10T15:30:00Z"
     *   }
     * ]
     * </pre>
     *
     * @return List of pending flags with enriched data
     */
    @GET
    @Path("/queue")
    public Response listPendingFlags() {
        LOG.info("Listing moderation queue (pending flags)");

        List<ListingFlag> flags = ListingFlag.findPending();

        // Enrich with listing and user details
        List<FlagType> enrichedFlags = flags.stream().map(this::enrichFlag).collect(Collectors.toList());

        LOG.infof("Returning %d pending flags", enrichedFlags.size());
        return Response.ok(enrichedFlags).build();
    }

    /**
     * Retrieves details for a specific flag.
     *
     * <p>
     * Returns full flag details including listing context, user info, fraud analysis, and review history.
     *
     * @param flagId
     *            the flag UUID
     * @return Flag details with enriched data
     */
    @GET
    @Path("/flags/{flagId}")
    public Response getFlagDetails(@PathParam("flagId") UUID flagId) {
        LOG.infof("Fetching flag details: flagId=%s", flagId);

        ListingFlag flag = ListingFlag.findById(flagId);
        if (flag == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("{\"error\": \"Flag not found\"}").build();
        }

        FlagType enrichedFlag = enrichFlag(flag);
        return Response.ok(enrichedFlag).build();
    }

    /**
     * Approves a flag and removes the associated listing.
     *
     * <p>
     * Delegates to {@link ModerationService#approveFlag} which:
     * <ul>
     * <li>Marks flag as approved</li>
     * <li>Sets listing status to 'removed'</li>
     * <li>Issues automatic refund if listing paid within 24h window</li>
     * <li>Checks chargeback threshold and bans user if 2+ chargebacks</li>
     * </ul>
     *
     * <p>
     * <b>Request Body:</b>
     *
     * <pre>
     * {
     *   "review_notes": "Confirmed scam - requests Western Union payment"
     * }
     * </pre>
     *
     * @param flagId
     *            the flag UUID to approve
     * @param request
     *            request body with optional review notes
     * @return 200 OK if approved, 404 if flag not found, 400 if already reviewed
     */
    @POST
    @Path("/flags/{flagId}/approve")
    public Response approveFlag(@PathParam("flagId") UUID flagId, @Valid ReviewActionRequest request) {
        LOG.infof("Approving flag: flagId=%s", flagId);

        try {
            // Note: In production, extract admin user ID from SecurityContext
            // For now, using a placeholder
            UUID adminUserId = UUID.fromString("00000000-0000-0000-0000-000000000001");

            String reviewNotes = request != null ? request.reviewNotes : null;
            moderationService.approveFlag(flagId, adminUserId, reviewNotes);

            return Response.ok().entity("{\"message\": \"Flag approved and listing removed\"}").build();

        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND).entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity("{\"error\": \"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    /**
     * Dismisses a flag as invalid.
     *
     * <p>
     * Delegates to {@link ModerationService#dismissFlag} which:
     * <ul>
     * <li>Marks flag as dismissed</li>
     * <li>Decrements listing flag_count</li>
     * <li>Restores listing status from 'flagged' to 'active' if no pending flags remain</li>
     * </ul>
     *
     * <p>
     * <b>Request Body:</b>
     *
     * <pre>
     * {
     *   "review_notes": "False positive - legitimate listing"
     * }
     * </pre>
     *
     * @param flagId
     *            the flag UUID to dismiss
     * @param request
     *            request body with optional review notes
     * @return 200 OK if dismissed, 404 if flag not found, 400 if already reviewed
     */
    @POST
    @Path("/flags/{flagId}/dismiss")
    public Response dismissFlag(@PathParam("flagId") UUID flagId, @Valid ReviewActionRequest request) {
        LOG.infof("Dismissing flag: flagId=%s", flagId);

        try {
            // Note: In production, extract admin user ID from SecurityContext
            // For now, using a placeholder
            UUID adminUserId = UUID.fromString("00000000-0000-0000-0000-000000000001");

            String reviewNotes = request != null ? request.reviewNotes : null;
            moderationService.dismissFlag(flagId, adminUserId, reviewNotes);

            return Response.ok().entity("{\"message\": \"Flag dismissed\"}").build();

        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND).entity("{\"error\": \"" + e.getMessage() + "\"}").build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity("{\"error\": \"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    /**
     * Returns moderation analytics for admin dashboard.
     *
     * <p>
     * Aggregates metrics including:
     * <ul>
     * <li>Count of pending flags</li>
     * <li>Count of flagged listings</li>
     * <li>Approved/dismissed flags in last 24h</li>
     * <li>Auto-refunds issued in last 24h</li>
     * <li>Users banned in last 24h</li>
     * <li>Flag counts by reason</li>
     * <li>Average review time</li>
     * </ul>
     *
     * <p>
     * <b>Example Response:</b>
     *
     * <pre>
     * {
     *   "pending_flags": 42,
     *   "flagged_listings": 15,
     *   "approved_flags_24h": 8,
     *   "dismissed_flags_24h": 12,
     *   "auto_refunds_issued_24h": 3,
     *   "banned_users_24h": 1,
     *   "flag_reason_counts": {
     *     "fraud": 20,
     *     "spam": 15,
     *     "prohibited_item": 7
     *   },
     *   "average_review_time_hours": 4.5
     * }
     * </pre>
     *
     * @return Moderation statistics
     */
    @GET
    @Path("/stats")
    public Response getModerationStats() {
        LOG.info("Fetching moderation statistics");

        ModerationStatsType stats = ModerationStatsType.compute();
        return Response.ok(stats).build();
    }

    /**
     * Enriches a flag with listing and user details for admin display.
     *
     * @param flag
     *            the flag entity
     * @return enriched flag type
     */
    private FlagType enrichFlag(ListingFlag flag) {
        // Fetch listing details
        MarketplaceListing listing = MarketplaceListing.findById(flag.listingId);
        String listingTitle = listing != null ? listing.title : null;
        String listingStatus = listing != null ? listing.status : null;

        // Fetch flagger details
        User flagger = User.findById(flag.userId);
        String flaggerEmail = flagger != null ? flagger.email : null;

        // Fetch reviewer details
        String reviewerEmail = null;
        if (flag.reviewedByUserId != null) {
            User reviewer = User.findById(flag.reviewedByUserId);
            reviewerEmail = reviewer != null ? reviewer.email : null;
        }

        return FlagType.from(flag, listingTitle, listingStatus, flaggerEmail, reviewerEmail);
    }

    /**
     * Request type for approve/dismiss actions.
     */
    public record ReviewActionRequest(String reviewNotes) {
    }
}
