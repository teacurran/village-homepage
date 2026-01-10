package villagecompute.homepage.data.models;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * DirectoryVote entity representing a user's vote on a site-category membership.
 *
 * <p>
 * Database mapping: directory_votes table
 * </p>
 *
 * <p>
 * Vote mechanics:
 * <ul>
 * <li>Vote value: +1 (upvote) or -1 (downvote)</li>
 * <li>One vote per user per site+category (enforced by unique constraint)</li>
 * <li>Login required (anonymous users cannot vote)</li>
 * <li>Users can change their vote (update existing row)</li>
 * <li>Deleting vote is allowed (removes row)</li>
 * </ul>
 *
 * <p>
 * Voting is scoped to the site-category combination, following the principle that a site might be excellent in one
 * category but mediocre in another.
 * </p>
 *
 * @see DirectorySiteCategory
 * @see DirectorySite
 */
@Entity
@Table(
        name = "directory_votes")
public class DirectoryVote extends PanacheEntityBase {

    @Id
    @GeneratedValue
    @Column(
            nullable = false)
    public UUID id;

    @Column(
            name = "site_category_id",
            nullable = false)
    public UUID siteCategoryId;

    @Column(
            name = "user_id",
            nullable = false)
    public UUID userId;

    @Column(
            nullable = false)
    public short vote;

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public Instant updatedAt;

    /**
     * Check if user has already voted on this site+category.
     *
     * @param siteCategoryId
     *            Site-category membership ID
     * @param userId
     *            User ID
     * @return true if user has voted, false otherwise
     */
    public static boolean hasUserVoted(UUID siteCategoryId, UUID userId) {
        return count("siteCategoryId = ?1 AND userId = ?2", siteCategoryId, userId) > 0;
    }

    /**
     * Get user's existing vote value on a site+category.
     *
     * @param siteCategoryId
     *            Site-category membership ID
     * @param userId
     *            User ID
     * @return Optional containing vote value (+1 or -1) if user has voted
     */
    public static Optional<Short> getUserVote(UUID siteCategoryId, UUID userId) {
        Optional<DirectoryVote> vote = find("siteCategoryId = ?1 AND userId = ?2", siteCategoryId, userId)
                .firstResultOptional();
        return vote.map(v -> v.vote);
    }

    /**
     * Find user's existing vote record on a site+category.
     *
     * @param siteCategoryId
     *            Site-category membership ID
     * @param userId
     *            User ID
     * @return Optional containing the vote record if it exists
     */
    public static Optional<DirectoryVote> findByUserAndSiteCategory(UUID siteCategoryId, UUID userId) {
        return find("siteCategoryId = ?1 AND userId = ?2", siteCategoryId, userId).firstResultOptional();
    }

    /**
     * Find all votes for a site-category membership.
     *
     * @param siteCategoryId
     *            Site-category membership ID
     * @return List of all votes on this site+category
     */
    public static List<DirectoryVote> findBySiteCategoryId(UUID siteCategoryId) {
        return find("siteCategoryId", siteCategoryId).list();
    }

    /**
     * Find user's voting history.
     *
     * @param userId
     *            User ID
     * @return List of user's votes, ordered by most recent first
     */
    public static List<DirectoryVote> findByUserId(UUID userId) {
        return find("userId = ?1 ORDER BY createdAt DESC", userId).list();
    }

    /**
     * Count upvotes for a site-category membership.
     *
     * @param siteCategoryId
     *            Site-category membership ID
     * @return Number of upvotes
     */
    public static long countUpvotes(UUID siteCategoryId) {
        return count("siteCategoryId = ?1 AND vote = 1", siteCategoryId);
    }

    /**
     * Count downvotes for a site-category membership.
     *
     * @param siteCategoryId
     *            Site-category membership ID
     * @return Number of downvotes
     */
    public static long countDownvotes(UUID siteCategoryId) {
        return count("siteCategoryId = ?1 AND vote = -1", siteCategoryId);
    }

    /**
     * Calculate score (upvotes - downvotes) for a site-category membership.
     *
     * @param siteCategoryId
     *            Site-category membership ID
     * @return Score value
     */
    public static int calculateScore(UUID siteCategoryId) {
        long upvotes = countUpvotes(siteCategoryId);
        long downvotes = countDownvotes(siteCategoryId);
        return (int) (upvotes - downvotes);
    }

    /**
     * Updates the cached aggregates on the associated DirectorySiteCategory.
     *
     * <p>
     * This should be called after any vote is created, updated, or deleted to keep the denormalized
     * upvotes/downvotes/score values in sync.
     * </p>
     */
    public void updateAggregates() {
        DirectorySiteCategory siteCategory = DirectorySiteCategory.findById(this.siteCategoryId);
        if (siteCategory != null) {
            siteCategory.updateAggregates();
        }
    }
}
