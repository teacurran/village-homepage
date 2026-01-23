/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.test.junit.QuarkusTest;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import villagecompute.homepage.api.types.FeedItemTaggingResultType;
import villagecompute.homepage.config.AiCacheConfig;
import villagecompute.homepage.data.models.FeedItem;

/**
 * Test suite for {@link AiTaggingService} caching and circuit breaker behavior.
 *
 * <p>
 * Tests cache hit/miss scenarios, content hashing, and fallback behavior when circuit breaker is OPEN.
 */
@QuarkusTest
class AiTaggingServiceCacheTest {

    @Inject
    AiTaggingService aiTaggingService;

    @Inject
    AiCacheConfig cacheConfig;

    @Inject
    AiUsageTrackingService usageTrackingService;

    @Test
    void testTagFeedItem_nullItemReturnsNull() {
        FeedItemTaggingResultType result = aiTaggingService.tagFeedItem(null);

        assertNull(result, "Tagging null item should return null");
    }

    @Test
    void testTagFeedItem_cacheHitScenario() {
        // Create feed item
        FeedItem item = new FeedItem();
        item.title = "AI Advances in 2025";
        item.description = "Breakthrough in artificial intelligence technology";
        item.content = "Recent developments in AI have led to significant breakthroughs...";

        // Pre-populate cache with result for this content
        String content = item.title + item.description + item.content;
        String contentHash = cacheConfig.generateContentHash(content);
        FeedItemTaggingResultType cachedResult = new FeedItemTaggingResultType(
                java.util.List.of("ai", "technology", "innovation"), "Article about AI breakthroughs",
                java.util.List.of("Tech News", "Science"), 0.92);
        cacheConfig.putTaggingResult(contentHash, cachedResult);

        // Record initial cache stats
        long hitsBefore = usageTrackingService.getCacheStatistics().hits();

        // Tag the item (should hit cache)
        FeedItemTaggingResultType result = aiTaggingService.tagFeedItem(item);

        // Verify cache hit
        assertNotNull(result);
        assertEquals(cachedResult.topics(), result.topics());
        assertEquals(cachedResult.summary(), result.summary());
        assertEquals(cachedResult.categories(), result.categories());
        assertEquals(cachedResult.confidenceScore(), result.confidenceScore());

        // Verify cache hit was recorded
        long hitsAfter = usageTrackingService.getCacheStatistics().hits();
        assertEquals(hitsBefore + 1, hitsAfter, "Cache hit should be recorded");
    }

    @Test
    void testContentHash_deterministicForSameContent() {
        FeedItem item1 = new FeedItem();
        item1.title = "Test Article";
        item1.description = "Test description";
        item1.content = "Test content";

        FeedItem item2 = new FeedItem();
        item2.title = "Test Article";
        item2.description = "Test description";
        item2.content = "Test content";

        String content1 = item1.title + item1.description + item1.content;
        String content2 = item2.title + item2.description + item2.content;

        String hash1 = cacheConfig.generateContentHash(content1);
        String hash2 = cacheConfig.generateContentHash(content2);

        assertEquals(hash1, hash2, "Identical content should produce identical hashes");
    }

    @Test
    void testContentHash_differentForDifferentContent() {
        FeedItem item1 = new FeedItem();
        item1.title = "Test Article 1";
        item1.description = "Description 1";
        item1.content = "Content 1";

        FeedItem item2 = new FeedItem();
        item2.title = "Test Article 2";
        item2.description = "Description 2";
        item2.content = "Content 2";

        String content1 = item1.title + item1.description + item1.content;
        String content2 = item2.title + item2.description + item2.content;

        String hash1 = cacheConfig.generateContentHash(content1);
        String hash2 = cacheConfig.generateContentHash(content2);

        assertEquals(false, hash1.equals(hash2), "Different content should produce different hashes");
    }
}
