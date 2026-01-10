package villagecompute.homepage.api.rest;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.DirectorySiteType;
import villagecompute.homepage.api.types.SiteSubmissionResultType;
import villagecompute.homepage.api.types.SubmitSiteType;
import villagecompute.homepage.exceptions.DuplicateResourceException;
import villagecompute.homepage.exceptions.ResourceNotFoundException;
import villagecompute.homepage.exceptions.ValidationException;
import villagecompute.homepage.services.DirectoryService;

import java.util.List;
import java.util.UUID;

/**
 * User-facing REST endpoints for Good Sites directory submissions (Feature F13.2).
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>POST /api/good-sites/submissions – Submit site to directory</li>
 * <li>GET /api/good-sites/submissions – List user's submissions</li>
 * <li>GET /api/good-sites/submissions/{id} – Get submission status</li>
 * <li>DELETE /api/good-sites/submissions/{id} – Remove own submission</li>
 * </ul>
 *
 * <p>
 * Security: All endpoints require authentication. Users can only view/modify their own submissions (ownership enforced
 * via user_id check). Moderators can approve/reject submissions via separate admin endpoints.
 * </p>
 *
 * <p>
 * Karma-based auto-approval:
 * <ul>
 * <li>untrusted → pending (requires moderation)</li>
 * <li>trusted → approved (auto-approve)</li>
 * <li>moderator → approved (auto-approve)</li>
 * </ul>
 *
 * <p>
 * Error codes:
 * <ul>
 * <li>400 Bad Request – Validation failure (invalid URL, missing category, etc.)</li>
 * <li>403 Forbidden – User not authenticated or not authorized</li>
 * <li>404 Not Found – Site or category not found</li>
 * <li>409 Conflict – Duplicate URL already submitted</li>
 * <li>500 Internal Server Error – Unexpected exception</li>
 * </ul>
 */
@Path("/api/good-sites/submissions")
@RolesAllowed({"user", "super_admin", "support", "ops"})
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DirectorySubmissionResource {

    private static final Logger LOG = Logger.getLogger(DirectorySubmissionResource.class);

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    DirectoryService directoryService;

    /**
     * Submits a new site to the Good Sites directory.
     *
     * <p>
     * Flow:
     * <ol>
     * <li>Validates URL and categories</li>
     * <li>Checks for duplicates</li>
     * <li>Fetches metadata (OpenGraph)</li>
     * <li>Determines auto-approval based on user karma</li>
     * <li>Creates site and site-category entries</li>
     * <li>Returns submission result with status</li>
     * </ol>
     *
     * <p>
     * Example request:
     *
     * <pre>{@code
     * POST /api/good-sites/submissions
     * {
     *   "url": "https://news.ycombinator.com",
     *   "category_ids": ["550e8400-e29b-41d4-a716-446655440000"],
     *   "title": "Hacker News",
     *   "description": "Social news website focusing on computer science"
     * }
     * }</pre>
     *
     * @param request
     *            Submission request with URL and categories
     * @return 201 Created with submission result
     */
    @POST
    public Response submitSite(@Valid SubmitSiteType request) {
        if (request == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse("Request body required"))
                    .build();
        }

        try {
            UUID userId = getCurrentUserId();

            // Validate request
            request.validate();

            // Submit site
            SiteSubmissionResultType result = directoryService.submitSite(userId, request);

            LOG.infof("User %s submitted site %s (status: %s)", userId, result.siteId(), result.status());

            return Response.status(Response.Status.CREATED).entity(result).build();

        } catch (DuplicateResourceException e) {
            LOG.warnf("Duplicate site submission: %s", e.getMessage());
            return Response.status(Response.Status.CONFLICT).entity(new ErrorResponse(e.getMessage())).build();

        } catch (ResourceNotFoundException e) {
            LOG.warnf("Resource not found during submission: %s", e.getMessage());
            return Response.status(Response.Status.NOT_FOUND).entity(new ErrorResponse(e.getMessage())).build();

        } catch (ValidationException e) {
            LOG.warnf("Validation failed: %s", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse(e.getMessage())).build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to submit site: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Failed to submit site: " + e.getMessage())).build();
        }
    }

    /**
     * Lists current user's directory submissions.
     *
     * <p>
     * Returns submissions in all statuses (pending, approved, rejected) ordered by creation date descending.
     * </p>
     *
     * <p>
     * Example response:
     *
     * <pre>{@code
     * [
     *   {
     *     "id": "550e8400-e29b-41d4-a716-446655440001",
     *     "url": "https://news.ycombinator.com",
     *     "title": "Hacker News",
     *     "status": "approved",
     *     "created_at": "2025-01-10T12:00:00Z"
     *   }
     * ]
     * }</pre>
     *
     * @return List of user's submissions
     */
    @GET
    public Response listUserSubmissions() {
        try {
            UUID userId = getCurrentUserId();

            List<DirectorySiteType> submissions = directoryService.getUserSubmissions(userId);

            LOG.infof("Returned %d submissions for user %s", submissions.size(), userId);

            return Response.ok(submissions).build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to list user submissions: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Failed to list submissions: " + e.getMessage())).build();
        }
    }

    /**
     * Retrieves a single directory submission by ID.
     *
     * <p>
     * Returns submission if user is owner. Draft/pending/rejected submissions only visible to owner.
     * </p>
     *
     * @param id
     *            Site UUID
     * @return Submission details or 404 if not found / 403 if not owner
     */
    @GET
    @Path("/{id}")
    public Response getSubmission(@PathParam("id") UUID id) {
        try {
            DirectorySiteType site = directoryService.getSite(id);

            UUID userId = getCurrentUserId();

            // Check ownership (or admin)
            if (!site.submittedByUserId().equals(userId) && !isAdmin()) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(new ErrorResponse("Not authorized to view this submission")).build();
            }

            return Response.ok(site).build();

        } catch (ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(new ErrorResponse(e.getMessage())).build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to get submission: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Failed to get submission: " + e.getMessage())).build();
        }
    }

    /**
     * Deletes a directory submission.
     *
     * <p>
     * Soft-deletes the site and cascades to site-category and vote records. Decrements category link counts for
     * approved categories.
     * </p>
     *
     * <p>
     * Can only be deleted by the submitting user or an admin.
     * </p>
     *
     * @param id
     *            Site UUID to delete
     * @return 204 No Content on success
     */
    @DELETE
    @Path("/{id}")
    public Response deleteSubmission(@PathParam("id") UUID id) {
        try {
            UUID userId = getCurrentUserId();

            directoryService.deleteSite(id, userId);

            LOG.infof("User %s deleted submission %s", userId, id);

            return Response.noContent().build();

        } catch (ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(new ErrorResponse(e.getMessage())).build();

        } catch (ValidationException e) {
            return Response.status(Response.Status.FORBIDDEN).entity(new ErrorResponse(e.getMessage())).build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to delete submission: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Failed to delete submission: " + e.getMessage())).build();
        }
    }

    /**
     * Gets current user ID from security context.
     *
     * @return User UUID
     */
    private UUID getCurrentUserId() {
        String userIdStr = securityIdentity.getPrincipal().getName();
        return UUID.fromString(userIdStr);
    }

    /**
     * Checks if current user is an admin.
     *
     * @return true if user has admin role
     */
    private boolean isAdmin() {
        return securityIdentity.hasRole("super_admin") || securityIdentity.hasRole("support")
                || securityIdentity.hasRole("ops");
    }

    /**
     * Error response DTO.
     */
    private record ErrorResponse(String message) {
    }
}
