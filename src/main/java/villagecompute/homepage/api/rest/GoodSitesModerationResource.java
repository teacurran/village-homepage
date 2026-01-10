package villagecompute.homepage.api.rest;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.DirectoryCategoryType;
import villagecompute.homepage.api.types.DirectorySiteType;
import villagecompute.homepage.data.models.DirectoryCategory;
import villagecompute.homepage.data.models.DirectorySite;
import villagecompute.homepage.data.models.DirectorySiteCategory;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.exceptions.ResourceNotFoundException;
import villagecompute.homepage.exceptions.ValidationException;
import villagecompute.homepage.services.DirectoryService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Good Sites moderation console (Feature F13.2 moderation flow).
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>GET /good-sites/moderate – Moderation queue UI</li>
 * <li>POST /api/good-sites/moderate/{id}/approve – Approve pending submission</li>
 * <li>POST /api/good-sites/moderate/{id}/reject – Reject pending submission</li>
 * <li>POST /api/good-sites/moderate/bulk-approve – Bulk approve multiple submissions</li>
 * <li>POST /api/good-sites/moderate/bulk-reject – Bulk reject multiple submissions</li>
 * </ul>
 *
 * <p>
 * Security: All endpoints require moderator or admin role. Category moderators can only moderate their assigned
 * categories (to be implemented in I5.T8).
 * </p>
 *
 * <p>
 * Karma Integration: Approvals award +5 karma to submitter; rejections deduct -3 karma.
 * </p>
 */
@Path("/good-sites/moderate")
@RolesAllowed({"super_admin", "ops"})
public class GoodSitesModerationResource {

    private static final Logger LOG = Logger.getLogger(GoodSitesModerationResource.class);

    @Inject
    DirectoryService directoryService;

    @Context
    SecurityIdentity securityIdentity;

    /**
     * Type-safe Qute templates for moderation UI.
     */
    @CheckedTemplate(
            requireTypeSafeExpressions = false)
    public static class Templates {
        public static native TemplateInstance moderate(ModerationQueueData data);
    }

    /**
     * Moderation queue page with pending submissions.
     *
     * <p>
     * Route: GET /good-sites/moderate
     * </p>
     *
     * <p>
     * Displays table of pending site-category submissions with actions: Approve, Reject, Request changes.
     * </p>
     *
     * @param categoryFilter
     *            Optional category ID filter
     * @return HTML page with moderation queue
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance moderationQueue(@QueryParam("category") @DefaultValue("") String categoryFilter) {
        LOG.infof("Rendering moderation queue: category=%s", categoryFilter);

        // Get all pending submissions
        List<DirectorySiteCategory> pendingSubmissions = DirectorySiteCategory.findAllPending();

        // Filter by category if specified
        if (!categoryFilter.isEmpty()) {
            UUID categoryId = UUID.fromString(categoryFilter);
            pendingSubmissions = pendingSubmissions.stream().filter(sc -> sc.categoryId.equals(categoryId)).toList();
        }

        // Build moderation queue items
        List<ModerationQueueItem> queueItems = new ArrayList<>();
        for (DirectorySiteCategory sc : pendingSubmissions) {
            DirectorySite site = DirectorySite.findById(sc.siteId);
            DirectoryCategory category = DirectoryCategory.findById(sc.categoryId);
            User submitter = User.findById(sc.submittedByUserId);

            if (site != null && category != null && submitter != null) {
                queueItems.add(new ModerationQueueItem(sc.id, DirectorySiteType.fromEntity(site),
                        DirectoryCategoryType.fromEntity(category), submitter.email, submitter.directoryKarma,
                        submitter.directoryTrustLevel, site.screenshotUrl, site.ogImageUrl, sc.createdAt));
            }
        }

        // Get all categories for filter dropdown
        List<DirectoryCategory> allCategories = DirectoryCategory.findActive();
        List<DirectoryCategoryType> categoryOptions = allCategories.stream().map(DirectoryCategoryType::fromEntity)
                .toList();

        ModerationQueueData templateData = new ModerationQueueData(queueItems, categoryOptions,
                categoryFilter.isEmpty() ? null : categoryFilter, getCurrentUserId());

        return Templates.moderate(templateData);
    }

    /**
     * Approves a pending site-category submission.
     *
     * <p>
     * Route: POST /api/good-sites/moderate/{id}/approve
     * </p>
     *
     * <p>
     * Sets status to approved, increments category link count, and awards karma to submitter.
     * </p>
     *
     * @param id
     *            Site-category membership ID
     * @return 200 OK on success
     */
    @POST
    @Path("/api/{id}/approve")
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response approve(@PathParam("id") UUID id) {
        UUID moderatorId = getCurrentUserId();

        LOG.infof("Moderator %s approving site-category %s", moderatorId, id);

        try {
            directoryService.approveSiteCategory(id, moderatorId);

            return Response.ok(new SuccessResponse("Site approved successfully")).build();

        } catch (ResourceNotFoundException | ValidationException e) {
            LOG.warnf("Approval failed: %s", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse(e.getMessage())).build();
        }
    }

    /**
     * Rejects a pending site-category submission.
     *
     * <p>
     * Route: POST /api/good-sites/moderate/{id}/reject
     * </p>
     *
     * <p>
     * Sets status to rejected and deducts karma from submitter.
     * </p>
     *
     * @param id
     *            Site-category membership ID
     * @param request
     *            Rejection request with optional reason
     * @return 200 OK on success
     */
    @POST
    @Path("/api/{id}/reject")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response reject(@PathParam("id") UUID id, RejectRequestType request) {
        UUID moderatorId = getCurrentUserId();

        LOG.infof("Moderator %s rejecting site-category %s (reason: %s)", moderatorId, id, request.reason());

        try {
            directoryService.rejectSiteCategory(id, moderatorId);

            // TODO: Store rejection reason in audit log (I5.T8)

            return Response.ok(new SuccessResponse("Site rejected successfully")).build();

        } catch (ResourceNotFoundException | ValidationException e) {
            LOG.warnf("Rejection failed: %s", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse(e.getMessage())).build();
        }
    }

    /**
     * Bulk approves multiple site-category submissions.
     *
     * <p>
     * Route: POST /api/good-sites/moderate/bulk-approve
     * </p>
     *
     * @param request
     *            Bulk approval request with list of IDs
     * @return 200 OK with count of approved submissions
     */
    @POST
    @Path("/api/bulk-approve")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response bulkApprove(BulkActionRequestType request) {
        UUID moderatorId = getCurrentUserId();

        LOG.infof("Moderator %s bulk approving %d submissions", moderatorId, request.ids().size());

        int successCount = 0;
        List<String> errors = new ArrayList<>();

        for (UUID id : request.ids()) {
            try {
                directoryService.approveSiteCategory(id, moderatorId);
                successCount++;
            } catch (Exception e) {
                LOG.warnf("Failed to approve site-category %s: %s", id, e.getMessage());
                errors.add(id + ": " + e.getMessage());
            }
        }

        BulkActionResponseType response = new BulkActionResponseType(successCount, errors);

        return Response.ok(response).build();
    }

    /**
     * Bulk rejects multiple site-category submissions.
     *
     * <p>
     * Route: POST /api/good-sites/moderate/bulk-reject
     * </p>
     *
     * @param request
     *            Bulk rejection request with list of IDs
     * @return 200 OK with count of rejected submissions
     */
    @POST
    @Path("/api/bulk-reject")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response bulkReject(BulkActionRequestType request) {
        UUID moderatorId = getCurrentUserId();

        LOG.infof("Moderator %s bulk rejecting %d submissions", moderatorId, request.ids().size());

        int successCount = 0;
        List<String> errors = new ArrayList<>();

        for (UUID id : request.ids()) {
            try {
                directoryService.rejectSiteCategory(id, moderatorId);
                successCount++;
            } catch (Exception e) {
                LOG.warnf("Failed to reject site-category %s: %s", id, e.getMessage());
                errors.add(id + ": " + e.getMessage());
            }
        }

        BulkActionResponseType response = new BulkActionResponseType(successCount, errors);

        return Response.ok(response).build();
    }

    /**
     * Gets current user ID from security context.
     */
    private UUID getCurrentUserId() {
        String userIdStr = securityIdentity.getPrincipal().getName();
        return UUID.fromString(userIdStr);
    }

    /**
     * Template data for moderation queue page.
     */
    public record ModerationQueueData(List<ModerationQueueItem> queue, List<DirectoryCategoryType> categoryOptions,
            String selectedCategory, UUID moderatorId) {
    }

    /**
     * Moderation queue item (pending submission with metadata).
     */
    public record ModerationQueueItem(UUID siteCategoryId, DirectorySiteType site, DirectoryCategoryType category,
            String submitterEmail, int submitterKarma, String submitterTrustLevel, String screenshotUrl,
            String ogImageUrl, Instant submittedAt) {
    }

    /**
     * Rejection request with optional reason.
     */
    public record RejectRequestType(String reason) {
    }

    /**
     * Bulk action request (list of IDs).
     */
    public record BulkActionRequestType(List<UUID> ids) {
    }

    /**
     * Bulk action response (success count + errors).
     */
    public record BulkActionResponseType(int successCount, List<String> errors) {
    }

    /**
     * Success response DTO.
     */
    private record SuccessResponse(String message) {
    }

    /**
     * Error response DTO.
     */
    private record ErrorResponse(String message) {
    }
}
