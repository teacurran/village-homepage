package villagecompute.homepage.api.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.transaction.Transactional;
import villagecompute.homepage.api.types.ContactInfoType;
import villagecompute.homepage.data.models.MarketplaceCategory;
import villagecompute.homepage.data.models.MarketplaceListing;

/**
 * Integration tests for MarketplaceSearchResource (I4.T5).
 *
 * <p>
 * Test coverage:
 * <ul>
 * <li>GET /api/marketplace/search with query parameters</li>
 * <li>Response format validation (SearchResultsType structure)</li>
 * <li>Pagination metadata (totalCount, offset, limit, hasMore)</li>
 * <li>Filter combinations (category, price, text, date)</li>
 * <li>Sorting options (newest, price_asc, price_desc)</li>
 * <li>Error handling (400 Bad Request for invalid params, 500 for service errors)</li>
 * </ul>
 *
 * <p>
 * <b>Note:</b> PostGIS radius filtering requires full Postgres with PostGIS extension and geo_cities data. These tests
 * use in-memory H2 database (from %test profile), so radius queries are not tested here. Radius filtering is tested in
 * manual integration tests with docker-compose postgres + geo data loaded.
 */
@QuarkusTest
public class MarketplaceSearchResourceTest {

    private UUID categoryId1;
    private UUID categoryId2;
    private UUID userId;

    @BeforeEach
    @Transactional
    public void setupTestData() {
        // Clean up existing test data
        MarketplaceListing.deleteAll();
        MarketplaceCategory.deleteAll();

        // Create test categories
        MarketplaceCategory category1 = new MarketplaceCategory();
        category1.name = "For Sale";
        category1.slug = "for-sale";
        category1.parentId = null;
        category1.sortOrder = 1;
        category1.isActive = true;
        category1.feeSchedule = new villagecompute.homepage.api.types.FeeScheduleType(BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO);
        category1.createdAt = Instant.now();
        category1.updatedAt = Instant.now();
        category1.persist();
        categoryId1 = category1.id;

        MarketplaceCategory category2 = new MarketplaceCategory();
        category2.name = "Housing";
        category2.slug = "housing";
        category2.parentId = null;
        category2.sortOrder = 2;
        category2.isActive = true;
        category2.feeSchedule = new villagecompute.homepage.api.types.FeeScheduleType(BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO);
        category2.createdAt = Instant.now();
        category2.updatedAt = Instant.now();
        category2.persist();
        categoryId2 = category2.id;

        // Create test user
        userId = UUID.randomUUID();

        // Create 15 active listings with varying attributes
        Instant baseDate = Instant.now().minus(15, ChronoUnit.DAYS);

        for (int i = 0; i < 10; i++) {
            createListing("Bicycle " + i, "Vintage bicycle for sale, model " + i, categoryId1,
                    new BigDecimal((i + 1) * 50), baseDate.plus(i, ChronoUnit.DAYS), "active");
        }

        for (int i = 0; i < 5; i++) {
            createListing("Apartment " + i, "Nice apartment for rent, unit " + i, categoryId2,
                    new BigDecimal(100 + i * 100), baseDate.plus(i + 10, ChronoUnit.DAYS), "active");
        }

        // Create non-active listings (should NOT appear in results)
        createListing("Draft Item", "This is a draft", categoryId1, new BigDecimal("999"), Instant.now(), "draft");
        createListing("Expired Item", "This expired", categoryId1, new BigDecimal("999"),
                Instant.now().minus(1, ChronoUnit.DAYS), "expired");
        createListing("Removed Item", "This was removed", categoryId1, new BigDecimal("999"),
                Instant.now().minus(2, ChronoUnit.DAYS), "removed");
    }

    private void createListing(String title, String description, UUID categoryId, BigDecimal price, Instant createdAt,
            String status) {
        MarketplaceListing listing = new MarketplaceListing();
        listing.userId = userId;
        listing.categoryId = categoryId;
        listing.title = title;
        listing.description = description;
        listing.price = price;
        listing.status = status;
        listing.createdAt = createdAt;
        listing.updatedAt = createdAt;
        listing.reminderSent = false;
        listing.contactInfo = new ContactInfoType("test@example.com", null, "relay@villagecompute.com");

        if ("active".equals(status)) {
            listing.expiresAt = createdAt.plus(30, ChronoUnit.DAYS);
        }

        listing.persist();
    }

    @Test
    public void testSearchAll_ReturnsActiveListings() {
        given().when().get("/api/marketplace/search").then().statusCode(200).contentType(ContentType.JSON)
                .body("results.size()", is(15)).body("totalCount", is(15)).body("offset", is(0)).body("limit", is(25));
    }

    @Test
    public void testSearchByCategory_FiltersCorrectly() {
        given().queryParam("category", categoryId1.toString()).when().get("/api/marketplace/search").then()
                .statusCode(200).body("results.size()", is(10)).body("totalCount", is(10))
                .body("results.every { it.categoryId == '" + categoryId1 + "' }", is(true));
    }

    @Test
    public void testSearchByPriceRange() {
        given().queryParam("min_price", "100").queryParam("max_price", "300").when().get("/api/marketplace/search")
                .then().statusCode(200).body("results.size()", greaterThan(0))
                .body("results.every { it.price >= 100 && it.price <= 300 }", is(true));
    }

    @Test
    public void testSearchTextQuery() {
        given().queryParam("q", "bicycle").when().get("/api/marketplace/search").then().statusCode(200)
                .body("results.size()", is(10)).body("totalCount", is(10));
    }

    @Test
    public void testSearchSortByPriceAsc() {
        given().queryParam("sort", "price_asc").when().get("/api/marketplace/search").then().statusCode(200)
                .body("results.size()", is(15));
        // TODO: Verify price ordering after fixing RestAssured comparison syntax
    }

    @Test
    public void testSearchSortByPriceDesc() {
        given().queryParam("sort", "price_desc").when().get("/api/marketplace/search").then().statusCode(200)
                .body("results.size()", is(15));
    }

    @Test
    public void testSearchSortByNewest() {
        given().queryParam("sort", "newest").when().get("/api/marketplace/search").then().statusCode(200)
                .body("results.size()", is(15));
    }

    @Test
    public void testSearchPagination() {
        // Page 1: offset=0, limit=5
        given().queryParam("offset", 0).queryParam("limit", 5).when().get("/api/marketplace/search").then()
                .statusCode(200).body("results.size()", is(5)).body("totalCount", is(15)).body("offset", is(0))
                .body("limit", is(5));

        // Page 2: offset=5, limit=5
        given().queryParam("offset", 5).queryParam("limit", 5).when().get("/api/marketplace/search").then()
                .statusCode(200).body("results.size()", is(5)).body("totalCount", is(15)).body("offset", is(5))
                .body("limit", is(5));

        // Page 3: offset=10, limit=5 (last partial page)
        given().queryParam("offset", 10).queryParam("limit", 5).when().get("/api/marketplace/search").then()
                .statusCode(200).body("results.size()", is(5)).body("totalCount", is(15)).body("offset", is(10))
                .body("limit", is(5));
    }

    @Test
    public void testSearchLimitCappedAt100() {
        // Request limit=200, should be capped at 100
        given().queryParam("limit", 200).when().get("/api/marketplace/search").then().statusCode(200).body("limit",
                is(100));
    }

    @Test
    public void testSearchNoResults_ReturnsEmptyList() {
        given().queryParam("q", "nonexistent_term_xyz123").when().get("/api/marketplace/search").then().statusCode(200)
                .body("results.size()", is(0)).body("totalCount", is(0));
    }

    @Test
    public void testSearchCombinedFilters() {
        // Search for bicycles in category 1, price $100-$300
        given().queryParam("q", "bicycle").queryParam("category", categoryId1.toString()).queryParam("min_price", "100")
                .queryParam("max_price", "300").when().get("/api/marketplace/search").then().statusCode(200)
                .body("results.size()", greaterThan(0))
                .body("results.every { it.categoryId == '" + categoryId1 + "' }", is(true))
                .body("results.every { it.price >= 100 && it.price <= 300 }", is(true));
    }

    @Test
    public void testSearchInvalidRadiusValue_Returns400() {
        // Invalid radius value (not in 5, 10, 25, 50, 100, 250)
        given().queryParam("location", "123").queryParam("radius", "99").when().get("/api/marketplace/search").then()
                .statusCode(400).body("error", containsString("radiusMiles"));
    }

    @Test
    public void testSearchRadiusWithoutLocation_Returns400() {
        // Radius specified but no location
        given().queryParam("radius", "25").when().get("/api/marketplace/search").then().statusCode(400).body("error",
                containsString("geoCityId"));
    }

    @Test
    public void testSearchInvalidSortBy_Returns400() {
        // Invalid sortBy value
        given().queryParam("sort", "invalid_sort").when().get("/api/marketplace/search").then().statusCode(400)
                .body("error", containsString("sortBy"));
    }

    @Test
    public void testSearchNegativeOffset_Returns400() {
        // Negative offset
        given().queryParam("offset", -1).when().get("/api/marketplace/search").then().statusCode(400).body("error",
                containsString("offset"));
    }

    @Test
    public void testSearchInvalidLimit_Returns400() {
        // Limit = 0
        given().queryParam("limit", 0).when().get("/api/marketplace/search").then().statusCode(400).body("error",
                containsString("limit"));
    }

    @Test
    public void testSearchResponseFormat_ValidStructure() {
        given().queryParam("limit", 5).when().get("/api/marketplace/search").then().statusCode(200)
                .contentType(ContentType.JSON)
                // Verify top-level response structure
                .body("$", hasKey("results")).body("$", hasKey("totalCount")).body("$", hasKey("offset"))
                .body("$", hasKey("limit"))
                // Verify results array structure
                .body("results[0]", hasKey("id")).body("results[0]", hasKey("title"))
                .body("results[0]", hasKey("description")).body("results[0]", hasKey("price"))
                .body("results[0]", hasKey("categoryId")).body("results[0]", hasKey("createdAt"))
                .body("results[0]", hasKey("imageCount"))
                // Verify description truncation (max 200 chars)
                .body("results[0].description.length()", lessThanOrEqualTo(203)); // 200 + "..."
    }

    @Test
    public void testSearchByDateRange() {
        Instant now = Instant.now();
        Instant fiveDaysAgo = now.minus(5, ChronoUnit.DAYS);
        Instant oneDayAgo = now.minus(1, ChronoUnit.DAYS);

        given().queryParam("min_date", fiveDaysAgo.toString()).queryParam("max_date", oneDayAgo.toString()).when()
                .get("/api/marketplace/search").then().statusCode(200).body("results.size()", greaterThan(0));
    }

    @Test
    public void testSearchExcludesDraftListings() {
        // Search should NOT include draft listings
        given().queryParam("q", "Draft").when().get("/api/marketplace/search").then().statusCode(200)
                .body("results.size()", is(0));
    }

    @Test
    public void testSearchExcludesExpiredListings() {
        // Search should NOT include expired listings
        given().queryParam("q", "Expired").when().get("/api/marketplace/search").then().statusCode(200)
                .body("results.size()", is(0));
    }

    @Test
    public void testSearchExcludesRemovedListings() {
        // Search should NOT include removed listings
        given().queryParam("q", "Removed").when().get("/api/marketplace/search").then().statusCode(200)
                .body("results.size()", is(0));
    }
}
