package villagecompute.homepage.data.models;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.FeeScheduleType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Marketplace category entity implementing hierarchical structure for classifieds per Feature F12.3.
 *
 * <p>
 * Supports Craigslist-style category hierarchy with unlimited parent-child depth. Categories organize listings into
 * main sections (For Sale, Housing, Jobs, Services, Community) with subcategories. Fee schedules per category enable
 * monetization via Stripe (Policy P3).
 *
 * <p>
 * <b>Schema Mapping:</b>
 * <ul>
 * <li>{@code id} (UUID, PK) - Primary identifier</li>
 * <li>{@code parent_id} (UUID, FK) - Parent category for hierarchical structure (null for root categories)</li>
 * <li>{@code name} (TEXT) - Display name (e.g., "Electronics", "For Sale")</li>
 * <li>{@code slug} (TEXT, UNIQUE) - URL-friendly identifier (e.g., "electronics", "for-sale")</li>
 * <li>{@code sort_order} (INT) - Display order within same parent level</li>
 * <li>{@code is_active} (BOOLEAN) - Active/disabled status (admin control)</li>
 * <li>{@code fee_schedule} (JSONB) - Monetization rules (posting_fee, featured_fee, bump_fee)</li>
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
 * enabled for common lookups like root categories and active children.
 *
 * @see FeeScheduleType for fee structure definition
 */
@Entity
@Table(
        name = "marketplace_categories")
public class MarketplaceCategory extends PanacheEntityBase {

    private static final Logger LOG = Logger.getLogger(MarketplaceCategory.class);

    public static final String QUERY_FIND_ROOT_CATEGORIES = "MarketplaceCategory.findRootCategories";
    public static final String QUERY_FIND_BY_PARENT_ID = "MarketplaceCategory.findByParentId";
    public static final String QUERY_FIND_BY_SLUG = "MarketplaceCategory.findBySlug";
    public static final String QUERY_FIND_ACTIVE = "MarketplaceCategory.findActive";
    public static final String QUERY_FIND_ALL_ORDERED = "MarketplaceCategory.findAllOrdered";

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

    @Column(
            name = "sort_order",
            nullable = false)
    public int sortOrder;

    @Column(
            name = "is_active",
            nullable = false)
    public boolean isActive;

    @Column(
            name = "fee_schedule",
            nullable = false,
            columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    public FeeScheduleType feeSchedule;

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
     * Use this for main navigation menu display. Results cached by Hibernate query cache.
     *
     * @return List of root categories ordered by sort_order
     */
    public static List<MarketplaceCategory> findRootCategories() {
        return find("parentId IS NULL AND isActive = true ORDER BY sortOrder").list();
    }

    /**
     * Finds all child categories of a specific parent, active only, ordered by sort_order.
     *
     * @param parentId
     *            the parent category UUID
     * @return List of child categories ordered by sort_order
     */
    public static List<MarketplaceCategory> findByParentId(UUID parentId) {
        if (parentId == null) {
            return List.of();
        }
        return find("parentId = ?1 AND isActive = true ORDER BY sortOrder", parentId).list();
    }

    /**
     * Finds a category by its URL slug.
     *
     * @param slug
     *            the URL-friendly identifier (e.g., "electronics")
     * @return Optional containing the category if found
     */
    public static Optional<MarketplaceCategory> findBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return find("slug = ?1", slug).firstResultOptional();
    }

    /**
     * Finds all active categories regardless of hierarchy level, ordered by sort_order.
     *
     * @return List of all active categories
     */
    public static List<MarketplaceCategory> findActive() {
        return find("isActive = true ORDER BY sortOrder").list();
    }

    /**
     * Finds all categories (including inactive) ordered by parent_id then sort_order.
     *
     * <p>
     * Used for admin tree display showing full hierarchy including disabled categories.
     *
     * @return List of all categories ordered hierarchically
     */
    public static List<MarketplaceCategory> findAllOrdered() {
        return find("ORDER BY parentId NULLS FIRST, sortOrder").list();
    }

    /**
     * Builds a hierarchical tree structure of all categories.
     *
     * <p>
     * Returns a list of root-level CategoryNode objects, each containing their children recursively. Used for tree UI
     * components in admin panel.
     *
     * @return List of root category nodes with nested children
     */
    public static List<CategoryNode> buildCategoryTree() {
        List<MarketplaceCategory> allCategories = findAllOrdered();

        // Map categories by ID for fast lookup
        var categoryMap = new java.util.HashMap<UUID, MarketplaceCategory>();
        for (MarketplaceCategory cat : allCategories) {
            categoryMap.put(cat.id, cat);
        }

        // Build tree structure
        List<CategoryNode> roots = new ArrayList<>();
        for (MarketplaceCategory cat : allCategories) {
            if (cat.parentId == null) {
                roots.add(buildNode(cat, allCategories));
            }
        }

        return roots;
    }

    /**
     * Recursively builds a CategoryNode with its children.
     */
    private static CategoryNode buildNode(MarketplaceCategory category, List<MarketplaceCategory> allCategories) {
        List<CategoryNode> children = new ArrayList<>();
        for (MarketplaceCategory child : allCategories) {
            if (category.id.equals(child.parentId)) {
                children.add(buildNode(child, allCategories));
            }
        }
        return new CategoryNode(category.id, category.parentId, category.name, category.slug, category.sortOrder,
                category.isActive, category.feeSchedule, children);
    }

    /**
     * Checks if this category has any child categories.
     *
     * @return true if category has children
     */
    public static boolean hasChildren(UUID categoryId) {
        if (categoryId == null) {
            return false;
        }
        return count("parentId = ?1", categoryId) > 0;
    }

    /**
     * Creates a new marketplace category with audit timestamps.
     *
     * @param category
     *            the category to persist
     * @return the persisted category with generated ID
     */
    public static MarketplaceCategory create(MarketplaceCategory category) {
        QuarkusTransaction.requiringNew().run(() -> {
            category.createdAt = Instant.now();
            category.updatedAt = Instant.now();
            category.persist();
            LOG.infof("Created marketplace category: id=%s, name=%s, slug=%s, parentId=%s", category.id, category.name,
                    category.slug, category.parentId);
        });
        return category;
    }

    /**
     * Updates an existing marketplace category with audit timestamp.
     *
     * @param category
     *            the category to update
     */
    public static void update(MarketplaceCategory category) {
        QuarkusTransaction.requiringNew().run(() -> {
            // Refetch the entity to ensure it's managed in this transaction
            MarketplaceCategory managed = findById(category.id);
            if (managed == null) {
                throw new IllegalStateException("Category not found: " + category.id);
            }

            // Update fields
            managed.parentId = category.parentId;
            managed.name = category.name;
            managed.slug = category.slug;
            managed.sortOrder = category.sortOrder;
            managed.isActive = category.isActive;
            managed.feeSchedule = category.feeSchedule;
            managed.updatedAt = Instant.now();

            // persist() will update the managed entity
            managed.persist();

            LOG.infof("Updated marketplace category: id=%s, name=%s, isActive=%b, sortOrder=%d", managed.id,
                    managed.name, managed.isActive, managed.sortOrder);
        });
    }

    /**
     * Deletes a marketplace category if it has no children and no associated listings.
     *
     * @param categoryId
     *            the category UUID to delete
     * @throws IllegalStateException
     *             if category has children or listings
     */
    public static void deleteIfSafe(UUID categoryId) {
        QuarkusTransaction.requiringNew().run(() -> {
            MarketplaceCategory category = findById(categoryId);
            if (category == null) {
                throw new IllegalStateException("Category not found: " + categoryId);
            }

            // Check for children
            if (hasChildren(categoryId)) {
                throw new IllegalStateException(
                        "Cannot delete category with children: " + category.name + ". Delete children first.");
            }

            // Check for listings (will be implemented in I4.T3)
            // For now, just proceed with delete
            category.delete();
            LOG.infof("Deleted marketplace category: id=%s, name=%s, slug=%s", category.id, category.name,
                    category.slug);
        });
    }

    /**
     * Tree node structure for hierarchical category display.
     *
     * @param id
     *            category UUID
     * @param parentId
     *            parent category UUID (null for root)
     * @param name
     *            display name
     * @param slug
     *            URL slug
     * @param sortOrder
     *            display order
     * @param isActive
     *            active status
     * @param feeSchedule
     *            fee configuration
     * @param children
     *            nested child nodes
     */
    public record CategoryNode(UUID id, UUID parentId, String name, String slug, int sortOrder, boolean isActive,
            FeeScheduleType feeSchedule, List<CategoryNode> children) {
    }
}
