package villagecompute.homepage.services;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.DirectorySiteCategory;
import villagecompute.homepage.data.models.KarmaAudit;
import villagecompute.homepage.data.models.User;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * KarmaService manages directory karma adjustments and trust level promotions for the Good Sites directory.
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>Award karma for approved submissions</li>
 * <li>Deduct karma for rejected submissions</li>
 * <li>Award karma for upvotes received on submitted sites</li>
 * <li>Deduct karma for downvotes received</li>
 * <li>Auto-promote users to trusted level at karma thresholds</li>
 * <li>Manual admin karma adjustments</li>
 * <li>Audit trail logging for all karma changes</li>
 * </ul>
 *
 * <p>
 * <b>Karma Point Rules:</b>
 * <ul>
 * <li>+5 points: Site-category submission approved</li>
 * <li>-2 points: Site-category submission rejected</li>
 * <li>+1 point: Upvote received on submitted site-category</li>
 * <li>-1 point: Downvote received on submitted site-category</li>
 * </ul>
 *
 * <p>
 * <b>Trust Level Promotion:</b>
 * <ul>
 * <li>Untrusted → Trusted: Automatic at 10+ karma (aligned with RateLimitService.Tier)</li>
 * <li>Trusted → Moderator: Manual admin promotion only</li>
 * </ul>
 *
 * @see User
 * @see KarmaAudit
 */
@ApplicationScoped
public class KarmaService {

    private static final Logger LOG = Logger.getLogger(KarmaService.class);

    // Karma adjustment values
    public static final int KARMA_SUBMISSION_APPROVED = 5;
    public static final int KARMA_SUBMISSION_REJECTED = -2;
    public static final int KARMA_UPVOTE_RECEIVED = 1;
    public static final int KARMA_DOWNVOTE_RECEIVED = -1;

    /**
     * Awards karma for an approved site-category submission.
     *
     * <p>
     * Called when a DirectorySiteCategory is approved by a moderator or auto-approved for trusted users. Awards +5
     * karma to the submitter and auto-promotes to trusted if threshold is reached.
     *
     * @param siteCategoryId
     *            Site-category membership ID that was approved
     */
    public void awardForApprovedSubmission(UUID siteCategoryId) {
        DirectorySiteCategory siteCategory = DirectorySiteCategory.findById(siteCategoryId);
        if (siteCategory == null) {
            LOG.warnf("Cannot award karma - site category %s not found", siteCategoryId);
            return;
        }

        User submitter = User.findById(siteCategory.submittedByUserId);
        if (submitter == null) {
            LOG.warnf("Cannot award karma - submitter user %s not found", siteCategory.submittedByUserId);
            return;
        }

        String reason = String.format("Site submission approved in category");
        adjustKarma(submitter, KARMA_SUBMISSION_APPROVED, reason, KarmaAudit.TRIGGER_SUBMISSION_APPROVED,
                KarmaAudit.ENTITY_TYPE_SITE_CATEGORY, siteCategoryId, null, null);
    }

    /**
     * Deducts karma for a rejected site-category submission.
     *
     * <p>
     * Called when a DirectorySiteCategory is rejected by a moderator. Deducts -2 karma from the submitter.
     *
     * @param siteCategoryId
     *            Site-category membership ID that was rejected
     */
    public void deductForRejectedSubmission(UUID siteCategoryId) {
        DirectorySiteCategory siteCategory = DirectorySiteCategory.findById(siteCategoryId);
        if (siteCategory == null) {
            LOG.warnf("Cannot deduct karma - site category %s not found", siteCategoryId);
            return;
        }

        User submitter = User.findById(siteCategory.submittedByUserId);
        if (submitter == null) {
            LOG.warnf("Cannot deduct karma - submitter user %s not found", siteCategory.submittedByUserId);
            return;
        }

        String reason = String.format("Site submission rejected in category");
        adjustKarma(submitter, KARMA_SUBMISSION_REJECTED, reason, KarmaAudit.TRIGGER_SUBMISSION_REJECTED,
                KarmaAudit.ENTITY_TYPE_SITE_CATEGORY, siteCategoryId, null, null);
    }

    /**
     * Awards karma for an upvote received on a submitted site-category.
     *
     * <p>
     * Called when a DirectoryVote with value +1 is created. Awards +1 karma to the site submitter. The karma is
     * credited to the original submitter of the site, not the category submitter.
     *
     * @param siteCategoryId
     *            Site-category membership ID that received the upvote
     * @param voteId
     *            Vote ID for audit trail
     */
    public void awardForUpvoteReceived(UUID siteCategoryId, UUID voteId) {
        DirectorySiteCategory siteCategory = DirectorySiteCategory.findById(siteCategoryId);
        if (siteCategory == null) {
            LOG.warnf("Cannot award karma - site category %s not found", siteCategoryId);
            return;
        }

        // Award karma to the user who submitted the site to this category
        User submitter = User.findById(siteCategory.submittedByUserId);
        if (submitter == null) {
            LOG.warnf("Cannot award karma - submitter user %s not found", siteCategory.submittedByUserId);
            return;
        }

        String reason = "Upvote received on submitted site";
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("site_category_id", siteCategoryId.toString());

        adjustKarma(submitter, KARMA_UPVOTE_RECEIVED, reason, KarmaAudit.TRIGGER_VOTE_RECEIVED,
                KarmaAudit.ENTITY_TYPE_VOTE, voteId, null, metadata);
    }

    /**
     * Deducts karma for a downvote received on a submitted site-category.
     *
     * <p>
     * Called when a DirectoryVote with value -1 is created. Deducts -1 karma from the site submitter.
     *
     * @param siteCategoryId
     *            Site-category membership ID that received the downvote
     * @param voteId
     *            Vote ID for audit trail
     */
    public void deductForDownvoteReceived(UUID siteCategoryId, UUID voteId) {
        DirectorySiteCategory siteCategory = DirectorySiteCategory.findById(siteCategoryId);
        if (siteCategory == null) {
            LOG.warnf("Cannot deduct karma - site category %s not found", siteCategoryId);
            return;
        }

        // Deduct karma from the user who submitted the site to this category
        User submitter = User.findById(siteCategory.submittedByUserId);
        if (submitter == null) {
            LOG.warnf("Cannot deduct karma - submitter user %s not found", siteCategory.submittedByUserId);
            return;
        }

        String reason = "Downvote received on submitted site";
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("site_category_id", siteCategoryId.toString());

        adjustKarma(submitter, KARMA_DOWNVOTE_RECEIVED, reason, KarmaAudit.TRIGGER_VOTE_RECEIVED,
                KarmaAudit.ENTITY_TYPE_VOTE, voteId, null, metadata);
    }

    /**
     * Processes karma adjustment when a vote is changed (e.g., upvote to downvote).
     *
     * <p>
     * Called when an existing DirectoryVote is updated. Calculates the net change in karma and applies the adjustment.
     * For example, changing from +1 to -1 results in a -2 karma adjustment.
     *
     * @param siteCategoryId
     *            Site-category membership ID
     * @param voteId
     *            Vote ID for audit trail
     * @param oldVoteValue
     *            Previous vote value (+1 or -1)
     * @param newVoteValue
     *            New vote value (+1 or -1)
     */
    public void processVoteChange(UUID siteCategoryId, UUID voteId, short oldVoteValue, short newVoteValue) {
        if (oldVoteValue == newVoteValue) {
            return; // No change
        }

        DirectorySiteCategory siteCategory = DirectorySiteCategory.findById(siteCategoryId);
        if (siteCategory == null) {
            LOG.warnf("Cannot process vote change - site category %s not found", siteCategoryId);
            return;
        }

        User submitter = User.findById(siteCategory.submittedByUserId);
        if (submitter == null) {
            LOG.warnf("Cannot process vote change - submitter user %s not found", siteCategory.submittedByUserId);
            return;
        }

        // Calculate net karma change
        // Example: upvote (+1) to downvote (-1) = -2 karma change
        int karmaDelta = (newVoteValue - oldVoteValue);

        String reason = String.format("Vote changed from %+d to %+d on submitted site", oldVoteValue, newVoteValue);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("site_category_id", siteCategoryId.toString());
        metadata.put("old_vote", oldVoteValue);
        metadata.put("new_vote", newVoteValue);

        adjustKarma(submitter, karmaDelta, reason, KarmaAudit.TRIGGER_VOTE_RECEIVED, KarmaAudit.ENTITY_TYPE_VOTE,
                voteId, null, metadata);
    }

    /**
     * Processes karma adjustment when a vote is deleted.
     *
     * <p>
     * Called when a DirectoryVote is removed. Reverses the karma that was originally awarded/deducted.
     *
     * @param siteCategoryId
     *            Site-category membership ID
     * @param voteId
     *            Vote ID for audit trail
     * @param deletedVoteValue
     *            Value of the deleted vote (+1 or -1)
     */
    public void processVoteDeleted(UUID siteCategoryId, UUID voteId, short deletedVoteValue) {
        DirectorySiteCategory siteCategory = DirectorySiteCategory.findById(siteCategoryId);
        if (siteCategory == null) {
            LOG.warnf("Cannot process vote deletion - site category %s not found", siteCategoryId);
            return;
        }

        User submitter = User.findById(siteCategory.submittedByUserId);
        if (submitter == null) {
            LOG.warnf("Cannot process vote deletion - submitter user %s not found", siteCategory.submittedByUserId);
            return;
        }

        // Reverse the karma effect of the deleted vote
        int karmaDelta = -deletedVoteValue;

        String reason = String.format("Vote (%+d) removed from submitted site", deletedVoteValue);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("site_category_id", siteCategoryId.toString());
        metadata.put("deleted_vote", deletedVoteValue);

        adjustKarma(submitter, karmaDelta, reason, KarmaAudit.TRIGGER_VOTE_RECEIVED, KarmaAudit.ENTITY_TYPE_VOTE,
                voteId, null, metadata);
    }

    /**
     * Manual karma adjustment by an admin.
     *
     * <p>
     * Allows super admins to manually adjust a user's karma for exceptional cases (e.g., correcting errors, penalizing
     * abuse, rewarding exceptional contributions). All manual adjustments are logged with admin user ID.
     *
     * @param userId
     *            User whose karma to adjust
     * @param delta
     *            Karma change (can be positive or negative)
     * @param reason
     *            Human-readable reason for adjustment
     * @param adminUserId
     *            Admin user making the adjustment
     * @param metadata
     *            Optional additional context
     * @throws IllegalArgumentException
     *             if user not found
     */
    public void adminAdjustKarma(UUID userId, int delta, String reason, UUID adminUserId,
            Map<String, Object> metadata) {
        User user = User.findById(userId);
        if (user == null) {
            throw new IllegalArgumentException("User not found: " + userId);
        }

        if (delta == 0) {
            LOG.warnf("Admin karma adjustment with delta=0 for user %s - ignoring", userId);
            return;
        }

        LOG.infof("Admin %s adjusting karma for user %s by %+d: %s", adminUserId, userId, delta, reason);

        adjustKarma(user, delta, reason, KarmaAudit.TRIGGER_ADMIN_ADJUSTMENT, KarmaAudit.ENTITY_TYPE_MANUAL, null,
                adminUserId, metadata);
    }

    /**
     * Core karma adjustment method with automatic trust level promotion.
     *
     * <p>
     * This method handles all karma adjustments and trust level changes. It:
     * <ul>
     * <li>Updates the user's karma value</li>
     * <li>Auto-promotes to trusted if threshold is reached</li>
     * <li>Persists the user record</li>
     * <li>Creates an audit log entry</li>
     * </ul>
     *
     * <p>
     * All operations are executed within a transaction to ensure atomicity.
     *
     * @param user
     *            User to adjust karma for
     * @param delta
     *            Karma change (positive or negative)
     * @param reason
     *            Human-readable reason
     * @param triggerType
     *            Event type that triggered adjustment
     * @param triggerEntityType
     *            Entity type that triggered adjustment (optional)
     * @param triggerEntityId
     *            Entity ID that triggered adjustment (optional)
     * @param adjustedByUserId
     *            Admin user ID for manual adjustments (optional)
     * @param metadata
     *            Additional context (optional)
     */
    private void adjustKarma(User user, int delta, String reason, String triggerType, String triggerEntityType,
            UUID triggerEntityId, UUID adjustedByUserId, Map<String, Object> metadata) {

        QuarkusTransaction.requiringNew().run(() -> {
            int oldKarma = user.directoryKarma;
            String oldTrustLevel = user.directoryTrustLevel;

            // Apply karma change (but don't allow negative karma)
            int newKarma = Math.max(0, oldKarma + delta);
            user.directoryKarma = newKarma;

            // Auto-promote to trusted if threshold reached
            String newTrustLevel = user.directoryTrustLevel;
            if (user.shouldPromoteToTrusted()) {
                user.directoryTrustLevel = User.TRUST_LEVEL_TRUSTED;
                newTrustLevel = User.TRUST_LEVEL_TRUSTED;
                LOG.infof("AUTO-PROMOTED user %s to TRUSTED (karma: %d → %d)", user.id, oldKarma, newKarma);
            }

            user.persist();

            // Create audit record
            KarmaAudit.create(user.id, oldKarma, newKarma, oldTrustLevel, newTrustLevel, reason, triggerType,
                    triggerEntityType, triggerEntityId, adjustedByUserId, metadata);

            LOG.infof("Karma adjusted for user %s: %d → %d (%+d) | Trust: %s → %s | Reason: %s", user.id, oldKarma,
                    newKarma, delta, oldTrustLevel, newTrustLevel, reason);
        });
    }

    /**
     * Manually sets a user's trust level (admin-only operation).
     *
     * <p>
     * Used to promote users to moderator or demote users for trust violations. Moderator promotion is always manual (no
     * automatic promotion path).
     *
     * @param userId
     *            User whose trust level to change
     * @param newTrustLevel
     *            New trust level (untrusted, trusted, moderator)
     * @param reason
     *            Human-readable reason for change
     * @param adminUserId
     *            Admin user making the change
     * @throws IllegalArgumentException
     *             if user not found or invalid trust level
     */
    public void setTrustLevel(UUID userId, String newTrustLevel, String reason, UUID adminUserId) {
        User user = User.findById(userId);
        if (user == null) {
            throw new IllegalArgumentException("User not found: " + userId);
        }

        if (!User.TRUST_LEVEL_UNTRUSTED.equals(newTrustLevel) && !User.TRUST_LEVEL_TRUSTED.equals(newTrustLevel)
                && !User.TRUST_LEVEL_MODERATOR.equals(newTrustLevel)) {
            throw new IllegalArgumentException("Invalid trust level: " + newTrustLevel);
        }

        if (user.directoryTrustLevel.equals(newTrustLevel)) {
            LOG.warnf("Trust level already set to %s for user %s - ignoring", newTrustLevel, userId);
            return;
        }

        QuarkusTransaction.requiringNew().run(() -> {
            String oldTrustLevel = user.directoryTrustLevel;
            user.directoryTrustLevel = newTrustLevel;
            user.persist();

            // Create audit record (delta = 0 for trust-only changes)
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("trust_level_change_only", true);

            KarmaAudit.create(user.id, user.directoryKarma, user.directoryKarma, oldTrustLevel, newTrustLevel, reason,
                    KarmaAudit.TRIGGER_ADMIN_ADJUSTMENT, KarmaAudit.ENTITY_TYPE_MANUAL, null, adminUserId, metadata);

            LOG.infof("Trust level changed for user %s: %s → %s by admin %s | Reason: %s", user.id, oldTrustLevel,
                    newTrustLevel, adminUserId, reason);
        });
    }
}
