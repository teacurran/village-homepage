package villagecompute.homepage.data.models;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQuery;
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
 * ProfileCuratedArticle entity for managing curated content on user profiles.
 *
 * <p>
 * Supports both RSS feed-sourced articles (via feed_items) and manual entries. Users can customize headlines,
 * descriptions, and images for display on their profile templates.
 *
 * <p>
 * <b>Schema Mapping:</b>
 * <ul>
 * <li>{@code id} (UUID, PK) - Primary identifier</li>
 * <li>{@code profile_id} (UUID, FK) - Reference to user_profiles table</li>
 * <li>{@code feed_item_id} (UUID, FK, nullable) - Reference to feed_items (null for manual entries)</li>
 * <li>{@code original_url} (TEXT) - Article URL (required)</li>
 * <li>{@code original_title} (TEXT) - Original article title (required)</li>
 * <li>{@code original_description} (TEXT) - Original description/excerpt</li>
 * <li>{@code original_image_url} (TEXT) - Original featured image URL</li>
 * <li>{@code custom_headline} (TEXT) - User-customized headline override</li>
 * <li>{@code custom_blurb} (TEXT) - User-customized description override</li>
 * <li>{@code custom_image_url} (TEXT) - User-customized image URL override</li>
 * <li>{@code slot_assignment} (JSONB) - Template-specific positioning config</li>
 * <li>{@code is_active} (BOOLEAN) - Active/hidden flag (false = draft/removed)</li>
 * <li>{@code created_at} (TIMESTAMPTZ) - Record creation timestamp</li>
 * <li>{@code updated_at} (TIMESTAMPTZ) - Last modification timestamp</li>
 * </ul>
 *
 * <p>
 * <b>Article Sources:</b>
 * <ul>
 * <li>RSS feeds: {@code feed_item_id} populated, original fields copied from feed_items</li>
 * <li>Manual entries: {@code feed_item_id} NULL, user provides all original fields</li>
 * </ul>
 *
 * <p>
 * <b>Customization Strategy:</b>
 * <ul>
 * <li>Display logic prefers custom_* fields when present, falls back to original_* fields</li>
 * <li>slot_assignment determines template-specific positioning (JSON schema varies by template)</li>
 * <li>is_active flag allows hiding articles without deletion (draft workflow)</li>
 * </ul>
 *
 * @see UserProfile for profile owner
 * @see FeedItem for RSS feed source integration
 */
@Entity
@Table(
        name = "profile_curated_articles")
@NamedQuery(
        name = ProfileCuratedArticle.QUERY_FIND_BY_PROFILE,
        query = ProfileCuratedArticle.JPQL_FIND_BY_PROFILE)
@NamedQuery(
        name = ProfileCuratedArticle.QUERY_FIND_ACTIVE,
        query = ProfileCuratedArticle.JPQL_FIND_ACTIVE)
@NamedQuery(
        name = ProfileCuratedArticle.QUERY_FIND_BY_FEED_ITEM,
        query = ProfileCuratedArticle.JPQL_FIND_BY_FEED_ITEM)
public class ProfileCuratedArticle extends PanacheEntityBase {

    private static final Logger LOG = Logger.getLogger(ProfileCuratedArticle.class);

    public static final String JPQL_FIND_BY_PROFILE = "FROM ProfileCuratedArticle WHERE profileId = ?1 ORDER BY createdAt DESC";
    public static final String QUERY_FIND_BY_PROFILE = "ProfileCuratedArticle.findByProfile";

    public static final String JPQL_FIND_ACTIVE = "FROM ProfileCuratedArticle WHERE profileId = ?1 AND isActive = true ORDER BY createdAt DESC";
    public static final String QUERY_FIND_ACTIVE = "ProfileCuratedArticle.findActive";

    public static final String JPQL_FIND_BY_FEED_ITEM = "FROM ProfileCuratedArticle WHERE feedItemId = ?1";
    public static final String QUERY_FIND_BY_FEED_ITEM = "ProfileCuratedArticle.findByFeedItem";

    @Id
    @GeneratedValue
    @Column(
            nullable = false)
    public UUID id;

    @Column(
            name = "profile_id",
            nullable = false)
    public UUID profileId;

    @Column(
            name = "feed_item_id")
    public UUID feedItemId;

    @Column(
            name = "original_url",
            nullable = false)
    public String originalUrl;

    @Column(
            name = "original_title",
            nullable = false)
    public String originalTitle;

    @Column(
            name = "original_description")
    public String originalDescription;

    @Column(
            name = "original_image_url")
    public String originalImageUrl;

    @Column(
            name = "custom_headline")
    public String customHeadline;

    @Column(
            name = "custom_blurb")
    public String customBlurb;

    @Column(
            name = "custom_image_url")
    public String customImageUrl;

    @Column(
            name = "slot_assignment",
            nullable = false,
            columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Object> slotAssignment;

    @Column(
            name = "is_active",
            nullable = false)
    public boolean isActive;

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public Instant updatedAt;

    /**
     * Finds all curated articles for a profile (active and inactive).
     *
     * @param profileId
     *            profile UUID
     * @return list of articles ordered by creation date
     */
    public static List<ProfileCuratedArticle> findByProfile(UUID profileId) {
        if (profileId == null) {
            return List.of();
        }
        return find(JPQL_FIND_BY_PROFILE, profileId).list();
    }

    /**
     * Finds active curated articles for a profile (for public display).
     *
     * @param profileId
     *            profile UUID
     * @return list of active articles ordered by creation date
     */
    public static List<ProfileCuratedArticle> findActive(UUID profileId) {
        if (profileId == null) {
            return List.of();
        }
        return find(JPQL_FIND_ACTIVE, profileId).list();
    }

    /**
     * Finds curated articles by feed item ID (for sync/update scenarios).
     *
     * @param feedItemId
     *            feed item UUID
     * @return list of articles linked to this feed item
     */
    public static List<ProfileCuratedArticle> findByFeedItem(UUID feedItemId) {
        if (feedItemId == null) {
            return List.of();
        }
        return find(JPQL_FIND_BY_FEED_ITEM, feedItemId).list();
    }

    /**
     * Finds a specific article by ID.
     *
     * @param id
     *            article UUID
     * @return Optional containing the article if found
     */
    public static Optional<ProfileCuratedArticle> findByIdOptional(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return find("id = ?1", id).firstResultOptional();
    }

    /**
     * Counts active articles for a profile.
     *
     * @param profileId
     *            profile UUID
     * @return count of active articles
     */
    public static long countActive(UUID profileId) {
        if (profileId == null) {
            return 0;
        }
        return count("profileId = ?1 AND isActive = true", profileId);
    }

    /**
     * Counts total articles for a profile (active and inactive).
     *
     * @param profileId
     *            profile UUID
     * @return count of all articles
     */
    public static long countAll(UUID profileId) {
        if (profileId == null) {
            return 0;
        }
        return count("profileId = ?1", profileId);
    }

    /**
     * Creates a new curated article from a feed item.
     *
     * @param profileId
     *            owner profile UUID
     * @param feedItemId
     *            source feed item UUID
     * @param originalUrl
     *            article URL
     * @param originalTitle
     *            article title
     * @param originalDescription
     *            article description
     * @param originalImageUrl
     *            article image URL
     * @return persisted curated article
     * @throws ValidationException
     *             if required fields are missing
     */
    public static ProfileCuratedArticle createFromFeedItem(UUID profileId, UUID feedItemId, String originalUrl,
            String originalTitle, String originalDescription, String originalImageUrl) throws ValidationException {

        if (profileId == null) {
            throw new ValidationException("Profile ID cannot be null");
        }
        if (originalUrl == null || originalUrl.isBlank()) {
            throw new ValidationException("Original URL cannot be blank");
        }
        if (originalTitle == null || originalTitle.isBlank()) {
            throw new ValidationException("Original title cannot be blank");
        }

        ProfileCuratedArticle article = new ProfileCuratedArticle();
        article.profileId = profileId;
        article.feedItemId = feedItemId;
        article.originalUrl = originalUrl;
        article.originalTitle = originalTitle;
        article.originalDescription = originalDescription;
        article.originalImageUrl = originalImageUrl;
        article.slotAssignment = Map.of();
        article.isActive = true;
        article.createdAt = Instant.now();
        article.updatedAt = Instant.now();

        QuarkusTransaction.requiringNew().run(() -> article.persist());
        LOG.infof("Created curated article %s from feed item %s for profile %s", article.id, feedItemId, profileId);
        return article;
    }

    /**
     * Creates a new curated article from manual entry (no feed item).
     *
     * @param profileId
     *            owner profile UUID
     * @param originalUrl
     *            article URL
     * @param originalTitle
     *            article title
     * @param originalDescription
     *            article description
     * @param originalImageUrl
     *            article image URL
     * @return persisted curated article
     * @throws ValidationException
     *             if required fields are missing
     */
    public static ProfileCuratedArticle createManual(UUID profileId, String originalUrl, String originalTitle,
            String originalDescription, String originalImageUrl) throws ValidationException {

        return createFromFeedItem(profileId, null, originalUrl, originalTitle, originalDescription, originalImageUrl);
    }

    /**
     * Updates customization fields (headline, blurb, image).
     *
     * @param customHeadline
     *            custom headline (null to clear)
     * @param customBlurb
     *            custom blurb (null to clear)
     * @param customImageUrl
     *            custom image URL (null to clear)
     */
    public void updateCustomization(String customHeadline, String customBlurb, String customImageUrl) {
        this.customHeadline = customHeadline;
        this.customBlurb = customBlurb;
        this.customImageUrl = customImageUrl;
        this.updatedAt = Instant.now();
        this.persist();
    }

    /**
     * Updates slot assignment configuration.
     *
     * @param slotAssignment
     *            new slot assignment map
     */
    public void updateSlotAssignment(Map<String, Object> slotAssignment) {
        if (slotAssignment != null) {
            this.slotAssignment = slotAssignment;
            this.updatedAt = Instant.now();
            this.persist();
        }
    }

    /**
     * Activates this article (makes it visible on profile).
     */
    public void activate() {
        this.isActive = true;
        this.updatedAt = Instant.now();
        QuarkusTransaction.requiringNew().run(() -> this.persist());
        LOG.infof("Activated curated article %s for profile %s", this.id, this.profileId);
    }

    /**
     * Deactivates this curated article (removes from profile display). The caller must be in a transaction context for
     * the update to be persisted.
     */
    public void deactivate() {
        this.isActive = false;
        this.updatedAt = Instant.now();
        // No persist() call - entity is managed and will be updated by the transaction
        LOG.infof("Deactivated curated article %s for profile %s", this.id, this.profileId);
    }

    /**
     * Gets the effective headline (custom or original).
     *
     * @return custom headline if present, otherwise original title
     */
    public String getEffectiveHeadline() {
        return customHeadline != null && !customHeadline.isBlank() ? customHeadline : originalTitle;
    }

    /**
     * Gets the effective description (custom or original).
     *
     * @return custom blurb if present, otherwise original description
     */
    public String getEffectiveDescription() {
        return customBlurb != null && !customBlurb.isBlank() ? customBlurb : originalDescription;
    }

    /**
     * Gets the effective image URL (custom or original).
     *
     * @return custom image URL if present, otherwise original image URL
     */
    public String getEffectiveImageUrl() {
        return customImageUrl != null && !customImageUrl.isBlank() ? customImageUrl : originalImageUrl;
    }
}
