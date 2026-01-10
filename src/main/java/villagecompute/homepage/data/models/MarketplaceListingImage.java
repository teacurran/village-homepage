package villagecompute.homepage.data.models;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Marketplace listing image entity.
 *
 * Stores metadata for uploaded listing images and their variants (thumbnail, list, full). Images are stored in R2
 * object storage via StorageGateway.
 *
 * Lifecycle: 1. Original uploaded → status='pending' 2. Job processes image → generates variants → status='processed'
 * 3. If processing fails → status='failed' (requires manual intervention)
 *
 * Policy References: - P1: GDPR compliance (CASCADE delete on listing) - P4: Indefinite retention in R2 (cleanup only
 * on listing removal/expiration) - P12: Image processing uses BULK queue (no semaphore limits like SCREENSHOT queue)
 */
@Entity
@Table(
        name = "marketplace_listing_images")
public class MarketplaceListingImage extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(
            name = "listing_id",
            nullable = false)
    public UUID listingId;

    @Column(
            name = "storage_key",
            nullable = false)
    public String storageKey;

    @Column(
            nullable = false)
    public String variant; // original, thumbnail, list, full

    @Column(
            name = "original_filename")
    public String originalFilename;

    @Column(
            name = "content_type",
            nullable = false)
    public String contentType;

    @Column(
            name = "size_bytes",
            nullable = false)
    public Long sizeBytes;

    @Column(
            name = "display_order")
    public Integer displayOrder;

    @Column(
            nullable = false)
    public String status; // pending, processed, failed

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt;

    // Static finders (Panache ActiveRecord pattern)

    /**
     * Find all images for a listing, ordered by display order.
     *
     * @param listingId
     *            the listing UUID
     * @return list of images ordered by display_order ASC
     */
    public static List<MarketplaceListingImage> findByListingId(UUID listingId) {
        return find("listingId = ?1 ORDER BY displayOrder ASC, variant ASC", listingId).list();
    }

    /**
     * Count non-failed images for a listing (for 12-image limit enforcement).
     *
     * @param listingId
     *            the listing UUID
     * @return count of images with status != 'failed'
     */
    public static long countByListingId(UUID listingId) {
        return count("listingId = ?1 AND status != 'failed'", listingId);
    }

    /**
     * Count original images for a listing (each original spawns 3 variants).
     *
     * @param listingId
     *            the listing UUID
     * @return count of original images (not variants)
     */
    public static long countOriginalsByListingId(UUID listingId) {
        return count("listingId = ?1 AND variant = 'original' AND status != 'failed'", listingId);
    }

    /**
     * Find specific variant for a listing.
     *
     * @param listingId
     *            the listing UUID
     * @param variant
     *            the variant name (original, thumbnail, list, full)
     * @return list of matching images
     */
    public static List<MarketplaceListingImage> findByListingIdAndVariant(UUID listingId, String variant) {
        return find("listingId = ?1 AND variant = ?2", listingId, variant).list();
    }

    /**
     * Find image by ID with validation.
     *
     * @param imageId
     *            the image UUID
     * @return optional containing the image if found
     */
    public static Optional<MarketplaceListingImage> findByIdOptional(UUID imageId) {
        return find("id = ?1", imageId).firstResultOptional();
    }

    /**
     * Delete all images for a listing (used by cleanup jobs).
     *
     * @param listingId
     *            the listing UUID
     * @return number of images deleted
     */
    public static long deleteByListingId(UUID listingId) {
        return delete("listingId = ?1", listingId);
    }

    /**
     * Find all images for a specific display order position. Used to find all variants (original, thumbnail, list,
     * full) for a single uploaded image.
     *
     * @param listingId
     *            the listing UUID
     * @param displayOrder
     *            the display order position
     * @return list of images (typically 4: original + 3 variants)
     */
    public static List<MarketplaceListingImage> findByListingIdAndDisplayOrder(UUID listingId, int displayOrder) {
        return find("listingId = ?1 AND displayOrder = ?2", listingId, displayOrder).list();
    }

    /**
     * Find pending images that need processing.
     *
     * @return list of images with status='pending'
     */
    public static List<MarketplaceListingImage> findPending() {
        return find("status = 'pending' ORDER BY created_at ASC").list();
    }

    /**
     * Find failed images that need manual review.
     *
     * @return list of images with status='failed'
     */
    public static List<MarketplaceListingImage> findFailed() {
        return find("status = 'failed' ORDER BY created_at DESC").list();
    }
}
