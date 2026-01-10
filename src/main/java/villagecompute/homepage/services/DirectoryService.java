package villagecompute.homepage.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.DirectorySiteType;
import villagecompute.homepage.api.types.SiteSubmissionResultType;
import villagecompute.homepage.api.types.SubmitSiteType;
import villagecompute.homepage.data.models.DirectoryCategory;
import villagecompute.homepage.data.models.DirectorySite;
import villagecompute.homepage.data.models.DirectorySiteCategory;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.exceptions.DuplicateResourceException;
import villagecompute.homepage.exceptions.ResourceNotFoundException;
import villagecompute.homepage.exceptions.ValidationException;

import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * DirectoryService handles business logic for Good Sites directory.
 *
 * <p>
 * Primary responsibilities:
 * <ul>
 * <li>Site submission with metadata fetching</li>
 * <li>Duplicate detection</li>
 * <li>Karma-based auto-approval</li>
 * <li>Content sanitization</li>
 * <li>Moderation queue management</li>
 * </ul>
 *
 * <p>
 * Feature: F13.2 - Hand-curated web directory
 * </p>
 * <p>
 * Policy: P13 - User-generated content moderation
 * </p>
 */
@ApplicationScoped
public class DirectoryService {

    private static final Logger LOG = Logger.getLogger(DirectoryService.class);

    /**
     * Submits a new site to the directory.
     *
     * <p>
     * Flow:
     * <ol>
     * <li>Normalize and validate URL</li>
     * <li>Check for duplicate (same URL already submitted)</li>
     * <li>Fetch OpenGraph metadata (title/description/image)</li>
     * <li>Sanitize user-provided overrides</li>
     * <li>Check user's karma trust level</li>
     * <li>Create DirectorySite with appropriate status</li>
     * <li>Create DirectorySiteCategory entries for each category</li>
     * <li>Return submission result with moderation status</li>
     * </ol>
     *
     * <p>
     * Auto-approval logic (karma-based):
     * <ul>
     * <li>untrusted → pending (requires moderation)</li>
     * <li>trusted → approved (auto-approve)</li>
     * <li>moderator → approved (auto-approve)</li>
     * </ul>
     *
     * @param userId
     *            User submitting the site
     * @param request
     *            Submission request with URL and categories
     * @return Submission result with status
     * @throws DuplicateResourceException
     *             if URL already submitted
     * @throws ResourceNotFoundException
     *             if category doesn't exist
     * @throws ValidationException
     *             if validation fails
     */
    @Transactional
    public SiteSubmissionResultType submitSite(UUID userId, SubmitSiteType request) {
        LOG.infof("Processing site submission from user %s: %s", userId, request.url());

        // 1. Normalize URL
        String normalizedUrl = DirectorySite.normalizeUrl(request.url());
        String domain = DirectorySite.extractDomain(normalizedUrl);

        // 2. Check for duplicate
        Optional<DirectorySite> existingOpt = DirectorySite.findByUrl(normalizedUrl);
        if (existingOpt.isPresent()) {
            throw new DuplicateResourceException(
                    "Site already submitted: " + normalizedUrl + " (ID: " + existingOpt.get().id + ")");
        }

        // 3. Validate categories exist
        for (UUID categoryId : request.categoryIds()) {
            DirectoryCategory category = DirectoryCategory.findById(categoryId);
            if (category == null) {
                throw new ResourceNotFoundException("Category not found: " + categoryId);
            }
        }

        // 4. Fetch metadata (OpenGraph)
        SiteMetadata metadata = fetchMetadata(normalizedUrl, request);

        // 5. Check user trust level
        User user = User.findById(userId);
        if (user == null) {
            throw new ResourceNotFoundException("User not found: " + userId);
        }

        boolean autoApprove = "trusted".equals(user.directoryTrustLevel)
                || "moderator".equals(user.directoryTrustLevel);

        LOG.infof("User %s trust level: %s, auto-approve: %s", userId, user.directoryTrustLevel, autoApprove);

        // 6. Create DirectorySite
        DirectorySite site = new DirectorySite();
        site.url = normalizedUrl;
        site.domain = domain;
        site.title = metadata.title();
        site.description = metadata.description();
        site.ogImageUrl = metadata.ogImageUrl();
        site.faviconUrl = metadata.faviconUrl();
        site.customImageUrl = metadata.customImageUrl();
        site.submittedByUserId = userId;
        site.status = "pending"; // Always start as pending
        site.isDead = false;
        site.createdAt = Instant.now();
        site.updatedAt = Instant.now();
        site.persist();

        LOG.infof("Created DirectorySite %s for URL %s", site.id, normalizedUrl);

        // 7. Create DirectorySiteCategory entries
        List<UUID> approvedCategories = new ArrayList<>();
        List<UUID> pendingCategories = new ArrayList<>();

        for (UUID categoryId : request.categoryIds()) {
            DirectorySiteCategory siteCategory = new DirectorySiteCategory();
            siteCategory.siteId = site.id;
            siteCategory.categoryId = categoryId;
            siteCategory.score = 0;
            siteCategory.upvotes = 0;
            siteCategory.downvotes = 0;
            siteCategory.submittedByUserId = userId;
            siteCategory.status = autoApprove ? "approved" : "pending";
            siteCategory.createdAt = Instant.now();
            siteCategory.updatedAt = Instant.now();

            if (autoApprove) {
                siteCategory.approvedByUserId = userId;
                approvedCategories.add(categoryId);

                // Increment category link count
                DirectoryCategory.incrementLinkCount(categoryId);
            } else {
                pendingCategories.add(categoryId);
            }

            siteCategory.persist();

            LOG.infof("Created DirectorySiteCategory %s for site %s in category %s (status: %s)", siteCategory.id,
                    site.id, categoryId, siteCategory.status);
        }

        // 8. Return result
        if (autoApprove) {
            return SiteSubmissionResultType.approved(site.id, metadata.title(), metadata.description(),
                    approvedCategories);
        } else {
            return SiteSubmissionResultType.pending(site.id, metadata.title(), metadata.description(),
                    pendingCategories);
        }
    }

    /**
     * Retrieves a site by ID.
     *
     * @param siteId
     *            Site ID
     * @return Site data
     * @throws ResourceNotFoundException
     *             if site not found
     */
    @Transactional
    public DirectorySiteType getSite(UUID siteId) {
        DirectorySite site = DirectorySite.findById(siteId);
        if (site == null) {
            throw new ResourceNotFoundException("Site not found: " + siteId);
        }
        return DirectorySiteType.fromEntity(site);
    }

    /**
     * Lists sites submitted by a user.
     *
     * @param userId
     *            User ID
     * @return List of user's submitted sites
     */
    @Transactional
    public List<DirectorySiteType> getUserSubmissions(UUID userId) {
        List<DirectorySite> sites = DirectorySite.findByUserId(userId);
        return sites.stream().map(DirectorySiteType::fromEntity).toList();
    }

    /**
     * Deletes a site submission.
     *
     * <p>
     * Can only be deleted by the submitting user or an admin. Cascade deletes DirectorySiteCategory and DirectoryVote
     * records.
     * </p>
     *
     * @param siteId
     *            Site ID to delete
     * @param userId
     *            User requesting deletion
     * @throws ResourceNotFoundException
     *             if site not found
     * @throws ValidationException
     *             if user not authorized
     */
    @Transactional
    public void deleteSite(UUID siteId, UUID userId) {
        DirectorySite site = DirectorySite.findById(siteId);
        if (site == null) {
            throw new ResourceNotFoundException("Site not found: " + siteId);
        }

        // Check authorization (owner or admin)
        User user = User.findById(userId);
        if (user == null) {
            throw new ResourceNotFoundException("User not found: " + userId);
        }
        boolean isOwner = site.submittedByUserId.equals(userId);
        boolean isAdmin = user.adminRole != null;

        if (!isOwner && !isAdmin) {
            throw new ValidationException("Not authorized to delete this site");
        }

        // Decrement category link counts for approved categories
        List<DirectorySiteCategory> siteCategories = DirectorySiteCategory.findBySiteId(siteId);
        for (DirectorySiteCategory sc : siteCategories) {
            if ("approved".equals(sc.status)) {
                DirectoryCategory.decrementLinkCount(sc.categoryId);
            }
        }

        // Delete site (cascades to site_categories and votes)
        site.delete();

        LOG.infof("Deleted DirectorySite %s by user %s", siteId, userId);
    }

    /**
     * Fetches OpenGraph metadata from URL.
     *
     * <p>
     * For I5.T2: Simple implementation using user-provided overrides. TODO (I5.T3): Enhance with actual HTTP fetch and
     * OpenGraph parsing.
     * </p>
     *
     * @param url
     *            Normalized URL
     * @param request
     *            User submission request
     * @return Site metadata
     */
    private SiteMetadata fetchMetadata(String url, SubmitSiteType request) {
        // Sanitize user-provided values
        String title = sanitize(request.title());
        String description = sanitize(request.description());
        String customImageUrl = request.customImageUrl();

        // Use user-provided values if available, otherwise extract from URL
        if (title == null || title.isBlank()) {
            title = extractTitleFromUrl(url);
        }

        if (description == null) {
            description = "";
        }

        // Validate title length after sanitization
        if (title.length() > 200) {
            title = title.substring(0, 200);
        }

        if (description.length() > 2000) {
            description = description.substring(0, 2000);
        }

        return new SiteMetadata(title, description, null, null, customImageUrl);
    }

    /**
     * Extracts a title from URL (fallback when metadata fetch fails).
     *
     * @param url
     *            URL to extract title from
     * @return Extracted title (domain name)
     */
    private String extractTitleFromUrl(String url) {
        try {
            String host = new URL(url).getHost();
            // Remove www. prefix
            return host.replaceFirst("^www\\.", "");
        } catch (Exception e) {
            LOG.warnf("Failed to extract title from URL %s: %s", url, e.getMessage());
            return url;
        }
    }

    /**
     * Sanitizes user-provided content to prevent XSS.
     *
     * <p>
     * Removes all HTML tags while preserving text content. Simple implementation using regex. TODO: Use Jsoup for more
     * robust sanitization.
     * </p>
     *
     * @param input
     *            User input
     * @return Sanitized output
     */
    private String sanitize(String input) {
        if (input == null) {
            return null;
        }
        // Simple HTML tag removal (replace with Jsoup in production)
        return input.replaceAll("<[^>]*>", "").trim();
    }

    /**
     * Internal DTO for site metadata.
     */
    private record SiteMetadata(String title, String description, String ogImageUrl, String faviconUrl,
            String customImageUrl) {
    }
}
