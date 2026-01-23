/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import villagecompute.homepage.api.types.FeedItemTaggingResultType;

/**
 * Cache configuration and utility service for AI API result caching.
 *
 * <p>
 * This service provides SHA-256 content hashing for cache keys and typed access to Caffeine caches. Caching reduces AI
 * API costs by reusing results for identical content per P2/P10 budget policy (Feature I4.T6).
 *
 * <p>
 * <b>Cache Configuration (application.yaml):</b>
 * <ul>
 * <li><b>ai-tagging-cache</b>: TTL=30 days, max size=10,000 entries (content hash → tagging result)</li>
 * <li><b>ai-embedding-cache</b>: TTL=90 days, max size=5,000 entries (text hash → embedding vector)</li>
 * </ul>
 *
 * <p>
 * <b>Cache Hit Rate Target:</b> &gt;50% for repeated content (Acceptance Criteria I4.T6)
 *
 * <p>
 * <b>Cache Key Strategy:</b>
 * <ul>
 * <li>Tagging: SHA-256(title + description + content) to capture full article context</li>
 * <li>Embedding: SHA-256(text) since embeddings are deterministic for identical input</li>
 * </ul>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P2/P10 (AI Budget Control): Cache reduces duplicate API calls by &gt;50%</li>
 * </ul>
 *
 * @see villagecompute.homepage.services.AiTaggingService
 * @see villagecompute.homepage.services.SemanticSearchService
 */
@ApplicationScoped
public class AiCacheConfig {

    private static final Logger LOG = Logger.getLogger(AiCacheConfig.class);

    @Inject
    @CacheName("ai-tagging-cache")
    Cache taggingCache;

    @Inject
    @CacheName("ai-embedding-cache")
    Cache embeddingCache;

    /**
     * Generates SHA-256 hash of content for cache key.
     *
     * <p>
     * Uses UTF-8 encoding and returns lowercase hex string. Hash collisions are astronomically unlikely for content
     * deduplication.
     *
     * @param content
     *            the content to hash
     * @return 64-character lowercase hex string
     * @throws AiCacheException
     *             if SHA-256 algorithm is unavailable (should never happen on modern JVMs)
     */
    public String generateContentHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new AiCacheException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Converts byte array to lowercase hex string.
     *
     * @param hash
     *            the byte array
     * @return lowercase hex string
     */
    private String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    /**
     * Retrieves tagging result from cache by content hash.
     *
     * @param contentHash
     *            SHA-256 hash of article content
     * @return cached tagging result, or null if cache miss
     */
    @SuppressWarnings("unchecked")
    public FeedItemTaggingResultType getTaggingResult(String contentHash) {
        Object cached = taggingCache.get(contentHash, k -> null).await().indefinitely();
        if (cached == null) {
            return null;
        }
        return (FeedItemTaggingResultType) cached;
    }

    /**
     * Stores tagging result in cache.
     *
     * @param contentHash
     *            SHA-256 hash of article content
     * @param result
     *            tagging result to cache
     */
    public void putTaggingResult(String contentHash, FeedItemTaggingResultType result) {
        taggingCache.get(contentHash, k -> result).await().indefinitely();
        LOG.debugf("Cached tagging result: hash=%s", contentHash);
    }

    /**
     * Retrieves embedding vector from cache by text hash.
     *
     * @param textHash
     *            SHA-256 hash of text
     * @return cached embedding vector, or null if cache miss
     */
    @SuppressWarnings("unchecked")
    public float[] getEmbedding(String textHash) {
        Object cached = embeddingCache.get(textHash, k -> null).await().indefinitely();
        if (cached == null) {
            return null;
        }
        return (float[]) cached;
    }

    /**
     * Stores embedding vector in cache.
     *
     * @param textHash
     *            SHA-256 hash of text
     * @param embedding
     *            embedding vector to cache
     */
    public void putEmbedding(String textHash, float[] embedding) {
        embeddingCache.get(textHash, k -> embedding).await().indefinitely();
        LOG.debugf("Cached embedding: hash=%s, dimensions=%d", textHash, embedding.length);
    }

    /**
     * Exception thrown when cache operations fail.
     */
    public static class AiCacheException extends RuntimeException {

        public AiCacheException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
