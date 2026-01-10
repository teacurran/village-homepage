package villagecompute.homepage.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.DirectoryVote;
import villagecompute.homepage.data.models.DirectorySiteCategory;
import villagecompute.homepage.exceptions.ResourceNotFoundException;
import villagecompute.homepage.exceptions.ValidationException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * DirectoryVotingService handles voting logic for the Good Sites directory.
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>Create/update/delete votes on site-category memberships</li>
 * <li>Update cached vote aggregates on DirectorySiteCategory</li>
 * <li>Trigger karma adjustments for site submitters</li>
 * <li>Enforce one-vote-per-user constraint</li>
 * </ul>
 *
 * <p>
 * Karma Integration:
 * <ul>
 * <li>New upvote → +1 karma to submitter</li>
 * <li>New downvote → -1 karma to submitter</li>
 * <li>Change vote → net karma adjustment</li>
 * <li>Delete vote → reverse karma effect</li>
 * </ul>
 */
@ApplicationScoped
public class DirectoryVotingService {

    private static final Logger LOG = Logger.getLogger(DirectoryVotingService.class);

    @Inject
    KarmaService karmaService;

    /**
     * Casts or updates a vote on a site-category membership.
     *
     * <p>
     * If user has already voted, updates existing vote. Otherwise creates new vote. Triggers karma adjustments and
     * updates cached aggregates.
     *
     * @param siteCategoryId
     *            Site-category membership ID to vote on
     * @param userId
     *            User casting the vote
     * @param voteValue
     *            Vote value (+1 for upvote, -1 for downvote)
     * @throws ResourceNotFoundException
     *             if site-category not found
     * @throws ValidationException
     *             if vote value invalid or site-category not approved
     */
    @Transactional
    public void castVote(UUID siteCategoryId, UUID userId, short voteValue) {
        // Validate vote value
        if (voteValue != 1 && voteValue != -1) {
            throw new ValidationException("Vote value must be +1 (upvote) or -1 (downvote)");
        }

        // Verify site-category exists and is approved
        DirectorySiteCategory siteCategory = DirectorySiteCategory.findById(siteCategoryId);
        if (siteCategory == null) {
            throw new ResourceNotFoundException("Site-category not found: " + siteCategoryId);
        }

        if (!"approved".equals(siteCategory.status)) {
            throw new ValidationException("Can only vote on approved site-category memberships");
        }

        // Check if user already voted
        Optional<DirectoryVote> existingVote = DirectoryVote.findByUserAndSiteCategory(siteCategoryId, userId);

        if (existingVote.isPresent()) {
            // Update existing vote
            DirectoryVote vote = existingVote.get();
            short oldVoteValue = vote.vote;

            if (oldVoteValue == voteValue) {
                LOG.infof("User %s already voted %+d on site-category %s - no change", userId, voteValue,
                        siteCategoryId);
                return;
            }

            vote.vote = voteValue;
            vote.updatedAt = Instant.now();
            vote.persist();

            LOG.infof("Updated vote %s: %+d → %+d", vote.id, oldVoteValue, voteValue);

            // Trigger karma adjustment for vote change
            karmaService.processVoteChange(siteCategoryId, vote.id, oldVoteValue, voteValue);

            // Update cached aggregates
            vote.updateAggregates();

        } else {
            // Create new vote
            DirectoryVote vote = new DirectoryVote();
            vote.siteCategoryId = siteCategoryId;
            vote.userId = userId;
            vote.vote = voteValue;
            vote.createdAt = Instant.now();
            vote.updatedAt = Instant.now();
            vote.persist();

            LOG.infof("Created new vote %s: user %s voted %+d on site-category %s", vote.id, userId, voteValue,
                    siteCategoryId);

            // Trigger karma adjustment for new vote
            if (voteValue == 1) {
                karmaService.awardForUpvoteReceived(siteCategoryId, vote.id);
            } else {
                karmaService.deductForDownvoteReceived(siteCategoryId, vote.id);
            }

            // Update cached aggregates
            vote.updateAggregates();
        }
    }

    /**
     * Removes a user's vote from a site-category membership.
     *
     * <p>
     * Deletes the vote record, reverses karma adjustment, and updates cached aggregates.
     *
     * @param siteCategoryId
     *            Site-category membership ID
     * @param userId
     *            User removing their vote
     * @throws ResourceNotFoundException
     *             if vote not found
     */
    @Transactional
    public void removeVote(UUID siteCategoryId, UUID userId) {
        Optional<DirectoryVote> voteOpt = DirectoryVote.findByUserAndSiteCategory(siteCategoryId, userId);

        if (voteOpt.isEmpty()) {
            throw new ResourceNotFoundException(
                    "Vote not found for user " + userId + " on site-category " + siteCategoryId);
        }

        DirectoryVote vote = voteOpt.get();
        UUID voteId = vote.id;
        short voteValue = vote.vote;

        // Delete the vote
        vote.delete();

        LOG.infof("Deleted vote %s: user %s removed %+d vote from site-category %s", voteId, userId, voteValue,
                siteCategoryId);

        // Reverse karma effect
        karmaService.processVoteDeleted(siteCategoryId, voteId, voteValue);

        // Update cached aggregates (need to fetch site-category since vote is deleted)
        DirectorySiteCategory siteCategory = DirectorySiteCategory.findById(siteCategoryId);
        if (siteCategory != null) {
            siteCategory.updateAggregates();
        }
    }

    /**
     * Gets a user's current vote on a site-category membership.
     *
     * @param siteCategoryId
     *            Site-category membership ID
     * @param userId
     *            User ID
     * @return Optional containing vote value (+1 or -1) if user has voted, empty otherwise
     */
    @Transactional
    public Optional<Short> getUserVote(UUID siteCategoryId, UUID userId) {
        return DirectoryVote.getUserVote(siteCategoryId, userId);
    }
}
