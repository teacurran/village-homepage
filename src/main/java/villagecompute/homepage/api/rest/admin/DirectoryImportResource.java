package villagecompute.homepage.api.rest.admin;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import villagecompute.homepage.api.types.CsvUploadForm;
import villagecompute.homepage.api.types.DirectoryAiSuggestionType;
import villagecompute.homepage.api.types.DirectoryAiSuggestionType.CategorySelectionType;
import villagecompute.homepage.data.models.DirectoryAiSuggestion;
import villagecompute.homepage.data.models.DirectoryCategory;
import villagecompute.homepage.data.models.DirectorySite;
import villagecompute.homepage.data.models.DirectorySiteCategory;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.jobs.JobType;
import villagecompute.homepage.services.DelayedJobService;
import villagecompute.homepage.services.DirectoryService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Admin REST endpoints for Good Sites bulk import with AI categorization.
 *
 * <p>
 * Workflow:
 * <ol>
 * <li>{@code POST /admin/api/directory/import/upload} – Upload CSV file, creates bulk import job</li>
 * <li>{@code GET /admin/api/directory/import/suggestions} – List AI suggestions pending review</li>
 * <li>{@code GET /admin/api/directory/import/suggestions/{id}} – Get single suggestion with diff view</li>
 * <li>{@code POST /admin/api/directory/import/suggestions/{id}/approve} – Approve and create site</li>
 * <li>{@code POST /admin/api/directory/import/suggestions/{id}/reject} – Reject suggestion</li>
 * </ol>
 *
 * <p>
 * <b>Security:</b> All endpoints secured with {@code @RolesAllowed} for super_admin or ops access.
 *
 * <p>
 * <b>CSV Format:</b>
 * <ul>
 * <li>url (required): Website URL to import</li>
 * <li>title (optional): Override fetched title</li>
 * <li>description (optional): Override fetched description</li>
 * <li>suggested_categories (optional): Comma-separated category slugs</li>
 * </ul>
 *
 * @see DirectoryAiSuggestion
 * @see villagecompute.homepage.jobs.BulkImportJobHandler
 */
@Path("/admin/api/directory/import")
@Tag(
        name = "Admin - System",
        description = "Admin endpoints for bulk directory import with AI categorization (requires super_admin or ops role)")
@SecurityRequirement(
        name = "bearerAuth")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({User.ROLE_SUPER_ADMIN, User.ROLE_OPS})
public class DirectoryImportResource {

    private static final Logger LOG = Logger.getLogger(DirectoryImportResource.class);

    // Maximum CSV file size (10MB)
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    // Maximum rows per CSV (configurable)
    private static final int MAX_ROWS = 10000;

    @Inject
    DelayedJobService jobService;

    @Inject
    DirectoryService directoryService;

    /**
     * Upload CSV file for bulk import with AI categorization.
     *
     * <p>
     * Validates CSV format, stores to temp directory, and enqueues BULK job for async processing.
     *
     * @param form
     *            multipart form with CSV file and optional description
     * @param identity
     *            current user identity
     * @return job ID for tracking progress
     */
    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(
            summary = "Upload CSV for bulk import",
            description = "Uploads CSV file for bulk directory import with AI categorization. Requires super_admin or ops role.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Success - CSV uploaded and job queued",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "400",
                            description = "Bad request - invalid file format or size"),
                    @APIResponse(
                            responseCode = "401",
                            description = "Unauthorized - missing or invalid authentication"),
                    @APIResponse(
                            responseCode = "403",
                            description = "Forbidden - insufficient permissions"),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error")})
    public Response uploadCsv(CsvUploadForm form, @Context SecurityIdentity identity) {
        try {
            FileUpload file = form.file;

            if (file == null) {
                return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Missing file parameter"))
                        .build();
            }

            // Validate file size
            if (file.size() > MAX_FILE_SIZE) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "File too large (max 10MB)", "size", file.size())).build();
            }

            // Validate file extension
            String filename = file.fileName();
            if (!filename.toLowerCase().endsWith(".csv")) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "File must be CSV format", "filename", filename)).build();
            }

            // Count rows (basic validation)
            long rowCount = Files.lines(file.uploadedFile()).count();
            if (rowCount > MAX_ROWS) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Too many rows (max " + MAX_ROWS + ")", "rows", rowCount)).build();
            }

            // Store to temp directory with unique name
            String tempDir = System.getProperty("java.io.tmpdir");
            String uniqueFilename = String.format("bulk-import-%d-%s", System.currentTimeMillis(), filename);
            java.nio.file.Path targetPath = Paths.get(tempDir, uniqueFilename);

            Files.copy(file.uploadedFile(), targetPath, StandardCopyOption.REPLACE_EXISTING);

            LOG.infof("CSV uploaded: filename=%s, size=%d, rows=%d, path=%s", filename, file.size(), rowCount,
                    targetPath);

            // Get current user ID
            UUID userId = UUID.fromString(identity.getAttribute("user_id"));

            // Enqueue job
            Map<String, Object> payload = new HashMap<>();
            payload.put("csv_path", targetPath.toString());
            payload.put("uploaded_by_user_id", userId.toString());
            if (form.description != null && !form.description.isBlank()) {
                payload.put("description", form.description);
            }

            long jobId = jobService.enqueue(JobType.DIRECTORY_BULK_IMPORT, payload);

            LOG.infof("Bulk import job created: jobId=%d, userId=%s, rows=%d", jobId, userId, rowCount);

            return Response.ok(Map.of("job_id", jobId, "rows", rowCount - 1, // Exclude header
                    "status", "queued", "message", "CSV uploaded successfully, processing in background")).build();

        } catch (IOException e) {
            LOG.errorf(e, "Failed to upload CSV");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to process file: " + e.getMessage())).build();
        }
    }

    /**
     * List AI categorization suggestions.
     *
     * <p>
     * Supports filtering by status (pending, approved, rejected).
     *
     * @param status
     *            optional filter (pending, approved, rejected)
     * @return list of suggestions with AI vs admin category diff
     */
    @GET
    @Path("/suggestions")
    @Operation(
            summary = "List AI categorization suggestions",
            description = "Returns AI categorization suggestions with optional status filter. Requires super_admin or ops role.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Success",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = DirectoryAiSuggestionType.class))),
                    @APIResponse(
                            responseCode = "401",
                            description = "Unauthorized - missing or invalid authentication"),
                    @APIResponse(
                            responseCode = "403",
                            description = "Forbidden - insufficient permissions"),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error")})
    public Response listSuggestions(@Parameter(
            description = "Filter by status (pending, approved, rejected, all)") @QueryParam("status") @DefaultValue("pending") String status) {
        List<DirectoryAiSuggestion> suggestions;

        if (status == null || status.equalsIgnoreCase("all")) {
            suggestions = DirectoryAiSuggestion.listAll();
        } else {
            suggestions = DirectoryAiSuggestion.findByStatus(status);
        }

        List<DirectoryAiSuggestionType> response = suggestions.stream().map(this::toDto).collect(Collectors.toList());

        return Response.ok(response).build();
    }

    /**
     * Get single AI suggestion with diff view.
     *
     * @param id
     *            suggestion UUID
     * @return suggestion details with AI vs admin category comparison
     */
    @GET
    @Path("/suggestions/{id}")
    @Operation(
            summary = "Get AI suggestion details",
            description = "Returns single AI suggestion with category comparison. Requires super_admin or ops role.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Success",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = DirectoryAiSuggestionType.class))),
                    @APIResponse(
                            responseCode = "401",
                            description = "Unauthorized - missing or invalid authentication"),
                    @APIResponse(
                            responseCode = "403",
                            description = "Forbidden - insufficient permissions"),
                    @APIResponse(
                            responseCode = "404",
                            description = "Suggestion not found"),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error")})
    public Response getSuggestion(@Parameter(
            description = "Suggestion UUID",
            required = true) @PathParam("id") UUID id) {
        Optional<DirectoryAiSuggestion> suggestionOpt = DirectoryAiSuggestion.findByIdOptional(id);

        if (suggestionOpt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "Suggestion not found")).build();
        }

        DirectoryAiSuggestion suggestion = suggestionOpt.get();
        DirectoryAiSuggestionType dto = toDto(suggestion);

        return Response.ok(dto).build();
    }

    /**
     * Approve AI suggestion and create directory site.
     *
     * <p>
     * Accepts optional category override. If admin selects different categories than AI suggested, stores both for
     * training data.
     *
     * @param id
     *            suggestion UUID
     * @param categoryIds
     *            optional admin-selected category IDs (comma-separated UUIDs)
     * @param identity
     *            current user identity
     * @return created site ID
     */
    @POST
    @Path("/suggestions/{id}/approve")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(
            summary = "Approve AI suggestion",
            description = "Approves AI suggestion and creates directory site. Optional category override. Requires super_admin or ops role.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Success - suggestion approved and site created",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "400",
                            description = "Bad request - suggestion already processed"),
                    @APIResponse(
                            responseCode = "401",
                            description = "Unauthorized - missing or invalid authentication"),
                    @APIResponse(
                            responseCode = "403",
                            description = "Forbidden - insufficient permissions"),
                    @APIResponse(
                            responseCode = "404",
                            description = "Suggestion not found"),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error")})
    public Response approveSuggestion(@Parameter(
            description = "Suggestion UUID",
            required = true) @PathParam("id") UUID id,
            @Parameter(
                    description = "Admin-selected category IDs (comma-separated UUIDs, optional)") @FormParam("category_ids") String categoryIds,
            @Context SecurityIdentity identity) {

        Optional<DirectoryAiSuggestion> suggestionOpt = DirectoryAiSuggestion.findByIdOptional(id);

        if (suggestionOpt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "Suggestion not found")).build();
        }

        DirectoryAiSuggestion suggestion = suggestionOpt.get();

        if (!"pending".equals(suggestion.status)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Suggestion already processed", "status", suggestion.status)).build();
        }

        UUID userId = UUID.fromString(identity.getAttribute("user_id"));

        // Parse admin-selected categories (if provided)
        UUID[] adminCategoryIds = null;
        if (categoryIds != null && !categoryIds.isBlank()) {
            adminCategoryIds = Arrays.stream(categoryIds.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                    .map(UUID::fromString).toArray(UUID[]::new);
        }

        // Mark as approved
        suggestion.approve(userId, adminCategoryIds);

        // Create DirectorySite from suggestion
        DirectorySite site = new DirectorySite();
        site.url = suggestion.url;
        site.domain = suggestion.domain;
        site.title = suggestion.title;
        site.description = suggestion.description;
        site.ogImageUrl = suggestion.ogImageUrl;
        site.status = "published"; // Auto-publish admin-approved imports
        site.submittedByUserId = suggestion.uploadedByUserId;
        site.createdAt = Instant.now();
        site.updatedAt = Instant.now();
        site.persist();

        // Create DirectorySiteCategory entries for final categories
        UUID[] finalCategories = suggestion.getFinalCategoryIds();
        for (UUID categoryId : finalCategories) {
            DirectorySiteCategory siteCategory = new DirectorySiteCategory();
            siteCategory.siteId = site.id;
            siteCategory.categoryId = categoryId;
            siteCategory.upvotes = 0;
            siteCategory.downvotes = 0;
            siteCategory.score = 0;
            siteCategory.createdAt = Instant.now();
            siteCategory.updatedAt = Instant.now();
            siteCategory.persist();
        }

        LOG.infof("Approved suggestion: id=%s, url=%s, siteId=%s, categories=%d, hasOverride=%s", suggestion.id,
                suggestion.url, site.id, finalCategories.length, suggestion.hasAdminOverride());

        return Response.ok(Map.of("site_id", site.id, "url", site.url, "categories", finalCategories.length,
                "had_override", suggestion.hasAdminOverride())).build();
    }

    /**
     * Reject AI suggestion.
     *
     * @param id
     *            suggestion UUID
     * @param identity
     *            current user identity
     * @return success message
     */
    @POST
    @Path("/suggestions/{id}/reject")
    @Operation(
            summary = "Reject AI suggestion",
            description = "Rejects AI suggestion. Requires super_admin or ops role.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Success - suggestion rejected",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "400",
                            description = "Bad request - suggestion already processed"),
                    @APIResponse(
                            responseCode = "401",
                            description = "Unauthorized - missing or invalid authentication"),
                    @APIResponse(
                            responseCode = "403",
                            description = "Forbidden - insufficient permissions"),
                    @APIResponse(
                            responseCode = "404",
                            description = "Suggestion not found"),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error")})
    public Response rejectSuggestion(@Parameter(
            description = "Suggestion UUID",
            required = true) @PathParam("id") UUID id, @Context SecurityIdentity identity) {

        Optional<DirectoryAiSuggestion> suggestionOpt = DirectoryAiSuggestion.findByIdOptional(id);

        if (suggestionOpt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "Suggestion not found")).build();
        }

        DirectoryAiSuggestion suggestion = suggestionOpt.get();

        if (!"pending".equals(suggestion.status)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Suggestion already processed", "status", suggestion.status)).build();
        }

        UUID userId = UUID.fromString(identity.getAttribute("user_id"));
        suggestion.reject(userId);

        LOG.infof("Rejected suggestion: id=%s, url=%s, reviewerId=%s", suggestion.id, suggestion.url, userId);

        return Response.ok(Map.of("status", "rejected", "id", suggestion.id)).build();
    }

    /**
     * Converts DirectoryAiSuggestion entity to DTO with category details.
     */
    private DirectoryAiSuggestionType toDto(DirectoryAiSuggestion suggestion) {
        // Build AI-suggested categories
        List<CategorySelectionType> aiSuggested = new ArrayList<>();
        if (suggestion.suggestedCategoryIds != null) {
            for (UUID catId : suggestion.suggestedCategoryIds) {
                DirectoryCategory cat = DirectoryCategory.findById(catId);
                if (cat != null) {
                    aiSuggested.add(new CategorySelectionType(cat.id, cat.slug, buildCategoryPath(cat), null // Reasoning
                                                                                                             // is in
                                                                                                             // overall_reasoning
                                                                                                             // field
                    ));
                }
            }
        }

        // Build admin-selected categories (if overridden)
        List<CategorySelectionType> adminSelected = new ArrayList<>();
        if (suggestion.adminSelectedCategoryIds != null) {
            for (UUID catId : suggestion.adminSelectedCategoryIds) {
                DirectoryCategory cat = DirectoryCategory.findById(catId);
                if (cat != null) {
                    adminSelected.add(new CategorySelectionType(cat.id, cat.slug, buildCategoryPath(cat), null));
                }
            }
        }

        return new DirectoryAiSuggestionType(suggestion.id, suggestion.url, suggestion.domain, suggestion.title,
                suggestion.description, suggestion.ogImageUrl, aiSuggested,
                adminSelected.isEmpty() ? null : adminSelected,
                suggestion.confidence != null ? suggestion.confidence.doubleValue() : null, suggestion.reasoning,
                suggestion.status, suggestion.uploadedByUserId, suggestion.reviewedByUserId, suggestion.tokensInput,
                suggestion.tokensOutput, suggestion.estimatedCostCents, suggestion.createdAt, suggestion.updatedAt);
    }

    /**
     * Builds full category path (e.g., "Computers > Programming > Java").
     */
    private String buildCategoryPath(DirectoryCategory category) {
        List<String> pathSegments = new ArrayList<>();
        DirectoryCategory current = category;

        while (current != null) {
            pathSegments.add(0, current.name);
            if (current.parentId != null) {
                current = DirectoryCategory.findById(current.parentId);
            } else {
                current = null;
            }
        }

        return String.join(" > ", pathSegments);
    }
}
