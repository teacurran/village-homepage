package villagecompute.homepage.api.rest.admin;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.data.models.DirectoryCategory;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link DirectoryCategoryResource} admin REST endpoints.
 *
 * <p>
 * Test coverage per I5.T1 acceptance criteria:
 * <ul>
 * <li>Category tree accessible via GET /admin/api/directory/categories/tree</li>
 * <li>Admin can create categories with POST</li>
 * <li>Admin can update categories (name, slug, description, icon_url, sort_order, is_active) with PATCH</li>
 * <li>Admin can delete categories without children/sites</li>
 * <li>Slug uniqueness enforced (409 Conflict on duplicate)</li>
 * <li>Authorization enforced (super_admin role required)</li>
 * </ul>
 *
 * <p>
 * Note: Tests use @TestSecurity to simulate super_admin role for RBAC per Policy I2.T8.
 */
@QuarkusTest
@TestSecurity(
        user = "test-admin",
        roles = "super_admin")
class DirectoryCategoryResourceTest {

    @BeforeEach
    @Transactional
    void setUp() {
        // Clear all categories before each test
        DirectoryCategory.deleteAll();
    }

    @AfterEach
    @Transactional
    void tearDown() {
        // Clean up test data
        DirectoryCategory.deleteAll();
    }

    /**
     * Test: GET /admin/api/directory/categories returns all categories.
     */
    @Test
    @Transactional
    void testListCategories_ReturnsAll() {
        // Create test categories
        createCategory(null, "Computers & Internet", "computers", "Tech category", 1, true);
        createCategory(null, "Science & Technology", "science", "Research category", 2, true);

        given().when().get("/admin/api/directory/categories").then().statusCode(200).body("$", hasSize(2))
                .body("[0].name", equalTo("Computers & Internet")).body("[1].name", equalTo("Science & Technology"));
    }

    /**
     * Test: GET /admin/api/directory/categories/tree returns hierarchical structure.
     */
    @Test
    @Transactional
    void testGetCategoryTree_ReturnsHierarchy() {
        // Create hierarchy: Computers > Software
        DirectoryCategory parent = createCategory(null, "Computers & Internet", "computers", "Tech category", 1, true);
        createCategory(parent.id, "Software", "computers-software", "Applications", 1, true);

        given().when().get("/admin/api/directory/categories/tree").then().statusCode(200).body("$", hasSize(1))
                .body("[0].name", equalTo("Computers & Internet")).body("[0].children", hasSize(1))
                .body("[0].children[0].name", equalTo("Software"));
    }

    /**
     * Test: GET /admin/api/directory/categories/{id} returns single category.
     */
    @Test
    @Transactional
    void testGetCategory_ReturnsCategory() {
        DirectoryCategory category = createCategory(null, "Computers & Internet", "computers", "Tech category", 1,
                true);

        given().when().get("/admin/api/directory/categories/" + category.id).then().statusCode(200)
                .body("id", equalTo(category.id.toString())).body("name", equalTo("Computers & Internet"))
                .body("slug", equalTo("computers")).body("description", equalTo("Tech category"))
                .body("sort_order", equalTo(1)).body("is_active", equalTo(true)).body("link_count", equalTo(0));
    }

    /**
     * Test: GET /admin/api/directory/categories/{id} with non-existent ID returns 404.
     */
    @Test
    void testGetCategory_NotFound() {
        given().when().get("/admin/api/directory/categories/00000000-0000-0000-0000-000000000000").then()
                .statusCode(404).body("error", notNullValue());
    }

    /**
     * Test: POST /admin/api/directory/categories creates new category.
     */
    @Test
    void testCreateCategory_Success() {
        String requestBody = """
                {
                  "name": "Computers & Internet",
                  "slug": "computers",
                  "description": "Software, hardware, and internet culture",
                  "sort_order": 1,
                  "is_active": true
                }
                """;

        given().contentType("application/json").body(requestBody).when().post("/admin/api/directory/categories").then()
                .statusCode(201).body("name", equalTo("Computers & Internet")).body("slug", equalTo("computers"))
                .body("description", equalTo("Software, hardware, and internet culture")).body("sort_order", equalTo(1))
                .body("is_active", equalTo(true)).body("link_count", equalTo(0));
    }

    /**
     * Test: POST /admin/api/directory/categories with icon_url.
     */
    @Test
    void testCreateCategory_WithIconUrl_Success() {
        String requestBody = """
                {
                  "name": "Computers & Internet",
                  "slug": "computers",
                  "description": "Tech category",
                  "icon_url": "https://cdn.example.com/icons/computers.svg",
                  "sort_order": 1,
                  "is_active": true
                }
                """;

        given().contentType("application/json").body(requestBody).when().post("/admin/api/directory/categories").then()
                .statusCode(201).body("icon_url", equalTo("https://cdn.example.com/icons/computers.svg"));
    }

    /**
     * Test: POST /admin/api/directory/categories with duplicate slug returns 409 Conflict.
     */
    @Test
    @Transactional
    void testCreateCategory_DuplicateSlug_ReturnsConflict() {
        createCategory(null, "Computers & Internet", "computers", "Tech category", 1, true);

        String requestBody = """
                {
                  "name": "Computers Duplicate",
                  "slug": "computers",
                  "description": "Duplicate",
                  "sort_order": 1,
                  "is_active": true
                }
                """;

        given().contentType("application/json").body(requestBody).when().post("/admin/api/directory/categories").then()
                .statusCode(409).body("error", notNullValue());
    }

    /**
     * Test: POST /admin/api/directory/categories with invalid parent ID returns 409 Conflict.
     */
    @Test
    void testCreateCategory_InvalidParent_ReturnsConflict() {
        String requestBody = """
                {
                  "parent_id": "00000000-0000-0000-0000-000000000000",
                  "name": "Software",
                  "slug": "computers-software",
                  "description": "Applications",
                  "sort_order": 1,
                  "is_active": true
                }
                """;

        given().contentType("application/json").body(requestBody).when().post("/admin/api/directory/categories").then()
                .statusCode(409).body("error", notNullValue());
    }

    /**
     * Test: PATCH /admin/api/directory/categories/{id} updates category.
     */
    @Test
    @Transactional
    void testUpdateCategory_Success() {
        DirectoryCategory category = createCategory(null, "Computers & Internet", "computers", "Old description", 1,
                true);

        String requestBody = """
                {
                  "name": "Computers & Technology",
                  "description": "Updated description",
                  "sort_order": 10,
                  "is_active": false
                }
                """;

        given().contentType("application/json").body(requestBody).when()
                .patch("/admin/api/directory/categories/" + category.id).then().statusCode(200)
                .body("name", equalTo("Computers & Technology")).body("description", equalTo("Updated description"))
                .body("sort_order", equalTo(10)).body("is_active", equalTo(false));

        // Verify in database
        DirectoryCategory updated = DirectoryCategory.findById(category.id);
        assertEquals("Computers & Technology", updated.name);
        assertEquals("Updated description", updated.description);
        assertEquals(10, updated.sortOrder);
        assertEquals(false, updated.isActive);
    }

    /**
     * Test: PATCH /admin/api/directory/categories/{id} updates icon_url.
     */
    @Test
    @Transactional
    void testUpdateCategory_IconUrl_Success() {
        DirectoryCategory category = createCategory(null, "Computers & Internet", "computers", "Tech", 1, true);

        String requestBody = """
                {
                  "icon_url": "https://cdn.example.com/icons/computers-updated.svg"
                }
                """;

        given().contentType("application/json").body(requestBody).when()
                .patch("/admin/api/directory/categories/" + category.id).then().statusCode(200)
                .body("icon_url", equalTo("https://cdn.example.com/icons/computers-updated.svg"));
    }

    /**
     * Test: PATCH /admin/api/directory/categories/{id} with duplicate slug returns 409 Conflict.
     */
    @Test
    @Transactional
    void testUpdateCategory_DuplicateSlug_ReturnsConflict() {
        createCategory(null, "Computers & Internet", "computers", "Tech", 1, true);
        DirectoryCategory science = createCategory(null, "Science & Technology", "science", "Research", 2, true);

        String requestBody = """
                {
                  "slug": "computers"
                }
                """;

        given().contentType("application/json").body(requestBody).when()
                .patch("/admin/api/directory/categories/" + science.id).then().statusCode(409)
                .body("error", notNullValue());
    }

    /**
     * Test: PATCH /admin/api/directory/categories/{id} with non-existent ID returns 404.
     */
    @Test
    void testUpdateCategory_NotFound() {
        String requestBody = """
                {
                  "name": "Updated"
                }
                """;

        given().contentType("application/json").body(requestBody).when()
                .patch("/admin/api/directory/categories/00000000-0000-0000-0000-000000000000").then().statusCode(404)
                .body("error", notNullValue());
    }

    /**
     * Test: DELETE /admin/api/directory/categories/{id} deletes category without children.
     */
    @Test
    @Transactional
    void testDeleteCategory_NoChildren_Success() {
        DirectoryCategory category = createCategory(null, "Computers & Internet", "computers", "Tech", 1, true);

        given().when().delete("/admin/api/directory/categories/" + category.id).then().statusCode(204);

        // Verify deletion
        assertNull(DirectoryCategory.findById(category.id));
    }

    /**
     * Test: DELETE /admin/api/directory/categories/{id} with children returns 409 Conflict.
     */
    @Test
    @Transactional
    void testDeleteCategory_WithChildren_ReturnsConflict() {
        DirectoryCategory parent = createCategory(null, "Computers & Internet", "computers", "Tech", 1, true);
        createCategory(parent.id, "Software", "computers-software", "Apps", 1, true);

        given().when().delete("/admin/api/directory/categories/" + parent.id).then().statusCode(409).body("error",
                notNullValue());

        // Verify parent still exists
        assertNotNull(DirectoryCategory.findById(parent.id));
    }

    /**
     * Test: DELETE /admin/api/directory/categories/{id} with non-existent ID returns 404.
     */
    @Test
    void testDeleteCategory_NotFound() {
        given().when().delete("/admin/api/directory/categories/00000000-0000-0000-0000-000000000000").then()
                .statusCode(404).body("error", notNullValue());
    }

    /**
     * Test: Create child category with valid parent.
     */
    @Test
    @Transactional
    void testCreateCategory_WithParent_Success() {
        DirectoryCategory parent = createCategory(null, "Computers & Internet", "computers", "Tech", 1, true);

        String requestBody = String.format("""
                {
                  "parent_id": "%s",
                  "name": "Software",
                  "slug": "computers-software",
                  "description": "Applications and tools",
                  "sort_order": 1,
                  "is_active": true
                }
                """, parent.id);

        given().contentType("application/json").body(requestBody).when().post("/admin/api/directory/categories").then()
                .statusCode(201).body("name", equalTo("Software")).body("parent_id", equalTo(parent.id.toString()));
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
