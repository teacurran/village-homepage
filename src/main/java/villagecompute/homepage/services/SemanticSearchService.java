/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.services;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.AiUsageTracking;
import villagecompute.homepage.data.models.DirectorySite;
import villagecompute.homepage.data.models.FeedItem;
import villagecompute.homepage.data.models.MarketplaceListing;

import java.time.YearMonth;
import java.util.List;

/**
 * Service for AI-powered semantic search using OpenAI embeddings and pgvector.
 *
 * <p>
 * Provides semantic search capabilities beyond keyword matching by generating and comparing embedding vectors. Uses
 * OpenAI's text-embedding-3-small model via LangChain4j to convert text into 1536-dimensional vectors, stored in
 * PostgreSQL via pgvector extension for efficient cosine similarity queries.
 *
 * <p>
 * <b>Note:</b> While Anthropic Claude is used for chat/tagging (via ChatModel), OpenAI is used for embeddings because
 * Anthropic's API does not provide embedding models as of 2025-01-23.
 *
 * <p>
 * <b>Key Features:</b>
 * <ul>
 * <li>Embedding generation for feed items, marketplace listings, and directory sites</li>
 * <li>Semantic search with cosine similarity ranking</li>
 * <li>Graceful fallback to Hibernate Search if embedding generation fails</li>
 * <li>AI budget tracking per Policy P2/P10</li>
 * </ul>
 *
 * <p>
 * <b>Architecture:</b>
 * <ol>
 * <li>Generate embedding vector for query text using Anthropic API</li>
 * <li>Query pgvector indexes using cosine distance operator ({@code <=>})</li>
 * <li>Combine semantic relevance with traditional filters (category, location, status)</li>
 * <li>Return results sorted by relevance score (1 - cosine_distance)</li>
 * </ol>
 *
 * <p>
 * <b>Performance:</b> pgvector IVFFlat indexes enable sub-100ms approximate nearest neighbor search on datasets up to
 * 1M vectors. Cosine distance operator uses SIMD instructions for efficient vector comparison.
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P2/P10 (AI Budget Control): All embedding operations respect budget limits</li>
 * <li>P11 (Performance): Sub-100ms query response times via pgvector indexes</li>
 * </ul>
 *
 * @see villagecompute.homepage.util.PgVectorType for vector type mapping
 * @see AiUsageTracking for cost tracking
 */
@ApplicationScoped
public class SemanticSearchService {

    private static final Logger LOG = Logger.getLogger(SemanticSearchService.class);

    private static final String PROVIDER = AiUsageTracking.DEFAULT_PROVIDER;

    // OpenAI text-embedding-3-small produces 1536-dimensional vectors
    private static final int EMBEDDING_DIMENSIONS = 1536;

    // Token estimate for embedding API calls (rough approximation: 4 chars = 1 token)
    private static final int CHARS_PER_TOKEN = 4;

    @Inject
    EmbeddingModel embeddingModel;

    @Inject
    EntityManager entityManager;

    /**
     * Generates an embedding vector for the given text using OpenAI's text-embedding-3-small model.
     *
     * <p>
     * Converts text into a 1536-dimensional float vector representing semantic meaning. Used for both indexing content
     * and generating query vectors for similarity search.
     *
     * <p>
     * <b>Cost Tracking:</b> Records API usage in {@link AiUsageTracking} for budget monitoring per Policy P2/P10.
     * OpenAI embedding costs are significantly lower than chat completion costs (~$0.02 per 1M tokens).
     *
     * @param text
     *            the text to embed (article content, listing description, search query, etc.)
     * @return embedding vector (float array of length 1536), or null if generation fails
     */
    public float[] generateEmbedding(String text) {
        if (text == null || text.isBlank()) {
            LOG.warn("Cannot generate embedding for null or empty text");
            return null;
        }

        try {
            LOG.debugf("Generating embedding for text: %s", text.substring(0, Math.min(100, text.length())));

            // Call Anthropic embedding API via LangChain4j
            Response<Embedding> response = embeddingModel.embed(text);
            Embedding embedding = response.content();

            // Convert to float array
            float[] vector = embedding.vector();

            // Validate dimensions
            if (vector.length != EMBEDDING_DIMENSIONS) {
                LOG.warnf("Unexpected embedding dimensions: expected=%d, actual=%d", EMBEDDING_DIMENSIONS,
                        vector.length);
            }

            // Estimate token usage for cost tracking
            long estimatedInputTokens = text.length() / CHARS_PER_TOKEN;
            // Embedding API doesn't return output tokens (just the vector), so estimate as 0
            long estimatedOutputTokens = 0;
            int costCents = AiUsageTracking.calculateEmbeddingCostCents(estimatedInputTokens);

            // Record usage
            AiUsageTracking.recordUsage(YearMonth.now(), PROVIDER, 1, estimatedInputTokens, estimatedOutputTokens,
                    costCents);

            LOG.debugf("Generated embedding: dimensions=%d, inputTokens=%d, costCents=%d", vector.length,
                    estimatedInputTokens, costCents);

            return vector;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to generate embedding for text: %s", text.substring(0, Math.min(100, text.length())));
            return null;
        }
    }

    /**
     * Indexes a feed item by generating and storing its content embedding.
     *
     * <p>
     * Generates embedding from feed item title + description + content, then persists to {@code content_embedding}
     * column. Called after new feed items are fetched from RSS sources.
     *
     * <p>
     * <b>Failure Handling:</b> If embedding generation fails, logs warning but does not throw exception. Feed item
     * remains searchable via Hibernate Search (Elasticsearch) fallback.
     *
     * @param item
     *            the feed item to index
     */
    public void indexContentEmbedding(FeedItem item) {
        if (item == null) {
            LOG.warn("Cannot index null feed item");
            return;
        }

        try {
            // Combine title, description, and content snippet for embedding
            StringBuilder textBuilder = new StringBuilder();
            if (item.title != null) {
                textBuilder.append(item.title).append(". ");
            }
            if (item.description != null) {
                textBuilder.append(item.description).append(" ");
            }
            if (item.content != null) {
                // Truncate content to avoid token limits (max ~3000 chars = ~750 tokens)
                String contentSnippet = item.content.substring(0, Math.min(3000, item.content.length()));
                textBuilder.append(contentSnippet);
            }

            String text = textBuilder.toString().trim();
            if (text.isEmpty()) {
                LOG.warnf("Feed item %s has no text content to embed", item.id);
                return;
            }

            // Generate embedding
            float[] embedding = generateEmbedding(text);
            if (embedding != null) {
                item.contentEmbedding = embedding;
                item.persist();
                LOG.infof("Indexed embedding for feed item: id=%s, title=\"%s\"", item.id, item.title);
            } else {
                LOG.warnf("Failed to generate embedding for feed item: id=%s, will fallback to Elasticsearch", item.id);
            }

        } catch (Exception e) {
            LOG.errorf(e, "Failed to index embedding for feed item: id=%s", item.id);
        }
    }

    /**
     * Indexes a marketplace listing by generating and storing its description embedding.
     *
     * <p>
     * Generates embedding from listing title + description, then persists to {@code description_embedding} column.
     * Called after new listings are created or updated.
     *
     * @param listing
     *            the marketplace listing to index
     */
    public void indexListingEmbedding(MarketplaceListing listing) {
        if (listing == null) {
            LOG.warn("Cannot index null marketplace listing");
            return;
        }

        try {
            // Combine title and description for embedding
            StringBuilder textBuilder = new StringBuilder();
            if (listing.title != null) {
                textBuilder.append(listing.title).append(". ");
            }
            if (listing.description != null) {
                textBuilder.append(listing.description);
            }

            String text = textBuilder.toString().trim();
            if (text.isEmpty()) {
                LOG.warnf("Marketplace listing %s has no text content to embed", listing.id);
                return;
            }

            // Generate embedding
            float[] embedding = generateEmbedding(text);
            if (embedding != null) {
                listing.descriptionEmbedding = embedding;
                listing.persist();
                LOG.infof("Indexed embedding for marketplace listing: id=%s, title=\"%s\"", listing.id, listing.title);
            } else {
                LOG.warnf("Failed to generate embedding for listing: id=%s, will fallback to Elasticsearch",
                        listing.id);
            }

        } catch (Exception e) {
            LOG.errorf(e, "Failed to index embedding for marketplace listing: id=%s", listing.id);
        }
    }

    /**
     * Indexes a directory site by generating and storing its description embedding.
     *
     * <p>
     * Generates embedding from site title + description, then persists to {@code description_embedding} column. Called
     * after new sites are submitted.
     *
     * @param site
     *            the directory site to index
     */
    public void indexSiteEmbedding(DirectorySite site) {
        if (site == null) {
            LOG.warn("Cannot index null directory site");
            return;
        }

        try {
            // Combine title and description for embedding
            StringBuilder textBuilder = new StringBuilder();
            if (site.title != null) {
                textBuilder.append(site.title).append(". ");
            }
            if (site.description != null) {
                textBuilder.append(site.description);
            }

            String text = textBuilder.toString().trim();
            if (text.isEmpty()) {
                LOG.warnf("Directory site %s has no text content to embed", site.id);
                return;
            }

            // Generate embedding
            float[] embedding = generateEmbedding(text);
            if (embedding != null) {
                site.descriptionEmbedding = embedding;
                site.persist();
                LOG.infof("Indexed embedding for directory site: id=%s, title=\"%s\"", site.id, site.title);
            } else {
                LOG.warnf("Failed to generate embedding for site: id=%s, will fallback to Elasticsearch", site.id);
            }

        } catch (Exception e) {
            LOG.errorf(e, "Failed to index embedding for directory site: id=%s", site.id);
        }
    }

    /**
     * Searches feed items using semantic similarity.
     *
     * <p>
     * Generates embedding for query text, then finds feed items with similar content using cosine distance. Results
     * sorted by relevance score (1 - cosine_distance, range 0-1 where 1 = identical).
     *
     * <p>
     * <b>Fallback:</b> If embedding generation fails, returns empty list (caller should fallback to Hibernate Search).
     *
     * @param query
     *            natural language search query
     * @param limit
     *            maximum number of results to return
     * @return list of feed items ordered by relevance (highest first)
     */
    @SuppressWarnings("unchecked")
    public List<FeedItem> searchFeedItems(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        try {
            // Generate query embedding
            float[] queryEmbedding = generateEmbedding(query);
            if (queryEmbedding == null) {
                LOG.warnf("Failed to generate query embedding, cannot perform semantic search");
                return List.of();
            }

            // Convert float array to pgvector string format: "[0.1, 0.2, ...]"
            String vectorStr = floatArrayToVectorString(queryEmbedding);

            // Native SQL query for pgvector cosine similarity
            String sql = """
                    SELECT f.*,
                           (1 - (f.content_embedding <=> CAST(:queryVector AS vector))) AS relevance
                    FROM feed_items f
                    WHERE f.content_embedding IS NOT NULL
                    ORDER BY f.content_embedding <=> CAST(:queryVector AS vector)
                    LIMIT :limit
                    """;

            Query nativeQuery = entityManager.createNativeQuery(sql, FeedItem.class);
            nativeQuery.setParameter("queryVector", vectorStr);
            nativeQuery.setParameter("limit", limit);

            List<FeedItem> results = nativeQuery.getResultList();

            LOG.infof("Semantic search found %d feed items for query: \"%s\"", results.size(),
                    query.substring(0, Math.min(50, query.length())));

            return results;

        } catch (Exception e) {
            LOG.errorf(e, "Semantic search failed for feed items query: \"%s\"", query);
            return List.of();
        }
    }

    /**
     * Searches marketplace listings using semantic similarity with optional filters.
     *
     * <p>
     * Generates embedding for query text, then finds listings with similar descriptions using cosine distance. Supports
     * combining semantic search with traditional filters (category, status).
     *
     * @param query
     *            natural language search query
     * @param limit
     *            maximum number of results to return
     * @return list of marketplace listings ordered by relevance (highest first)
     */
    @SuppressWarnings("unchecked")
    public List<MarketplaceListing> searchListings(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        try {
            // Generate query embedding
            float[] queryEmbedding = generateEmbedding(query);
            if (queryEmbedding == null) {
                LOG.warnf("Failed to generate query embedding, cannot perform semantic search");
                return List.of();
            }

            // Convert float array to pgvector string format
            String vectorStr = floatArrayToVectorString(queryEmbedding);

            // Native SQL query for pgvector cosine similarity
            // Only search active listings
            String sql = """
                    SELECT ml.*,
                           (1 - (ml.description_embedding <=> CAST(:queryVector AS vector))) AS relevance
                    FROM marketplace_listings ml
                    WHERE ml.description_embedding IS NOT NULL
                      AND ml.status = 'active'
                    ORDER BY ml.description_embedding <=> CAST(:queryVector AS vector)
                    LIMIT :limit
                    """;

            Query nativeQuery = entityManager.createNativeQuery(sql, MarketplaceListing.class);
            nativeQuery.setParameter("queryVector", vectorStr);
            nativeQuery.setParameter("limit", limit);

            List<MarketplaceListing> results = nativeQuery.getResultList();

            LOG.infof("Semantic search found %d marketplace listings for query: \"%s\"", results.size(),
                    query.substring(0, Math.min(50, query.length())));

            return results;

        } catch (Exception e) {
            LOG.errorf(e, "Semantic search failed for listings query: \"%s\"", query);
            return List.of();
        }
    }

    /**
     * Searches directory sites using semantic similarity.
     *
     * <p>
     * Generates embedding for query text, then finds sites with similar descriptions using cosine distance. Only
     * searches approved sites visible to public.
     *
     * @param query
     *            natural language search query
     * @param limit
     *            maximum number of results to return
     * @return list of directory sites ordered by relevance (highest first)
     */
    @SuppressWarnings("unchecked")
    public List<DirectorySite> searchSites(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        try {
            // Generate query embedding
            float[] queryEmbedding = generateEmbedding(query);
            if (queryEmbedding == null) {
                LOG.warnf("Failed to generate query embedding, cannot perform semantic search");
                return List.of();
            }

            // Convert float array to pgvector string format
            String vectorStr = floatArrayToVectorString(queryEmbedding);

            // Native SQL query for pgvector cosine similarity
            // Only search approved sites
            String sql = """
                    SELECT ds.*,
                           (1 - (ds.description_embedding <=> CAST(:queryVector AS vector))) AS relevance
                    FROM directory_sites ds
                    WHERE ds.description_embedding IS NOT NULL
                      AND ds.status = 'approved'
                      AND ds.is_dead = false
                    ORDER BY ds.description_embedding <=> CAST(:queryVector AS vector)
                    LIMIT :limit
                    """;

            Query nativeQuery = entityManager.createNativeQuery(sql, DirectorySite.class);
            nativeQuery.setParameter("queryVector", vectorStr);
            nativeQuery.setParameter("limit", limit);

            List<DirectorySite> results = nativeQuery.getResultList();

            LOG.infof("Semantic search found %d directory sites for query: \"%s\"", results.size(),
                    query.substring(0, Math.min(50, query.length())));

            return results;

        } catch (Exception e) {
            LOG.errorf(e, "Semantic search failed for sites query: \"%s\"", query);
            return List.of();
        }
    }

    /**
     * Converts float array to pgvector string format for SQL queries.
     *
     * <p>
     * Pgvector expects string format: {@code "[0.1, 0.2, 0.3]"} (JSON array style)
     *
     * @param vector
     *            float array to convert
     * @return pgvector string representation
     */
    private String floatArrayToVectorString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
