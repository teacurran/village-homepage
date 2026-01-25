package villagecompute.homepage.api.rest.admin;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.api.types.FeeScheduleType;
import villagecompute.homepage.data.models.MarketplaceCategory;

import java.math.BigDecimal;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link MarketplaceCategoryResource} admin REST endpoints.
 *
 * <p>
 * Test coverage per I4.T2 acceptance criteria:
 * <ul>
 * <li>Category tree accessible via GET /admin/api/marketplace/categories/tree</li>
 * <li>Admin can create categories with POST</li>
 * <li>Admin can update categories (name, sort_order, is_active, fee_schedule) with PATCH</li>
 * <li>Admin can delete categories without children/listings</li>
 * <li>Slug uniqueness enforced (409 Conflict on duplicate)</li>
 * </ul>
 *
 * <p>
 * Note: Tests use @TestSecurity to simulate super_admin role for RBAC.
 */
@QuarkusTest
@TestSecurity(
        user = "test-admin",
        roles = "super_admin")
class MarketplaceCategoryResourceTest {

    @BeforeEach
    @Transactional
    void setUp() {
        // Clear all categories before each test
        MarketplaceCategory.deleteAll();
    }

    @AfterEach
    @Transactional
    void tearDown() {
        // Clean up test data
        MarketplaceCategory.deleteAll();
    }

    /**
     * Test: GET /admin/api/marketplace/categories returns all categories.
     */
    @Test
    @Transactional
    void testListCategories_ReturnsAll() {
        // Create test categories
        createCategory(null, "For Sale", "for-sale", 1, true);
        createCategory(null, "Housing", "housing", 2, true);

        given().when().get("/admin/api/marketplace/categories").then().statusCode(200).body("$", hasSize(2))
                .body("[0].name", equalTo("For Sale")).body("[1].name", equalTo("Housing"));
    }

    /**
     * Test: GET /admin/api/marketplace/categories/tree returns hierarchical structure.
     */
    @Test
    @Transactional
    void testGetCategoryTree_ReturnsHierarchy() {
        // Create hierarchy: For Sale > Electronics
        MarketplaceCategory parent = createCategory(null, "For Sale", "for-sale", 1, true);
        createCategory(parent.id, "Electronics", "electronics", 1, true);

        given().when().get("/admin/api/marketplace/categories/tree").then().statusCode(200).body("$", hasSize(1))
                .body("[0].name", equalTo("For Sale")).body("[0].children", hasSize(1))
                .body("[0].children[0].name", equalTo("Electronics"));
    }

    /**
     * Test: GET /admin/api/marketplace/categories/{id} returns single category.
     */
    @Test
    @Transactional
    void testGetCategory_ReturnsCategory() {
        MarketplaceCategory category = createCategory(null, "For Sale", "for-sale", 1, true);

        given().when().get("/admin/api/marketplace/categories/" + category.id).then().statusCode(200)
                .body("id", equalTo(category.id.toString())).body("name", equalTo("For Sale"))
                .body("slug", equalTo("for-sale")).body("sort_order", equalTo(1)).body("is_active", equalTo(true));
    }

    /**
     * Test: GET /admin/api/marketplace/categories/{id} with non-existent ID returns 404.
     */
    @Test
    void testGetCategory_NotFound() {
        given().when().get("/admin/api/marketplace/categories/00000000-0000-0000-0000-000000000000").then()
                .statusCode(404).body("error", notNullValue());
    }

    /**
     * Test: POST /admin/api/marketplace/categories creates new category.
     */
    @Test
    void testCreateCategory_Success() {
        String requestBody = """
                {
                  "name": "For Sale",
                  "slug": "for-sale",
                  "sort_order": 1,
                  "is_active": true,
                  "fee_schedule": {
                    "posting_fee": 0,
                    "featured_fee": 5.00,
                    "bump_fee": 2.00
                  }
                }
                """;

        given().contentType("application/json").body(requestBody).when().post("/admin/api/marketplace/categories")
                .then().statusCode(201).body("name", equalTo("For Sale")).body("slug", equalTo("for-sale"))
                .body("sort_order", equalTo(1)).body("is_active", equalTo(true))
                .body("fee_schedule.posting_fee", equalTo(0)).body("fee_schedule.featured_fee", equalTo(5.0f))
                .body("fee_schedule.bump_fee", equalTo(2.0f));
    }

    /**
     * Test: POST /admin/api/marketplace/categories with duplicate slug returns 409 Conflict.
     */
    @Test
    @Transactional
    void testCreateCategory_DuplicateSlug_ReturnsConflict() {
        createCategory(null, "For Sale", "for-sale", 1, true);

        String requestBody = """
                {
                  "name": "For Sale Duplicate",
                  "slug": "for-sale",
                  "sort_order": 1,
                  "is_active": true,
                  "fee_schedule": {
                    "posting_fee": 0,
                    "featured_fee": 5.00,
                    "bump_fee": 2.00
                  }
                }
                """;

        given().contentType("application/json").body(requestBody).when().post("/admin/api/marketplace/categories")
                .then().statusCode(409).body("error", notNullValue());
    }

    /**
     * Test: POST /admin/api/marketplace/categories with invalid parent ID returns 409 Conflict.
     */
    @Test
    void testCreateCategory_InvalidParent_ReturnsConflict() {
        String requestBody = """
                {
                  "parent_id": "00000000-0000-0000-0000-000000000000",
                  "name": "Electronics",
                  "slug": "electronics",
                  "sort_order": 1,
                  "is_active": true,
                  "fee_schedule": {
                    "posting_fee": 0,
                    "featured_fee": 5.00,
                    "bump_fee": 2.00
                  }
                }
                """;

        given().contentType("application/json").body(requestBody).when().post("/admin/api/marketplace/categories")
                .then().statusCode(409).body("error", notNullValue());
    }

    /**
     * Test: PATCH /admin/api/marketplace/categories/{id} updates category.
     */
    @Test
    @Transactional
    void testUpdateCategory_Success() {
        MarketplaceCategory category = createCategory(null, "For Sale", "for-sale", 1, true);

        String requestBody = """
                {
                  "name": "For Sale - Updated",
                  "sort_order": 10,
                  "is_active": false
                }
                """;

        given().contentType("application/json").body(requestBody).when()
                .patch("/admin/api/marketplace/categories/" + category.id).then().statusCode(200)
                .body("name", equalTo("For Sale - Updated")).body("sort_order", equalTo(10))
                .body("is_active", equalTo(false));

        // Verify in database
        MarketplaceCategory updated = MarketplaceCategory.findById(category.id);
        assertEquals("For Sale - Updated", updated.name);
        assertEquals(10, updated.sortOrder);
        assertEquals(false, updated.isActive);
    }

    /**
     * Test: PATCH /admin/api/marketplace/categories/{id} with duplicate slug returns 409 Conflict.
     */
    @Test
    @Transactional
    void testUpdateCategory_DuplicateSlug_ReturnsConflict() {
        createCategory(null, "For Sale", "for-sale", 1, true);
        MarketplaceCategory housing = createCategory(null, "Housing", "housing", 2, true);

        String requestBody = """
                {
                  "slug": "for-sale"
                }
                """;

        given().contentType("application/json").body(requestBody).when()
                .patch("/admin/api/marketplace/categories/" + housing.id).then().statusCode(409)
                .body("error", notNullValue());
    }

    /**
     * Test: PATCH /admin/api/marketplace/categories/{id} with non-existent ID returns 404.
     */
    @Test
    void testUpdateCategory_NotFound() {
        String requestBody = """
                {
                  "name": "Updated"
                }
                """;

        given().contentType("application/json").body(requestBody).when()
                .patch("/admin/api/marketplace/categories/00000000-0000-0000-0000-000000000000").then().statusCode(404)
                .body("error", notNullValue());
    }

    /**
     * Test: DELETE /admin/api/marketplace/categories/{id} deletes category without children.
     */
    @Test
    @Transactional
    void testDeleteCategory_NoChildren_Success() {
        MarketplaceCategory category = createCategory(null, "For Sale", "for-sale", 1, true);

        given().when().delete("/admin/api/marketplace/categories/" + category.id).then().statusCode(204);

        // Verify deletion
        assertNull(MarketplaceCategory.findById(category.id));
    }

    /**
     * Test: DELETE /admin/api/marketplace/categories/{id} with children returns 409 Conflict.
     */
    @Test
    @Transactional
    void testDeleteCategory_WithChildren_ReturnsConflict() {
        MarketplaceCategory parent = createCategory(null, "For Sale", "for-sale", 1, true);
        createCategory(parent.id, "Electronics", "electronics", 1, true);

        given().when().delete("/admin/api/marketplace/categories/" + parent.id).then().statusCode(409).body("error",
                notNullValue());

        // Verify parent still exists
        assertNotNull(MarketplaceCategory.findById(parent.id));
    }

    /**
     * Test: DELETE /admin/api/marketplace/categories/{id} with non-existent ID returns 404.
     */
    @Test
    void testDeleteCategory_NotFound() {
        given().when().delete("/admin/api/marketplace/categories/00000000-0000-0000-0000-000000000000").then()
                .statusCode(404).body("error", notNullValue());
    }

    /**
     * Test: Create child category with valid parent.
     */
    @Test
    @Transactional
    void testCreateCategory_WithParent_Success() {
        MarketplaceCategory parent = createCategory(null, "For Sale", "for-sale", 1, true);

        String requestBody = String.format("""
                {
                  "parent_id": "%s",
                  "name": "Electronics",
                  "slug": "electronics",
                  "sort_order": 1,
                  "is_active": true,
                  "fee_schedule": {
                    "posting_fee": 0,
                    "featured_fee": 5.00,
                    "bump_fee": 2.00
                  }
                }
                """, parent.id);

        given().contentType("application/json").body(requestBody).when().post("/admin/api/marketplace/categories")
                .then().statusCode(201).body("name", equalTo("Electronics"))
                .body("parent_id", equalTo(parent.id.toString()));
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
        category.feeSchedule = new FeeScheduleType(BigDecimal.ZERO, new BigDecimal("5.00"), new BigDecimal("2.00"));
        return MarketplaceCategory.create(category);
    }
}
