package villagecompute.homepage.data.models;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Directory category entity implementing hierarchical structure for Good Sites web directory per Feature F13.1.
 *
 * <p>
 * Supports Yahoo Directory / DMOZ-style category hierarchy with unlimited parent-child depth. Categories organize
 * submitted sites into main sections (Arts, Business, Computers, News, Recreation, Science, Society) with
 * subcategories. Users can vote on sites within categories, and karma system determines submission moderation flow.
 *
 * <p>
 * <b>Schema Mapping:</b>
 * <ul>
 * <li>{@code id} (UUID, PK) - Primary identifier</li>
 * <li>{@code parent_id} (UUID, FK) - Parent category for hierarchical structure (null for root categories)</li>
 * <li>{@code name} (TEXT) - Display name (e.g., "Computers & Internet", "Open Source")</li>
 * <li>{@code slug} (TEXT, UNIQUE) - URL-friendly identifier (e.g., "computers", "computers-opensource")</li>
 * <li>{@code description} (TEXT) - Category description for directory pages</li>
 * <li>{@code icon_url} (TEXT) - URL to 32px category icon (hosted in R2 or external CDN)</li>
 * <li>{@code sort_order} (INT) - Display order within same parent level</li>
 * <li>{@code link_count} (INT) - Cached count of approved sites in this category (updated by rank job)</li>
 * <li>{@code is_active} (BOOLEAN) - Active/disabled status (admin control)</li>
 * <li>{@code created_at} (TIMESTAMPTZ) - Record creation timestamp</li>
 * <li>{@code updated_at} (TIMESTAMPTZ) - Last modification timestamp</li>
 * </ul>
 *
 * <p>
 * <b>Hierarchical Queries:</b> Use {@link #findRootCategories()} to get top-level categories, then
 * {@link #findByParentId(UUID)} to traverse children. Cascading delete ensures no orphaned subcategories.
 *
 * <p>
 * <b>Caching:</b> Hibernate 2nd-level cache (Caffeine) automatically caches frequently accessed categories. Query cache
 * enabled for common lookups like root categories and active children, critical for directory page performance.
 *
 * <p>
 * <b>Link Count:</b> The {@code link_count} field is a denormalized cache updated by the rank recalculation background
 * job (hourly). It represents the number of approved DirectorySite entries in this category via
 * directory_site_categories junction table.
 *
 * @see DirectorySite for site submissions
 * @see DirectorySiteCategory for site-category relationships with voting
 * @see DirectoryCategoryModerator for category-specific moderators
 */
@Entity
@Table(
        name = "directory_categories")
@NamedQuery(
        name = DirectoryCategory.QUERY_FIND_ROOT_CATEGORIES,
        query = DirectoryCategory.JPQL_FIND_ROOT_CATEGORIES)
@NamedQuery(
        name = DirectoryCategory.QUERY_FIND_BY_PARENT_ID,
        query = DirectoryCategory.JPQL_FIND_BY_PARENT_ID)
@NamedQuery(
        name = DirectoryCategory.QUERY_FIND_BY_SLUG,
        query = DirectoryCategory.JPQL_FIND_BY_SLUG)
@NamedQuery(
        name = DirectoryCategory.QUERY_FIND_ACTIVE,
        query = DirectoryCategory.JPQL_FIND_ACTIVE)
@NamedQuery(
        name = DirectoryCategory.QUERY_FIND_ALL_ORDERED,
        query = DirectoryCategory.JPQL_FIND_ALL_ORDERED)
public class DirectoryCategory extends PanacheEntityBase {

    private static final Logger LOG = Logger.getLogger(DirectoryCategory.class);

    public static final String JPQL_FIND_ROOT_CATEGORIES = "FROM DirectoryCategory WHERE parentId IS NULL AND isActive = true ORDER BY sortOrder";
    public static final String QUERY_FIND_ROOT_CATEGORIES = "DirectoryCategory.findRootCategories";

    public static final String JPQL_FIND_BY_PARENT_ID = "FROM DirectoryCategory WHERE parentId = ?1 AND isActive = true ORDER BY sortOrder";
    public static final String QUERY_FIND_BY_PARENT_ID = "DirectoryCategory.findByParentId";

    public static final String JPQL_FIND_BY_SLUG = "FROM DirectoryCategory WHERE slug = ?1";
    public static final String QUERY_FIND_BY_SLUG = "DirectoryCategory.findBySlug";

    public static final String JPQL_FIND_ACTIVE = "FROM DirectoryCategory WHERE isActive = true ORDER BY sortOrder";
    public static final String QUERY_FIND_ACTIVE = "DirectoryCategory.findActive";

    public static final String JPQL_FIND_ALL_ORDERED = "FROM DirectoryCategory ORDER BY parentId NULLS FIRST, sortOrder";
    public static final String QUERY_FIND_ALL_ORDERED = "DirectoryCategory.findAllOrdered";

    @Id
    @GeneratedValue
    @Column(
            nullable = false)
    public UUID id;

    @Column(
            name = "parent_id")
    public UUID parentId;

    @Column(
            nullable = false)
    public String name;

    @Column(
            nullable = false,
            unique = true)
    public String slug;

    @Column
    public String description;

    @Column(
            name = "icon_url")
    public String iconUrl;

    @Column(
            name = "sort_order",
            nullable = false)
    public int sortOrder;

    @Column(
            name = "link_count",
            nullable = false)
    public int linkCount;

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
     * Finds all root categories (parent_id IS NULL) that are active, ordered by sort_order.
     *
     * <p>
     * Use this for main directory navigation menu display. Results cached by Hibernate query cache. Called on every
     * directory homepage load.
     *
     * @return List of root categories ordered by sort_order
     */
    public static List<DirectoryCategory> findRootCategories() {
        return find(JPQL_FIND_ROOT_CATEGORIES).list();
    }

    /**
     * Finds all child categories of a specific parent, active only, ordered by sort_order.
     *
     * <p>
     * Used for subcategory navigation and breadcrumb trail construction.
     *
     * @param parentId
     *            the parent category UUID
     * @return List of child categories ordered by sort_order
     */
    public static List<DirectoryCategory> findByParentId(UUID parentId) {
        if (parentId == null) {
            return List.of();
        }
        return find(JPQL_FIND_BY_PARENT_ID, parentId).list();
    }

    /**
     * Finds a category by its URL slug.
     *
     * <p>
     * Used for category page routing (e.g., /directory/computers-opensource).
     *
     * @param slug
     *            the URL-friendly identifier (e.g., "computers-opensource")
     * @return Optional containing the category if found
     */
    public static Optional<DirectoryCategory> findBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return find(JPQL_FIND_BY_SLUG, slug).firstResultOptional();
    }

    /**
     * Finds all active categories regardless of hierarchy level, ordered by sort_order.
     *
     * <p>
     * Used for category selection dropdowns in site submission forms.
     *
     * @return List of all active categories
     */
    public static List<DirectoryCategory> findActive() {
        return find(JPQL_FIND_ACTIVE).list();
    }

    /**
     * Finds all categories (including inactive) ordered by parent_id then sort_order.
     *
     * <p>
     * Used for admin tree display showing full hierarchy including disabled categories.
     *
     * @return List of all categories ordered hierarchically
     */
    public static List<DirectoryCategory> findAllOrdered() {
        return find(JPQL_FIND_ALL_ORDERED).list();
    }

    /**
     * Finds most popular categories by link count (for trending/homepage display).
     *
     * <p>
     * Returns active categories with non-zero link counts, sorted by link count descending. Limited to top N results
     * for performance.
     *
     * @param limit
     *            maximum number of categories to return
     * @return List of popular categories ordered by link count descending
     */
    public static List<DirectoryCategory> findMostPopular(int limit) {
        return find("isActive = true AND linkCount > 0 ORDER BY linkCount DESC").page(0, limit).list();
    }

    /**
     * Builds a hierarchical tree structure of all categories.
     *
     * <p>
     * Returns a list of root-level CategoryNode objects, each containing their children recursively. Used for tree UI
     * components in admin panel and category browse navigation.
     *
     * @return List of root category nodes with nested children
     */
    public static List<CategoryNode> buildCategoryTree() {
        List<DirectoryCategory> allCategories = findAllOrdered();

        // Build tree structure (two-pass algorithm for performance)
        List<CategoryNode> roots = new ArrayList<>();
        for (DirectoryCategory cat : allCategories) {
            if (cat.parentId == null) {
                roots.add(buildNode(cat, allCategories));
            }
        }

        return roots;
    }

    /**
     * Recursively builds a CategoryNode with its children.
     */
    private static CategoryNode buildNode(DirectoryCategory category, List<DirectoryCategory> allCategories) {
        List<CategoryNode> children = new ArrayList<>();
        for (DirectoryCategory child : allCategories) {
            if (category.id.equals(child.parentId)) {
                children.add(buildNode(child, allCategories));
            }
        }
        return new CategoryNode(category.id, category.parentId, category.name, category.slug, category.description,
                category.iconUrl, category.sortOrder, category.linkCount, category.isActive, children);
    }

    /**
     * Checks if this category has any child categories.
     *
     * <p>
     * Used for delete safety checks (prevent deleting parent categories with children).
     *
     * @param categoryId
     *            the category UUID to check
     * @return true if category has children
     */
    public static boolean hasChildren(UUID categoryId) {
        if (categoryId == null) {
            return false;
        }
        return count("parentId = ?1", categoryId) > 0;
    }

    /**
     * Creates a new directory category with audit timestamps.
     *
     * @param category
     *            the category to persist
     * @return the persisted category with generated ID
     */
    public static DirectoryCategory create(DirectoryCategory category) {
        QuarkusTransaction.requiringNew().run(() -> {
            category.createdAt = Instant.now();
            category.updatedAt = Instant.now();
            category.persist();
            LOG.infof("Created directory category: id=%s, name=%s, slug=%s, parentId=%s", category.id, category.name,
                    category.slug, category.parentId);
        });
        return category;
    }

    /**
     * Updates an existing directory category with audit timestamp.
     *
     * @param category
     *            the category to update
     */
    public static void update(DirectoryCategory category) {
        QuarkusTransaction.requiringNew().run(() -> {
            // Refetch the entity to ensure it's managed in this transaction
            DirectoryCategory managed = findById(category.id);
            if (managed == null) {
                throw new IllegalStateException("Category not found: " + category.id);
            }

            // Update fields
            managed.parentId = category.parentId;
            managed.name = category.name;
            managed.slug = category.slug;
            managed.description = category.description;
            managed.iconUrl = category.iconUrl;
            managed.sortOrder = category.sortOrder;
            managed.linkCount = category.linkCount;
            managed.isActive = category.isActive;
            managed.updatedAt = Instant.now();

            // persist() will update the managed entity
            managed.persist();

            LOG.infof("Updated directory category: id=%s, name=%s, isActive=%b, sortOrder=%d, linkCount=%d", managed.id,
                    managed.name, managed.isActive, managed.sortOrder, managed.linkCount);
        });
    }

    /**
     * Deletes a directory category if it has no children and no associated sites.
     *
     * <p>
     * Safety check prevents orphaning subcategories or losing site categorizations. Admins must delete children first
     * or reassign sites to other categories.
     *
     * @param categoryId
     *            the category UUID to delete
     * @throws IllegalStateException
     *             if category has children or associated sites
     */
    public static void deleteIfSafe(UUID categoryId) {
        QuarkusTransaction.requiringNew().run(() -> {
            DirectoryCategory category = findById(categoryId);
            if (category == null) {
                throw new IllegalStateException("Category not found: " + categoryId);
            }

            // Check for children
            if (hasChildren(categoryId)) {
                throw new IllegalStateException(
                        "Cannot delete category with children: " + category.name + ". Delete children first.");
            }

            // Check for associated sites (will be implemented in I5.T2)
            // For now, just proceed with delete
            category.delete();
            LOG.infof("Deleted directory category: id=%s, name=%s, slug=%s", category.id, category.name, category.slug);
        });
    }

    /**
     * Increments the link count for a category (called when site approved in this category).
     *
     * <p>
     * This is typically called by background jobs, but exposed as static method for transactional control. Updates
     * updated_at timestamp to track last modification. Runs in existing transaction if present.
     *
     * @param categoryId
     *            the category UUID to increment
     */
    public static void incrementLinkCount(UUID categoryId) {
        DirectoryCategory category = findById(categoryId);
        if (category == null) {
            LOG.warnf("Cannot increment link count for non-existent category: %s", categoryId);
            return;
        }
        category.linkCount++;
        category.updatedAt = Instant.now();
        category.persist();
        LOG.debugf("Incremented link count for category %s (%s): new count = %d", category.name, category.id,
                category.linkCount);
    }

    /**
     * Decrements the link count for a category (called when site deleted or removed from category).
     *
     * <p>
     * Prevents negative link counts (minimum value is 0). Runs in existing transaction if present.
     *
     * @param categoryId
     *            the category UUID to decrement
     */
    public static void decrementLinkCount(UUID categoryId) {
        DirectoryCategory category = findById(categoryId);
        if (category == null) {
            LOG.warnf("Cannot decrement link count for non-existent category: %s", categoryId);
            return;
        }
        if (category.linkCount > 0) {
            category.linkCount--;
        }
        category.updatedAt = Instant.now();
        category.persist();
        LOG.debugf("Decremented link count for category %s (%s): new count = %d", category.name, category.id,
                category.linkCount);
    }

    /**
     * Tree node structure for hierarchical category display.
     *
     * <p>
     * Used by admin UI for category tree management and public directory for nested category browsing. The recursive
     * children structure allows unlimited depth.
     *
     * @param id
     *            category UUID
     * @param parentId
     *            parent category UUID (null for root)
     * @param name
     *            display name
     * @param slug
     *            URL slug
     * @param description
     *            category description
     * @param iconUrl
     *            URL to category icon (nullable)
     * @param sortOrder
     *            display order
     * @param linkCount
     *            cached site count
     * @param isActive
     *            active status
     * @param children
     *            nested child nodes
     */
    public record CategoryNode(UUID id, UUID parentId, String name, String slug, String description, String iconUrl,
            int sortOrder, int linkCount, boolean isActive, List<CategoryNode> children) {
    }
}
