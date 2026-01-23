package villagecompute.homepage.services;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.langchain4j.model.chat.ChatModel;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.api.types.AiCategorySuggestionType;
import villagecompute.homepage.api.types.ContactInfoType;
import villagecompute.homepage.api.types.ListingCategorizationResultType;
import villagecompute.homepage.data.models.MarketplaceListing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for AiCategorizationService.
 *
 * <p>
 * Tests prompt construction, response parsing, category validation, and stub behavior for both:
 * <ul>
 * <li>Directory site categorization (Good Sites feature)
 * <li>Marketplace listing categorization (Classifieds feature)
 * </ul>
 */
@QuarkusTest
public class AiCategorizationServiceTest {

    @Inject
    AiCategorizationService service;

    @InjectMock
    ChatModel mockChatModel;

    @Test
    public void testSuggestCategories_stubMode() {
        // Mock Claude response for directory site categorization
        String mockResponse = """
                {
                  "suggested_categories": [
                    {
                      "category_slug": "computers-programming",
                      "category_path": "Computers > Programming",
                      "reasoning": "GitHub is a code hosting platform"
                    }
                  ],
                  "confidence": 0.95,
                  "overall_reasoning": "Clear categorization based on platform purpose"
                }
                """;
        when(mockChatModel.chat(anyString())).thenReturn(mockResponse);

        AiCategorySuggestionType suggestion = service.suggestCategories("https://github.com", "GitHub",
                "Platform for code hosting and collaboration");

        assertNotNull(suggestion);
        assertNotNull(suggestion.suggestedCategories());
        assertFalse(suggestion.suggestedCategories().isEmpty());
        assertTrue(suggestion.confidence() >= 0.0 && suggestion.confidence() <= 1.0);
        assertNotNull(suggestion.overallReasoning());
    }

    @Test
    public void testSuggestCategories_programmingContent() {
        // Mock Claude response for programming site
        String mockResponse = """
                {
                  "suggested_categories": [
                    {
                      "category_slug": "computers-programming",
                      "category_path": "Computers > Programming",
                      "reasoning": "Q&A site for programmers"
                    }
                  ],
                  "confidence": 0.92,
                  "overall_reasoning": "Programming-focused content"
                }
                """;
        when(mockChatModel.chat(anyString())).thenReturn(mockResponse);

        AiCategorySuggestionType suggestion = service.suggestCategories("https://stackoverflow.com",
                "Stack Overflow - Programming Q&A",
                "Question and answer site for professional and enthusiast programmers");

        assertNotNull(suggestion);

        // Should detect "programming" keyword
        assertTrue(
                suggestion.suggestedCategories().stream().anyMatch(cat -> cat.categorySlug().contains("programming")),
                "Should suggest programming-related category");
    }

    @Test
    public void testSuggestCategories_newsContent() {
        // Mock Claude response for news site
        String mockResponse = """
                {
                  "suggested_categories": [
                    {
                      "category_slug": "news-technology",
                      "category_path": "News > Technology",
                      "reasoning": "Tech news aggregation site"
                    }
                  ],
                  "confidence": 0.88,
                  "overall_reasoning": "Technology news focus"
                }
                """;
        when(mockChatModel.chat(anyString())).thenReturn(mockResponse);

        AiCategorySuggestionType suggestion = service.suggestCategories("https://news.ycombinator.com",
                "Hacker News - Tech News", "Tech news aggregator");

        assertNotNull(suggestion);

        // Should detect "news" keyword
        assertTrue(suggestion.suggestedCategories().stream().anyMatch(cat -> cat.categorySlug().contains("news")),
                "Should suggest news-related category");
    }

    @Test
    public void testSuggestCategories_nullTitle() {
        // Mock Claude response for null title
        String mockResponse = """
                {
                  "suggested_categories": [
                    {
                      "category_slug": "general",
                      "category_path": "General",
                      "reasoning": "Based on description only"
                    }
                  ],
                  "confidence": 0.65,
                  "overall_reasoning": "Limited information without title"
                }
                """;
        when(mockChatModel.chat(anyString())).thenReturn(mockResponse);

        AiCategorySuggestionType suggestion = service.suggestCategories("https://example.com", null,
                "Some description");

        assertNotNull(suggestion);
        assertFalse(suggestion.suggestedCategories().isEmpty());
    }

    @Test
    public void testSuggestCategories_nullDescription() {
        // Mock Claude response for null description
        String mockResponse = """
                {
                  "suggested_categories": [
                    {
                      "category_slug": "general",
                      "category_path": "General",
                      "reasoning": "Based on title only"
                    }
                  ],
                  "confidence": 0.60,
                  "overall_reasoning": "Limited information without description"
                }
                """;
        when(mockChatModel.chat(anyString())).thenReturn(mockResponse);

        AiCategorySuggestionType suggestion = service.suggestCategories("https://example.com", "Example Site", null);

        assertNotNull(suggestion);
        assertFalse(suggestion.suggestedCategories().isEmpty());
    }

    @Test
    public void testSuggestCategories_confidenceRange() {
        // Mock Claude response
        String mockResponse = """
                {
                  "suggested_categories": [
                    {
                      "category_slug": "general",
                      "category_path": "General",
                      "reasoning": "Generic site"
                    }
                  ],
                  "confidence": 0.75,
                  "overall_reasoning": "General categorization"
                }
                """;
        when(mockChatModel.chat(anyString())).thenReturn(mockResponse);

        AiCategorySuggestionType suggestion = service.suggestCategories("https://example.com", "Test Site",
                "Test description");

        assertTrue(suggestion.confidence() >= 0.0, "Confidence should be >= 0.0");
        assertTrue(suggestion.confidence() <= 1.0, "Confidence should be <= 1.0");
    }

    // ========================================
    // Marketplace Listing Categorization Tests
    // ========================================

    @Test
    public void testCategorizeListing_electronics() {
        // Mock Claude response for electronics listing
        String mockResponse = """
                [
                  {"index": 0, "category": "For Sale", "subcategory": "Electronics", "confidenceScore": 0.95, "reasoning": "Gaming laptop with high-end specs clearly fits Electronics category"}
                ]
                """;
        when(mockChatModel.chat(anyString())).thenReturn(mockResponse);

        MarketplaceListing listing = createMockListing("Gaming Laptop for Sale",
                "Alienware gaming laptop with RTX 3080 GPU, 32GB RAM, 1TB SSD. Excellent condition.");

        ListingCategorizationResultType result = service.categorizeListing(listing);

        assertNotNull(result, "Result should not be null");
        assertNotNull(result.category(), "Category should not be null");
        assertNotNull(result.subcategory(), "Subcategory should not be null");
        assertNotNull(result.confidenceScore(), "Confidence score should not be null");
        assertNotNull(result.reasoning(), "Reasoning should not be null");

        assertTrue(result.confidenceScore() >= 0.0 && result.confidenceScore() <= 1.0,
                "Confidence should be in range 0.0-1.0");

        // For this test, we expect "For Sale" category (exact subcategory depends on AI)
        assertEquals("For Sale", result.category(), "Should categorize as For Sale");
    }

    @Test
    public void testCategorizeListing_housing() {
        // Mock Claude response for housing listing
        String mockResponse = """
                [
                  {"index": 0, "category": "Housing", "subcategory": "Roommates", "confidenceScore": 0.92, "reasoning": "Seeking roommate for shared apartment"}
                ]
                """;
        when(mockChatModel.chat(anyString())).thenReturn(mockResponse);

        MarketplaceListing listing = createMockListing("2BR Apartment Near Downtown",
                "Looking for roommate to share 2-bedroom apartment near downtown. $800/month plus utilities.");

        ListingCategorizationResultType result = service.categorizeListing(listing);

        assertNotNull(result);
        assertEquals("Housing", result.category(), "Should categorize as Housing");
        assertTrue(result.confidenceScore() >= 0.0 && result.confidenceScore() <= 1.0);
    }

    @Test
    public void testCategorizeListing_jobs() {
        // Mock Claude response for job listing
        String mockResponse = """
                [
                  {"index": 0, "category": "Jobs", "subcategory": "Full-time", "confidenceScore": 0.90, "reasoning": "Full-time software development position"}
                ]
                """;
        when(mockChatModel.chat(anyString())).thenReturn(mockResponse);

        MarketplaceListing listing = createMockListing("Software Developer - Remote",
                "Full-time software developer position. Remote work available. Java and React experience required.");

        ListingCategorizationResultType result = service.categorizeListing(listing);

        assertNotNull(result);
        assertEquals("Jobs", result.category(), "Should categorize as Jobs");
        assertTrue(result.confidenceScore() >= 0.0 && result.confidenceScore() <= 1.0);
    }

    @Test
    public void testCategorizeListing_services() {
        // Mock Claude response for services listing
        String mockResponse = """
                [
                  {"index": 0, "category": "Services", "subcategory": "Personal", "confidenceScore": 0.88, "reasoning": "House cleaning is a personal service"}
                ]
                """;
        when(mockChatModel.chat(anyString())).thenReturn(mockResponse);

        MarketplaceListing listing = createMockListing("Professional House Cleaning",
                "Experienced house cleaning service. Weekly or bi-weekly appointments available. References provided.");

        ListingCategorizationResultType result = service.categorizeListing(listing);

        assertNotNull(result);
        assertEquals("Services", result.category(), "Should categorize as Services");
        assertTrue(result.confidenceScore() >= 0.0 && result.confidenceScore() <= 1.0);
    }

    @Test
    public void testCategorizeListing_community() {
        // Mock Claude response for community listing
        String mockResponse = """
                [
                  {"index": 0, "category": "Community", "subcategory": "Activities", "confidenceScore": 0.93, "reasoning": "Book club is a community activity"}
                ]
                """;
        when(mockChatModel.chat(anyString())).thenReturn(mockResponse);

        MarketplaceListing listing = createMockListing("Book Club Meetup",
                "Weekly book club meeting every Thursday at local library. All genres welcome!");

        ListingCategorizationResultType result = service.categorizeListing(listing);

        assertNotNull(result);
        assertEquals("Community", result.category(), "Should categorize as Community");
        assertTrue(result.confidenceScore() >= 0.0 && result.confidenceScore() <= 1.0);
    }

    @Test
    public void testCategorizeListingsBatch_multipleListi() {
        // Mock Claude response for batch of 5 listings
        String mockResponse = """
                [
                  {"index": 0, "category": "For Sale", "subcategory": "Electronics", "confidenceScore": 0.94, "reasoning": "iPhone is an electronic device"},
                  {"index": 1, "category": "Housing", "subcategory": "Rent", "confidenceScore": 0.91, "reasoning": "Studio apartment for rent"},
                  {"index": 2, "category": "Jobs", "subcategory": "Part-time", "confidenceScore": 0.89, "reasoning": "Part-time employment position"},
                  {"index": 3, "category": "Services", "subcategory": "Home Improvement", "confidenceScore": 0.87, "reasoning": "Lawn care is home improvement service"},
                  {"index": 4, "category": "Community", "subcategory": "Lost & Found", "confidenceScore": 0.96, "reasoning": "Lost pet posting"}
                ]
                """;
        when(mockChatModel.chat(anyString())).thenReturn(mockResponse);

        List<MarketplaceListing> listings = new ArrayList<>();

        listings.add(createMockListing("iPhone 14 for Sale", "Used iPhone 14 in excellent condition. 128GB storage."));
        listings.add(createMockListing("Studio Apartment",
                "Cozy studio apartment for rent. $1200/month, utilities included."));
        listings.add(
                createMockListing("Part-time Barista", "Part-time barista position at local coffee shop. Weekends."));
        listings.add(createMockListing("Lawn Mowing Service", "Professional lawn care and landscaping services."));
        listings.add(createMockListing("Lost Dog", "Lost golden retriever near Main Street. Please contact if found."));

        List<ListingCategorizationResultType> results = service.categorizeListingsBatch(listings);

        assertNotNull(results);
        assertEquals(5, results.size(), "Should return result for each listing");

        // Verify all results are valid
        for (int i = 0; i < results.size(); i++) {
            ListingCategorizationResultType result = results.get(i);
            assertNotNull(result, "Result " + i + " should not be null");
            assertNotNull(result.category(), "Result " + i + " category should not be null");
            assertNotNull(result.subcategory(), "Result " + i + " subcategory should not be null");
            assertTrue(result.confidenceScore() >= 0.0 && result.confidenceScore() <= 1.0,
                    "Result " + i + " confidence should be in range");
        }

        // Verify expected categories (order matches input)
        assertEquals("For Sale", results.get(0).category(), "iPhone should be For Sale");
        assertEquals("Housing", results.get(1).category(), "Apartment should be Housing");
        assertEquals("Jobs", results.get(2).category(), "Barista should be Jobs");
        assertEquals("Services", results.get(3).category(), "Lawn care should be Services");
        assertEquals("Community", results.get(4).category(), "Lost dog should be Community");
    }

    @Test
    public void testCategorizeListingsBatch_emptyList() {
        // Test batch with empty list
        List<ListingCategorizationResultType> results = service.categorizeListingsBatch(List.of());

        assertNotNull(results);
        assertTrue(results.isEmpty(), "Should return empty list for empty input");
    }

    @Test
    public void testCategorizeListingsBatch_nullInput() {
        // Test batch with null input
        List<ListingCategorizationResultType> results = service.categorizeListingsBatch(null);

        assertNotNull(results);
        assertTrue(results.isEmpty(), "Should return empty list for null input");
    }

    @Test
    public void testStoreCategorizationSuggestion_nullListing() {
        // Test storing suggestion with null listing
        ListingCategorizationResultType result = new ListingCategorizationResultType("For Sale", "Electronics", 0.9,
                "Test reasoning");

        // Should not throw exception
        assertDoesNotThrow(() -> service.storeCategorizationSuggestion(null, result));
    }

    @Test
    public void testStoreCategorizationSuggestion_nullResult() {
        // Test storing null suggestion
        MarketplaceListing listing = createMockListing("Test", "Test description");

        // Should not throw exception
        assertDoesNotThrow(() -> service.storeCategorizationSuggestion(listing, null));
    }

    /**
     * Creates a mock MarketplaceListing for testing (not persisted to database).
     */
    private MarketplaceListing createMockListing(String title, String description) {
        MarketplaceListing listing = new MarketplaceListing();
        listing.id = UUID.randomUUID();
        listing.userId = UUID.randomUUID();
        listing.categoryId = UUID.randomUUID();
        listing.title = title;
        listing.description = description;
        listing.price = BigDecimal.valueOf(100.00);
        listing.contactInfo = new ContactInfoType("test@example.com", null,
                "listing-" + listing.id + "@villagecompute.com");
        listing.status = "active";
        listing.createdAt = Instant.now();
        listing.updatedAt = Instant.now();
        listing.reminderSent = false;
        listing.flagCount = 0L;
        return listing;
    }
}
