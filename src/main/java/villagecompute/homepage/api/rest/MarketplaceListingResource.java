package villagecompute.homepage.api.rest;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
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
import villagecompute.homepage.api.types.ContactInfoType;
import villagecompute.homepage.api.types.CreateListingRequestType;
import villagecompute.homepage.api.types.ListingType;
import villagecompute.homepage.api.types.UpdateListingRequestType;
import villagecompute.homepage.data.models.MarketplaceCategory;
import villagecompute.homepage.data.models.MarketplaceListing;

import java.util.List;
import java.util.UUID;

/**
 * User-facing REST endpoints for marketplace listing management (Features F12.4-F12.7).
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>{@code GET /api/marketplace/listings} – list current user's own listings (all statuses)</li>
 * <li>{@code GET /api/marketplace/listings/{id}} – get single listing (owner or public if active)</li>
 * <li>{@code POST /api/marketplace/listings} – create new listing (authenticated users only)</li>
 * <li>{@code PATCH /api/marketplace/listings/{id}} – update listing (owner only, draft listings only)</li>
 * <li>{@code DELETE /api/marketplace/listings/{id}} – soft-delete listing (owner only)</li>
 * <li>{@code POST /api/marketplace/listings/{id}/publish} – activate draft listing (owner only)</li>
 * </ul>
 *
 * <p>
 * <b>Security:</b> All endpoints require authentication via {@code @RolesAllowed}. Users can only view/modify their own
 * listings (ownership enforced via user_id check). Public active listings visible to all users via browse/search
 * endpoints (future).
 *
 * <p>
 * <b>Validation:</b> JAX-RS {@code @Valid} annotation triggers constraint validation on create/update request types.
 * Draft listings skip validation, but active listings enforce title/description length, price >= 0, etc.
 *
 * <p>
 * <b>Status Lifecycle:</b>
 * <ul>
 * <li>Create with status = "draft": Saved as draft, no validation, no expiration</li>
 * <li>Create with status = "active" (or omitted): Activated immediately if category has $0 posting fee, otherwise
 * status forced to "pending_payment"</li>
 * <li>PATCH: Only draft listings can be updated. Active/expired/removed listings return 409 Conflict</li>
 * <li>POST /publish: Transitions draft → active (or pending_payment if category requires payment)</li>
 * <li>DELETE: Soft-deletes by setting status = "removed"</li>
 * </ul>
 *
 * <p>
 * <b>Error Codes:</b>
 * <ul>
 * <li>400 Bad Request – Validation failure (title too short, invalid email, etc.)</li>
 * <li>403 Forbidden – User not authenticated or not listing owner</li>
 * <li>404 Not Found – Listing ID not found</li>
 * <li>409 Conflict – Cannot update non-draft listing, category/city not found, etc.</li>
 * <li>500 Internal Server Error – Database error, unexpected exception</li>
 * </ul>
 */
@Path("/api/marketplace/listings")
@RolesAllowed({"user", "super_admin", "support", "ops"})
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(
        name = "Marketplace",
        description = "Marketplace listing management endpoints")
public class MarketplaceListingResource {

    private static final Logger LOG = Logger.getLogger(MarketplaceListingResource.class);

    @Inject
    SecurityIdentity securityIdentity;

    /**
     * Lists current user's own marketplace listings (all statuses).
     *
     * <p>
     * Returns listings in all statuses (draft, active, expired, removed) ordered by creation date descending. Used for
     * "My Listings" dashboard showing user's own listings.
     *
     * @return list of user's listings
     */
    @GET
    @Operation(
            summary = "List current user's marketplace listings",
            description = "Returns all listings created by the authenticated user across all statuses (draft, active, expired, removed)")
    @SecurityRequirement(
            name = "bearerAuth")
    @APIResponses({@APIResponse(
            responseCode = "200",
            description = "List of user's listings retrieved successfully",
            content = @Content(
                    schema = @Schema(
                            implementation = ListingType.class))),
            @APIResponse(
                    responseCode = "401",
                    description = "User not authenticated",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "403",
                    description = "User does not have required role",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "429",
                    description = "Rate limit exceeded",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "500",
                    description = "Internal server error",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class)))})
    public Response listUserListings() {
        UUID userId = getCurrentUserId();

        List<MarketplaceListing> listings = MarketplaceListing.findByUserId(userId);
        List<ListingType> response = ListingType.fromEntities(listings);

        LOG.infof("Returned %d listings for user %s", response.size(), userId);
        return Response.ok(response).build();
    }

    /**
     * Retrieves a single marketplace listing by ID.
     *
     * <p>
     * Returns listing if user is owner OR listing is active (public visibility). Draft/expired/removed listings only
     * visible to owner.
     *
     * @param id
     *            the listing UUID
     * @return listing details or 404 if not found / 403 if not owner and not public
     */
    @GET
    @Path("/{id}")
    @Operation(
            summary = "Get a single marketplace listing",
            description = "Returns listing details if user is the owner or if the listing is active (publicly visible)")
    @SecurityRequirement(
            name = "bearerAuth")
    @APIResponses({@APIResponse(
            responseCode = "200",
            description = "Listing retrieved successfully",
            content = @Content(
                    schema = @Schema(
                            implementation = ListingType.class))),
            @APIResponse(
                    responseCode = "401",
                    description = "User not authenticated",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "403",
                    description = "Not authorized to view this listing",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "404",
                    description = "Listing not found",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "429",
                    description = "Rate limit exceeded",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "500",
                    description = "Internal server error",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class)))})
    public Response getListing(@Parameter(
            description = "The listing UUID",
            required = true) @PathParam("id") UUID id) {
        MarketplaceListing listing = MarketplaceListing.findById(id);
        if (listing == null) {
            return Response.status(Response.Status.NOT_FOUND).entity(new ErrorResponse("Listing not found: " + id))
                    .build();
        }

        UUID userId = getCurrentUserId();

        // Allow owner to view any status, others can only see active listings
        if (!listing.userId.equals(userId) && !"active".equals(listing.status)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse("Not authorized to view this listing")).build();
        }

        return Response.ok(ListingType.fromEntity(listing)).build();
    }

    /**
     * Creates a new marketplace listing (authenticated users only).
     *
     * <p>
     * Supports both draft and active listing creation:
     * <ul>
     * <li>Draft: Saved with status = "draft", no validation, no expiration</li>
     * <li>Active: Validated, activated immediately if category has $0 posting fee, otherwise status = "pending_payment"
     * </li>
     * </ul>
     *
     * <p>
     * Auto-generates masked email for contact relay. Sets user_id from authenticated principal.
     *
     * @param request
     *            creation payload with required fields
     * @return created listing with 201 Created status
     */
    @POST
    @Operation(
            summary = "Create a new marketplace listing",
            description = "Creates a new listing as draft or active. Active listings may transition to pending_payment if category requires posting fee.")
    @SecurityRequirement(
            name = "bearerAuth")
    @APIResponses({@APIResponse(
            responseCode = "201",
            description = "Listing created successfully",
            content = @Content(
                    schema = @Schema(
                            implementation = ListingType.class))),
            @APIResponse(
                    responseCode = "400",
                    description = "Invalid request (validation failure, missing required fields)",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "401",
                    description = "User not authenticated",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "403",
                    description = "User does not have required role",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "409",
                    description = "Conflict (category not found, city not found)",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "429",
                    description = "Rate limit exceeded",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "500",
                    description = "Internal server error",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class)))})
    public Response createListing(@Parameter(
            description = "Listing creation request",
            required = true) @Valid CreateListingRequestType request) {
        if (request == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse("Request body required"))
                    .build();
        }

        try {
            UUID userId = getCurrentUserId();

            // Validate category exists
            MarketplaceCategory category = MarketplaceCategory.findById(request.categoryId());
            if (category == null) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(new ErrorResponse("Category not found: " + request.categoryId())).build();
            }

            // TODO: Validate geo_city_id exists (requires geo_cities table query)
            // For now, accept any geo_city_id

            // Determine effective status
            String effectiveStatus = request.getEffectiveStatus();

            // If category has posting fee and status is active, force to pending_payment
            if ("active".equals(effectiveStatus)
                    && category.feeSchedule.postingFee().compareTo(java.math.BigDecimal.ZERO) > 0) {
                effectiveStatus = "pending_payment";
                LOG.infof("Category %s requires posting fee, setting status to pending_payment", category.name);
            }

            // Create listing entity
            MarketplaceListing listing = new MarketplaceListing();
            listing.userId = userId;
            listing.categoryId = request.categoryId();
            listing.geoCityId = request.geoCityId();
            listing.title = request.title();
            listing.description = request.description();
            listing.price = request.price();
            listing.contactInfo = ContactInfoType.forListing(request.contactEmail(), request.contactPhone());
            listing.status = effectiveStatus;
            listing.reminderSent = false;

            // Create in database (will auto-set expires_at if status = active)
            MarketplaceListing created = MarketplaceListing.create(listing);

            LOG.infof("Created marketplace listing: id=%s, userId=%s, categoryId=%s, status=%s, expiresAt=%s",
                    created.id, created.userId, created.categoryId, created.status, created.expiresAt);

            return Response.status(Response.Status.CREATED).entity(ListingType.fromEntity(created)).build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to create listing: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Failed to create listing: " + e.getMessage())).build();
        }
    }

    /**
     * Updates an existing marketplace listing (owner only, draft listings only).
     *
     * <p>
     * Only draft listings can be updated. Active, expired, or removed listings return 409 Conflict. Supports partial
     * updates (only non-null fields are updated).
     *
     * @param id
     *            the listing UUID
     * @param request
     *            update payload with optional fields
     * @return updated listing or 404/403/409 error
     */
    @PATCH
    @Path("/{id}")
    @Operation(
            summary = "Update a marketplace listing",
            description = "Updates an existing draft listing. Only draft listings can be modified. Supports partial updates.")
    @SecurityRequirement(
            name = "bearerAuth")
    @APIResponses({@APIResponse(
            responseCode = "200",
            description = "Listing updated successfully",
            content = @Content(
                    schema = @Schema(
                            implementation = ListingType.class))),
            @APIResponse(
                    responseCode = "400",
                    description = "Invalid request (validation failure, missing request body)",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "401",
                    description = "User not authenticated",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "403",
                    description = "Not authorized to update this listing (not owner)",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "404",
                    description = "Listing not found",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "409",
                    description = "Conflict (cannot update non-draft listing, category not found)",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "429",
                    description = "Rate limit exceeded",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "500",
                    description = "Internal server error",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class)))})
    public Response updateListing(@Parameter(
            description = "The listing UUID",
            required = true) @PathParam("id") UUID id,
            @Parameter(
                    description = "Listing update request with optional fields",
                    required = true) @Valid UpdateListingRequestType request) {
        if (request == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse("Request body required"))
                    .build();
        }

        try {
            MarketplaceListing listing = MarketplaceListing.findById(id);
            if (listing == null) {
                return Response.status(Response.Status.NOT_FOUND).entity(new ErrorResponse("Listing not found: " + id))
                        .build();
            }

            UUID userId = getCurrentUserId();

            // Enforce ownership
            if (!listing.userId.equals(userId)) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(new ErrorResponse("Not authorized to update this listing")).build();
            }

            // Only draft listings can be updated
            if (!"draft".equals(listing.status)) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(new ErrorResponse("Cannot update non-draft listing. Current status: " + listing.status))
                        .build();
            }

            // Apply partial updates
            if (request.categoryId() != null) {
                // Validate category exists
                MarketplaceCategory category = MarketplaceCategory.findById(request.categoryId());
                if (category == null) {
                    return Response.status(Response.Status.CONFLICT)
                            .entity(new ErrorResponse("Category not found: " + request.categoryId())).build();
                }
                listing.categoryId = request.categoryId();
            }

            if (request.geoCityId() != null) {
                // TODO: Validate geo_city_id exists
                listing.geoCityId = request.geoCityId();
            }

            if (request.title() != null) {
                listing.title = request.title();
            }

            if (request.description() != null) {
                listing.description = request.description();
            }

            if (request.price() != null) {
                listing.price = request.price();
            }

            if (request.contactEmail() != null || request.contactPhone() != null) {
                // Update contact info, preserving existing masked email
                String newEmail = request.contactEmail() != null ? request.contactEmail() : listing.contactInfo.email();
                String newPhone = request.contactPhone() != null ? request.contactPhone() : listing.contactInfo.phone();
                listing.contactInfo = ContactInfoType.of(newEmail, newPhone, listing.contactInfo.maskedEmail());
            }

            // Update in database
            MarketplaceListing.update(listing);

            LOG.infof("Updated marketplace listing: id=%s, userId=%s", listing.id, listing.userId);

            return Response.ok(ListingType.fromEntity(listing)).build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to update listing %s: %s", id, e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Failed to update listing: " + e.getMessage())).build();
        }
    }

    /**
     * Soft-deletes a marketplace listing (owner only).
     *
     * <p>
     * Sets status = "removed" rather than hard-delete to preserve audit trail. Removed listings no longer visible in
     * public searches or user's listing list (can be filtered in future admin view).
     *
     * @param id
     *            the listing UUID
     * @return 204 No Content on success or 404/403 error
     */
    @DELETE
    @Path("/{id}")
    @Operation(
            summary = "Delete a marketplace listing",
            description = "Soft-deletes a listing by setting status to 'removed'. Preserves data for audit trail.")
    @SecurityRequirement(
            name = "bearerAuth")
    @APIResponses({@APIResponse(
            responseCode = "204",
            description = "Listing deleted successfully"),
            @APIResponse(
                    responseCode = "401",
                    description = "User not authenticated",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "403",
                    description = "Not authorized to delete this listing (not owner)",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "404",
                    description = "Listing not found",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "429",
                    description = "Rate limit exceeded",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "500",
                    description = "Internal server error",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class)))})
    public Response deleteListing(@Parameter(
            description = "The listing UUID",
            required = true) @PathParam("id") UUID id) {
        try {
            MarketplaceListing listing = MarketplaceListing.findById(id);
            if (listing == null) {
                return Response.status(Response.Status.NOT_FOUND).entity(new ErrorResponse("Listing not found: " + id))
                        .build();
            }

            UUID userId = getCurrentUserId();

            // Enforce ownership
            if (!listing.userId.equals(userId)) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(new ErrorResponse("Not authorized to delete this listing")).build();
            }

            // Soft-delete
            MarketplaceListing.softDelete(id);

            LOG.infof("Soft-deleted marketplace listing: id=%s, userId=%s", id, userId);

            return Response.noContent().build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to delete listing %s: %s", id, e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Failed to delete listing: " + e.getMessage())).build();
        }
    }

    /**
     * Activates a draft listing (transitions draft → active or pending_payment).
     *
     * <p>
     * Validates all required fields and enforces payment if category requires posting fee. Sets expires_at to NOW() +
     * 30 days for active listings.
     *
     * @param id
     *            the listing UUID
     * @return updated listing with status = active or pending_payment
     */
    @POST
    @Path("/{id}/publish")
    @Operation(
            summary = "Publish a draft listing",
            description = "Activates a draft listing. Transitions to 'active' if category has no posting fee, or 'pending_payment' if payment required.")
    @SecurityRequirement(
            name = "bearerAuth")
    @APIResponses({@APIResponse(
            responseCode = "200",
            description = "Listing published successfully",
            content = @Content(
                    schema = @Schema(
                            implementation = ListingType.class))),
            @APIResponse(
                    responseCode = "401",
                    description = "User not authenticated",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "403",
                    description = "Not authorized to publish this listing (not owner)",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "404",
                    description = "Listing not found",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "409",
                    description = "Conflict (cannot publish non-draft listing, category not found)",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "429",
                    description = "Rate limit exceeded",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class))),
            @APIResponse(
                    responseCode = "500",
                    description = "Internal server error",
                    content = @Content(
                            schema = @Schema(
                                    implementation = ErrorResponse.class)))})
    public Response publishListing(@Parameter(
            description = "The listing UUID",
            required = true) @PathParam("id") UUID id) {
        try {
            MarketplaceListing listing = MarketplaceListing.findById(id);
            if (listing == null) {
                return Response.status(Response.Status.NOT_FOUND).entity(new ErrorResponse("Listing not found: " + id))
                        .build();
            }

            UUID userId = getCurrentUserId();

            // Enforce ownership
            if (!listing.userId.equals(userId)) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(new ErrorResponse("Not authorized to publish this listing")).build();
            }

            // Only draft listings can be published
            if (!"draft".equals(listing.status)) {
                return Response.status(Response.Status.CONFLICT).entity(
                        new ErrorResponse("Cannot publish non-draft listing. Current status: " + listing.status))
                        .build();
            }

            // Validate category exists and check fee schedule
            MarketplaceCategory category = MarketplaceCategory.findById(listing.categoryId);
            if (category == null) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(new ErrorResponse("Category not found: " + listing.categoryId)).build();
            }

            // Determine target status
            String targetStatus = "active";
            if (category.feeSchedule.postingFee().compareTo(java.math.BigDecimal.ZERO) > 0) {
                targetStatus = "pending_payment";
                LOG.infof("Category %s requires posting fee, setting status to pending_payment", category.name);
            }

            // Transition status
            listing.status = targetStatus;
            MarketplaceListing.update(listing);

            LOG.infof("Published marketplace listing: id=%s, userId=%s, status=%s, expiresAt=%s", listing.id,
                    listing.userId, listing.status, listing.expiresAt);

            return Response.ok(ListingType.fromEntity(listing)).build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to publish listing %s: %s", id, e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Failed to publish listing: " + e.getMessage())).build();
        }
    }

    /**
     * Extracts current authenticated user ID from SecurityIdentity.
     *
     * <p>
     * In production, this will extract user_id from authenticated OIDC principal. For now, uses a placeholder UUID
     * based on principal name.
     *
     * @return current user UUID
     */
    private UUID getCurrentUserId() {
        // TODO: Extract real user_id from User entity via SecurityIdentity
        // For now, use a placeholder UUID for testing
        String principalName = securityIdentity.getPrincipal().getName();

        // Try to parse as UUID, otherwise generate deterministic UUID from principal name
        try {
            return UUID.fromString(principalName);
        } catch (IllegalArgumentException e) {
            // Generate deterministic UUID from principal name for testing
            return UUID.nameUUIDFromBytes(principalName.getBytes());
        }
    }

    /**
     * Error response record for API error messages.
     *
     * @param message
     *            error description
     */
    public record ErrorResponse(String message) {
    }
}
