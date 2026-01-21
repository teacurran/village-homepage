package villagecompute.homepage.data.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.common.constraint.NotNull;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jboss.logging.Logger;
import villagecompute.homepage.exceptions.ValidationException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * UserProfile entity implementing the Panache ActiveRecord pattern for public profile pages.
 *
 * <p>
 * Supports Policy P1 (GDPR/CCPA) with soft delete and P4 (Data Retention) for user-generated content. Users create
 * customizable profile pages at /u/{username} with templates, curated articles, and social links.
 *
 * <p>
 * <b>Schema Mapping:</b>
 * <ul>
 * <li>{@code id} (UUID, PK) - Primary identifier</li>
 * <li>{@code user_id} (UUID, FK) - Owner reference to users table (one-to-one)</li>
 * <li>{@code username} (TEXT) - Unique username (3-30 chars, alphanumeric + underscore/dash)</li>
 * <li>{@code display_name} (TEXT) - Display name for profile header</li>
 * <li>{@code bio} (TEXT) - Profile bio/description</li>
 * <li>{@code avatar_url} (TEXT) - Profile avatar image URL</li>
 * <li>{@code location_text} (TEXT) - Location text (e.g., "Groton, Vermont")</li>
 * <li>{@code website_url} (TEXT) - Personal website URL</li>
 * <li>{@code social_links} (JSONB) - Social media links (Twitter, LinkedIn, GitHub, etc.)</li>
 * <li>{@code template} (TEXT) - Template type: public_homepage, your_times, your_report</li>
 * <li>{@code template_config} (JSONB) - Template configuration (colors, layout, slots)</li>
 * <li>{@code is_published} (BOOLEAN) - Published state (false = draft/404, true = live)</li>
 * <li>{@code view_count} (BIGINT) - Profile view count for analytics</li>
 * <li>{@code created_at} (TIMESTAMPTZ) - Record creation timestamp</li>
 * <li>{@code updated_at} (TIMESTAMPTZ) - Last modification timestamp</li>
 * <li>{@code deleted_at} (TIMESTAMPTZ) - Soft deletion timestamp (90-day retention per P1)</li>
 * </ul>
 *
 * <p>
 * <b>Username Validation Rules:</b>
 * <ul>
 * <li>Length: 3-30 characters</li>
 * <li>Characters: a-z, 0-9, underscore, dash (no spaces)</li>
 * <li>Case-insensitive (stored lowercase)</li>
 * <li>Unique constraint (excluding soft-deleted)</li>
 * <li>Reserved names blocked via reserved_usernames table</li>
 * </ul>
 *
 * <p>
 * <b>Profile Lifecycle:</b>
 * <ul>
 * <li>Create → Draft (is_published = false, returns 404)</li>
 * <li>Publish → Live (is_published = true, accessible at /u/{username})</li>
 * <li>Unpublish → Returns to draft (404)</li>
 * <li>Delete → Soft delete (deleted_at timestamp, 90-day retention)</li>
 * </ul>
 *
 * @see ProfileCuratedArticle for curated article management
 * @see ReservedUsername for reserved name enforcement
 */
@Entity
@Table(
        name = "user_profiles")
public class UserProfile extends PanacheEntityBase {

    private static final Logger LOG = Logger.getLogger(UserProfile.class);

    // Template types
    public static final String TEMPLATE_PUBLIC_HOMEPAGE = "public_homepage";
    public static final String TEMPLATE_YOUR_TIMES = "your_times";
    public static final String TEMPLATE_YOUR_REPORT = "your_report";

    // Query name constants
    public static final String QUERY_FIND_BY_USERNAME = "UserProfile.findByUsername";
    public static final String QUERY_FIND_BY_USER_ID = "UserProfile.findByUserId";
    public static final String QUERY_FIND_PUBLISHED = "UserProfile.findPublished";
    public static final String QUERY_FIND_PENDING_PURGE = "UserProfile.findPendingPurge";

    @Id
    @GeneratedValue
    @Column(
            nullable = false)
    public UUID id;

    @Column(
            name = "user_id",
            nullable = false)
    public UUID userId;

    @Column(
            nullable = false)
    public String username;

    @Column(
            name = "display_name")
    public String displayName;

    @Column
    public String bio;

    @Column(
            name = "avatar_url")
    public String avatarUrl;

    @Column(
            name = "location_text")
    public String locationText;

    @Column(
            name = "website_url")
    public String websiteUrl;

    @Column(
            name = "social_links",
            nullable = false,
            columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Object> socialLinks;

    @Column(
            nullable = false)
    public String template;

    @Column(
            name = "template_config",
            nullable = false,
            columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Object> templateConfig;

    @Column(
            name = "is_published",
            nullable = false)
    public boolean isPublished;

    @Column(
            name = "view_count",
            nullable = false)
    public long viewCount;

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public Instant updatedAt;

    @Column(
            name = "deleted_at")
    public Instant deletedAt;

    /**
     * Finds a profile by username (case-insensitive).
     *
     * @param username
     *            the username to search for (will be normalized to lowercase)
     * @return Optional containing the profile if found and not deleted
     */
    public static Optional<UserProfile> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        String normalized = username.trim().toLowerCase();
        return find("username = ?1 AND deletedAt IS NULL", normalized).firstResultOptional();
    }

    /**
     * Finds a profile by user ID.
     *
     * @param userId
     *            the user ID to search for
     * @return Optional containing the profile if found and not deleted
     */
    public static Optional<UserProfile> findByUserId(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return find("userId = ?1 AND deletedAt IS NULL", userId).firstResultOptional();
    }

    /**
     * Finds all published profiles ordered by view count.
     *
     * @return list of published profiles
     */
    public static List<UserProfile> findPublished() {
        return find("isPublished = true AND deletedAt IS NULL ORDER BY viewCount DESC").list();
    }

    /**
     * Finds published profiles with pagination.
     *
     * @param offset
     *            starting offset
     * @param limit
     *            maximum number of results
     * @return list of published profiles
     */
    public static List<UserProfile> findPublished(int offset, int limit) {
        return find("isPublished = true AND deletedAt IS NULL ORDER BY viewCount DESC")
                .range(offset, offset + limit - 1).list();
    }

    /**
     * Finds all soft-deleted profiles eligible for hard deletion (90 days past deleted_at).
     *
     * <p>
     * Used by cleanup job to purge profiles after 90-day retention period per Policy P1.
     *
     * @return list of profiles eligible for purge
     */
    public static List<UserProfile> findPendingPurge() {
        return find("deletedAt IS NOT NULL AND deletedAt < ?1", Instant.now().minusSeconds(90 * 24 * 60 * 60)).list();
    }

    /**
     * Counts published profiles.
     *
     * @return count of published profiles
     */
    public static long countPublished() {
        return count("isPublished = true AND deletedAt IS NULL");
    }

    /**
     * Counts total profiles (including unpublished, excluding soft-deleted).
     *
     * @return count of all profiles
     */
    public static long countAll() {
        return count("deletedAt IS NULL");
    }

    /**
     * Normalizes and validates a username according to profile naming rules.
     *
     * <p>
     * Validation rules:
     * <ul>
     * <li>Length: 3-30 characters</li>
     * <li>Characters: a-z, 0-9, underscore, dash only</li>
     * <li>Case-insensitive (converted to lowercase)</li>
     * <li>Reserved names blocked via ReservedUsername table</li>
     * </ul>
     *
     * @param username
     *            raw username input
     * @return normalized username (lowercase, trimmed)
     * @throws ValidationException
     *             if validation fails
     */
    public static String normalizeUsername(@NotNull String username) throws ValidationException {
        if (username == null || username.isBlank()) {
            throw new ValidationException("Username cannot be blank");
        }

        String normalized = username.trim().toLowerCase();

        // Validate length (3-30 chars)
        if (normalized.length() < 3) {
            throw new ValidationException("Username must be at least 3 characters");
        }
        if (normalized.length() > 30) {
            throw new ValidationException("Username must be 30 characters or less");
        }

        // Validate characters (alphanumeric + underscore/dash)
        if (!normalized.matches("^[a-z0-9_-]+$")) {
            throw new ValidationException("Username can only contain letters, numbers, underscore, and dash");
        }

        // Check reserved names
        if (ReservedUsername.isReserved(normalized)) {
            throw new ValidationException("Username is reserved: " + normalized);
        }

        return normalized;
    }

    /**
     * Validates template type.
     *
     * @param template
     *            template name to validate
     * @return true if valid template
     * @throws ValidationException
     *             if template is invalid
     */
    public static boolean validateTemplate(String template) throws ValidationException {
        if (template == null || template.isBlank()) {
            throw new ValidationException("Template cannot be blank");
        }

        if (!TEMPLATE_PUBLIC_HOMEPAGE.equals(template) && !TEMPLATE_YOUR_TIMES.equals(template)
                && !TEMPLATE_YOUR_REPORT.equals(template)) {
            throw new ValidationException(
                    "Invalid template: " + template + " (must be public_homepage, your_times, or your_report)");
        }

        return true;
    }

    /**
     * Creates a new profile with default settings.
     *
     * @param userId
     *            owner user ID
     * @param username
     *            desired username (will be normalized)
     * @return persisted profile in draft state
     * @throws ValidationException
     *             if username is invalid or already taken
     */
    public static UserProfile createProfile(UUID userId, String username) throws ValidationException {
        if (userId == null) {
            throw new ValidationException("User ID cannot be null");
        }

        // Check if user already has a profile
        Optional<UserProfile> existingOpt = findByUserId(userId);
        if (existingOpt.isPresent()) {
            throw new ValidationException("User already has a profile");
        }

        // Normalize and validate username
        String normalizedUsername = normalizeUsername(username);

        // Check for duplicate username
        Optional<UserProfile> duplicateOpt = findByUsername(normalizedUsername);
        if (duplicateOpt.isPresent()) {
            throw new ValidationException("Username already taken: " + normalizedUsername);
        }

        UserProfile profile = new UserProfile();
        profile.userId = userId;
        profile.username = normalizedUsername;
        profile.template = TEMPLATE_PUBLIC_HOMEPAGE;
        profile.templateConfig = Map.of();
        profile.socialLinks = Map.of();
        profile.isPublished = false;
        profile.viewCount = 0;
        profile.createdAt = Instant.now();
        profile.updatedAt = Instant.now();

        QuarkusTransaction.requiringNew().run(() -> profile.persist());
        LOG.infof("Created profile %s for user %s with username: %s", profile.id, userId, normalizedUsername);
        return profile;
    }

    /**
     * Publishes this profile (makes it publicly accessible).
     */
    public void publish() {
        this.isPublished = true;
        this.updatedAt = Instant.now();
        QuarkusTransaction.requiringNew().run(() -> this.persist());
        LOG.infof("Published profile %s (username: %s)", this.id, this.username);
    }

    /**
     * Unpublishes this profile (returns to draft, 404 on access).
     */
    public void unpublish() {
        this.isPublished = false;
        this.updatedAt = Instant.now();
        QuarkusTransaction.requiringNew().run(() -> this.persist());
        LOG.infof("Unpublished profile %s (username: %s)", this.id, this.username);
    }

    /**
     * Increments the view count for this profile.
     *
     * <p>
     * Called when public profile page is accessed. View count is cached here for fast reads; detailed analytics are
     * tracked in link_clicks table.
     */
    public void incrementViewCount() {
        this.viewCount++;
        this.updatedAt = Instant.now();
        QuarkusTransaction.requiringNew().run(() -> this.persist());
    }

    /**
     * Soft-deletes this profile by setting the deleted_at timestamp.
     *
     * <p>
     * Per Policy P1, soft-deleted profiles are hard-deleted after 90 days by the cleanup job. Username becomes
     * available for re-use after soft delete.
     */
    public void softDelete() {
        this.deletedAt = Instant.now();
        this.updatedAt = Instant.now();
        QuarkusTransaction.requiringNew().run(() -> this.persist());
        LOG.infof("Soft-deleted profile %s (username: %s)", this.id, this.username);
    }

    /**
     * Updates profile fields with new values.
     *
     * @param displayName
     *            new display name (optional)
     * @param bio
     *            new bio (optional)
     * @param locationText
     *            new location (optional)
     * @param websiteUrl
     *            new website URL (optional)
     * @param avatarUrl
     *            new avatar URL (optional)
     */
    public void updateProfile(String displayName, String bio, String locationText, String websiteUrl,
            String avatarUrl) {
        if (displayName != null) {
            this.displayName = displayName;
        }
        if (bio != null) {
            this.bio = bio;
        }
        if (locationText != null) {
            this.locationText = locationText;
        }
        if (websiteUrl != null) {
            this.websiteUrl = websiteUrl;
        }
        if (avatarUrl != null) {
            this.avatarUrl = avatarUrl;
        }
        this.updatedAt = Instant.now();
        this.persist();
    }

    /**
     * Updates social links.
     *
     * @param socialLinks
     *            new social links map
     */
    public void updateSocialLinks(Map<String, Object> socialLinks) {
        if (socialLinks != null) {
            this.socialLinks = socialLinks;
            this.updatedAt = Instant.now();
            this.persist();
        }
    }

    /**
     * Updates template and configuration.
     *
     * @param template
     *            new template type
     * @param templateConfig
     *            new template configuration
     * @throws ValidationException
     *             if template is invalid
     */
    public void updateTemplate(String template, Map<String, Object> templateConfig) throws ValidationException {
        validateTemplate(template);
        this.template = template;
        if (templateConfig != null) {
            this.templateConfig = templateConfig;
        }
        this.updatedAt = Instant.now();
        this.persist();
    }

    /**
     * Immutable snapshot record for JSON serialization in audit logs.
     */
    public record UserProfileSnapshot(@JsonProperty("profile_id") UUID profileId, @JsonProperty("user_id") UUID userId,
            String username, @JsonProperty("display_name") String displayName, String template,
            @JsonProperty("is_published") boolean isPublished, @JsonProperty("view_count") long viewCount,
            @JsonProperty("updated_at") Instant updatedAt) {
    }

    /**
     * Creates a snapshot of this profile's state for audit logging.
     *
     * @return immutable snapshot record
     */
    public UserProfileSnapshot toSnapshot() {
        return new UserProfileSnapshot(this.id, this.userId, this.username, this.displayName, this.template,
                this.isPublished, this.viewCount, this.updatedAt);
    }
}
