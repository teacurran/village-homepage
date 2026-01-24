package villagecompute.homepage.api.rest;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.GdprRequest;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.jobs.JobType;
import villagecompute.homepage.services.DelayedJobService;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * REST API for GDPR data access and erasure requests per Policy P1.
 *
 * <p>
 * Provides user-facing endpoints for:
 * <ul>
 * <li><b>POST /api/gdpr/export</b> - Request data export (GDPR Article 15)</li>
 * <li><b>POST /api/gdpr/delete</b> - Request account deletion (GDPR Article 17)</li>
 * </ul>
 *
 * <p>
 * <b>Authentication:</b> All endpoints require authenticated user (via {@code @Authenticated}). Anonymous users cannot
 * request GDPR operations.
 *
 * <p>
 * <b>Idempotency:</b> Subsequent export/deletion requests for the same user will return 429 TOO_MANY_REQUESTS if a
 * PENDING or PROCESSING request already exists.
 *
 * <p>
 * <b>Audit Trail:</b> All requests are logged to {@code gdpr_requests} table with IP address, user agent, and status
 * transitions.
 *
 * @see villagecompute.homepage.services.GdprService
 * @see villagecompute.homepage.jobs.GdprExportJobHandler
 * @see villagecompute.homepage.jobs.GdprDeletionJobHandler
 */
@Path("/api/gdpr")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(
        name = "GDPR",
        description = "GDPR data access and erasure operations")
public class GdprResource {

    private static final Logger LOG = Logger.getLogger(GdprResource.class);

    @Inject
    DelayedJobService jobService;

    @Context
    SecurityIdentity identity;

    /**
     * Requests GDPR data export for authenticated user.
     *
     * <p>
     * <b>Flow:</b>
     * <ol>
     * <li>Verify user is authenticated</li>
     * <li>Check for existing PENDING/PROCESSING export request (prevent duplicates)</li>
     * <li>Create GdprRequest audit record</li>
     * <li>Enqueue GDPR_EXPORT job</li>
     * <li>Return 202 ACCEPTED with job ID</li>
     * </ol>
     *
     * <p>
     * User will receive email with signed download URL within 24 hours (typically &lt;5 minutes).
     *
     * @param uriInfo
     *            JAX-RS context for extracting request metadata
     * @return 202 ACCEPTED with job ID, or 429 TOO_MANY_REQUESTS if duplicate request
     */
    @POST
    @Path("/export")
    @Authenticated
    @Transactional
    @Operation(
            summary = "Request GDPR data export",
            description = "Request export of all user data (GDPR Article 15). Receive email with download link within 24 hours.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "202",
                    description = "Export request accepted and queued",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "401",
                            description = "Authentication required",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "404",
                            description = "User not found",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "429",
                            description = "Export request already in progress",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "500",
                            description = "Server error",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    @SecurityRequirement(
            name = "bearerAuth")
    public Response requestExport(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
        String principalName = identity.getPrincipal().getName();
        UUID userId;
        try {
            userId = UUID.fromString(principalName);
        } catch (IllegalArgumentException e) {
            // Generate deterministic UUID from principal name for testing
            userId = UUID.nameUUIDFromBytes(principalName.getBytes());
        }

        User user = User.findById(userId);
        if (user == null) {
            LOG.errorf("User not found for principal: %s", principalName);
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "User not found")).build();
        }

        // Check for existing PENDING/PROCESSING export request
        Optional<GdprRequest> existingRequest = GdprRequest.findLatestExport(userId);
        if (existingRequest.isPresent()) {
            GdprRequest request = existingRequest.get();
            if (request.status == GdprRequest.RequestStatus.PENDING
                    || request.status == GdprRequest.RequestStatus.PROCESSING) {
                LOG.warnf("Duplicate export request for user %s (existing request: %s, status: %s)", userId, request.id,
                        request.status);
                return Response.status(Response.Status.TOO_MANY_REQUESTS)
                        .entity(Map.of("error", "Export request already in progress", "existing_request_id",
                                request.id.toString(), "status", request.status.name()))
                        .build();
            }
        }

        // Extract IP address and user agent for audit trail
        String ipAddress = extractIpAddress(headers);
        String userAgent = extractUserAgent(headers);

        // Create GdprRequest audit record
        GdprRequest request = GdprRequest.create(userId, GdprRequest.RequestType.EXPORT, ipAddress, userAgent, null // jobId
                                                                                                                    // will
                                                                                                                    // be
                                                                                                                    // set
                                                                                                                    // after
                                                                                                                    // enqueue
        );

        // Enqueue GDPR_EXPORT job
        Map<String, Object> payload = Map.of("user_id", userId.toString(), "email", user.email, "request_id",
                request.id.toString());
        long jobId = jobService.enqueue(JobType.GDPR_EXPORT, payload);

        // Update request with job ID
        request.jobId = jobId;
        request.persist();

        LOG.infof("GDPR export requested for user %s (request: %s, job: %d)", userId, request.id, jobId);

        return Response.accepted()
                .entity(Map.of("message",
                        "Export requested. You will receive an email with download link within 24 hours.", "request_id",
                        request.id.toString(), "job_id", jobId))
                .build();
    }

    /**
     * Requests GDPR account deletion for authenticated user.
     *
     * <p>
     * <b>⚠️ WARNING:</b> This is a permanent, irreversible operation. All user data and related entities will be
     * hard-deleted (no soft delete).
     *
     * <p>
     * <b>Flow:</b>
     * <ol>
     * <li>Verify user is authenticated</li>
     * <li>Check for existing PENDING/PROCESSING deletion request (prevent duplicates)</li>
     * <li>Create GdprRequest audit record</li>
     * <li>Enqueue GDPR_DELETION job (HIGH priority)</li>
     * <li>Return 202 ACCEPTED with job ID</li>
     * </ol>
     *
     * <p>
     * User will receive confirmation email once deletion completes (typically within 24 hours).
     *
     * @param uriInfo
     *            JAX-RS context for extracting request metadata
     * @return 202 ACCEPTED with job ID, or 429 TOO_MANY_REQUESTS if duplicate request
     */
    @POST
    @Path("/delete")
    @Authenticated
    @Transactional
    @Operation(
            summary = "Request GDPR account deletion",
            description = "Request permanent deletion of all user data (GDPR Article 17). WARNING: This is irreversible.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "202",
                    description = "Deletion request accepted and queued",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "401",
                            description = "Authentication required",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "404",
                            description = "User not found",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "429",
                            description = "Deletion request already in progress",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "500",
                            description = "Server error",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    @SecurityRequirement(
            name = "bearerAuth")
    public Response requestDeletion(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
        String principalName = identity.getPrincipal().getName();
        UUID userId;
        try {
            userId = UUID.fromString(principalName);
        } catch (IllegalArgumentException e) {
            // Generate deterministic UUID from principal name for testing
            userId = UUID.nameUUIDFromBytes(principalName.getBytes());
        }

        User user = User.findById(userId);
        if (user == null) {
            LOG.errorf("User not found for principal: %s", principalName);
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "User not found")).build();
        }

        // Check for existing PENDING/PROCESSING deletion request
        Optional<GdprRequest> existingRequest = GdprRequest.findLatestDeletion(userId);
        if (existingRequest.isPresent()) {
            GdprRequest request = existingRequest.get();
            if (request.status == GdprRequest.RequestStatus.PENDING
                    || request.status == GdprRequest.RequestStatus.PROCESSING) {
                LOG.warnf("Duplicate deletion request for user %s (existing request: %s, status: %s)", userId,
                        request.id, request.status);
                return Response.status(Response.Status.TOO_MANY_REQUESTS)
                        .entity(Map.of("error", "Deletion request already in progress", "existing_request_id",
                                request.id.toString(), "status", request.status.name()))
                        .build();
            }
        }

        // Extract IP address and user agent for audit trail
        String ipAddress = extractIpAddress(headers);
        String userAgent = extractUserAgent(headers);

        // Create GdprRequest audit record
        GdprRequest request = GdprRequest.create(userId, GdprRequest.RequestType.DELETION, ipAddress, userAgent, null // jobId
                                                                                                                      // will
                                                                                                                      // be
                                                                                                                      // set
                                                                                                                      // after
                                                                                                                      // enqueue
        );

        // Enqueue GDPR_DELETION job (HIGH priority)
        Map<String, Object> payload = Map.of("user_id", userId.toString(), "email", user.email, "request_id",
                request.id.toString());
        long jobId = jobService.enqueue(JobType.GDPR_DELETION, payload);

        // Update request with job ID
        request.jobId = jobId;
        request.persist();

        LOG.infof("GDPR deletion requested for user %s (request: %s, job: %d)", userId, request.id, jobId);

        return Response.accepted().entity(Map.of("message",
                "Deletion requested. Your account will be permanently deleted within 24 hours.", "request_id",
                request.id.toString(), "job_id", jobId, "warning", "This action is permanent and cannot be undone"))
                .build();
    }

    /**
     * Extracts client IP address from request headers (X-Forwarded-For, X-Real-IP) or remote address.
     */
    private String extractIpAddress(HttpHeaders headers) {
        // Check X-Forwarded-For header (set by reverse proxy/load balancer)
        String xForwardedFor = headers.getHeaderString("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // X-Forwarded-For can contain multiple IPs (client, proxy1, proxy2, ...)
            // First IP is the original client IP
            return xForwardedFor.split(",")[0].trim();
        }

        // Check X-Real-IP header (alternative to X-Forwarded-For)
        String xRealIp = headers.getHeaderString("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp.trim();
        }

        // Fallback to unknown if no headers present
        // In production, this should be set by Cloudflare/NGINX ingress
        LOG.warnf("Unable to extract IP address from headers, using fallback");
        return "0.0.0.0";
    }

    /**
     * Extracts user agent from request headers.
     */
    private String extractUserAgent(HttpHeaders headers) {
        String userAgent = headers.getHeaderString(HttpHeaders.USER_AGENT);
        if (userAgent != null && !userAgent.isEmpty()) {
            return userAgent.trim();
        }
        return "Unknown";
    }
}
