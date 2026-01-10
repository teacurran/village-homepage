/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.api.rest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jboss.logging.Logger;

import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import villagecompute.homepage.api.types.ListingSearchResultType;
import villagecompute.homepage.api.types.SearchCriteria;
import villagecompute.homepage.api.types.SearchResultsType;
import villagecompute.homepage.services.MarketplaceSearchService;

/**
 * Public REST endpoint for marketplace listing search (Feature F12.2).
 *
 * <p>
 * Provides combined Elasticsearch full-text search and PostGIS radius filtering for marketplace listings. This endpoint
 * is publicly accessible (no authentication required) to enable anonymous users to browse listings.
 *
 * <p>
 * <b>Endpoint:</b> {@code GET /api/marketplace/search}
 *
 * <p>
 * <b>Query Parameters:</b>
 * <ul>
 * <li>{@code q} - Full-text search query (searches title and description fields), optional</li>
 * <li>{@code category} - Filter by marketplace category UUID, optional</li>
 * <li>{@code min_price} - Minimum price in USD (inclusive), optional</li>
 * <li>{@code max_price} - Maximum price in USD (inclusive), optional</li>
 * <li>{@code location} - Center location for radius search (geo_cities.id), required if radius specified</li>
 * <li>{@code radius} - Radius in miles (5, 10, 25, 50, 100, 250, or omit for "Any"), optional</li>
 * <li>{@code has_images} - Filter to listings with images (future I4.T6), optional</li>
 * <li>{@code min_date} - Filter to listings created on/after this date (ISO-8601 format), optional</li>
 * <li>{@code max_date} - Filter to listings created on/before this date (ISO-8601 format), optional</li>
 * <li>{@code sort} - Sorting option: "newest" (default), "price_asc", "price_desc", "distance", optional</li>
 * <li>{@code offset} - Pagination offset (0-based), default: 0</li>
 * <li>{@code limit} - Results per page (1-100), default: 25</li>
 * </ul>
 *
 * <p>
 * <b>Example Requests:</b>
 *
 * <pre>
 * # Basic text search
 * GET /api/marketplace/search?q=vintage+bicycle
 *
 * # Category filter
 * GET /api/marketplace/search?category=c1&sort=price_asc
 *
 * # Radius search (25 miles from Seattle)
 * GET /api/marketplace/search?q=furniture&location=123&radius=25&sort=distance
 *
 * # Price range + pagination
 * GET /api/marketplace/search?category=c1&min_price=50.00&max_price=500.00&offset=25&limit=25
 *
 * # Date range (listings from last week)
 * GET /api/marketplace/search?min_date=2025-01-03T00:00:00Z&max_date=2025-01-10T23:59:59Z
 * </pre>
 *
 * <p>
 * <b>Response Format:</b>
 *
 * <pre>
 * {
 *   "results": [
 *     {
 *       "id": "550e8400-e29b-41d4-a716-446655440000",
 *       "title": "Vintage Road Bicycle - Excellent Condition",
 *       "description": "Lightly used vintage road bike from the 1980s...",
 *       "price": 350.00,
 *       "categoryId": "c1",
 *       "geoCityId": 456,
 *       "cityName": "Seattle, WA",
 *       "createdAt": "2025-01-05T10:30:00Z",
 *       "imageCount": 5,
 *       "distance": 12.3
 *     },
 *     ...
 *   ],
 *   "totalCount": 47,
 *   "offset": 0,
 *   "limit": 25
 * }
 * </pre>
 *
 * <p>
 * <b>Error Codes:</b>
 * <ul>
 * <li>400 Bad Request – Invalid parameters (e.g., invalid radius value, negative offset, limit > 100)</li>
 * <li>500 Internal Server Error – Search service failure (logged with trace ID)</li>
 * </ul>
 *
 * <p>
 * <b>Rate Limiting:</b> Anonymous users limited to 60 requests/minute per IP address (future rate-limit filter).
 *
 * <p>
 * <b>Performance (Policy P11):</b> Targets <200ms p99 latency for 250-mile radius queries through PostGIS GIST indexes
 * and Elasticsearch optimizations.
 *
 * @see MarketplaceSearchService
 * @see SearchCriteria
 * @see SearchResultsType
 */
@Path("/api/marketplace/search")
@Produces(MediaType.APPLICATION_JSON)
public class MarketplaceSearchResource {

    private static final Logger LOG = Logger.getLogger(MarketplaceSearchResource.class);

    @Inject
    MarketplaceSearchService searchService;

    /**
     * Searches marketplace listings with filters and pagination.
     *
     * <p>
     * Combines Elasticsearch full-text search with PostGIS radius filtering for optimal performance and accuracy.
     *
     * @param query
     *            Full-text search query (searches title + description), optional
     * @param categoryId
     *            Filter by marketplace category UUID, optional
     * @param minPrice
     *            Minimum price in USD (inclusive), optional
     * @param maxPrice
     *            Maximum price in USD (inclusive), optional
     * @param geoCityId
     *            Center location for radius search (geo_cities.id), required if radius specified
     * @param radiusMiles
     *            Radius in miles (5, 10, 25, 50, 100, 250, or null for "Any"), optional
     * @param hasImages
     *            Filter to listings with at least one image (future I4.T6), optional
     * @param minDate
     *            Filter to listings created on/after this date (ISO-8601 format), optional
     * @param maxDate
     *            Filter to listings created on/before this date (ISO-8601 format), optional
     * @param sortBy
     *            Sorting option: "newest" (default), "price_asc", "price_desc", "distance"
     * @param offset
     *            Pagination offset (0-based), default: 0
     * @param limit
     *            Results per page (1-100), default: 25
     * @return Search results with pagination metadata
     */
    @GET
    public Response search(@QueryParam("q") String query, @QueryParam("category") UUID categoryId,
            @QueryParam("min_price") BigDecimal minPrice, @QueryParam("max_price") BigDecimal maxPrice,
            @QueryParam("location") Long geoCityId, @QueryParam("radius") Integer radiusMiles,
            @QueryParam("has_images") Boolean hasImages, @QueryParam("min_date") Instant minDate,
            @QueryParam("max_date") Instant maxDate, @QueryParam("sort") @DefaultValue("newest") String sortBy,
            @QueryParam("offset") @DefaultValue("0") int offset, @QueryParam("limit") @DefaultValue("25") int limit) {

        // Validate limit (max 100)
        if (limit > 100) {
            LOG.warnf("Limit %d exceeds maximum (100), capping to 100", limit);
            limit = 100;
        }

        // Build search criteria
        SearchCriteria criteria;
        try {
            criteria = new SearchCriteria(query, categoryId, minPrice, maxPrice, geoCityId, radiusMiles, hasImages,
                    minDate, maxDate, sortBy, offset, limit);
        } catch (IllegalArgumentException e) {
            LOG.warnf(e, "Invalid search parameters: %s", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        }

        // Log search request
        LOG.infof("Search request: query=%s, category=%s, location=%s, radius=%s, offset=%d, limit=%d", query,
                categoryId, geoCityId, radiusMiles, offset, limit);

        // Execute search
        try {
            List<ListingSearchResultType> results = searchService.searchListings(criteria);
            long totalCount = searchService.countListings(criteria);

            SearchResultsType response = new SearchResultsType(results, totalCount, offset, limit);

            LOG.infof("Search returned %d results (total: %d)", results.size(), totalCount);
            return Response.ok(response).build();
        } catch (Exception e) {
            LOG.errorf(e, "Search service error: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Search service temporarily unavailable")).build();
        }
    }

    /**
     * Helper to create error response map.
     */
    private static class Map {
        static java.util.Map<String, String> of(String key, String value) {
            return java.util.Map.of(key, value);
        }
    }
}
