package villagecompute.homepage.services;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import villagecompute.homepage.api.types.ContactInfoType;
import villagecompute.homepage.api.types.ListingSearchResultType;
import villagecompute.homepage.api.types.SearchCriteria;
import villagecompute.homepage.data.models.MarketplaceCategory;
import villagecompute.homepage.data.models.MarketplaceListing;

/**
 * Unit tests for MarketplaceSearchService (I4.T5).
 *
 * <p>
 * Test coverage:
 * <ul>
 * <li>Text search with Elasticsearch (or fallback to Postgres ILIKE)</li>
 * <li>Category filtering</li>
 * <li>Price range filtering</li>
 * <li>Date range filtering</li>
 * <li>Sorting (newest, price_asc, price_desc)</li>
 * <li>Pagination (offset, limit)</li>
 * <li>Empty result sets</li>
 * <li>Only active listings returned (draft/expired/removed excluded)</li>
 * </ul>
 *
 * <p>
 * <b>Note:</b> PostGIS radius filtering tests require PostGIS extension and geo_cities data, so they are tested in
 * integration tests (MarketplaceSearchResourceTest) with full database setup.
 *
 * <p>
 * <b>Note:</b> In-memory Lucene backend is used for tests (configured in application.yaml %test profile), so
 * Elasticsearch-specific behavior (fuzzy matching tolerance, cluster failover) is not tested here.
 */
@QuarkusTest
public class MarketplaceSearchServiceTest {

    @Inject
    MarketplaceSearchService searchService;

    private UUID categoryId1;
    private UUID categoryId2;
    private UUID userId;

    /**
     * Sets up test data: 2 categories, 10 active listings with varying prices and dates.
     *
     * <p>
     * Test data structure:
     * <ul>
     * <li>Category 1: 5 listings (prices: $100, $200, $300, $400, $500)</li>
     * <li>Category 2: 5 listings (prices: $50, $150, $250, $350, $450)</li>
     * <li>Dates: spread over 10 days (newest to oldest)</li>
     * <li>1 draft listing (excluded from search results)</li>
     * <li>1 expired listing (excluded from search results)</li>
     * </ul>
     */
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

        // Create 10 active listings
        Instant baseDate = Instant.now().minus(10, ChronoUnit.DAYS);

        for (int i = 0; i < 5; i++) {
            createListing("Bicycle " + i, "Vintage bicycle for sale " + i, categoryId1, new BigDecimal((i + 1) * 100),
                    baseDate.plus(i, ChronoUnit.DAYS), "active");
        }

        for (int i = 0; i < 5; i++) {
            createListing("Apartment " + i, "Nice apartment for rent " + i, categoryId2, new BigDecimal(50 + i * 100),
                    baseDate.plus(i + 5, ChronoUnit.DAYS), "active");
        }

        // Create 1 draft listing (should NOT appear in search results)
        createListing("Draft Item", "This is a draft", categoryId1, new BigDecimal("999"), Instant.now(), "draft");

        // Create 1 expired listing (should NOT appear in search results)
        createListing("Expired Item", "This expired", categoryId1, new BigDecimal("999"),
                Instant.now().minus(1, ChronoUnit.DAYS), "expired");
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
    @Transactional
    public void testSearchAll_ReturnsOnlyActiveListings() {
        SearchCriteria criteria = new SearchCriteria(null, null, null, null, null, null, null, null, null, "newest", 0,
                25);

        List<ListingSearchResultType> results = searchService.searchListings(criteria);
        long totalCount = searchService.countListings(criteria);

        assertEquals(10, results.size(), "Should return 10 active listings (exclude draft + expired)");
        assertEquals(10, totalCount, "Total count should be 10");

        // Verify newest first (default sort)
        assertTrue(results.get(0).createdAt().isAfter(results.get(9).createdAt()),
                "Results should be sorted newest first");
    }

    @Test
    @Transactional
    public void testSearchByCategory_FiltersCorrectly() {
        SearchCriteria criteria = new SearchCriteria(null, categoryId1, null, null, null, null, null, null, null,
                "newest", 0, 25);

        List<ListingSearchResultType> results = searchService.searchListings(criteria);
        long totalCount = searchService.countListings(criteria);

        assertEquals(5, results.size(), "Should return 5 listings in category 1");
        assertEquals(5, totalCount, "Total count should be 5");

        // Verify all results are in the correct category
        assertTrue(results.stream().allMatch(r -> r.categoryId().equals(categoryId1)),
                "All results should be in category 1");
    }

    @Test
    @Transactional
    public void testSearchByPriceRange_FiltersCorrectly() {
        SearchCriteria criteria = new SearchCriteria(null, null, new BigDecimal("150"), new BigDecimal("350"), null,
                null, null, null, null, "price_asc", 0, 25);

        List<ListingSearchResultType> results = searchService.searchListings(criteria);

        assertEquals(5, results.size(), "Should return 5 listings in price range $150-$350");

        // Verify all results are within price range
        assertTrue(
                results.stream()
                        .allMatch(r -> r.price().compareTo(new BigDecimal("150")) >= 0
                                && r.price().compareTo(new BigDecimal("350")) <= 0),
                "All results should be within price range");

        // Verify sorted by price ascending
        assertEquals(new BigDecimal("150"), results.get(0).price(), "First result should be $150");
        assertEquals(new BigDecimal("350"), results.get(4).price(), "Last result should be $350");
    }

    @Test
    @Transactional
    public void testSearchSortByPriceDescending() {
        SearchCriteria criteria = new SearchCriteria(null, null, null, null, null, null, null, null, null, "price_desc",
                0, 25);

        List<ListingSearchResultType> results = searchService.searchListings(criteria);

        assertEquals(10, results.size(), "Should return all 10 active listings");

        // Verify sorted by price descending
        assertEquals(new BigDecimal("500"), results.get(0).price(), "First result should be $500");
        assertEquals(new BigDecimal("50"), results.get(9).price(), "Last result should be $50");
    }

    @Test
    @Transactional
    public void testSearchByDateRange_FiltersCorrectly() {
        Instant now = Instant.now();
        Instant threeDaysAgo = now.minus(3, ChronoUnit.DAYS);
        Instant oneDayAgo = now.minus(1, ChronoUnit.DAYS);

        SearchCriteria criteria = new SearchCriteria(null, null, null, null, null, null, null, threeDaysAgo, oneDayAgo,
                "newest", 0, 25);

        List<ListingSearchResultType> results = searchService.searchListings(criteria);

        assertTrue(results.size() >= 2, "Should return at least 2 listings from last 3 days");

        // Verify all results are within date range
        assertTrue(
                results.stream()
                        .allMatch(r -> !r.createdAt().isBefore(threeDaysAgo) && !r.createdAt().isAfter(oneDayAgo)),
                "All results should be within date range");
    }

    @Test
    @Transactional
    public void testSearchPagination() {
        // Page 1: offset=0, limit=3
        SearchCriteria page1 = new SearchCriteria(null, null, null, null, null, null, null, null, null, "newest", 0, 3);
        List<ListingSearchResultType> results1 = searchService.searchListings(page1);

        assertEquals(3, results1.size(), "Page 1 should return 3 results");

        // Page 2: offset=3, limit=3
        SearchCriteria page2 = new SearchCriteria(null, null, null, null, null, null, null, null, null, "newest", 3, 3);
        List<ListingSearchResultType> results2 = searchService.searchListings(page2);

        assertEquals(3, results2.size(), "Page 2 should return 3 results");

        // Verify no overlap between pages
        assertFalse(results1.stream().anyMatch(r1 -> results2.stream().anyMatch(r2 -> r1.id().equals(r2.id()))),
                "Pages should not have overlapping results");
    }

    @Test
    @Transactional
    public void testSearchTextQuery_FindsMatchingListings() {
        SearchCriteria criteria = new SearchCriteria("bicycle", null, null, null, null, null, null, null, null,
                "newest", 0, 25);

        List<ListingSearchResultType> results = searchService.searchListings(criteria);

        assertEquals(5, results.size(), "Should find 5 bicycle listings");

        // Verify all results contain "bicycle" in title or description
        assertTrue(
                results.stream()
                        .allMatch(r -> r.title().toLowerCase().contains("bicycle")
                                || r.description().toLowerCase().contains("bicycle")),
                "All results should contain 'bicycle' in title or description");
    }

    @Test
    @Transactional
    public void testSearchNoResults_ReturnsEmptyList() {
        SearchCriteria criteria = new SearchCriteria("nonexistent", null, null, null, null, null, null, null, null,
                "newest", 0, 25);

        List<ListingSearchResultType> results = searchService.searchListings(criteria);
        long totalCount = searchService.countListings(criteria);

        assertEquals(0, results.size(), "Should return empty list for no matches");
        assertEquals(0, totalCount, "Total count should be 0");
    }

    @Test
    @Transactional
    public void testSearchCombinedFilters() {
        // Search for bicycles in category 1, price $200-$400, sorted by price descending
        SearchCriteria criteria = new SearchCriteria("bicycle", categoryId1, new BigDecimal("200"),
                new BigDecimal("400"), null, null, null, null, null, "price_desc", 0, 25);

        List<ListingSearchResultType> results = searchService.searchListings(criteria);

        assertEquals(3, results.size(), "Should find 3 bicycle listings in price range $200-$400");

        // Verify filters applied correctly
        assertTrue(results.stream().allMatch(r -> r.categoryId().equals(categoryId1)), "All results in category 1");
        assertTrue(results.stream().allMatch(r -> r.price().compareTo(new BigDecimal("200")) >= 0
                && r.price().compareTo(new BigDecimal("400")) <= 0), "All results in price range");

        // Verify sorted by price descending
        assertEquals(new BigDecimal("400"), results.get(0).price(), "First result should be $400");
        assertEquals(new BigDecimal("200"), results.get(2).price(), "Last result should be $200");
    }

    @Test
    public void testSearchCriteriaValidation_InvalidRadius() {
        // Invalid radius value (not in 5, 10, 25, 50, 100, 250)
        assertThrows(IllegalArgumentException.class, () -> {
            new SearchCriteria(null, null, null, null, 123L, 99, null, null, null, "newest", 0, 25);
        }, "Should reject invalid radius value");
    }

    @Test
    public void testSearchCriteriaValidation_RadiusWithoutLocation() {
        // Radius specified but no geoCityId
        assertThrows(IllegalArgumentException.class, () -> {
            new SearchCriteria(null, null, null, null, null, 25, null, null, null, "newest", 0, 25);
        }, "Should reject radius without geoCityId");
    }

    @Test
    public void testSearchCriteriaValidation_InvalidSortBy() {
        // Invalid sortBy value
        assertThrows(IllegalArgumentException.class, () -> {
            new SearchCriteria(null, null, null, null, null, null, null, null, null, "invalid_sort", 0, 25);
        }, "Should reject invalid sortBy value");
    }

    @Test
    public void testSearchCriteriaValidation_NegativeOffset() {
        // Negative offset
        assertThrows(IllegalArgumentException.class, () -> {
            new SearchCriteria(null, null, null, null, null, null, null, null, null, "newest", -1, 25);
        }, "Should reject negative offset");
    }

    @Test
    public void testSearchCriteriaValidation_InvalidLimit() {
        // Limit > 100
        assertThrows(IllegalArgumentException.class, () -> {
            new SearchCriteria(null, null, null, null, null, null, null, null, null, "newest", 0, 101);
        }, "Should reject limit > 100");

        // Limit <= 0
        assertThrows(IllegalArgumentException.class, () -> {
            new SearchCriteria(null, null, null, null, null, null, null, null, null, "newest", 0, 0);
        }, "Should reject limit <= 0");
    }
}
