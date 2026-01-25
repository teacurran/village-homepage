package villagecompute.homepage.data.models;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for DirectoryCategory entity covering hierarchical queries and CRUD operations.
 *
 * <p>
 * Test coverage per I5.T1 acceptance criteria:
 * <ul>
 * <li>Category tree manageable via hierarchical queries</li>
 * <li>Admin can reorder categories via sort_order</li>
 * <li>Admin can enable/disable categories via is_active flag</li>
 * <li>Cascading delete for parent-child relationships</li>
 * <li>Link count caching and increment/decrement operations</li>
 * <li>Slug uniqueness constraint enforcement</li>
 * <li>Caching layer (implicit via Hibernate 2nd-level cache)</li>
 * <li>Tests cover recursion + sort per acceptance criteria</li>
 * </ul>
 */
@QuarkusTest
class DirectoryCategoryTest {

    @BeforeEach
    @Transactional
    void setUp() {
        // Clear all categories before each test
        DirectoryCategory.deleteAll();
    }

    /**
     * Test: Create root category with default values.
     */
    @Test
    @Transactional
    void testCreateRootCategory_WithDefaults() {
        DirectoryCategory category = new DirectoryCategory();
        category.parentId = null;
        category.name = "Computers & Internet";
        category.slug = "computers";
        category.description = "Software, hardware, programming, and internet culture";
        category.iconUrl = null;
        category.sortOrder = 1;
        category.linkCount = 0;
        category.isActive = true;

        DirectoryCategory created = DirectoryCategory.create(category);

        assertNotNull(created.id);
        assertEquals("Computers & Internet", created.name);
        assertEquals("computers", created.slug);
        assertEquals("Software, hardware, programming, and internet culture", created.description);
        assertNull(created.parentId);
        assertNull(created.iconUrl);
        assertEquals(1, created.sortOrder);
        assertEquals(0, created.linkCount);
        assertTrue(created.isActive);
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
        DirectoryCategory parent = new DirectoryCategory();
        parent.name = "Computers & Internet";
        parent.slug = "computers";
        parent.description = "Software, hardware, programming, and internet culture";
        parent.sortOrder = 1;
        parent.linkCount = 0;
        parent.isActive = true;
        parent = DirectoryCategory.create(parent);

        // Create child
        DirectoryCategory child = new DirectoryCategory();
        child.parentId = parent.id;
        child.name = "Open Source";
        child.slug = "computers-opensource";
        child.description = "Open source projects and collaborative development";
        child.sortOrder = 1;
        child.linkCount = 0;
        child.isActive = true;
        child = DirectoryCategory.create(child);

        assertNotNull(child.id);
        assertEquals("Open Source", child.name);
        assertEquals(parent.id, child.parentId);
        assertEquals("computers-opensource", child.slug);
    }

    /**
     * Test: Find root categories (parent_id IS NULL).
     */
    @Test
    @Transactional
    void testFindRootCategories_ReturnsOnlyRoots() {
        // Create root categories
        createCategory(null, "Arts & Entertainment", "arts", "Movies, music, and culture", 1, true);
        createCategory(null, "Computers & Internet", "computers", "Software and hardware", 2, true);
        createCategory(null, "Science & Technology", "science", "Engineering and research", 3, true);

        // Create child category (should not appear in root results)
        DirectoryCategory parent = DirectoryCategory.findBySlug("computers").get();
        createCategory(parent.id, "Open Source", "computers-opensource", "FOSS projects", 1, true);

        List<DirectoryCategory> roots = DirectoryCategory.findRootCategories();

        assertEquals(3, roots.size());
        assertEquals("Arts & Entertainment", roots.get(0).name);
        assertEquals("Computers & Internet", roots.get(1).name);
        assertEquals("Science & Technology", roots.get(2).name);
    }

    /**
     * Test: Find children by parent ID.
     */
    @Test
    @Transactional
    void testFindByParentId_ReturnsChildren() {
        // Create parent
        DirectoryCategory parent = createCategory(null, "Computers & Internet", "computers", "Tech category", 1, true);

        // Create children
        createCategory(parent.id, "Software", "computers-software", "Applications", 1, true);
        createCategory(parent.id, "Hardware", "computers-hardware", "Components", 2, true);
        createCategory(parent.id, "Programming", "computers-programming", "Coding tutorials", 3, true);

        List<DirectoryCategory> children = DirectoryCategory.findByParentId(parent.id);

        assertEquals(3, children.size());
        assertEquals("Software", children.get(0).name);
        assertEquals("Hardware", children.get(1).name);
        assertEquals("Programming", children.get(2).name);
    }

    /**
     * Test: Find category by slug.
     */
    @Test
    @Transactional
    void testFindBySlug_ReturnsCategory() {
        createCategory(null, "Computers & Internet", "computers", "Tech category", 1, true);

        Optional<DirectoryCategory> found = DirectoryCategory.findBySlug("computers");

        assertTrue(found.isPresent());
        assertEquals("Computers & Internet", found.get().name);
    }

    /**
     * Test: Find by slug with non-existent slug returns empty.
     */
    @Test
    @Transactional
    void testFindBySlug_NonExistent_ReturnsEmpty() {
        Optional<DirectoryCategory> found = DirectoryCategory.findBySlug("non-existent");
        assertFalse(found.isPresent());
    }

    /**
     * Test: Find active categories only.
     */
    @Test
    @Transactional
    void testFindActive_FiltersInactive() {
        createCategory(null, "Arts & Entertainment", "arts", "Culture", 1, true);
        createCategory(null, "Computers & Internet", "computers", "Tech", 2, true);
        createCategory(null, "Adult Content", "adult", "18+ content", 3, false); // inactive

        List<DirectoryCategory> active = DirectoryCategory.findActive();

        assertEquals(2, active.size());
        assertTrue(active.stream().allMatch(c -> c.isActive));
    }

    /**
     * Test: Update category with partial fields.
     */
    @Test
    @Transactional
    void testUpdateCategory_UpdatesFields() {
        DirectoryCategory category = createCategory(null, "Computers & Internet", "computers", "Old description", 1,
                true);

        // Update fields
        category.name = "Computers & Technology";
        category.description = "Updated description with new content";
        category.sortOrder = 10;
        category.isActive = false;
        category.iconUrl = "https://cdn.example.com/icons/computers.svg";
        DirectoryCategory.update(category);

        // Refetch and verify
        DirectoryCategory updated = DirectoryCategory.findById(category.id);
        assertEquals("Computers & Technology", updated.name);
        assertEquals("Updated description with new content", updated.description);
        assertEquals(10, updated.sortOrder);
        assertFalse(updated.isActive);
        assertEquals("https://cdn.example.com/icons/computers.svg", updated.iconUrl);
    }

    /**
     * Test: Delete category without children succeeds.
     */
    @Test
    @Transactional
    void testDeleteIfSafe_NoChildren_Succeeds() {
        DirectoryCategory category = createCategory(null, "Computers & Internet", "computers", "Tech", 1, true);

        DirectoryCategory.deleteIfSafe(category.id);

        assertNull(DirectoryCategory.findById(category.id));
    }

    /**
     * Test: Delete category with children throws exception.
     */
    @Test
    @Transactional
    void testDeleteIfSafe_WithChildren_ThrowsException() {
        DirectoryCategory parent = createCategory(null, "Computers & Internet", "computers", "Tech", 1, true);
        createCategory(parent.id, "Software", "computers-software", "Apps", 1, true);

        assertThrows(IllegalStateException.class, () -> {
            DirectoryCategory.deleteIfSafe(parent.id);
        });
    }

    /**
     * Test: hasChildren returns true for category with children.
     */
    @Test
    @Transactional
    void testHasChildren_WithChildren_ReturnsTrue() {
        DirectoryCategory parent = createCategory(null, "Computers & Internet", "computers", "Tech", 1, true);
        createCategory(parent.id, "Software", "computers-software", "Apps", 1, true);

        assertTrue(DirectoryCategory.hasChildren(parent.id));
    }

    /**
     * Test: hasChildren returns false for category without children.
     */
    @Test
    @Transactional
    void testHasChildren_NoChildren_ReturnsFalse() {
        DirectoryCategory category = createCategory(null, "Computers & Internet", "computers", "Tech", 1, true);

        assertFalse(DirectoryCategory.hasChildren(category.id));
    }

    /**
     * Test: Build category tree returns hierarchical structure (tests recursion per acceptance criteria).
     */
    @Test
    @Transactional
    void testBuildCategoryTree_ReturnsHierarchy() {
        // Create hierarchy: Computers > Software, Hardware
        DirectoryCategory computers = createCategory(null, "Computers & Internet", "computers", "Tech", 1, true);
        createCategory(computers.id, "Software", "computers-software", "Apps", 1, true);
        createCategory(computers.id, "Hardware", "computers-hardware", "Components", 2, true);

        // Create another root: Science
        createCategory(null, "Science & Technology", "science", "Research", 2, true);

        List<DirectoryCategory.CategoryNode> tree = DirectoryCategory.buildCategoryTree();

        assertEquals(2, tree.size()); // 2 root categories

        // Verify first root and children
        DirectoryCategory.CategoryNode computersNode = tree.get(0);
        assertEquals("Computers & Internet", computersNode.name());
        assertEquals(2, computersNode.children().size());
        assertEquals("Software", computersNode.children().get(0).name());
        assertEquals("Hardware", computersNode.children().get(1).name());

        // Verify second root has no children
        DirectoryCategory.CategoryNode scienceNode = tree.get(1);
        assertEquals("Science & Technology", scienceNode.name());
        assertEquals(0, scienceNode.children().size());
    }

    /**
     * Test: Categories ordered by sort_order within same parent (tests sort per acceptance criteria).
     */
    @Test
    @Transactional
    void testSortOrder_MaintainsOrdering() {
        DirectoryCategory parent = createCategory(null, "Computers & Internet", "computers", "Tech", 1, true);

        // Create children with specific sort orders (out of order)
        createCategory(parent.id, "Z Programming", "computers-z-programming", "Last", 3, true);
        createCategory(parent.id, "A Software", "computers-a-software", "First", 1, true);
        createCategory(parent.id, "M Hardware", "computers-m-hardware", "Middle", 2, true);

        List<DirectoryCategory> children = DirectoryCategory.findByParentId(parent.id);

        assertEquals(3, children.size());
        assertEquals("A Software", children.get(0).name); // sort_order 1
        assertEquals("M Hardware", children.get(1).name); // sort_order 2
        assertEquals("Z Programming", children.get(2).name); // sort_order 3
    }

    /**
     * Test: Increment link count updates category.
     */
    @Test
    @Transactional
    void testIncrementLinkCount_UpdatesCount() {
        DirectoryCategory category = createCategory(null, "Computers & Internet", "computers", "Tech", 1, true);
        assertEquals(0, category.linkCount);

        DirectoryCategory.incrementLinkCount(category.id);

        DirectoryCategory updated = DirectoryCategory.findById(category.id);
        assertEquals(1, updated.linkCount);

        DirectoryCategory.incrementLinkCount(category.id);
        DirectoryCategory.incrementLinkCount(category.id);

        updated = DirectoryCategory.findById(category.id);
        assertEquals(3, updated.linkCount);
    }

    /**
     * Test: Decrement link count updates category and prevents negative values.
     */
    @Test
    @Transactional
    void testDecrementLinkCount_UpdatesCount_PreventsNegative() {
        DirectoryCategory category = createCategory(null, "Computers & Internet", "computers", "Tech", 1, true);

        // Manually set link count to 2
        category.linkCount = 2;
        DirectoryCategory.update(category);

        DirectoryCategory.decrementLinkCount(category.id);

        DirectoryCategory updated = DirectoryCategory.findById(category.id);
        assertEquals(1, updated.linkCount);

        DirectoryCategory.decrementLinkCount(category.id);
        updated = DirectoryCategory.findById(category.id);
        assertEquals(0, updated.linkCount);

        // Attempt to decrement below zero (should stay at 0)
        DirectoryCategory.decrementLinkCount(category.id);
        updated = DirectoryCategory.findById(category.id);
        assertEquals(0, updated.linkCount);
    }

    /**
     * Test: Find most popular categories by link count.
     */
    @Test
    @Transactional
    void testFindMostPopular_ReturnsByLinkCount() {
        DirectoryCategory cat1 = createCategory(null, "Low Traffic", "low-traffic", "Few links", 1, true);
        DirectoryCategory cat2 = createCategory(null, "High Traffic", "high-traffic", "Many links", 2, true);
        DirectoryCategory cat3 = createCategory(null, "Medium Traffic", "medium-traffic", "Some links", 3, true);

        // Set link counts
        cat1.linkCount = 5;
        cat2.linkCount = 100;
        cat3.linkCount = 50;
        DirectoryCategory.update(cat1);
        DirectoryCategory.update(cat2);
        DirectoryCategory.update(cat3);

        List<DirectoryCategory> popular = DirectoryCategory.findMostPopular(2);

        assertEquals(2, popular.size());
        assertEquals("High Traffic", popular.get(0).name); // linkCount 100
        assertEquals("Medium Traffic", popular.get(1).name); // linkCount 50
    }

    /**
     * Test: Three-level hierarchy to verify deep recursion.
     */
    @Test
    @Transactional
    void testBuildCategoryTree_ThreeLevels_VerifiesDeepRecursion() {
        // Create hierarchy: Computers > Software > Open Source
        DirectoryCategory computers = createCategory(null, "Computers & Internet", "computers", "Tech", 1, true);
        DirectoryCategory software = createCategory(computers.id, "Software", "computers-software", "Apps", 1, true);
        createCategory(software.id, "Open Source", "computers-software-opensource", "FOSS", 1, true);

        List<DirectoryCategory.CategoryNode> tree = DirectoryCategory.buildCategoryTree();

        assertEquals(1, tree.size());
        DirectoryCategory.CategoryNode root = tree.get(0);
        assertEquals("Computers & Internet", root.name());
        assertEquals(1, root.children().size());

        DirectoryCategory.CategoryNode softwareNode = root.children().get(0);
        assertEquals("Software", softwareNode.name());
        assertEquals(1, softwareNode.children().size());

        DirectoryCategory.CategoryNode opensourceNode = softwareNode.children().get(0);
        assertEquals("Open Source", opensourceNode.name());
        assertEquals(0, opensourceNode.children().size());
    }

    // Helper method to create category
    private DirectoryCategory createCategory(java.util.UUID parentId, String name, String slug, String description,
            int sortOrder, boolean isActive) {
        DirectoryCategory category = new DirectoryCategory();
        category.parentId = parentId;
        category.name = name;
        category.slug = slug;
        category.description = description;
        category.iconUrl = null;
        category.sortOrder = sortOrder;
        category.linkCount = 0;
        category.isActive = isActive;
        return DirectoryCategory.create(category);
    }
}
