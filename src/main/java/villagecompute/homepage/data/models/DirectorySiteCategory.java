package villagecompute.homepage.data.models;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * DirectorySiteCategory junction entity representing a site's membership in a category.
 *
 * <p>
 * Database mapping: directory_site_categories table
 * </p>
 *
 * <p>
 * This enables the many-to-many relationship between sites and categories. A single site can exist in multiple
 * categories (e.g., a news site in both "News" and "Politics"). Each site-category membership has separate voting and
 * scoring, following the DMOZ/Yahoo Directory model.
 * </p>
 *
 * <p>
 * Status lifecycle (per category):
 * <ul>
 * <li>pending → approved (moderator approval for this category)</li>
 * <li>pending → rejected (moderator rejection for this category)</li>
 * </ul>
 *
 * <p>
 * Vote aggregation: upvotes/downvotes/score are cached denormalized values updated whenever a vote is cast. Rank is
 * computed periodically by background job to order sites within each category.
 * </p>
 *
 * @see DirectorySite
 * @see DirectoryCategory
 * @see DirectoryVote
 */
@Entity
@Table(
        name = "directory_site_categories")
public class DirectorySiteCategory extends PanacheEntityBase {

    @Id
    @GeneratedValue
    @Column(
            nullable = false)
    public UUID id;

    @Column(
            name = "site_id",
            nullable = false)
    public UUID siteId;

    @Column(
            name = "category_id",
            nullable = false)
    public UUID categoryId;

    @Column(
            nullable = false)
    public int score;

    @Column(
            nullable = false)
    public int upvotes;

    @Column(
            nullable = false)
    public int downvotes;

    @Column(
            name = "rank_in_category")
    public Integer rankInCategory;

    @Column(
            name = "submitted_by_user_id",
            nullable = false)
    public UUID submittedByUserId;

    @Column(
            name = "approved_by_user_id")
    public UUID approvedByUserId;

    @Column(
            nullable = false)
    public String status;

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public Instant updatedAt;

    /**
     * Find all categories a site is in.
     *
     * @param siteId
     *            Site ID to search for
     * @return List of site-category memberships for this site
     */
    public static List<DirectorySiteCategory> findBySiteId(UUID siteId) {
        return find("siteId", siteId).list();
    }

    /**
     * Find all sites in a category.
     *
     * @param categoryId
     *            Category ID to search for
     * @return List of site-category memberships in this category
     */
    public static List<DirectorySiteCategory> findByCategoryId(UUID categoryId) {
        return find("categoryId", categoryId).list();
    }

    /**
     * Find approved sites in a category, ordered by score.
     *
     * @param categoryId
     *            Category ID to search for
     * @return List of approved sites in category, sorted by score descending
     */
    public static List<DirectorySiteCategory> findApprovedInCategory(UUID categoryId) {
        return find("categoryId = ?1 AND status = 'approved' ORDER BY score DESC, createdAt DESC", categoryId).list();
    }

    /**
     * Find pending submissions in a category (moderation queue).
     *
     * @param categoryId
     *            Category ID to search for
     * @return List of pending submissions in this category
     */
    public static List<DirectorySiteCategory> findPendingInCategory(UUID categoryId) {
        return find("categoryId = ?1 AND status = 'pending' ORDER BY createdAt ASC", categoryId).list();
    }

    /**
     * Find all pending submissions across all categories.
     *
     * @return List of all pending submissions
     */
    public static List<DirectorySiteCategory> findAllPending() {
        return find("status = 'pending' ORDER BY createdAt ASC").list();
    }

    /**
     * Check if a site already exists in a category.
     *
     * @param siteId
     *            Site ID
     * @param categoryId
     *            Category ID
     * @return Optional containing the membership if it exists
     */
    public static Optional<DirectorySiteCategory> findBySiteAndCategory(UUID siteId, UUID categoryId) {
        return find("siteId = ?1 AND categoryId = ?2", siteId, categoryId).firstResultOptional();
    }

    /**
     * Find user's submitted sites in a category.
     *
     * @param userId
     *            User ID
     * @param categoryId
     *            Category ID
     * @return List of user's submissions in this category
     */
    public static List<DirectorySiteCategory> findByUserAndCategory(UUID userId, UUID categoryId) {
        return find("submittedByUserId = ?1 AND categoryId = ?2 ORDER BY createdAt DESC", userId, categoryId).list();
    }

    /**
     * Approves this site-category membership.
     *
     * <p>
     * Side effects:
     * <ul>
     * <li>Changes status to approved</li>
     * <li>Records approving moderator</li>
     * <li>Increments category's link count</li>
     * </ul>
     *
     * @param approvedByUserId
     *            ID of the moderator approving this submission
     * @return this site-category membership for method chaining
     */
    public DirectorySiteCategory approve(UUID approvedByUserId) {
        this.status = "approved";
        this.approvedByUserId = approvedByUserId;
        this.updatedAt = Instant.now();
        this.persist();

        // Increment category link count
        DirectoryCategory.incrementLinkCount(this.categoryId);

        return this;
    }

    /**
     * Rejects this site-category membership.
     *
     * @return this site-category membership for method chaining
     */
    public DirectorySiteCategory reject() {
        this.status = "rejected";
        this.updatedAt = Instant.now();
        this.persist();
        return this;
    }

    /**
     * Updates vote aggregates (score, upvotes, downvotes) from votes table.
     *
     * <p>
     * Recalculates cached values by counting DirectoryVote records.
     * </p>
     */
    public void updateAggregates() {
        long upvoteCount = DirectoryVote.count("siteCategoryId = ?1 AND vote = 1", this.id);
        long downvoteCount = DirectoryVote.count("siteCategoryId = ?1 AND vote = -1", this.id);

        this.upvotes = (int) upvoteCount;
        this.downvotes = (int) downvoteCount;
        this.score = (int) (upvoteCount - downvoteCount);
        this.updatedAt = Instant.now();
        this.persist();
    }

    /**
     * Finds bubbled sites for a parent category.
     *
     * <p>
     * Bubbling criteria (Feature F13.8):
     * <ul>
     * <li>Site must be in a child category of the specified parent</li>
     * <li>Site must have score ≥ 10</li>
     * <li>Site must have rank ≤ 3 in its category</li>
     * <li>Site must have status = 'approved'</li>
     * </ul>
     *
     * <p>
     * <b>UI Display:</b> Bubbled sites show with yellow background (#fff9e6) and green badge indicating source category.
     * They are sorted AFTER direct sites but still ordered by score DESC within the bubbled group.
     *
     * @param parentCategoryId
     *            Parent category UUID
     * @return List of bubbled sites sorted by score DESC
     */
    public static List<DirectorySiteCategory> findBubbledSites(UUID parentCategoryId) {
        if (parentCategoryId == null) {
            return List.of();
        }

        // Find all child categories
        List<DirectoryCategory> children = DirectoryCategory.findByParentId(parentCategoryId);

        if (children.isEmpty()) {
            return List.of();
        }

        // Build query to find bubbled sites across all children
        // Sites must meet: score >= 10 AND rank <= 3 AND status = 'approved'
        StringBuilder query = new StringBuilder();
        query.append("categoryId IN (");

        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                query.append(", ");
            }
            query.append("?").append(i + 1);
        }

        query.append(") AND status = 'approved' ");
        query.append("AND score >= 10 ");
        query.append("AND rankInCategory <= 3 ");
        query.append("ORDER BY score DESC, createdAt DESC");

        // Extract child IDs as parameters
        Object[] params = children.stream().map(c -> c.id).toArray();

        return find(query.toString(), params).list();
    }
}
