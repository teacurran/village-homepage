package villagecompute.homepage.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.LinkClick;
import villagecompute.homepage.data.models.ProfileCuratedArticle;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.data.models.UserProfile;
import villagecompute.homepage.exceptions.DuplicateResourceException;
import villagecompute.homepage.exceptions.ResourceNotFoundException;
import villagecompute.homepage.exceptions.ValidationException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ProfileService handles business logic for public user profiles.
 *
 * <p>
 * Primary responsibilities:
 * <ul>
 * <li>Profile creation with username validation</li>
 * <li>Profile CRUD operations</li>
 * <li>Publish/unpublish workflow</li>
 * <li>View count tracking</li>
 * <li>Curated article management</li>
 * <li>Template configuration</li>
 * </ul>
 *
 * <p>
 * Feature: F11 - Public Profiles
 * </p>
 * <p>
 * Policy: P1 - GDPR/CCPA Compliance (soft delete support)
 * </p>
 * <p>
 * Policy: P4 - Data Retention (indefinite for user-generated content)
 * </p>
 */
@ApplicationScoped
public class ProfileService {

    private static final Logger LOG = Logger.getLogger(ProfileService.class);

    /**
     * Creates a new profile for a user.
     *
     * <p>
     * Flow:
     * <ol>
     * <li>Validate user exists and is authenticated (not anonymous)</li>
     * <li>Check user doesn't already have a profile</li>
     * <li>Normalize and validate username (length, characters, reserved check)</li>
     * <li>Check for duplicate username</li>
     * <li>Create profile in draft state (unpublished)</li>
     * <li>Return created profile</li>
     * </ol>
     *
     * <p>
     * Anonymous users cannot create profiles per Policy P9.
     *
     * @param userId
     *            user creating the profile
     * @param username
     *            desired username (3-30 chars, alphanumeric + underscore/dash)
     * @return created profile in draft state
     * @throws ResourceNotFoundException
     *             if user not found
     * @throws ValidationException
     *             if username invalid, reserved, or user is anonymous
     * @throws DuplicateResourceException
     *             if username already taken or user already has profile
     */
    @Transactional
    public UserProfile createProfile(UUID userId, String username) {
        LOG.infof("Creating profile for user %s with username: %s", userId, username);

        // 1. Validate user exists and is authenticated
        User user = User.findById(userId);
        if (user == null) {
            throw new ResourceNotFoundException("User not found: " + userId);
        }

        if (user.isAnonymous) {
            throw new ValidationException("Anonymous users cannot create profiles");
        }

        // 2. Check user doesn't already have a profile
        Optional<UserProfile> existingProfileOpt = UserProfile.findByUserId(userId);
        if (existingProfileOpt.isPresent()) {
            throw new DuplicateResourceException("User already has a profile");
        }

        // 3-4. Normalize, validate, and check for duplicates (handled in createProfile)
        UserProfile profile = UserProfile.createProfile(userId, username);

        LOG.infof("Created profile %s for user %s (username: %s)", profile.id, userId, profile.username);
        return profile;
    }

    /**
     * Retrieves a profile by ID.
     *
     * @param profileId
     *            profile UUID
     * @return profile
     * @throws ResourceNotFoundException
     *             if profile not found
     */
    @Transactional
    public UserProfile getProfile(UUID profileId) {
        UserProfile profile = UserProfile.findById(profileId);
        if (profile == null || profile.deletedAt != null) {
            throw new ResourceNotFoundException("Profile not found: " + profileId);
        }
        return profile;
    }

    /**
     * Retrieves a profile by username.
     *
     * @param username
     *            username (case-insensitive)
     * @return profile
     * @throws ResourceNotFoundException
     *             if profile not found
     */
    @Transactional
    public UserProfile getProfileByUsername(String username) {
        Optional<UserProfile> profileOpt = UserProfile.findByUsername(username);
        if (profileOpt.isEmpty()) {
            throw new ResourceNotFoundException("Profile not found: " + username);
        }
        return profileOpt.get();
    }

    /**
     * Retrieves a profile by user ID.
     *
     * @param userId
     *            user UUID
     * @return profile
     * @throws ResourceNotFoundException
     *             if profile not found
     */
    @Transactional
    public UserProfile getProfileByUserId(UUID userId) {
        Optional<UserProfile> profileOpt = UserProfile.findByUserId(userId);
        if (profileOpt.isEmpty()) {
            throw new ResourceNotFoundException("No profile found for user: " + userId);
        }
        return profileOpt.get();
    }

    /**
     * Updates profile fields.
     *
     * @param profileId
     *            profile UUID
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
     * @return updated profile
     * @throws ResourceNotFoundException
     *             if profile not found
     */
    @Transactional
    public UserProfile updateProfile(UUID profileId, String displayName, String bio, String locationText,
            String websiteUrl, String avatarUrl) {

        UserProfile profile = getProfile(profileId);
        profile.updateProfile(displayName, bio, locationText, websiteUrl, avatarUrl);

        LOG.infof("Updated profile %s", profileId);
        return profile;
    }

    /**
     * Updates social links.
     *
     * @param profileId
     *            profile UUID
     * @param socialLinks
     *            new social links map (Twitter, LinkedIn, GitHub, etc.)
     * @return updated profile
     * @throws ResourceNotFoundException
     *             if profile not found
     */
    @Transactional
    public UserProfile updateSocialLinks(UUID profileId, Map<String, Object> socialLinks) {
        UserProfile profile = getProfile(profileId);
        profile.updateSocialLinks(socialLinks);

        LOG.infof("Updated social links for profile %s", profileId);
        return profile;
    }

    /**
     * Updates template and configuration.
     *
     * @param profileId
     *            profile UUID
     * @param template
     *            new template type (public_homepage, your_times, your_report)
     * @param templateConfig
     *            new template configuration
     * @return updated profile
     * @throws ResourceNotFoundException
     *             if profile not found
     * @throws ValidationException
     *             if template is invalid
     */
    @Transactional
    public UserProfile updateTemplate(UUID profileId, String template, Map<String, Object> templateConfig) {
        UserProfile profile = getProfile(profileId);
        profile.updateTemplate(template, templateConfig);

        LOG.infof("Updated template for profile %s to: %s", profileId, template);
        return profile;
    }

    /**
     * Publishes a profile (makes it publicly accessible at /u/{username}).
     *
     * @param profileId
     *            profile UUID
     * @return published profile
     * @throws ResourceNotFoundException
     *             if profile not found
     */
    @Transactional
    public UserProfile publishProfile(UUID profileId) {
        UserProfile profile = getProfile(profileId);
        profile.publish();

        LOG.infof("Published profile %s (username: %s)", profileId, profile.username);
        return profile;
    }

    /**
     * Unpublishes a profile (returns to draft, returns 404 when accessed).
     *
     * @param profileId
     *            profile UUID
     * @return unpublished profile
     * @throws ResourceNotFoundException
     *             if profile not found
     */
    @Transactional
    public UserProfile unpublishProfile(UUID profileId) {
        UserProfile profile = getProfile(profileId);
        profile.unpublish();

        LOG.infof("Unpublished profile %s (username: %s)", profileId, profile.username);
        return profile;
    }

    /**
     * Increments view count for a profile and logs to link_clicks for analytics.
     *
     * <p>
     * Called when public profile page is accessed. View count is cached on user_profiles.view_count for fast reads;
     * link_clicks table has full audit trail for analytics.
     *
     * @param profileId
     *            profile UUID
     * @param userId
     *            viewing user ID (null if anonymous)
     * @param sessionId
     *            session identifier
     * @param ipAddress
     *            client IP address
     * @param userAgent
     *            client user agent
     * @throws ResourceNotFoundException
     *             if profile not found
     */
    @Transactional
    public void incrementViewCount(UUID profileId, UUID userId, String sessionId, String ipAddress, String userAgent) {

        UserProfile profile = getProfile(profileId);

        // Increment cached view count
        profile.incrementViewCount();

        // Log to link_clicks for analytics (full audit trail)
        LinkClick click = new LinkClick();
        click.clickDate = java.time.LocalDate.now();
        click.clickTimestamp = java.time.Instant.now();
        click.clickType = "profile_view";
        click.targetId = profileId;
        click.userId = userId;
        click.sessionId = sessionId;
        click.ipAddress = ipAddress;
        click.userAgent = userAgent;
        click.createdAt = java.time.Instant.now();
        click.persist();

        LOG.debugf("Incremented view count for profile %s to %d", profileId, profile.viewCount);
    }

    /**
     * Soft-deletes a profile (90-day retention before hard delete per Policy P1).
     *
     * <p>
     * Username becomes available for re-use after soft delete.
     *
     * @param profileId
     *            profile UUID
     * @throws ResourceNotFoundException
     *             if profile not found
     */
    @Transactional
    public void deleteProfile(UUID profileId) {
        UserProfile profile = getProfile(profileId);
        profile.softDelete();

        LOG.infof("Soft-deleted profile %s (username: %s)", profileId, profile.username);
    }

    /**
     * Lists all published profiles (for directory page).
     *
     * @param offset
     *            starting offset
     * @param limit
     *            maximum results
     * @return list of published profiles ordered by view count
     */
    @Transactional
    public List<UserProfile> listPublishedProfiles(int offset, int limit) {
        return UserProfile.findPublished(offset, limit);
    }

    /**
     * Gets statistics for admin dashboard.
     *
     * @return map of statistics (total_profiles, published_profiles, total_views)
     */
    @Transactional
    public Map<String, Object> getStats() {
        long totalProfiles = UserProfile.countAll();
        long publishedProfiles = UserProfile.countPublished();

        // Calculate total views (sum of all view_count)
        long totalViews = UserProfile.find("deletedAt IS NULL").stream().mapToLong(p -> ((UserProfile) p).viewCount)
                .sum();

        return Map.of("total_profiles", totalProfiles, "published_profiles", publishedProfiles, "total_views",
                totalViews);
    }

    /**
     * Adds a curated article to a profile from a feed item.
     *
     * @param profileId
     *            profile UUID
     * @param feedItemId
     *            feed item UUID
     * @param originalUrl
     *            article URL
     * @param originalTitle
     *            article title
     * @param originalDescription
     *            article description
     * @param originalImageUrl
     *            article image URL
     * @return created curated article
     * @throws ResourceNotFoundException
     *             if profile not found
     * @throws ValidationException
     *             if required fields missing
     */
    @Transactional
    public ProfileCuratedArticle addCuratedArticle(UUID profileId, UUID feedItemId, String originalUrl,
            String originalTitle, String originalDescription, String originalImageUrl) {

        // Validate profile exists
        getProfile(profileId);

        ProfileCuratedArticle article = ProfileCuratedArticle.createFromFeedItem(profileId, feedItemId, originalUrl,
                originalTitle, originalDescription, originalImageUrl);

        LOG.infof("Added curated article %s to profile %s", article.id, profileId);
        return article;
    }

    /**
     * Adds a manually-entered curated article (no feed item).
     *
     * @param profileId
     *            profile UUID
     * @param originalUrl
     *            article URL
     * @param originalTitle
     *            article title
     * @param originalDescription
     *            article description
     * @param originalImageUrl
     *            article image URL
     * @return created curated article
     * @throws ResourceNotFoundException
     *             if profile not found
     * @throws ValidationException
     *             if required fields missing
     */
    @Transactional
    public ProfileCuratedArticle addManualArticle(UUID profileId, String originalUrl, String originalTitle,
            String originalDescription, String originalImageUrl) {

        // Validate profile exists
        getProfile(profileId);

        ProfileCuratedArticle article = ProfileCuratedArticle.createManual(profileId, originalUrl, originalTitle,
                originalDescription, originalImageUrl);

        LOG.infof("Added manual curated article %s to profile %s", article.id, profileId);
        return article;
    }

    /**
     * Updates curated article customization.
     *
     * @param articleId
     *            article UUID
     * @param customHeadline
     *            custom headline
     * @param customBlurb
     *            custom blurb
     * @param customImageUrl
     *            custom image URL
     * @return updated article
     * @throws ResourceNotFoundException
     *             if article not found
     */
    @Transactional
    public ProfileCuratedArticle updateArticleCustomization(UUID articleId, String customHeadline, String customBlurb,
            String customImageUrl) {

        Optional<ProfileCuratedArticle> articleOpt = ProfileCuratedArticle.findByIdOptional(articleId);
        if (articleOpt.isEmpty()) {
            throw new ResourceNotFoundException("Article not found: " + articleId);
        }

        ProfileCuratedArticle article = articleOpt.get();
        article.updateCustomization(customHeadline, customBlurb, customImageUrl);

        LOG.infof("Updated customization for article %s", articleId);
        return article;
    }

    /**
     * Removes a curated article (deactivates it).
     *
     * @param articleId
     *            article UUID
     * @throws ResourceNotFoundException
     *             if article not found
     */
    @Transactional
    public void removeArticle(UUID articleId) {
        Optional<ProfileCuratedArticle> articleOpt = ProfileCuratedArticle.findByIdOptional(articleId);
        if (articleOpt.isEmpty()) {
            throw new ResourceNotFoundException("Article not found: " + articleId);
        }

        ProfileCuratedArticle article = articleOpt.get();
        article.deactivate();

        LOG.infof("Removed article %s from profile %s", articleId, article.profileId);
    }

    /**
     * Lists active curated articles for a profile.
     *
     * @param profileId
     *            profile UUID
     * @return list of active articles
     * @throws ResourceNotFoundException
     *             if profile not found
     */
    @Transactional
    public List<ProfileCuratedArticle> listArticles(UUID profileId) {
        // Validate profile exists
        getProfile(profileId);

        return ProfileCuratedArticle.findActive(profileId);
    }
}
