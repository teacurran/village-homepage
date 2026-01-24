package villagecompute.homepage.api.rest.admin;

import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
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
import villagecompute.homepage.api.types.CreateDirectoryCategoryRequestType;
import villagecompute.homepage.api.types.DirectoryCategoryType;
import villagecompute.homepage.api.types.UpdateDirectoryCategoryRequestType;
import villagecompute.homepage.data.models.DirectoryCategory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Admin REST endpoints for directory category management (Good Sites web directory).
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>{@code GET /admin/api/directory/categories} – list all categories (flat)</li>
 * <li>{@code GET /admin/api/directory/categories/tree} – get hierarchical category tree</li>
 * <li>{@code GET /admin/api/directory/categories/{id}} – get single category</li>
 * <li>{@code POST /admin/api/directory/categories} – create new category</li>
 * <li>{@code PATCH /admin/api/directory/categories/{id}} – update category configuration</li>
 * <li>{@code DELETE /admin/api/directory/categories/{id}} – delete category (if no children/sites)</li>
 * </ul>
 *
 * <p>
 * <b>Security:</b> All endpoints secured with {@code @RolesAllowed("super_admin")} for admin-only access per Policy
 * I2.T8.
 *
 * <p>
 * <b>Validation:</b> JAX-RS {@code @Valid} annotation triggers constraint validation (slug format, name length,
 * description size).
 *
 * <p>
 * <b>Category Import:</b> The {@code POST /import} endpoint supports bulk category import from YAML/JSON files for ops
 * team to quickly adjust taxonomy. See ops documentation for import file format.
 */
@Path("/admin/api/directory/categories")
@Tag(
        name = "Admin - Categories",
        description = "Admin endpoints for directory category management (requires super_admin role)")
@SecurityRequirement(
        name = "bearerAuth")
@RolesAllowed("super_admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DirectoryCategoryResource {

    private static final Logger LOG = Logger.getLogger(DirectoryCategoryResource.class);

    /**
     * Lists all directory categories (flat list, ordered hierarchically).
     *
     * <p>
     * Returns all categories including inactive ones. Categories are ordered by parent_id (nulls first) then sort_order
     * for hierarchical display.
     *
     * @return list of all categories
     */
    @GET
    @Operation(
            summary = "List all directory categories",
            description = "Returns all directory categories in flat list, ordered hierarchically. Requires super_admin role.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Success",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = DirectoryCategoryType.class))),
                    @APIResponse(
                            responseCode = "401",
                            description = "Unauthorized - missing or invalid authentication"),
                    @APIResponse(
                            responseCode = "403",
                            description = "Forbidden - insufficient permissions (requires super_admin role)"),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error")})
    public Response listCategories() {
        List<DirectoryCategory> categories = DirectoryCategory.findAllOrdered();
        List<DirectoryCategoryType> response = DirectoryCategoryType.fromEntities(categories);
        return Response.ok(response).build();
    }

    /**
     * Retrieves the full category tree (hierarchical structure).
     *
     * <p>
     * Returns root categories with nested children. Includes inactive categories for admin visibility. Used for admin
     * tree UI and category management.
     *
     * @return hierarchical category tree
     */
    @GET
    @Path("/tree")
    @Operation(
            summary = "Get category tree",
            description = "Returns hierarchical directory category tree with nested children. Requires super_admin role.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Success"),
                    @APIResponse(
                            responseCode = "401",
                            description = "Unauthorized - missing or invalid authentication"),
                    @APIResponse(
                            responseCode = "403",
                            description = "Forbidden - insufficient permissions (requires super_admin role)"),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error")})
    public Response getCategoryTree() {
        List<DirectoryCategory.CategoryNode> tree = DirectoryCategory.buildCategoryTree();
        return Response.ok(tree).build();
    }

    /**
     * Retrieves a single directory category by ID.
     *
     * @param id
     *            the category UUID
     * @return category configuration or 404 if not found
     */
    @GET
    @Path("/{id}")
    @Operation(
            summary = "Get directory category by ID",
            description = "Returns a single directory category by ID. Requires super_admin role.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Success",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = DirectoryCategoryType.class))),
                    @APIResponse(
                            responseCode = "401",
                            description = "Unauthorized - missing or invalid authentication"),
                    @APIResponse(
                            responseCode = "403",
                            description = "Forbidden - insufficient permissions (requires super_admin role)"),
                    @APIResponse(
                            responseCode = "404",
                            description = "Category not found"),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error")})
    public Response getCategory(@Parameter(
            description = "Category UUID",
            required = true) @PathParam("id") UUID id) {
        DirectoryCategory category = DirectoryCategory.findById(id);
        if (category == null) {
            return Response.status(Response.Status.NOT_FOUND).entity(new ErrorResponse("Category not found: " + id))
                    .build();
        }
        return Response.ok(DirectoryCategoryType.fromEntity(category)).build();
    }

    /**
     * Creates a new directory category (admin-only).
     *
     * <p>
     * Validates slug uniqueness and parent_id existence (if provided). Returns 409 Conflict if slug already exists or
     * parent not found.
     *
     * @param request
     *            creation payload with required fields
     * @return created category configuration with 201 Created status
     */
    @POST
    @Operation(
            summary = "Create directory category",
            description = "Creates a new directory category. Validates slug uniqueness and parent existence. Requires super_admin role.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "201",
                    description = "Created",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = DirectoryCategoryType.class))),
                    @APIResponse(
                            responseCode = "400",
                            description = "Bad request - invalid request body"),
                    @APIResponse(
                            responseCode = "401",
                            description = "Unauthorized - missing or invalid authentication"),
                    @APIResponse(
                            responseCode = "403",
                            description = "Forbidden - insufficient permissions (requires super_admin role)"),
                    @APIResponse(
                            responseCode = "409",
                            description = "Conflict - slug already exists or parent not found"),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error")})
    public Response createCategory(@Valid CreateDirectoryCategoryRequestType request) {
        if (request == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse("Request body required"))
                    .build();
        }

        try {
            // Validate slug uniqueness
            Optional<DirectoryCategory> existing = DirectoryCategory.findBySlug(request.slug());
            if (existing.isPresent()) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(new ErrorResponse("Category slug already exists: " + request.slug())).build();
            }

            // Validate parent exists (if provided)
            if (request.parentId() != null) {
                DirectoryCategory parent = DirectoryCategory.findById(request.parentId());
                if (parent == null) {
                    return Response.status(Response.Status.CONFLICT)
                            .entity(new ErrorResponse("Parent category not found: " + request.parentId())).build();
                }
            }

            // Create category with defaults
            DirectoryCategory category = new DirectoryCategory();
            category.parentId = request.parentId();
            category.name = request.name();
            category.slug = request.slug();
            category.description = request.description();
            category.iconUrl = request.iconUrl();
            category.sortOrder = request.sortOrder() != null ? request.sortOrder() : 0;
            category.linkCount = 0; // Always start at 0 (updated by background jobs)
            category.isActive = request.isActive() != null ? request.isActive() : true;

            DirectoryCategory created = DirectoryCategory.create(category);

            LOG.infof("Created directory category: id=%s, name=%s, slug=%s, parentId=%s", created.id, created.name,
                    created.slug, created.parentId);
            return Response.status(Response.Status.CREATED).entity(DirectoryCategoryType.fromEntity(created)).build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to create directory category: %s", request.slug());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Failed to create directory category")).build();
        }
    }

    /**
     * Updates a directory category configuration.
     *
     * <p>
     * Supports partial updates via PATCH semantics. Null fields are ignored. Validates slug uniqueness if slug is being
     * changed.
     *
     * @param id
     *            the category UUID to update
     * @param request
     *            update payload with optional fields
     * @return updated category configuration or 404 if not found
     */
    @PATCH
    @Path("/{id}")
    @Operation(
            summary = "Update directory category",
            description = "Updates a directory category with partial update support. Validates slug uniqueness if changed. Requires super_admin role.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Success - category updated",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = DirectoryCategoryType.class))),
                    @APIResponse(
                            responseCode = "400",
                            description = "Bad request - invalid request body"),
                    @APIResponse(
                            responseCode = "401",
                            description = "Unauthorized - missing or invalid authentication"),
                    @APIResponse(
                            responseCode = "403",
                            description = "Forbidden - insufficient permissions (requires super_admin role)"),
                    @APIResponse(
                            responseCode = "404",
                            description = "Category not found"),
                    @APIResponse(
                            responseCode = "409",
                            description = "Conflict - slug already exists"),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error")})
    public Response updateCategory(@Parameter(
            description = "Category UUID",
            required = true) @PathParam("id") UUID id, @Valid UpdateDirectoryCategoryRequestType request) {
        if (request == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse("Request body required"))
                    .build();
        }

        try {
            DirectoryCategory category = DirectoryCategory.findById(id);
            if (category == null) {
                return Response.status(Response.Status.NOT_FOUND).entity(new ErrorResponse("Category not found: " + id))
                        .build();
            }

            // Validate slug uniqueness if changing slug
            if (request.slug() != null && !request.slug().equals(category.slug)) {
                Optional<DirectoryCategory> existing = DirectoryCategory.findBySlug(request.slug());
                if (existing.isPresent() && !existing.get().id.equals(id)) {
                    return Response.status(Response.Status.CONFLICT)
                            .entity(new ErrorResponse("Category slug already exists: " + request.slug())).build();
                }
            }

            // Apply partial updates
            if (request.name() != null) {
                category.name = request.name();
            }
            if (request.slug() != null) {
                category.slug = request.slug();
            }
            if (request.description() != null) {
                category.description = request.description();
            }
            if (request.iconUrl() != null) {
                category.iconUrl = request.iconUrl();
            }
            if (request.sortOrder() != null) {
                category.sortOrder = request.sortOrder();
            }
            if (request.isActive() != null) {
                category.isActive = request.isActive();
            }

            DirectoryCategory.update(category);

            LOG.infof("Updated directory category: id=%s, name=%s, isActive=%b, sortOrder=%d", id, category.name,
                    category.isActive, category.sortOrder);
            return Response.ok(DirectoryCategoryType.fromEntity(category)).build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to update directory category: %s", id);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Failed to update directory category")).build();
        }
    }

    /**
     * Deletes a directory category by ID.
     *
     * <p>
     * Only allows deletion if category has no children and no associated sites. Returns 409 Conflict if category cannot
     * be safely deleted.
     *
     * @param id
     *            the category UUID to delete
     * @return 204 No Content on success, 404 if not found, 409 if has children/sites
     */
    @DELETE
    @Path("/{id}")
    @Operation(
            summary = "Delete directory category",
            description = "Deletes a directory category if it has no children or associated sites. Requires super_admin role.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "204",
                    description = "Success - category deleted"),
                    @APIResponse(
                            responseCode = "401",
                            description = "Unauthorized - missing or invalid authentication"),
                    @APIResponse(
                            responseCode = "403",
                            description = "Forbidden - insufficient permissions (requires super_admin role)"),
                    @APIResponse(
                            responseCode = "404",
                            description = "Category not found"),
                    @APIResponse(
                            responseCode = "409",
                            description = "Conflict - category has children or associated sites"),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error")})
    public Response deleteCategory(@Parameter(
            description = "Category UUID",
            required = true) @PathParam("id") UUID id) {
        try {
            DirectoryCategory.deleteIfSafe(id);
            LOG.infof("Deleted directory category: id=%s", id);
            return Response.noContent().build();
        } catch (IllegalStateException e) {
            // Category not found or has dependencies
            if (e.getMessage().contains("not found")) {
                return Response.status(Response.Status.NOT_FOUND).entity(new ErrorResponse(e.getMessage())).build();
            } else {
                return Response.status(Response.Status.CONFLICT).entity(new ErrorResponse(e.getMessage())).build();
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to delete directory category: %s", id);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Failed to delete directory category")).build();
        }
    }

    /**
     * Simple error response record for API errors.
     */
    public record ErrorResponse(String error) {
    }
}
