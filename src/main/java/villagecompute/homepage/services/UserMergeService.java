package villagecompute.homepage.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.AccountMergeAudit;
import villagecompute.homepage.data.models.MarketplaceListing;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.data.models.UserNotification;

/**
 * Service for upgrading anonymous accounts to authenticated by merging user data.
 *
 * <p>
 * Orchestrates the account merge process when an anonymous user signs in with OAuth. All merge operations occur within
 * a single transaction to ensure data integrity. If any step fails, the entire merge is rolled back.
 *
 * <p>
 * <b>Merge Process:</b>
 *
 * <ol>
 * <li>Validate source (anonymous) and target (authenticated) users
 * <li>Transfer marketplace listings from anonymous to authenticated user
 * <li>Transfer notifications from anonymous to authenticated user
 * <li>Merge preferences (authenticated user's preferences take precedence)
 * <li>Create audit trail for GDPR compliance (Policy P1)
 * <li>Soft-delete anonymous user (90-day retention)
 * </ol>
 *
 * <p>
 * <b>Data Integrity Guarantees:</b>
 *
 * <ul>
 * <li>Transactional consistency - all changes commit or rollback together
 * <li>Listing IDs preserved (no duplication)
 * <li>Notification read/unread status preserved
 * <li>Authenticated user's existing data takes precedence over anonymous
 * <li>Audit trail created for compliance investigations
 * </ul>
 *
 * <p>
 * <b>GDPR Compliance (Policy P1):</b>
 *
 * <ul>
 * <li>Merge consent assumed (implicit via OAuth login)
 * <li>IP address and user agent recorded for consent verification
 * <li>Anonymous user soft-deleted (deleted_at timestamp set)
 * <li>90-day retention period enforced via AccountMergeAudit.purgeAfter
 * <li>Hard deletion performed by AccountMergeCleanupJobHandler after 90 days
 * </ul>
 *
 * @see OAuthService for OAuth callback integration points
 * @see AccountMergeAudit for GDPR compliance audit trail
 */
@ApplicationScoped
public class UserMergeService {

    private static final Logger LOG = Logger.getLogger(UserMergeService.class);
    private static final String PRIVACY_POLICY_VERSION = "2024-01"; // Update as needed

    /**
     * Upgrades anonymous account to authenticated by merging all user data.
     *
     * <p>
     * This is the main entry point for account merge. All operations occur within a single transaction. If any step
     * fails, the entire merge is rolled back to prevent partial data transfer.
     *
     * <p>
     * <b>Validation:</b>
     *
     * <ul>
     * <li>Source user must have {@code isAnonymous = true}
     * <li>Target user must have {@code isAnonymous = false}
     * <li>Throws {@link IllegalArgumentException} if validation fails
     * </ul>
     *
     * <p>
     * <b>Merge Operations:</b>
     *
     * <ol>
     * <li>Transfer marketplace listings (ownership reassignment)
     * <li>Transfer notifications (ownership reassignment)
     * <li>Merge preferences (authenticated user's preferences take precedence)
     * <li>Create audit trail with merged data summary
     * <li>Soft-delete anonymous user
     * </ol>
     *
     * @param anonymousUser
     *            the anonymous user to merge from (will be soft-deleted)
     * @param authenticatedUser
     *            the authenticated user to merge into (target)
     * @throws IllegalArgumentException
     *             if source is not anonymous or target is anonymous
     */
    @Transactional
    public void upgradeAnonymousAccount(User anonymousUser, User authenticatedUser) {
        // Validation
        if (!anonymousUser.isAnonymous) {
            LOG.errorf("Cannot merge - source user %s is not anonymous", anonymousUser.id);
            throw new IllegalArgumentException("Source user is not anonymous");
        }
        if (authenticatedUser.isAnonymous) {
            LOG.errorf("Cannot merge - target user %s is anonymous", authenticatedUser.id);
            throw new IllegalArgumentException("Target user must be authenticated");
        }

        LOG.infof("Starting account merge: anonymous=%s → authenticated=%s", anonymousUser.id, authenticatedUser.id);

        // Merge data
        int listingCount = mergeMarketplaceListings(anonymousUser, authenticatedUser);
        int notificationCount = mergeNotifications(anonymousUser, authenticatedUser);

        // Merge preferences (authenticated user's preferences take precedence)
        authenticatedUser.mergePreferences(anonymousUser.preferences);
        authenticatedUser.persist();

        // Create audit trail
        Map<String, Object> summary = Map.of("listings_transferred", listingCount, "notifications_transferred",
                notificationCount, "preferences_merged", true, "source_user_id", anonymousUser.id.toString(),
                "target_user_id", authenticatedUser.id.toString());

        createMergeAudit(anonymousUser, authenticatedUser, summary);

        // Soft-delete anonymous user (90-day retention per Policy P1)
        anonymousUser.softDelete();

        LOG.infof("Completed account merge: %d listings, %d notifications transferred from %s to %s", listingCount,
                notificationCount, anonymousUser.id, authenticatedUser.id);
    }

    /**
     * Transfers marketplace listings from anonymous to authenticated user.
     *
     * <p>
     * Ownership reassignment preserves:
     *
     * <ul>
     * <li>Listing IDs (no duplication or cloning)
     * <li>Creation timestamps
     * <li>Status (draft, active, expired, etc.)
     * <li>Images, prices, contact info
     * </ul>
     *
     * <p>
     * Only the {@code userId} and {@code updatedAt} fields are modified.
     *
     * @param from
     *            the anonymous user to transfer listings from
     * @param to
     *            the authenticated user to transfer listings to
     * @return count of transferred listings
     */
    @Transactional
    public int mergeMarketplaceListings(User from, User to) {
        // Find all listings owned by anonymous user
        List<MarketplaceListing> listings = MarketplaceListing.find("userId = ?1", from.id).list();

        if (listings.isEmpty()) {
            LOG.debugf("No marketplace listings to transfer from user %s", from.id);
            return 0;
        }

        // Reassign ownership to authenticated user
        for (MarketplaceListing listing : listings) {
            listing.userId = to.id;
            listing.updatedAt = Instant.now();
            listing.persist();
        }

        LOG.infof("Transferred %d marketplace listings from %s to %s", listings.size(), from.id, to.id);
        return listings.size();
    }

    /**
     * Transfers notifications from anonymous to authenticated user.
     *
     * <p>
     * Ownership reassignment preserves:
     *
     * <ul>
     * <li>Notification IDs
     * <li>Read/unread status (readAt timestamp)
     * <li>Creation timestamps
     * <li>Notification type, title, message, action URLs
     * </ul>
     *
     * <p>
     * Only the {@code userId} field is modified. Unread notifications remain unread after transfer.
     *
     * @param from
     *            the anonymous user to transfer notifications from
     * @param to
     *            the authenticated user to transfer notifications to
     * @return count of transferred notifications
     */
    @Transactional
    public int mergeNotifications(User from, User to) {
        // Find all notifications for anonymous user
        List<UserNotification> notifications = UserNotification.find("userId = ?1", from.id).list();

        if (notifications.isEmpty()) {
            LOG.debugf("No notifications to transfer from user %s", from.id);
            return 0;
        }

        // Reassign notifications to authenticated user
        for (UserNotification notification : notifications) {
            notification.userId = to.id;
            notification.persist();
        }

        LOG.infof("Transferred %d notifications from %s to %s", notifications.size(), from.id, to.id);
        return notifications.size();
    }

    /**
     * Creates merge audit record for GDPR compliance.
     *
     * <p>
     * Records explicit consent (implicit via OAuth login) with context:
     *
     * <ul>
     * <li>IP address - Currently hardcoded placeholder (TODO: extract from request context)
     * <li>User agent - Currently hardcoded placeholder (TODO: extract from HTTP headers)
     * <li>Privacy policy version - Current policy version at time of merge
     * <li>Merged data summary - JSON summary of transferred data counts
     * </ul>
     *
     * <p>
     * <b>Future Enhancement (I3.T6):</b> Extract IP and user agent from JAX-RS {@code HttpServletRequest} context
     * passed from {@code OAuthService}.
     *
     * @param from
     *            the anonymous user being merged
     * @param to
     *            the authenticated user receiving data
     * @param summary
     *            JSONB summary of merged data (listing counts, notification counts, etc.)
     */
    @Transactional
    public void createMergeAudit(User from, User to, Map<String, Object> summary) {
        // TODO (I3.T6): Extract IP and user agent from request context
        // For now, use placeholders. In production, these should be passed from OAuthService
        // which has access to HttpServletRequest context.
        String ipAddress = "0.0.0.0"; // Placeholder - extract from request.getRemoteAddr()
        String userAgent = "Unknown"; // Placeholder - extract from request.getHeader("User-Agent")

        AccountMergeAudit.create(from.id, to.id, summary, true, // Consent given (implicit via OAuth)
                ipAddress, userAgent, PRIVACY_POLICY_VERSION);

        LOG.infof("Created account merge audit for anonymous user %s → authenticated user %s", from.id, to.id);
    }
}
