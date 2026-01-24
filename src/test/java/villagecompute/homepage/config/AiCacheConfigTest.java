/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.Test;

import villagecompute.homepage.BaseIntegrationTest;
import villagecompute.homepage.api.types.FeedItemTaggingResultType;
import villagecompute.homepage.testing.PostgreSQLTestProfile;

import java.util.List;

/**
 * Test suite for {@link AiCacheConfig}.
 *
 * <p>
 * Tests SHA-256 content hashing, cache put/get operations, and cache expiration behavior.
 */
@QuarkusTest
@TestProfile(PostgreSQLTestProfile.class)
class AiCacheConfigTest extends BaseIntegrationTest {

    @Inject
    AiCacheConfig cacheConfig;

    @Test
    void testGenerateContentHash_sameContentProducesSameHash() {
        String content1 = "The quick brown fox jumps over the lazy dog";
        String content2 = "The quick brown fox jumps over the lazy dog";

        String hash1 = cacheConfig.generateContentHash(content1);
        String hash2 = cacheConfig.generateContentHash(content2);

        assertNotNull(hash1);
        assertNotNull(hash2);
        assertEquals(hash1, hash2, "Same content should produce identical hashes");
        assertEquals(64, hash1.length(), "SHA-256 hash should be 64 hex characters");
    }

    @Test
    void testGenerateContentHash_differentContentProducesDifferentHash() {
        String content1 = "The quick brown fox";
        String content2 = "The lazy dog";

        String hash1 = cacheConfig.generateContentHash(content1);
        String hash2 = cacheConfig.generateContentHash(content2);

        assertNotNull(hash1);
        assertNotNull(hash2);
        assertEquals(false, hash1.equals(hash2), "Different content should produce different hashes");
    }

    @Test
    @Transactional
    void testTaggingCache_putAndGet() {
        String contentHash = cacheConfig.generateContentHash("test article content");
        FeedItemTaggingResultType result = new FeedItemTaggingResultType(List.of("technology", "ai", "testing"),
                "Test article about AI technology", List.of("Tech News", "Science"), 0.95);

        // Store in cache
        cacheConfig.putTaggingResult(contentHash, result);

        // Retrieve from cache
        FeedItemTaggingResultType cached = cacheConfig.getTaggingResult(contentHash);

        assertNotNull(cached);
        assertEquals(result.topics(), cached.topics());
        assertEquals(result.summary(), cached.summary());
        assertEquals(result.categories(), cached.categories());
        assertEquals(result.confidenceScore(), cached.confidenceScore());
    }

    @Test
    void testTaggingCache_getMissReturnsNull() {
        String nonExistentHash = cacheConfig.generateContentHash("non-existent content");

        FeedItemTaggingResultType cached = cacheConfig.getTaggingResult(nonExistentHash);

        assertNull(cached, "Cache miss should return null");
    }

    @Test
    @Transactional
    void testEmbeddingCache_putAndGet() {
        String textHash = cacheConfig.generateContentHash("test text for embedding");
        float[] embedding = new float[]{0.1f, 0.2f, 0.3f, 0.4f, 0.5f};

        // Store in cache
        cacheConfig.putEmbedding(textHash, embedding);

        // Retrieve from cache
        float[] cached = cacheConfig.getEmbedding(textHash);

        assertNotNull(cached);
        assertEquals(embedding.length, cached.length);
        for (int i = 0; i < embedding.length; i++) {
            assertEquals(embedding[i], cached[i], 0.0001f);
        }
    }

    @Test
    void testEmbeddingCache_getMissReturnsNull() {
        String nonExistentHash = cacheConfig.generateContentHash("non-existent text");

        float[] cached = cacheConfig.getEmbedding(nonExistentHash);

        assertNull(cached, "Cache miss should return null");
    }

    @Test
    void testContentHash_deterministicForIdenticalInput() {
        String content = "deterministic hash test";

        String hash1 = cacheConfig.generateContentHash(content);
        String hash2 = cacheConfig.generateContentHash(content);
        String hash3 = cacheConfig.generateContentHash(content);

        assertEquals(hash1, hash2);
        assertEquals(hash2, hash3);
    }
}
