/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.data.models;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cached social media post entity for Instagram and Facebook content per Policy P5/P13.
 *
 * <p>
 * Implements Panache ActiveRecord pattern with static finder methods. Caches posts for offline display when API
 * unavailable or tokens stale. Supports 90-day retention and staleness tracking.
 *
 * <p>
 * <b>Schema Mapping:</b>
 * <ul>
 * <li>{@code id} (BIGINT, PK) - Primary identifier</li>
 * <li>{@code social_token_id} (BIGINT, FK) - References social_tokens.id</li>
 * <li>{@code platform} (VARCHAR) - instagram | facebook</li>
 * <li>{@code platform_post_id} (VARCHAR) - Platform-specific post ID</li>
 * <li>{@code post_type} (VARCHAR) - image | video | carousel | story | reel</li>
 * <li>{@code caption} (TEXT) - Post caption text</li>
 * <li>{@code media_urls} (JSONB) - Array of media URLs with type and thumbnails</li>
 * <li>{@code posted_at} (TIMESTAMPTZ) - When post was created on platform</li>
 * <li>{@code fetched_at} (TIMESTAMPTZ) - When post was last fetched from API</li>
 * <li>{@code engagement_data} (JSONB) - Likes, comments, shares, views counts</li>
 * <li>{@code is_archived} (BOOLEAN) - True if archived due to token expiration</li>
 * <li>{@code created_at} (TIMESTAMPTZ) - Record creation timestamp</li>
 * <li>{@code updated_at} (TIMESTAMPTZ) - Last modification timestamp</li>
 * </ul>
 *
 * @see SocialToken for OAuth token storage
 */
@Entity
@Table(
        name = "social_posts")
public class SocialPost extends PanacheEntityBase {

    private static final Logger LOG = Logger.getLogger(SocialPost.class);

    public static final String POST_TYPE_IMAGE = "image";
    public static final String POST_TYPE_VIDEO = "video";
    public static final String POST_TYPE_CAROUSEL = "carousel";
    public static final String POST_TYPE_STORY = "story";
    public static final String POST_TYPE_REEL = "reel";

    public static final String QUERY_FIND_RECENT_BY_TOKEN = "SocialPost.findRecentByToken";
    public static final String QUERY_FIND_BY_PLATFORM_POST_ID = "SocialPost.findByPlatformPostId";
    public static final String QUERY_FIND_EXPIRED = "SocialPost.findExpired";

    @Id
    @GeneratedValue(
            strategy = GenerationType.IDENTITY)
    @Column(
            nullable = false)
    public Long id;

    @Column(
            name = "social_token_id",
            nullable = false)
    public Long socialTokenId;

    @Column(
            nullable = false,
            length = 20)
    public String platform;

    @Column(
            name = "platform_post_id",
            nullable = false,
            length = 100)
    public String platformPostId;

    @Column(
            name = "post_type",
            nullable = false,
            length = 50)
    public String postType;

    @Column(
            columnDefinition = "TEXT")
    public String caption;

    @Column(
            name = "media_urls",
            nullable = false,
            columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    public List<Map<String, Object>> mediaUrls; // [{url, type, thumbnail_url}]

    @Column(
            name = "posted_at",
            nullable = false)
    public Instant postedAt;

    @Column(
            name = "fetched_at",
            nullable = false)
    public Instant fetchedAt;

    @Column(
            name = "engagement_data",
            columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Object> engagementData; // {likes, comments, shares, views}

    @Column(
            name = "is_archived",
            nullable = false)
    public boolean isArchived;

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public Instant updatedAt;

    /**
     * Finds recent posts for a social token, ordered by posted_at DESC.
     *
     * @param socialTokenId
     *            social token ID
     * @param limit
     *            maximum number of posts to return
     * @return list of recent posts (most recent first)
     */
    public static List<SocialPost> findRecentByToken(Long socialTokenId, int limit) {
        if (socialTokenId == null) {
            return List.of();
        }
        return find("#" + QUERY_FIND_RECENT_BY_TOKEN
                + " WHERE social_token_id = ?1 AND is_archived = false ORDER BY posted_at DESC", socialTokenId)
                .page(0, limit).list();
    }

    /**
     * Finds all posts (including archived) for a social token.
     *
     * @param socialTokenId
     *            social token ID
     * @return list of all posts for the token
     */
    public static List<SocialPost> findAllByToken(Long socialTokenId) {
        if (socialTokenId == null) {
            return List.of();
        }
        return find("social_token_id = ?1 ORDER BY posted_at DESC", socialTokenId).list();
    }

    /**
     * Finds a post by platform and platform-specific post ID.
     *
     * @param platform
     *            "instagram" or "facebook"
     * @param platformPostId
     *            platform-specific post ID
     * @return Optional containing the post if found
     */
    public static Optional<SocialPost> findByPlatformPostId(String platform, String platformPostId) {
        if (platform == null || platformPostId == null) {
            return Optional.empty();
        }
        return find("#" + QUERY_FIND_BY_PLATFORM_POST_ID + " WHERE platform = ?1 AND platform_post_id = ?2", platform,
                platformPostId).firstResultOptional();
    }

    /**
     * Finds all posts older than the retention period (90 days) eligible for deletion.
     *
     * @return list of expired posts
     */
    public static List<SocialPost> findExpired() {
        Instant threshold = Instant.now().minus(90, ChronoUnit.DAYS);
        return find("#" + QUERY_FIND_EXPIRED + " WHERE fetched_at < ?1 AND is_archived = false", threshold).list();
    }

    /**
     * Creates and persists a new social post.
     *
     * @param socialTokenId
     *            social token ID
     * @param platform
     *            "instagram" or "facebook"
     * @param platformPostId
     *            platform-specific post ID
     * @param postType
     *            post type (image, video, carousel, story, reel)
     * @param caption
     *            post caption text
     * @param mediaUrls
     *            list of media URL objects
     * @param postedAt
     *            when post was created on platform
     * @param engagementData
     *            engagement metrics (likes, comments, shares, views)
     * @return persisted post entity
     */
    public static SocialPost create(Long socialTokenId, String platform, String platformPostId, String postType,
            String caption, List<Map<String, Object>> mediaUrls, Instant postedAt, Map<String, Object> engagementData) {

        // Check if post already exists
        Optional<SocialPost> existing = findByPlatformPostId(platform, platformPostId);
        if (existing.isPresent()) {
            // Update existing post
            SocialPost post = existing.get();
            post.caption = caption;
            post.mediaUrls = mediaUrls;
            post.engagementData = engagementData;
            post.fetchedAt = Instant.now();
            post.updatedAt = Instant.now();
            QuarkusTransaction.requiringNew().run(() -> post.persist());
            LOG.debugf("Updated existing social post %s on %s", platformPostId, platform);
            return post;
        }

        // Create new post
        SocialPost post = new SocialPost();
        post.socialTokenId = socialTokenId;
        post.platform = platform;
        post.platformPostId = platformPostId;
        post.postType = postType;
        post.caption = caption;
        post.mediaUrls = mediaUrls;
        post.postedAt = postedAt;
        post.fetchedAt = Instant.now();
        post.engagementData = engagementData;
        post.isArchived = false;
        post.createdAt = Instant.now();
        post.updatedAt = Instant.now();

        QuarkusTransaction.requiringNew().run(() -> post.persist());
        LOG.debugf("Created new social post %s on %s", platformPostId, platform);
        return post;
    }

    /**
     * Archives this post (soft delete).
     * <p>
     * Used when token expires beyond 7 days or user requests deletion.
     */
    public void archive() {
        this.isArchived = true;
        this.updatedAt = Instant.now();
        QuarkusTransaction.requiringNew().run(() -> this.persist());
        LOG.debugf("Archived social post id=%d (%s on %s)", this.id, this.platformPostId, this.platform);
    }

    /**
     * Archives all posts for a social token.
     *
     * @param socialTokenId
     *            social token ID
     * @return number of posts archived
     */
    public static int archiveAllByToken(Long socialTokenId) {
        if (socialTokenId == null) {
            return 0;
        }
        List<SocialPost> posts = find("social_token_id = ?1 AND is_archived = false", socialTokenId).list();
        posts.forEach(SocialPost::archive);
        LOG.infof("Archived %d social posts for token %d", posts.size(), socialTokenId);
        return posts.size();
    }

    /**
     * Checks if this post is stale (fetched more than N days ago).
     *
     * @param daysThreshold
     *            number of days to consider stale (typically 1, 3, or 7)
     * @return true if post was fetched more than N days ago
     */
    public boolean isStale(int daysThreshold) {
        return this.fetchedAt.isBefore(Instant.now().minus(daysThreshold, ChronoUnit.DAYS));
    }

    /**
     * Gets the number of days since this post was last fetched.
     *
     * @return days since fetch
     */
    public long getDaysSinceFetch() {
        return ChronoUnit.DAYS.between(this.fetchedAt, Instant.now());
    }
}
