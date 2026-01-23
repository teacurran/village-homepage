/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.langchain4j.model.chat.ChatModel;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import villagecompute.homepage.api.types.FeedItemTaggingResultType;
import villagecompute.homepage.data.models.FeedItem;

/**
 * Tests for AI-powered content tagging service with batch processing.
 *
 * <p>
 * Verifies LangChain4j integration, JSON parsing robustness, batch processing logic, and error handling for feed item
 * tagging operations.
 */
@QuarkusTest
class AiTaggingServiceTest {

    @Inject
    AiTaggingService service;

    @InjectMock
    ChatModel mockChatModel;

    /**
     * Tests single-item tagging with valid Claude response.
     */
    @Test
    void testTagFeedItem_Success() {
        // Given: mock Claude response
        String mockResponse = """
                {
                  "topics": ["Technology", "AI", "Software"],
                  "summary": "Article discusses recent advances in artificial intelligence.",
                  "categories": ["Tech News"],
                  "confidenceScore": 0.95
                }
                """;

        when(mockChatModel.chat(anyString())).thenReturn(mockResponse);

        // Create test item
        FeedItem item = createTestFeedItem("AI Breakthrough in Machine Learning", "New ML techniques...",
                "Article content about AI...");

        // When: tag item
        FeedItemTaggingResultType result = service.tagFeedItem(item);

        // Then: verify result
        assertNotNull(result);
        assertEquals(3, result.topics().size());
        assertTrue(result.topics().contains("Technology"));
        assertTrue(result.topics().contains("AI"));
        assertEquals("Article discusses recent advances in artificial intelligence.", result.summary());
        assertEquals(1, result.categories().size());
        assertEquals("Tech News", result.categories().get(0));
        assertEquals(0.95, result.confidenceScore(), 0.001);
    }

    /**
     * Tests batch tagging with 5 items.
     */
    @Test
    void testTagFeedItemsBatch_FiveItems() {
        // Given: mock batch response for 5 items
        String mockResponse = """
                [
                  {"index": 0, "topics": ["Technology", "AI"], "summary": "AI article summary.", "categories": ["Tech News"], "confidenceScore": 0.95},
                  {"index": 1, "topics": ["Politics", "Elections"], "summary": "Political news summary.", "categories": ["Politics"], "confidenceScore": 0.88},
                  {"index": 2, "topics": ["Sports", "Football"], "summary": "Game results summary.", "categories": ["Sports"], "confidenceScore": 0.92},
                  {"index": 3, "topics": ["Business", "Markets"], "summary": "Stock market summary.", "categories": ["Business"], "confidenceScore": 0.90},
                  {"index": 4, "topics": ["Health", "Nutrition"], "summary": "Health tips summary.", "categories": ["Health"], "confidenceScore": 0.87}
                ]
                """;

        when(mockChatModel.chat(anyString())).thenReturn(mockResponse);

        // Create test items
        List<FeedItem> items = createTestFeedItems(5);

        // When: tag batch
        List<FeedItemTaggingResultType> results = service.tagFeedItemsBatch(items);

        // Then: verify results
        assertNotNull(results);
        assertEquals(5, results.size());

        // Verify first item
        FeedItemTaggingResultType result0 = results.get(0);
        assertNotNull(result0);
        assertEquals(2, result0.topics().size());
        assertEquals("AI article summary.", result0.summary());
        assertEquals("Tech News", result0.categories().get(0));
        assertEquals(0.95, result0.confidenceScore(), 0.001);

        // Verify second item
        FeedItemTaggingResultType result1 = results.get(1);
        assertNotNull(result1);
        assertEquals("Politics", result1.topics().get(0));
        assertEquals("Political news summary.", result1.summary());

        // Verify third item
        FeedItemTaggingResultType result2 = results.get(2);
        assertNotNull(result2);
        assertEquals("Sports", result2.categories().get(0));
        assertEquals(0.92, result2.confidenceScore(), 0.001);
    }

    /**
     * Tests batch tagging with 20 items (maximum batch size).
     */
    @Test
    void testTagFeedItemsBatch_TwentyItems() {
        // Given: mock batch response for 20 items
        StringBuilder responseBuilder = new StringBuilder("[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) {
                responseBuilder.append(",");
            }
            responseBuilder.append(String.format(
                    """
                            {"index": %d, "topics": ["Topic%d"], "summary": "Summary %d.", "categories": ["Tech News"], "confidenceScore": 0.9}
                            """,
                    i, i, i));
        }
        responseBuilder.append("]");

        when(mockChatModel.chat(anyString())).thenReturn(responseBuilder.toString());

        // Create test items
        List<FeedItem> items = createTestFeedItems(20);

        // When: tag batch
        List<FeedItemTaggingResultType> results = service.tagFeedItemsBatch(items);

        // Then: verify results
        assertNotNull(results);
        assertEquals(20, results.size());

        // Verify all items were tagged
        for (int i = 0; i < 20; i++) {
            FeedItemTaggingResultType result = results.get(i);
            assertNotNull(result, "Result " + i + " should not be null");
            assertEquals("Summary " + i + ".", result.summary());
        }
    }

    /**
     * Tests batch splitting when more than 20 items provided.
     */
    @Test
    void testTagFeedItemsBatch_SplittingOver20Items() {
        // Given: mock response for batches
        // First batch (20 items)
        StringBuilder response1 = new StringBuilder("[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) {
                response1.append(",");
            }
            response1.append(String.format(
                    """
                            {"index": %d, "topics": ["Topic%d"], "summary": "Summary %d.", "categories": ["Tech News"], "confidenceScore": 0.9}
                            """,
                    i, i, i));
        }
        response1.append("]");

        // Second batch (5 items)
        StringBuilder response2 = new StringBuilder("[");
        for (int i = 0; i < 5; i++) {
            if (i > 0) {
                response2.append(",");
            }
            response2.append(String.format(
                    """
                            {"index": %d, "topics": ["Topic%d"], "summary": "Summary %d.", "categories": ["Tech News"], "confidenceScore": 0.9}
                            """,
                    i, i + 20, i + 20));
        }
        response2.append("]");

        // Return different responses for each batch
        when(mockChatModel.chat(anyString())).thenReturn(response1.toString()).thenReturn(response2.toString());

        // Create test items (25 total)
        List<FeedItem> items = createTestFeedItems(25);

        // When: tag batch
        List<FeedItemTaggingResultType> results = service.tagFeedItemsBatch(items);

        // Then: verify results
        assertNotNull(results);
        assertEquals(25, results.size());

        // Verify batch 1 (items 0-19)
        for (int i = 0; i < 20; i++) {
            assertNotNull(results.get(i), "Result " + i + " should not be null");
        }

        // Verify batch 2 (items 20-24)
        for (int i = 20; i < 25; i++) {
            assertNotNull(results.get(i), "Result " + i + " should not be null");
        }
    }

    /**
     * Tests parsing robustness with markdown-wrapped JSON response.
     */
    @Test
    void testTagFeedItem_WithMarkdownCodeBlocks() {
        // Given: mock response wrapped in markdown
        String mockResponse = """
                ```json
                {
                  "topics": ["Technology"],
                  "summary": "Article about tech.",
                  "categories": ["Tech News"],
                  "confidenceScore": 0.85
                }
                ```
                """;

        when(mockChatModel.chat(anyString())).thenReturn(mockResponse);

        FeedItem item = createTestFeedItem("Tech Article", "Description", "Content");

        // When: tag item
        FeedItemTaggingResultType result = service.tagFeedItem(item);

        // Then: verify parsing succeeded despite markdown
        assertNotNull(result);
        assertEquals("Technology", result.topics().get(0));
        assertEquals("Article about tech.", result.summary());
        assertEquals(0.85, result.confidenceScore(), 0.001);
    }

    /**
     * Tests handling of malformed JSON response.
     */
    @Test
    void testTagFeedItem_MalformedJSON() {
        // Given: mock response with invalid JSON
        String mockResponse = "This is not valid JSON at all!";

        when(mockChatModel.chat(anyString())).thenReturn(mockResponse);

        FeedItem item = createTestFeedItem("Test Article", "Description", "Content");

        // When: tag item
        FeedItemTaggingResultType result = service.tagFeedItem(item);

        // Then: verify defaults applied (service should not throw exception)
        assertNotNull(result);
        assertEquals("Uncategorized", result.topics().get(0));
        assertEquals("No summary available.", result.summary());
        assertEquals("Lifestyle", result.categories().get(0));
        assertEquals(0.3, result.confidenceScore(), 0.001); // Low confidence for fallback
    }

    /**
     * Tests handling of partial batch failures.
     */
    @Test
    void testTagFeedItemsBatch_PartialFailure() {
        // Given: mock response with only 3 items instead of 5
        String mockResponse = """
                [
                  {"index": 0, "topics": ["Topic1"], "summary": "Summary 1.", "categories": ["Tech News"], "confidenceScore": 0.9},
                  {"index": 1, "topics": ["Topic2"], "summary": "Summary 2.", "categories": ["Politics"], "confidenceScore": 0.85},
                  {"index": 2, "topics": ["Topic3"], "summary": "Summary 3.", "categories": ["Sports"], "confidenceScore": 0.88}
                ]
                """;

        when(mockChatModel.chat(anyString())).thenReturn(mockResponse);

        // Create test items (5 total)
        List<FeedItem> items = createTestFeedItems(5);

        // When: tag batch
        List<FeedItemTaggingResultType> results = service.tagFeedItemsBatch(items);

        // Then: verify first 3 items succeeded, last 2 are null
        assertNotNull(results);
        assertEquals(5, results.size());

        assertNotNull(results.get(0));
        assertNotNull(results.get(1));
        assertNotNull(results.get(2));
        assertNull(results.get(3)); // Missing from response
        assertNull(results.get(4)); // Missing from response
    }

    /**
     * Tests null/empty input handling.
     */
    @Test
    void testTagFeedItemsBatch_EmptyInput() {
        // When: tag empty list
        List<FeedItemTaggingResultType> results = service.tagFeedItemsBatch(new ArrayList<>());

        // Then: verify empty results
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    /**
     * Tests null item handling.
     */
    @Test
    void testTagFeedItem_NullItem() {
        // When: tag null item
        FeedItemTaggingResultType result = service.tagFeedItem(null);

        // Then: verify null result
        assertNull(result);
    }

    /**
     * Tests validation of confidence scores out of range.
     */
    @Test
    void testTagFeedItem_InvalidConfidenceScore() {
        // Given: mock response with confidence score > 1.0
        String mockResponse = """
                {
                  "topics": ["Technology"],
                  "summary": "Article summary.",
                  "categories": ["Tech News"],
                  "confidenceScore": 1.5
                }
                """;

        when(mockChatModel.chat(anyString())).thenReturn(mockResponse);

        FeedItem item = createTestFeedItem("Test Article", "Description", "Content");

        // When: tag item
        FeedItemTaggingResultType result = service.tagFeedItem(item);

        // Then: verify confidence defaulted to 0.5
        assertNotNull(result);
        assertEquals(0.5, result.confidenceScore(), 0.001);
    }

    /**
     * Tests handling of missing topics field in response.
     */
    @Test
    void testTagFeedItem_MissingTopics() {
        // Given: mock response without topics field
        String mockResponse = """
                {
                  "summary": "Article summary.",
                  "categories": ["Tech News"],
                  "confidenceScore": 0.9
                }
                """;

        when(mockChatModel.chat(anyString())).thenReturn(mockResponse);

        FeedItem item = createTestFeedItem("Test Article", "Description", "Content");

        // When: tag item
        FeedItemTaggingResultType result = service.tagFeedItem(item);

        // Then: verify defaults applied
        assertNotNull(result);
        assertEquals("Uncategorized", result.topics().get(0));
    }

    // Test helper methods

    /**
     * Creates a test feed item with specified fields.
     */
    private FeedItem createTestFeedItem(String title, String description, String content) {
        FeedItem item = new FeedItem();
        item.id = UUID.randomUUID();
        item.sourceId = UUID.randomUUID();
        item.title = title;
        item.description = description;
        item.content = content;
        item.url = "https://example.com/article";
        item.itemGuid = "test-guid-" + UUID.randomUUID();
        item.publishedAt = Instant.now();
        item.fetchedAt = Instant.now();
        item.aiTagged = false;
        return item;
    }

    /**
     * Creates multiple test feed items.
     */
    private List<FeedItem> createTestFeedItems(int count) {
        List<FeedItem> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add(createTestFeedItem("Test Article " + i, "Description " + i, "Content " + i));
        }
        return items;
    }
}
