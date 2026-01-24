package villagecompute.homepage.api.rest;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
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
import org.jboss.resteasy.reactive.MultipartForm;
import villagecompute.homepage.api.types.ImageUploadForm;
import villagecompute.homepage.api.types.ListingImageType;
import villagecompute.homepage.api.types.SignedUrlType;
import villagecompute.homepage.api.types.StorageUploadResultType;
import villagecompute.homepage.data.models.MarketplaceListing;
import villagecompute.homepage.data.models.MarketplaceListingImage;
import villagecompute.homepage.jobs.JobType;
import villagecompute.homepage.services.DelayedJobService;
import villagecompute.homepage.services.StorageGateway;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST endpoints for marketplace listing image management.
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>{@code POST /api/marketplace/listings/{listingId}/images} – upload new image</li>
 * <li>{@code GET /api/marketplace/listings/{listingId}/images} – list images with signed URLs</li>
 * <li>{@code DELETE /api/marketplace/listings/{listingId}/images/{imageId}} – delete image</li>
 * <li>{@code PUT /api/marketplace/listings/{listingId}/images/reorder} – reorder images (future)</li>
 * </ul>
 *
 * <p>
 * <b>Upload Flow:</b>
 * <ol>
 * <li>Client POSTs multipart form with image file</li>
 * <li>API validates: listing ownership, image count limit (12), file size (max 10MB), content type</li>
 * <li>API uploads original to R2 via StorageGateway</li>
 * <li>API creates pending database record</li>
 * <li>API enqueues LISTING_IMAGE_PROCESSING job for background variant generation</li>
 * <li>Client receives 202 Accepted with image ID and processing status</li>
 * <li>Job handler generates thumbnail/list/full variants asynchronously</li>
 * </ol>
 *
 * <p>
 * <b>Image Limits & Validation:</b>
 * <ul>
 * <li>Maximum 12 images per listing (enforced at upload time)</li>
 * <li>Maximum file size: 10 MB</li>
 * <li>Allowed types: image/jpeg, image/jpg, image/png, image/webp, image/gif</li>
 * <li>Variants generated: thumbnail (150x150), list (300x225), full (1200x900)</li>
 * </ul>
 *
 * <p>
 * <b>Signed URLs:</b> All image URLs returned by GET endpoint are signed with 24-hour TTL. Clients should request fresh
 * URLs on each page load (do not cache URLs client-side).
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P1: GDPR compliance - images deleted when listing soft-deleted (CASCADE)</li>
 * <li>P4: Indefinite R2 retention until listing removed/expired</li>
 * <li>P12: Image processing uses BULK queue (no semaphore limits)</li>
 * </ul>
 */
@Path("/api/marketplace/listings/{listingId}/images")
@RolesAllowed({"user", "super_admin", "support", "ops"})
@Produces(MediaType.APPLICATION_JSON)
@Tag(
        name = "Marketplace",
        description = "Marketplace listing search and management operations")
public class ListingImageResource {

    private static final Logger LOG = Logger.getLogger(ListingImageResource.class);

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/jpg", "image/png",
            "image/webp", "image/gif");
    private static final int MAX_IMAGES_PER_LISTING = 12;
    private static final int SIGNED_URL_TTL_MINUTES = 1440; // 24 hours

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    StorageGateway storageGateway;

    @Inject
    DelayedJobService jobService;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Upload new image for a listing.
     *
     * <p>
     * Validates ownership, count limits, file size/type. Uploads original to R2 and enqueues processing job for variant
     * generation.
     *
     * @param listingId
     *            the listing UUID
     * @param form
     *            multipart form with image file
     * @return 202 Accepted with image ID and processing status
     */
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Transactional
    @Operation(
            summary = "Upload listing image",
            description = "Upload a new image for a marketplace listing. Maximum 12 images per listing, 10MB file size limit.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "202",
                    description = "Image uploaded and queued for processing",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "400",
                            description = "Invalid request (file too large, invalid type, limit exceeded)",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "401",
                            description = "Authentication required",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "403",
                            description = "Not authorized to upload images for this listing",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "404",
                            description = "Listing not found",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "409",
                            description = "Cannot upload to removed/expired listing",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "500",
                            description = "Upload failed",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    @SecurityRequirement(
            name = "bearerAuth")
    public Response uploadImage(@Parameter(
            description = "Listing UUID",
            required = true) @PathParam("listingId") UUID listingId, @MultipartForm ImageUploadForm form) {

        UUID userId = getCurrentUserId();

        // 1. Validate listing ownership and status
        MarketplaceListing listing = MarketplaceListing.findById(listingId);
        if (listing == null) {
            LOG.warnf("Listing not found: listingId=%s", listingId);
            return Response.status(404).entity(Map.of("error", "Listing not found")).build();
        }

        if (!listing.userId.equals(userId)) {
            LOG.warnf("User %s attempted to upload image for listing %s owned by %s", userId, listingId,
                    listing.userId);
            return Response.status(403).entity(Map.of("error", "Not authorized")).build();
        }

        if ("removed".equals(listing.status) || "expired".equals(listing.status)) {
            LOG.warnf("Cannot upload image to %s listing: listingId=%s", listing.status, listingId);
            return Response.status(409).entity(Map.of("error", "Cannot upload to " + listing.status + " listing"))
                    .build();
        }

        // 2. Check image count limit (count originals only, not variants)
        long currentImageCount = MarketplaceListingImage.countOriginalsByListingId(listingId);
        if (currentImageCount >= MAX_IMAGES_PER_LISTING) {
            LOG.warnf("Image limit exceeded for listing %s: current=%d, max=%d", listingId, currentImageCount,
                    MAX_IMAGES_PER_LISTING);
            meterRegistry.counter("marketplace.images.upload.rejected.total", "reason", "limit_exceeded").increment();
            return Response.status(400).entity(Map.of("error",
                    "Maximum " + MAX_IMAGES_PER_LISTING + " images per listing", "current_count", currentImageCount))
                    .build();
        }

        // 3. Validate file
        byte[] fileBytes;
        try {
            fileBytes = form.file.readAllBytes();
        } catch (IOException e) {
            LOG.errorf(e, "Failed to read uploaded file for listing %s", listingId);
            meterRegistry.counter("marketplace.images.upload.rejected.total", "reason", "read_error").increment();
            return Response.status(400).entity(Map.of("error", "Failed to read file")).build();
        }

        if (fileBytes.length > MAX_FILE_SIZE) {
            LOG.warnf("File too large: %d bytes (max %d)", fileBytes.length, MAX_FILE_SIZE);
            meterRegistry.counter("marketplace.images.upload.rejected.total", "reason", "file_too_large").increment();
            return Response.status(400)
                    .entity(Map.of("error", "File too large (max 10MB)", "file_size", fileBytes.length)).build();
        }

        String contentType = form.contentType != null ? form.contentType : "image/jpeg";
        if (!ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            LOG.warnf("Invalid content type: %s", contentType);
            meterRegistry.counter("marketplace.images.upload.rejected.total", "reason", "invalid_type").increment();
            return Response.status(400)
                    .entity(Map.of("error", "Invalid file type", "allowed_types", ALLOWED_CONTENT_TYPES)).build();
        }

        // 4. Upload original to R2
        StorageUploadResultType uploadResult;
        try {
            uploadResult = storageGateway.upload(StorageGateway.BucketType.LISTINGS, listingId.toString(), "original",
                    fileBytes, contentType);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to upload image to R2 for listing %s", listingId);
            meterRegistry.counter("marketplace.images.upload.errors.total", "error_type", e.getClass().getSimpleName())
                    .increment();
            return Response.status(500).entity(Map.of("error", "Upload failed")).build();
        }

        // 5. Create pending image record
        int displayOrder = form.displayOrder != null ? form.displayOrder : (int) currentImageCount + 1;
        MarketplaceListingImage image = new MarketplaceListingImage();
        image.listingId = listingId;
        image.storageKey = uploadResult.objectKey();
        image.variant = "original";
        image.originalFilename = form.fileName != null ? form.fileName : "image.jpg";
        image.contentType = uploadResult.contentType();
        image.sizeBytes = uploadResult.sizeBytes();
        image.displayOrder = displayOrder;
        image.status = "pending";
        image.createdAt = Instant.now();
        image.persist();

        LOG.infof("Created pending image record: imageId=%s, listingId=%s, storageKey=%s", image.id, listingId,
                uploadResult.objectKey());

        // 6. Enqueue processing job
        Map<String, Object> payload = Map.of("imageId", image.id.toString(), "listingId", listingId.toString(),
                "originalKey", uploadResult.objectKey(), "originalFilename", image.originalFilename, "displayOrder",
                displayOrder);
        jobService.enqueue(JobType.LISTING_IMAGE_PROCESSING, payload);

        LOG.infof("Enqueued image processing job: imageId=%s, listingId=%s", image.id, listingId);

        // 7. Export metrics
        meterRegistry.counter("marketplace.images.uploaded.total", "listing_id", listingId.toString()).increment();

        // 8. Return response
        return Response.status(202)
                .entity(Map.of("image_id", image.id, "listing_id", listingId, "status", "processing", "display_order",
                        displayOrder, "original_filename", image.originalFilename, "size_bytes", image.sizeBytes))
                .build();
    }

    /**
     * List all images for a listing with signed URLs.
     *
     * <p>
     * Returns images grouped by display order with signed URLs for all variants (thumbnail, list, full). URLs have
     * 24-hour TTL.
     *
     * @param listingId
     *            the listing UUID
     * @return list of images with signed URLs
     */
    @GET
    @Operation(
            summary = "List listing images",
            description = "Get all images for a listing with signed URLs for all variants. URLs have 24-hour TTL.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Images returned successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = ListingImageType.class))),
                    @APIResponse(
                            responseCode = "401",
                            description = "Authentication required",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "404",
                            description = "Listing not found",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "500",
                            description = "Server error",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    @SecurityRequirement(
            name = "bearerAuth")
    public Response listImages(@Parameter(
            description = "Listing UUID",
            required = true) @PathParam("listingId") UUID listingId) {
        UUID userId = getCurrentUserId();

        // 1. Validate listing exists (ownership check optional for read - could allow public)
        MarketplaceListing listing = MarketplaceListing.findById(listingId);
        if (listing == null) {
            return Response.status(404).entity(Map.of("error", "Listing not found")).build();
        }

        // 2. Get all images for listing
        List<MarketplaceListingImage> images = MarketplaceListingImage.findByListingId(listingId);

        if (images.isEmpty()) {
            return Response.ok(Collections.emptyList()).build();
        }

        // 3. Group by display order and generate signed URLs
        Map<Integer, List<MarketplaceListingImage>> imagesByOrder = images.stream()
                .collect(Collectors.groupingBy(img -> img.displayOrder));

        List<ListingImageType> result = imagesByOrder.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    int displayOrder = entry.getKey();
                    List<MarketplaceListingImage> variants = entry.getValue();

                    // Find specific variants
                    MarketplaceListingImage original = findVariant(variants, "original");
                    MarketplaceListingImage thumbnail = findVariant(variants, "thumbnail");
                    MarketplaceListingImage list = findVariant(variants, "list");
                    MarketplaceListingImage full = findVariant(variants, "full");

                    // Generate signed URLs
                    String thumbnailUrl = thumbnail != null ? generateSignedUrl(thumbnail.storageKey) : null;
                    String listUrl = list != null ? generateSignedUrl(list.storageKey) : null;
                    String fullUrl = full != null ? generateSignedUrl(full.storageKey) : null;

                    // Use original for metadata
                    MarketplaceListingImage metadata = original != null ? original : variants.get(0);

                    return new ListingImageType(metadata.id, listingId, displayOrder, metadata.originalFilename,
                            metadata.status, metadata.createdAt, thumbnailUrl, listUrl, fullUrl);
                }).toList();

        LOG.infof("Returned %d image groups for listing %s", result.size(), listingId);

        return Response.ok(result).build();
    }

    /**
     * Delete an image and all its variants.
     *
     * @param listingId
     *            the listing UUID
     * @param imageId
     *            the image UUID (original variant ID)
     * @return 204 No Content on success
     */
    @DELETE
    @Path("/{imageId}")
    @Transactional
    @Operation(
            summary = "Delete listing image",
            description = "Delete an image and all its variants (thumbnail, list, full) from a listing.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "204",
                    description = "Image deleted successfully"),
                    @APIResponse(
                            responseCode = "400",
                            description = "Image does not belong to this listing",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "401",
                            description = "Authentication required",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "403",
                            description = "Not authorized to delete this image",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "404",
                            description = "Listing or image not found",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "500",
                            description = "Server error",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    @SecurityRequirement(
            name = "bearerAuth")
    public Response deleteImage(@Parameter(
            description = "Listing UUID",
            required = true) @PathParam("listingId") UUID listingId,
            @Parameter(
                    description = "Image UUID",
                    required = true) @PathParam("imageId") UUID imageId) {

        UUID userId = getCurrentUserId();

        // 1. Validate listing ownership
        MarketplaceListing listing = MarketplaceListing.findById(listingId);
        if (listing == null) {
            return Response.status(404).entity(Map.of("error", "Listing not found")).build();
        }

        if (!listing.userId.equals(userId)) {
            return Response.status(403).entity(Map.of("error", "Not authorized")).build();
        }

        // 2. Find the image to get its display order
        MarketplaceListingImage image = MarketplaceListingImage.findById(imageId);
        if (image == null) {
            return Response.status(404).entity(Map.of("error", "Image not found")).build();
        }

        if (!image.listingId.equals(listingId)) {
            return Response.status(400).entity(Map.of("error", "Image does not belong to this listing")).build();
        }

        // 3. Find all variants for this display order
        List<MarketplaceListingImage> variants = MarketplaceListingImage.findByListingIdAndDisplayOrder(listingId,
                image.displayOrder);

        LOG.infof("Deleting %d image variants for imageId=%s, listingId=%s", variants.size(), imageId, listingId);

        // 4. Delete from R2
        long totalBytesFreed = 0;
        for (MarketplaceListingImage variant : variants) {
            try {
                storageGateway.delete(StorageGateway.BucketType.LISTINGS, variant.storageKey);
                totalBytesFreed += variant.sizeBytes;
                LOG.debugf("Deleted from R2: %s", variant.storageKey);
            } catch (Exception e) {
                LOG.warnf(e, "Failed to delete image from R2: %s (continuing)", variant.storageKey);
            }
        }

        // 5. Delete from database
        List<UUID> variantIds = variants.stream().map(v -> v.id).toList();
        long deletedCount = MarketplaceListingImage.delete("id IN ?1", variantIds);

        LOG.infof("Deleted %d image records, freed %d bytes", deletedCount, totalBytesFreed);

        // 6. Export metrics
        meterRegistry.counter("marketplace.images.deleted.total", "listing_id", listingId.toString())
                .increment(deletedCount);

        meterRegistry.counter("marketplace.storage.bytes.freed", "bucket", "listings").increment(totalBytesFreed);

        return Response.noContent().build();
    }

    /**
     * Get current authenticated user ID.
     */
    private UUID getCurrentUserId() {
        return UUID.fromString(securityIdentity.getPrincipal().getName());
    }

    /**
     * Find specific variant from list.
     */
    private MarketplaceListingImage findVariant(List<MarketplaceListingImage> variants, String variantName) {
        return variants.stream().filter(v -> variantName.equals(v.variant)).findFirst().orElse(null);
    }

    /**
     * Generate signed URL for storage key.
     */
    private String generateSignedUrl(String storageKey) {
        try {
            SignedUrlType signedUrl = storageGateway.generateSignedUrl(StorageGateway.BucketType.LISTINGS, storageKey,
                    SIGNED_URL_TTL_MINUTES);
            return signedUrl.url();
        } catch (Exception e) {
            LOG.warnf(e, "Failed to generate signed URL for %s", storageKey);
            return null;
        }
    }
}
