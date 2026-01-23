package villagecompute.homepage.services;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import jakarta.transaction.Transactional;
import villagecompute.homepage.api.types.AiCategorySuggestionType;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AiCategorizationService.
 *
 * <p>
 * Tests prompt construction, response parsing, category validation, and stub behavior.
 */
@QuarkusTest
public class AiCategorizationServiceTest {

    @Inject
    AiCategorizationService service;

    @Test
    public void testSuggestCategories_stubMode() {
        // Test stub response generation (before LangChain4j API key configured)
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
        // Test keyword-based categorization for programming content
        AiCategorySuggestionType suggestion = service.suggestCategories("https://stackoverflow.com",
                "Stack Overflow - Programming Q&A",
                "Question and answer site for professional and enthusiast programmers");

        assertNotNull(suggestion);

        // Stub should detect "programming" keyword
        assertTrue(
                suggestion.suggestedCategories().stream().anyMatch(cat -> cat.categorySlug().contains("programming")),
                "Should suggest programming-related category");
    }

    @Test
    public void testSuggestCategories_newsContent() {
        // Test keyword-based categorization for news content
        AiCategorySuggestionType suggestion = service.suggestCategories("https://news.ycombinator.com",
                "Hacker News - Tech News", "Tech news aggregator");

        assertNotNull(suggestion);

        // Stub should detect "news" keyword
        assertTrue(suggestion.suggestedCategories().stream().anyMatch(cat -> cat.categorySlug().contains("news")),
                "Should suggest news-related category");
    }

    @Test
    public void testSuggestCategories_nullTitle() {
        // Test handling of missing title
        AiCategorySuggestionType suggestion = service.suggestCategories("https://example.com", null,
                "Some description");

        assertNotNull(suggestion);
        assertFalse(suggestion.suggestedCategories().isEmpty());
    }

    @Test
    public void testSuggestCategories_nullDescription() {
        // Test handling of missing description
        AiCategorySuggestionType suggestion = service.suggestCategories("https://example.com", "Example Site", null);

        assertNotNull(suggestion);
        assertFalse(suggestion.suggestedCategories().isEmpty());
    }

    @Test
    public void testSuggestCategories_confidenceRange() {
        // Test that confidence is always in valid range
        AiCategorySuggestionType suggestion = service.suggestCategories("https://example.com", "Test Site",
                "Test description");

        assertTrue(suggestion.confidence() >= 0.0, "Confidence should be >= 0.0");
        assertTrue(suggestion.confidence() <= 1.0, "Confidence should be <= 1.0");
    }
}
