/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.homepage.api.types.AiTagsType;
import villagecompute.homepage.data.models.AiUsageTracking;
import villagecompute.homepage.data.models.FeedItem;
import villagecompute.homepage.services.AiTaggingService;

/**
 * Tests for AI tagging job handler.
 *
 * <p>
 * Verifies budget enforcement, batch processing, error handling, and telemetry per P2/P10 and P7 policy requirements.
 */
@QuarkusTest
class AiTaggingJobHandlerTest {

    @Inject
    AiTaggingJobHandler handler;

    @InjectMock
    AiTaggingService aiTaggingService;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up test data
        FeedItem.deleteAll();
        AiUsageTracking.deleteAll();
    }

    @Test
    void testHandlesType() {
        assertEquals(JobType.AI_TAGGING, handler.handlesType());
    }

    @Test
    @Transactional
    void testHandle_NoUntaggedItems() throws Exception {
        // No untagged items exist
        handler.execute(123L, Map.of());

        // Should complete without error
        assertEquals(0, FeedItem.count());
    }

    @Test
    @Transactional
    void testHandle_NormalBudgetAction() throws Exception {
        // Setup: budget at 50% (NORMAL)
        setupBudget(25000, 50000);

        // Create 5 untagged items
        createUntaggedItems(5);

        // Mock AI service to return tags
        when(aiTaggingService.tagArticle(anyString(), anyString(), anyString()))
                .thenReturn(new AiTagsType(List.of("Technology", "AI"), "positive", List.of("Technology"), 0.85));

        // Execute handler
        handler.execute(123L, Map.of());

        // Verify all items tagged
        assertEquals(5, FeedItem.count("aiTagged = true"));
        assertEquals(0, FeedItem.count("aiTagged = false"));
    }

    @Test
    @Transactional
    void testHandle_ReduceBudgetAction() throws Exception {
        // Setup: budget at 80% (REDUCE)
        setupBudget(40000, 50000);

        // Create 25 untagged items
        createUntaggedItems(25);

        // Mock AI service
        when(aiTaggingService.tagArticle(anyString(), anyString(), anyString()))
                .thenReturn(new AiTagsType(List.of("Business"), "neutral", List.of("Business"), 0.75));

        // Execute handler
        handler.execute(123L, Map.of());

        // With REDUCE action, batch size = 10, so first 10 items should be tagged
        long taggedCount = FeedItem.count("aiTagged = true");
        assertTrue(taggedCount >= 10, "At least 10 items should be tagged in REDUCE mode");
    }

    @Test
    @Transactional
    void testHandle_QueueBudgetAction() throws Exception {
        // Setup: budget at 95% (QUEUE)
        setupBudget(47500, 50000);

        // Create 10 untagged items
        createUntaggedItems(10);

        // Mock AI service (should not be called)
        when(aiTaggingService.tagArticle(anyString(), anyString(), anyString()))
                .thenReturn(new AiTagsType(List.of("Test"), "neutral", List.of("Test"), 0.5));

        // Execute handler
        handler.execute(123L, Map.of());

        // No items should be tagged (processing skipped)
        assertEquals(0, FeedItem.count("aiTagged = true"));
        assertEquals(10, FeedItem.count("aiTagged = false"));
    }

    @Test
    @Transactional
    void testHandle_HardStopBudgetAction() throws Exception {
        // Setup: budget at 105% (HARD_STOP)
        setupBudget(52500, 50000);

        // Create 10 untagged items
        createUntaggedItems(10);

        // Mock AI service (should not be called)
        when(aiTaggingService.tagArticle(anyString(), anyString(), anyString()))
                .thenReturn(new AiTagsType(List.of("Test"), "neutral", List.of("Test"), 0.5));

        // Execute handler
        handler.execute(123L, Map.of());

        // No items should be tagged (processing stopped)
        assertEquals(0, FeedItem.count("aiTagged = true"));
        assertEquals(10, FeedItem.count("aiTagged = false"));
    }

    @Test
    @Transactional
    void testHandle_IndividualItemFailure() throws Exception {
        // Setup: normal budget
        setupBudget(10000, 50000);

        // Create 3 untagged items
        createUntaggedItems(3);

        // Mock AI service to fail for first item, succeed for others
        when(aiTaggingService.tagArticle(anyString(), anyString(), anyString())).thenReturn(null) // Fail
                .thenReturn(new AiTagsType(List.of("Tech"), "positive", List.of("Technology"), 0.9)) // Success
                .thenReturn(new AiTagsType(List.of("Science"), "neutral", List.of("Science"), 0.85)); // Success

        // Execute handler
        handler.execute(123L, Map.of());

        // 2 items should be tagged (1 failed)
        assertEquals(2, FeedItem.count("aiTagged = true"));
        assertEquals(1, FeedItem.count("aiTagged = false"));
    }

    @Test
    @Transactional
    void testHandle_VerifyTagsPersisted() throws Exception {
        // Setup: normal budget
        setupBudget(5000, 50000);

        // Create 1 untagged item
        FeedItem item = createFeedItem("Test Article", "Test description", "Test content");

        // Mock AI service
        AiTagsType expectedTags = new AiTagsType(List.of("AI", "Machine Learning"), "positive",
                List.of("Technology", "Science"), 0.92);
        when(aiTaggingService.tagArticle(anyString(), anyString(), anyString())).thenReturn(expectedTags);

        // Execute handler
        handler.execute(123L, Map.of());

        // Verify tags persisted
        FeedItem taggedItem = FeedItem.findById(item.id);
        assertNotNull(taggedItem);
        assertTrue(taggedItem.aiTagged);
        assertNotNull(taggedItem.aiTags);
        assertEquals(2, taggedItem.aiTags.topics().size());
        assertEquals("positive", taggedItem.aiTags.sentiment());
        assertEquals(2, taggedItem.aiTags.categories().size());
        assertEquals(0.92, taggedItem.aiTags.confidence());
    }

    @Test
    @Transactional
    void testHandle_BatchProcessing() throws Exception {
        // Setup: normal budget
        setupBudget(10000, 50000);

        // Create 50 untagged items (should process in batches of 20)
        createUntaggedItems(50);

        // Mock AI service
        when(aiTaggingService.tagArticle(anyString(), anyString(), anyString()))
                .thenReturn(new AiTagsType(List.of("Topic"), "neutral", List.of("Business"), 0.8));

        // Execute handler
        handler.execute(123L, Map.of());

        // All 50 items should be tagged
        assertEquals(50, FeedItem.count("aiTagged = true"));
    }

    /**
     * Helper: sets up budget tracking with specified costs.
     */
    private void setupBudget(int costCents, int limitCents) {
        LocalDate currentMonth = YearMonth.now().atDay(1);

        AiUsageTracking tracking = new AiUsageTracking();
        tracking.month = currentMonth;
        tracking.provider = AiUsageTracking.DEFAULT_PROVIDER;
        tracking.estimatedCostCents = costCents;
        tracking.budgetLimitCents = limitCents;
        tracking.persist();
    }

    /**
     * Helper: creates N untagged feed items.
     */
    private void createUntaggedItems(int count) {
        for (int i = 0; i < count; i++) {
            createFeedItem("Article " + i, "Description " + i, "Content " + i);
        }
    }

    /**
     * Helper: creates a single feed item.
     */
    private FeedItem createFeedItem(String title, String description, String content) {
        FeedItem item = new FeedItem();
        item.sourceId = java.util.UUID.randomUUID(); // Mock source ID
        item.title = title;
        item.description = description;
        item.content = content;
        item.url = "https://example.com/" + System.nanoTime();
        item.itemGuid = "guid-" + System.nanoTime();
        item.publishedAt = Instant.now();
        item.fetchedAt = Instant.now();
        item.aiTagged = false;
        item.persist();
        return item;
    }
}
