package villagecompute.homepage.data.models;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Version history for Good Sites directory screenshots.
 *
 * <p>
 * Implements Policy P4: Indefinite retention with full version history. Each screenshot capture (initial or recapture)
 * creates a new version record. Old versions are never automatically deleted.
 * </p>
 *
 * <p>
 * <b>Storage Pattern:</b>
 * <ul>
 * <li>Object keys: {site-id}/v{version}/{variant}.webp</li>
 * <li>Variants: thumbnail (320x200), full (1280x800)</li>
 * <li>Retention: indefinite (tagged in R2 metadata)</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>Version Increments:</b>
 * <ul>
 * <li>Initial capture: version 1</li>
 * <li>Admin recapture: version increments (v1 → v2 → v3 ...)</li>
 * <li>Failed captures still get version records for audit trail</li>
 * </ul>
 * </p>
 *
 * @see DirectorySite
 */
@Entity
@Table(
        name = "directory_screenshot_versions")
public class DirectoryScreenshotVersion extends PanacheEntityBase {

    @Id
    @GeneratedValue
    @Column(
            nullable = false)
    public UUID id;

    /**
     * Foreign key to directory_sites. CASCADE DELETE: deleting a site deletes all its screenshot versions.
     */
    @Column(
            name = "site_id",
            nullable = false)
    public UUID siteId;

    /**
     * Version number for this site's screenshot history.
     *
     * <p>
     * Monotonically increasing per site. Starts at 1 for initial capture.
     * </p>
     */
    @Column(
            nullable = false)
    public int version;

    /**
     * R2 object key for 320x200 thumbnail variant.
     *
     * <p>
     * Format: {site-id}/v{version}/thumbnail.webp
     * </p>
     *
     * <p>
     * Empty string if capture failed (status != success).
     * </p>
     */
    @Column(
            name = "thumbnail_storage_key",
            nullable = false)
    public String thumbnailStorageKey;

    /**
     * R2 object key for 1280x800 full variant.
     *
     * <p>
     * Format: {site-id}/v{version}/full.webp
     * </p>
     *
     * <p>
     * Empty string if capture failed (status != success).
     * </p>
     */
    @Column(
            name = "full_storage_key",
            nullable = false)
    public String fullStorageKey;

    /**
     * Timestamp when screenshot capture completed (success or failure).
     */
    @Column(
            name = "captured_at",
            nullable = false)
    public Instant capturedAt;

    /**
     * Duration of capture operation in milliseconds.
     *
     * <p>
     * Includes browser launch, page load, and screenshot generation. Used for performance monitoring.
     * </p>
     */
    @Column(
            name = "capture_duration_ms")
    public Integer captureDurationMs;

    /**
     * Capture status.
     *
     * <ul>
     * <li>success: Screenshot captured successfully, uploaded to R2</li>
     * <li>failed: Capture failed due to error (network, SSL, etc.)</li>
     * <li>timeout: Page load or browser exceeded 30-second timeout</li>
     * </ul>
     */
    @Column(
            nullable = false)
    public String status; // success, failed, timeout

    /**
     * Error message if status is 'failed' or 'timeout'. Null for successful captures.
     */
    @Column(
            name = "error_message",
            columnDefinition = "TEXT")
    public String errorMessage;

    /**
     * Record creation timestamp.
     */
    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt;

    /**
     * Gets the latest version number for a site, or 0 if no versions exist.
     *
     * <p>
     * Used to determine next version number before creating a new capture.
     * </p>
     *
     * @param siteId
     *            Site UUID
     * @return Latest version number (or 0 if site has no captures)
     */
    public static int getLatestVersion(UUID siteId) {
        return find("siteId = ?1 ORDER BY version DESC", siteId).firstResultOptional()
                .map(v -> ((DirectoryScreenshotVersion) v).version).orElse(0);
    }

    /**
     * Finds all screenshot versions for a site, newest first.
     *
     * @param siteId
     *            Site UUID
     * @return List of all versions (empty if none)
     */
    public static List<DirectoryScreenshotVersion> findBySiteId(UUID siteId) {
        return find("siteId = ?1 ORDER BY version DESC", siteId).list();
    }

    /**
     * Finds a specific version for a site.
     *
     * @param siteId
     *            Site UUID
     * @param version
     *            Version number
     * @return Optional version record
     */
    public static Optional<DirectoryScreenshotVersion> findByVersion(UUID siteId, int version) {
        return find("siteId = ?1 AND version = ?2", siteId, version).firstResultOptional();
    }

    /**
     * Finds all failed captures (status = 'failed' or 'timeout') for retry analysis.
     *
     * @return List of failed captures, newest first
     */
    public static List<DirectoryScreenshotVersion> findFailedCaptures() {
        return find("status IN ('failed', 'timeout') ORDER BY capturedAt DESC").list();
    }

    /**
     * Counts total captures for a site (all statuses).
     *
     * @param siteId
     *            Site UUID
     * @return Total capture count
     */
    public static long countBySiteId(UUID siteId) {
        return count("siteId = ?1", siteId);
    }

    /**
     * Counts successful captures for a site.
     *
     * @param siteId
     *            Site UUID
     * @return Successful capture count
     */
    public static long countSuccessfulCaptures(UUID siteId) {
        return count("siteId = ?1 AND status = 'success'", siteId);
    }
}
