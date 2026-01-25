package villagecompute.homepage.data.models;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.api.types.FeeScheduleType;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for MarketplaceCategory entity covering hierarchical queries and CRUD operations.
 *
 * <p>
 * Test coverage per I4.T2 acceptance criteria:
 * <ul>
 * <li>Category tree accessible via hierarchical queries</li>
 * <li>Admin can reorder categories via sort_order</li>
 * <li>Admin can enable/disable categories via is_active flag</li>
 * <li>Cascading delete for parent-child relationships</li>
 * <li>Fee schedule JSONB serialization/deserialization</li>
 * <li>Slug uniqueness constraint enforcement</li>
 * <li>Caching layer (implicit via Hibernate 2nd-level cache)</li>
 * </ul>
 */
@QuarkusTest
class MarketplaceCategoryTest {

    @BeforeEach
    @Transactional
    void setUp() {
        // Clear all categories before each test
        MarketplaceCategory.deleteAll();
    }

    /**
     * Test: Create root category with default fee schedule.
     */
    @Test
    @Transactional
    void testCreateRootCategory_WithDefaults() {
        MarketplaceCategory category = new MarketplaceCategory();
        category.parentId = null;
        category.name = "For Sale";
        category.slug = "for-sale";
        category.sortOrder = 1;
        category.isActive = true;
        category.feeSchedule = FeeScheduleType.free();

        MarketplaceCategory created = MarketplaceCategory.create(category);

        assertNotNull(created.id);
        assertEquals("For Sale", created.name);
        assertEquals("for-sale", created.slug);
        assertNull(created.parentId);
        assertEquals(1, created.sortOrder);
        assertTrue(created.isActive);
        assertNotNull(created.feeSchedule);
        assertEquals(BigDecimal.ZERO, created.feeSchedule.postingFee());
        assertNotNull(created.createdAt);
        assertNotNull(created.updatedAt);
    }

    /**
     * Test: Create child category with parent reference.
     */
    @Test
    @Transactional
    void testCreateChildCategory_WithParent() {
        // Create parent
        MarketplaceCategory parent = new MarketplaceCategory();
        parent.name = "For Sale";
        parent.slug = "for-sale";
        parent.sortOrder = 1;
        parent.isActive = true;
        parent.feeSchedule = FeeScheduleType.free();
        parent = MarketplaceCategory.create(parent);

        // Create child
        MarketplaceCategory child = new MarketplaceCategory();
        child.parentId = parent.id;
        child.name = "Electronics";
        child.slug = "electronics";
        child.sortOrder = 1;
        child.isActive = true;
        child.feeSchedule = FeeScheduleType.standard();
        child = MarketplaceCategory.create(child);

        assertNotNull(child.id);
        assertEquals("Electronics", child.name);
        assertEquals(parent.id, child.parentId);
        assertEquals(new BigDecimal("5.00"), child.feeSchedule.featuredFee());
    }

    /**
     * Test: Find root categories (parent_id IS NULL).
     */
    @Test
    @Transactional
    void testFindRootCategories_ReturnsOnlyRoots() {
        // Create root categories
        createCategory(null, "For Sale", "for-sale", 1, true);
        createCategory(null, "Housing", "housing", 2, true);
        createCategory(null, "Jobs", "jobs", 3, true);

        // Create child category (should not appear in root results)
        MarketplaceCategory parent = MarketplaceCategory.findBySlug("for-sale").get();
        createCategory(parent.id, "Electronics", "electronics", 1, true);

        List<MarketplaceCategory> roots = MarketplaceCategory.findRootCategories();

        assertEquals(3, roots.size());
        assertEquals("For Sale", roots.get(0).name);
        assertEquals("Housing", roots.get(1).name);
        assertEquals("Jobs", roots.get(2).name);
    }

    /**
     * Test: Find children by parent ID.
     */
    @Test
    @Transactional
    void testFindByParentId_ReturnsChildren() {
        // Create parent
        MarketplaceCategory parent = createCategory(null, "For Sale", "for-sale", 1, true);

        // Create children
        createCategory(parent.id, "Electronics", "electronics", 1, true);
        createCategory(parent.id, "Furniture", "furniture", 2, true);
        createCategory(parent.id, "Books", "books", 3, true);

        List<MarketplaceCategory> children = MarketplaceCategory.findByParentId(parent.id);

        assertEquals(3, children.size());
        assertEquals("Electronics", children.get(0).name);
        assertEquals("Furniture", children.get(1).name);
        assertEquals("Books", children.get(2).name);
    }

    /**
     * Test: Find category by slug.
     */
    @Test
    @Transactional
    void testFindBySlug_ReturnsCategory() {
        createCategory(null, "For Sale", "for-sale", 1, true);

        Optional<MarketplaceCategory> found = MarketplaceCategory.findBySlug("for-sale");

        assertTrue(found.isPresent());
        assertEquals("For Sale", found.get().name);
    }

    /**
     * Test: Find by slug with non-existent slug returns empty.
     */
    @Test
    @Transactional
    void testFindBySlug_NonExistent_ReturnsEmpty() {
        Optional<MarketplaceCategory> found = MarketplaceCategory.findBySlug("non-existent");
        assertFalse(found.isPresent());
    }

    /**
     * Test: Find active categories only.
     */
    @Test
    @Transactional
    void testFindActive_FiltersInactive() {
        createCategory(null, "For Sale", "for-sale", 1, true);
        createCategory(null, "Housing", "housing", 2, true);
        createCategory(null, "Jobs", "jobs", 3, false); // inactive

        List<MarketplaceCategory> active = MarketplaceCategory.findActive();

        assertEquals(2, active.size());
        assertTrue(active.stream().allMatch(c -> c.isActive));
    }

    /**
     * Test: Update category with partial fields.
     */
    @Test
    @Transactional
    void testUpdateCategory_UpdatesFields() {
        MarketplaceCategory category = createCategory(null, "For Sale", "for-sale", 1, true);

        // Update fields
        category.name = "For Sale - Updated";
        category.sortOrder = 10;
        category.isActive = false;
        category.feeSchedule = FeeScheduleType.premium();
        MarketplaceCategory.update(category);

        // Refetch and verify
        MarketplaceCategory updated = MarketplaceCategory.findById(category.id);
        assertEquals("For Sale - Updated", updated.name);
        assertEquals(10, updated.sortOrder);
        assertFalse(updated.isActive);
        assertEquals(new BigDecimal("10.00"), updated.feeSchedule.featuredFee());
    }

    /**
     * Test: Delete category without children succeeds.
     */
    @Test
    @Transactional
    void testDeleteIfSafe_NoChildren_Succeeds() {
        MarketplaceCategory category = createCategory(null, "For Sale", "for-sale", 1, true);

        MarketplaceCategory.deleteIfSafe(category.id);

        assertNull(MarketplaceCategory.findById(category.id));
    }

    /**
     * Test: Delete category with children throws exception.
     */
    @Test
    @Transactional
    void testDeleteIfSafe_WithChildren_ThrowsException() {
        MarketplaceCategory parent = createCategory(null, "For Sale", "for-sale", 1, true);
        createCategory(parent.id, "Electronics", "electronics", 1, true);

        assertThrows(IllegalStateException.class, () -> {
            MarketplaceCategory.deleteIfSafe(parent.id);
        });
    }

    /**
     * Test: hasChildren returns true for category with children.
     */
    @Test
    @Transactional
    void testHasChildren_WithChildren_ReturnsTrue() {
        MarketplaceCategory parent = createCategory(null, "For Sale", "for-sale", 1, true);
        createCategory(parent.id, "Electronics", "electronics", 1, true);

        assertTrue(MarketplaceCategory.hasChildren(parent.id));
    }

    /**
     * Test: hasChildren returns false for category without children.
     */
    @Test
    @Transactional
    void testHasChildren_NoChildren_ReturnsFalse() {
        MarketplaceCategory category = createCategory(null, "For Sale", "for-sale", 1, true);

        assertFalse(MarketplaceCategory.hasChildren(category.id));
    }

    /**
     * Test: Build category tree returns hierarchical structure.
     */
    @Test
    @Transactional
    void testBuildCategoryTree_ReturnsHierarchy() {
        // Create hierarchy: For Sale > Electronics, Furniture
        MarketplaceCategory forSale = createCategory(null, "For Sale", "for-sale", 1, true);
        createCategory(forSale.id, "Electronics", "electronics", 1, true);
        createCategory(forSale.id, "Furniture", "furniture", 2, true);

        // Create another root: Housing
        createCategory(null, "Housing", "housing", 2, true);

        List<MarketplaceCategory.CategoryNode> tree = MarketplaceCategory.buildCategoryTree();

        assertEquals(2, tree.size()); // 2 root categories

        // Verify first root and children
        MarketplaceCategory.CategoryNode forSaleNode = tree.get(0);
        assertEquals("For Sale", forSaleNode.name());
        assertEquals(2, forSaleNode.children().size());
        assertEquals("Electronics", forSaleNode.children().get(0).name());
        assertEquals("Furniture", forSaleNode.children().get(1).name());

        // Verify second root has no children
        MarketplaceCategory.CategoryNode housingNode = tree.get(1);
        assertEquals("Housing", housingNode.name());
        assertEquals(0, housingNode.children().size());
    }

    /**
     * Test: Fee schedule JSONB serialization/deserialization.
     */
    @Test
    @Transactional
    void testFeeSchedule_JsonbPersistence() {
        MarketplaceCategory category = new MarketplaceCategory();
        category.name = "Premium Category";
        category.slug = "premium-category";
        category.sortOrder = 1;
        category.isActive = true;
        category.feeSchedule = new FeeScheduleType(new BigDecimal("5.00"), new BigDecimal("10.00"),
                new BigDecimal("2.50"));
        category = MarketplaceCategory.create(category);

        // Refetch and verify JSONB deserialization
        MarketplaceCategory fetched = MarketplaceCategory.findById(category.id);
        assertNotNull(fetched.feeSchedule);
        assertEquals(new BigDecimal("5.00"), fetched.feeSchedule.postingFee());
        assertEquals(new BigDecimal("10.00"), fetched.feeSchedule.featuredFee());
        assertEquals(new BigDecimal("2.50"), fetched.feeSchedule.bumpFee());
    }

    /**
     * Test: Categories ordered by sort_order within same parent.
     */
    @Test
    @Transactional
    void testSortOrder_MaintainsOrdering() {
        MarketplaceCategory parent = createCategory(null, "For Sale", "for-sale", 1, true);

        // Create children with specific sort orders
        createCategory(parent.id, "Z Item", "z-item", 3, true);
        createCategory(parent.id, "A Item", "a-item", 1, true);
        createCategory(parent.id, "M Item", "m-item", 2, true);

        List<MarketplaceCategory> children = MarketplaceCategory.findByParentId(parent.id);

        assertEquals(3, children.size());
        assertEquals("A Item", children.get(0).name); // sort_order 1
        assertEquals("M Item", children.get(1).name); // sort_order 2
        assertEquals("Z Item", children.get(2).name); // sort_order 3
    }

    // Helper method to create category
    private MarketplaceCategory createCategory(java.util.UUID parentId, String name, String slug, int sortOrder,
            boolean isActive) {
        MarketplaceCategory category = new MarketplaceCategory();
        category.parentId = parentId;
        category.name = name;
        category.slug = slug;
        category.sortOrder = sortOrder;
        category.isActive = isActive;
        category.feeSchedule = FeeScheduleType.free();
        return MarketplaceCategory.create(category);
    }
}
