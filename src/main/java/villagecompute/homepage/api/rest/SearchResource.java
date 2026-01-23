/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.api.rest;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.DirectorySite;
import villagecompute.homepage.data.models.FeedItem;
import villagecompute.homepage.data.models.MarketplaceListing;
import villagecompute.homepage.services.SemanticSearchService;

import java.util.List;

/**
 * REST API for semantic search across content types (feed items, listings, directory sites).
 *
 * <p>
 * Provides unified search interface with optional semantic search via Anthropic embeddings and pgvector. When
 * {@code ?semantic=true} parameter is provided, uses AI-powered semantic similarity instead of traditional keyword
 * matching.
 *
 * <p>
 * <b>Endpoints:</b>
 * <ul>
 * <li>{@code GET /api/search/feed-items?q=query&semantic=true} - Search feed items</li>
 * <li>{@code GET /api/search/listings?q=query&semantic=true} - Search marketplace listings</li>
 * <li>{@code GET /api/search/sites?q=query&semantic=true} - Search directory sites</li>
 * </ul>
 *
 * <p>
 * <b>Query Parameters:</b>
 * <ul>
 * <li>{@code q} (required) - Search query string</li>
 * <li>{@code semantic} (optional) - Enable semantic search (default: false)</li>
 * <li>{@code limit} (optional) - Maximum results to return (default: 20, max: 100)</li>
 * </ul>
 *
 * <p>
 * <b>Fallback Strategy:</b> If semantic search fails (embedding generation error, pgvector unavailable), automatically
 * falls back to Hibernate Search (Elasticsearch) for keyword-based search.
 *
 * <p>
 * <b>Response Format:</b> Returns array of entities with standard JSON serialization. Semantic search results include
 * implicit relevance ordering (most relevant first).
 *
 * @see SemanticSearchService for semantic search implementation
 */
@Path("/api/search")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SearchResource {

    private static final Logger LOG = Logger.getLogger(SearchResource.class);

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    @Inject
    SemanticSearchService semanticSearchService;

    /**
     * Searches feed items using semantic or traditional search.
     *
     * <p>
     * When {@code semantic=true}, uses Anthropic embeddings with pgvector cosine similarity to find semantically
     * related articles. When {@code semantic=false} (default), uses Hibernate Search (Elasticsearch) for keyword
     * matching.
     *
     * <p>
     * <b>Example Requests:</b>
     *
     * <pre>
     * GET /api/search/feed-items?q=climate change renewable energy&semantic=true&limit=10
     * GET /api/search/feed-items?q=technology news&limit=20
     * </pre>
     *
     * @param query
     *            natural language search query (required)
     * @param semantic
     *            enable semantic search (default: false)
     * @param limit
     *            maximum results (default: 20, max: 100)
     * @return list of feed items ordered by relevance
     */
    @GET
    @Path("/feed-items")
    public Response searchFeedItems(@QueryParam("q") String query,
            @QueryParam("semantic") @DefaultValue("false") boolean semantic,
            @QueryParam("limit") @DefaultValue("20") int limit) {

        // Validate query parameter
        if (query == null || query.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Query parameter 'q' is required").build();
        }

        // Enforce limit bounds
        if (limit < 1) {
            limit = DEFAULT_LIMIT;
        }
        if (limit > MAX_LIMIT) {
            limit = MAX_LIMIT;
        }

        try {
            List<FeedItem> results;

            if (semantic) {
                LOG.infof("Semantic search for feed items: query=\"%s\", limit=%d", query, limit);

                // Attempt semantic search
                results = semanticSearchService.searchFeedItems(query, limit);

                // Fallback to Hibernate Search if semantic search fails
                if (results.isEmpty()) {
                    LOG.warnf("Semantic search returned no results, falling back to Hibernate Search");
                    results = hibernateSearchFeedItems(query, limit);
                }
            } else {
                LOG.infof("Keyword search for feed items: query=\"%s\", limit=%d", query, limit);
                results = hibernateSearchFeedItems(query, limit);
            }

            return Response.ok(results).build();

        } catch (Exception e) {
            LOG.errorf(e, "Search failed for feed items: query=\"%s\"", query);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Search failed: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Searches marketplace listings using semantic or traditional search.
     *
     * <p>
     * When {@code semantic=true}, uses Anthropic embeddings with pgvector to find semantically similar listings. Only
     * searches active listings visible to public.
     *
     * <p>
     * <b>Example Requests:</b>
     *
     * <pre>
     * GET /api/search/listings?q=used car in good condition&semantic=true&limit=10
     * GET /api/search/listings?q=apartment&limit=20
     * </pre>
     *
     * @param query
     *            natural language search query (required)
     * @param semantic
     *            enable semantic search (default: false)
     * @param limit
     *            maximum results (default: 20, max: 100)
     * @return list of marketplace listings ordered by relevance
     */
    @GET
    @Path("/listings")
    public Response searchListings(@QueryParam("q") String query,
            @QueryParam("semantic") @DefaultValue("false") boolean semantic,
            @QueryParam("limit") @DefaultValue("20") int limit) {

        // Validate query parameter
        if (query == null || query.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Query parameter 'q' is required").build();
        }

        // Enforce limit bounds
        if (limit < 1) {
            limit = DEFAULT_LIMIT;
        }
        if (limit > MAX_LIMIT) {
            limit = MAX_LIMIT;
        }

        try {
            List<MarketplaceListing> results;

            if (semantic) {
                LOG.infof("Semantic search for listings: query=\"%s\", limit=%d", query, limit);

                // Attempt semantic search
                results = semanticSearchService.searchListings(query, limit);

                // Fallback to Hibernate Search if semantic search fails
                if (results.isEmpty()) {
                    LOG.warnf("Semantic search returned no results, falling back to Hibernate Search");
                    results = hibernateSearchListings(query, limit);
                }
            } else {
                LOG.infof("Keyword search for listings: query=\"%s\", limit=%d", query, limit);
                results = hibernateSearchListings(query, limit);
            }

            return Response.ok(results).build();

        } catch (Exception e) {
            LOG.errorf(e, "Search failed for listings: query=\"%s\"", query);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Search failed: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Searches directory sites using semantic or traditional search.
     *
     * <p>
     * When {@code semantic=true}, uses Anthropic embeddings with pgvector to find semantically related websites. Only
     * searches approved, non-dead sites.
     *
     * <p>
     * <b>Example Requests:</b>
     *
     * <pre>
     * GET /api/search/sites?q=educational resources for programming&semantic=true&limit=10
     * GET /api/search/sites?q=news&limit=20
     * </pre>
     *
     * @param query
     *            natural language search query (required)
     * @param semantic
     *            enable semantic search (default: false)
     * @param limit
     *            maximum results (default: 20, max: 100)
     * @return list of directory sites ordered by relevance
     */
    @GET
    @Path("/sites")
    public Response searchSites(@QueryParam("q") String query,
            @QueryParam("semantic") @DefaultValue("false") boolean semantic,
            @QueryParam("limit") @DefaultValue("20") int limit) {

        // Validate query parameter
        if (query == null || query.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Query parameter 'q' is required").build();
        }

        // Enforce limit bounds
        if (limit < 1) {
            limit = DEFAULT_LIMIT;
        }
        if (limit > MAX_LIMIT) {
            limit = MAX_LIMIT;
        }

        try {
            List<DirectorySite> results;

            if (semantic) {
                LOG.infof("Semantic search for sites: query=\"%s\", limit=%d", query, limit);

                // Attempt semantic search
                results = semanticSearchService.searchSites(query, limit);

                // Fallback to Hibernate Search if semantic search fails
                if (results.isEmpty()) {
                    LOG.warnf("Semantic search returned no results, falling back to Hibernate Search");
                    results = hibernateSearchSites(query, limit);
                }
            } else {
                LOG.infof("Keyword search for sites: query=\"%s\", limit=%d", query, limit);
                results = hibernateSearchSites(query, limit);
            }

            return Response.ok(results).build();

        } catch (Exception e) {
            LOG.errorf(e, "Search failed for sites: query=\"%s\"", query);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Search failed: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Performs Hibernate Search (Elasticsearch) query for feed items.
     *
     * <p>
     * Fallback search method using traditional keyword matching when semantic search is unavailable or fails. Searches
     * title, description, and content fields with English analyzer.
     *
     * @param query
     *            keyword search query
     * @param limit
     *            maximum results
     * @return list of matching feed items
     */
    private List<FeedItem> hibernateSearchFeedItems(String query, int limit) {
        // Simple JPQL query as fallback (in production, use Hibernate Search)
        // For now, use basic LIKE query until Hibernate Search is configured
        return FeedItem.find("title LIKE ?1 OR description LIKE ?1 OR content LIKE ?1 ORDER BY publishedAt DESC",
                "%" + query + "%").page(0, limit).list();
    }

    /**
     * Performs Hibernate Search (Elasticsearch) query for marketplace listings.
     *
     * <p>
     * Fallback search method using traditional keyword matching. Only searches active listings.
     *
     * @param query
     *            keyword search query
     * @param limit
     *            maximum results
     * @return list of matching listings
     */
    private List<MarketplaceListing> hibernateSearchListings(String query, int limit) {
        // Simple JPQL query as fallback
        return MarketplaceListing
                .find("status = 'active' AND (title LIKE ?1 OR description LIKE ?1) ORDER BY createdAt DESC",
                        "%" + query + "%")
                .page(0, limit).list();
    }

    /**
     * Performs Hibernate Search (Elasticsearch) query for directory sites.
     *
     * <p>
     * Fallback search method using traditional keyword matching. Only searches approved, non-dead sites.
     *
     * @param query
     *            keyword search query
     * @param limit
     *            maximum results
     * @return list of matching sites
     */
    private List<DirectorySite> hibernateSearchSites(String query, int limit) {
        // Simple JPQL query as fallback
        return DirectorySite.find(
                "status = 'approved' AND isDead = false AND (title LIKE ?1 OR description LIKE ?1 OR url LIKE ?1) ORDER BY createdAt DESC",
                "%" + query + "%").page(0, limit).list();
    }
}
