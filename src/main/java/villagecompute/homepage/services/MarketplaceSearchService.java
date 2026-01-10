/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.hibernate.search.engine.search.query.SearchResult;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.orm.session.SearchSession;
import org.jboss.logging.Logger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Parameters;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import jakarta.transaction.Transactional;
import villagecompute.homepage.api.types.ListingSearchResultType;
import villagecompute.homepage.api.types.SearchCriteria;
import villagecompute.homepage.data.models.MarketplaceListing;

/**
 * Service for marketplace listing search combining Elasticsearch full-text indexing and PostGIS radius queries.
 *
 * <p>
 * This service implements a two-phase search strategy to meet Policy P11 performance targets (<200ms p99 for 250-mile
 * radius queries):
 * <ol>
 * <li><b>Phase 1 (Elasticsearch):</b> Full-text search on title/description with category, price, and date filters</li>
 * <li><b>Phase 2 (PostGIS):</b> Geographic radius filtering using ST_DWithin() on GIST spatial index</li>
 * </ol>
 *
 * <h2>Search Architecture</h2>
 *
 * <p>
 * <b>Elasticsearch Phase:</b>
 * <ul>
 * <li>Searches {@code title} and {@code description} fields with fuzzy matching (2-char typo tolerance)</li>
 * <li>Filters by: {@code categoryId}, {@code price} range, {@code createdAt} date range</li>
 * <li>Sorting: newest (created_at DESC), price_asc, price_desc</li>
 * <li>Returns max 500 results before PostGIS filtering to prevent excessive result sets</li>
 * </ul>
 *
 * <p>
 * <b>PostGIS Phase (if radius specified):</b>
 * <ul>
 * <li>Fetches center location from {@code geo_cities} by {@code geoCityId}</li>
 * <li>Joins {@code marketplace_listings} with {@code geo_cities} on {@code geo_city_id}</li>
 * <li>Filters using {@code ST_DWithin(listing_location, center_location, radius_meters)}</li>
 * <li>Calculates distance for sorting: {@code ST_Distance() / 1609.34 AS distance_miles}</li>
 * <li>Returns listings ordered by distance if {@code sortBy=distance}</li>
 * </ul>
 *
 * <h2>Resilience & Fallback</h2>
 *
 * <p>
 * If Elasticsearch is unavailable, the service degrades gracefully to Postgres-only queries:
 * <ul>
 * <li>Text search uses {@code ILIKE '%query%'} on title/description (slower, but functional)</li>
 * <li>Filters applied via Panache queries with {@code Parameters}</li>
 * <li>PostGIS radius filtering still used for geographic searches</li>
 * <li>Metrics track {@code marketplace.search.elasticsearch.errors.total} counter</li>
 * </ul>
 *
 * <h2>Performance Optimizations</h2>
 *
 * <p>
 * To meet Policy P11 targets (<200ms p99):
 * <ul>
 * <li>Elasticsearch limits results to 500 before PostGIS filtering (configurable)</li>
 * <li>PostGIS uses GIST spatial index on {@code geo_cities.location} (created in I4.T1 migration)</li>
 * <li>Composite index on {@code (status, category_id, created_at)} for ES-skipped queries</li>
 * <li>City name resolution via single JOIN (no N+1 problem)</li>
 * <li>Image count pre-aggregated (future I4.T6 - defaults to 0)</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>
 * {@code
 * @Inject
 * MarketplaceSearchService searchService;
 *
 * SearchCriteria criteria = new SearchCriteria("vintage bicycle", // query
 *         categoryId, // categoryId
 *         new BigDecimal("50.00"), // minPrice
 *         new BigDecimal("500.00"), // maxPrice
 *         123L, // geoCityId (Seattle)
 *         25, // radiusMiles (25-mile radius)
 *         null, // hasImages (future I4.T6)
 *         null, // minDate
 *         null, // maxDate
 *         "distance", // sortBy
 *         0, // offset
 *         25 // limit
 * );
 *
 * List<ListingSearchResultType> results = searchService.searchListings(criteria);
 * long totalCount = searchService.countListings(criteria);
 * }
 * </pre>
 *
 * @see SearchCriteria
 * @see ListingSearchResultType
 * @see MarketplaceListing
 */
@ApplicationScoped
public class MarketplaceSearchService {

    private static final Logger LOG = Logger.getLogger(MarketplaceSearchService.class);

    /** Maximum Elasticsearch results before PostGIS filtering (prevents excessive result sets). */
    private static final int MAX_ES_RESULTS_BEFORE_RADIUS_FILTER = 500;

    /** Conversion factor: miles to meters. */
    private static final double MILES_TO_METERS = 1609.34;

    @Inject
    EntityManager entityManager;

    @Inject
    Tracer tracer;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Searches marketplace listings using combined Elasticsearch + PostGIS queries.
     *
     * <p>
     * Two-phase approach:
     * <ol>
     * <li>Elasticsearch: text search + filters (category, price, date)</li>
     * <li>PostGIS: radius filtering if geoCityId + radiusMiles specified</li>
     * </ol>
     *
     * <p>
     * Falls back to Postgres-only queries if Elasticsearch is unavailable.
     *
     * @param criteria
     *            Search parameters (query, filters, pagination)
     * @return List of search results with resolved city names and distances
     */
    @Transactional
    public List<ListingSearchResultType> searchListings(SearchCriteria criteria) {
        Span span = tracer.spanBuilder("marketplace.search").startSpan();
        Timer.Sample timer = Timer.start(meterRegistry);

        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("query", criteria.query() != null ? criteria.query() : "");
            span.setAttribute("has_radius", criteria.isGeographicSearch());
            span.setAttribute("category_id", criteria.categoryId() != null ? criteria.categoryId().toString() : "");

            // Increment request counter
            getRequestCounter(criteria).increment();

            List<ListingSearchResultType> results;

            try {
                // Phase 1: Elasticsearch query
                results = searchWithElasticsearch(criteria);
            } catch (Exception e) {
                LOG.warnf(e, "Elasticsearch search failed, falling back to Postgres");
                getElasticsearchErrorCounter().increment();
                span.setAttribute("fallback", "postgres");

                // Fallback: Postgres-only query
                results = searchWithPostgres(criteria);
            }

            // Record result count
            meterRegistry.summary("marketplace.search.results.count").record(results.size());

            return results;
        } finally {
            timer.stop(meterRegistry.timer("marketplace.search.duration"));
            span.end();
        }
    }

    /**
     * Counts total matching listings (for pagination metadata).
     *
     * <p>
     * Uses same filter logic as {@link #searchListings(SearchCriteria)} but returns count only (no sorting/offset).
     *
     * @param criteria
     *            Search parameters
     * @return Total count of matching listings across all pages
     */
    @Transactional
    public long countListings(SearchCriteria criteria) {
        try {
            return countWithElasticsearch(criteria);
        } catch (Exception e) {
            LOG.warnf(e, "Elasticsearch count failed, falling back to Postgres");
            return countWithPostgres(criteria);
        }
    }

    /**
     * Searches listings using Elasticsearch full-text index.
     *
     * <p>
     * Applies text search, category filter, price range, date range. If geographic search, limits results to
     * MAX_ES_RESULTS_BEFORE_RADIUS_FILTER before applying PostGIS radius filter.
     *
     * @param criteria
     *            Search parameters
     * @return List of search results
     */
    private List<ListingSearchResultType> searchWithElasticsearch(SearchCriteria criteria) {
        SearchSession searchSession = Search.session(entityManager);

        // Determine fetch size
        int fetchLimit = criteria.limit();
        int fetchOffset = criteria.offset();

        // If geographic search, fetch more results before PostGIS filtering
        if (criteria.isGeographicSearch()) {
            fetchLimit = Math.min(MAX_ES_RESULTS_BEFORE_RADIUS_FILTER, criteria.limit() * 10);
            fetchOffset = 0; // Reset offset - will re-apply after PostGIS filtering
        }

        SearchResult<MarketplaceListing> esResults = searchSession.search(MarketplaceListing.class).where(f -> {
            var bool = f.bool();

            // Always filter to active listings only
            bool.must(f.match().field("status").matching("active"));

            // Text search on title + description (if query provided)
            if (criteria.isTextSearch()) {
                bool.must(f.match().fields("title", "description").matching(criteria.query()).fuzzy(2));
            }

            // Category filter
            if (criteria.categoryId() != null) {
                bool.must(f.match().field("categoryId").matching(criteria.categoryId()));
            }

            // Price range filter
            if (criteria.minPrice() != null || criteria.maxPrice() != null) {
                bool.must(f.range().field("price").between(criteria.minPrice(), criteria.maxPrice()));
            }

            // Date range filter
            if (criteria.minDate() != null || criteria.maxDate() != null) {
                bool.must(f.range().field("createdAt").between(criteria.minDate(), criteria.maxDate()));
            }

            return bool;
        }).sort(f -> buildElasticsearchSort(f, criteria)).fetch(fetchOffset, fetchLimit);

        List<MarketplaceListing> listings = esResults.hits();

        // Phase 2: PostGIS radius filtering (if geographic search)
        if (criteria.isGeographicSearch()) {
            listings = filterByRadius(listings, criteria.geoCityId(), criteria.radiusMiles());

            // Re-apply pagination after PostGIS filtering
            int startIndex = criteria.offset();
            int endIndex = Math.min(startIndex + criteria.limit(), listings.size());

            if (startIndex >= listings.size()) {
                listings = List.of();
            } else {
                listings = listings.subList(startIndex, endIndex);
            }
        }

        // Convert to search result DTOs with city names and distances
        return convertToSearchResults(listings, criteria);
    }

    /**
     * Builds Elasticsearch sort order.
     */
    private org.hibernate.search.engine.search.sort.dsl.SortFinalStep buildElasticsearchSort(
            org.hibernate.search.engine.search.sort.dsl.SearchSortFactory f, SearchCriteria criteria) {
        String sortBy = criteria.sortBy();

        // Distance sorting handled by PostGIS, use createdAt for ES phase
        if ("distance".equals(sortBy)) {
            sortBy = "newest";
        }

        return switch (sortBy) {
            case "price_asc" -> f.field("price").asc();
            case "price_desc" -> f.field("price").desc();
            default -> f.field("createdAt").desc(); // newest (default)
        };
    }

    /**
     * Filters listings by geographic radius using PostGIS ST_DWithin().
     *
     * <p>
     * Strategy:
     * <ol>
     * <li>Fetch center location from geo_cities by geoCityId</li>
     * <li>Join marketplace_listings with geo_cities on geo_city_id</li>
     * <li>Filter using ST_DWithin(listing_location, center_location, radius_meters)</li>
     * <li>Calculate distance for sorting</li>
     * <li>Return filtered listings ordered by distance</li>
     * </ol>
     *
     * @param listings
     *            Listings from Elasticsearch phase
     * @param centerCityId
     *            Center location (geo_cities.id)
     * @param radiusMiles
     *            Radius in miles (5, 10, 25, 50, 100, 250)
     * @return Filtered listings within radius, ordered by distance
     */
    private List<MarketplaceListing> filterByRadius(List<MarketplaceListing> listings, Long centerCityId,
            Integer radiusMiles) {
        if (listings.isEmpty()) {
            return List.of();
        }

        // Extract listing IDs for PostGIS query
        List<UUID> listingIds = listings.stream().map(l -> l.id).collect(Collectors.toList());

        double radiusMeters = radiusMiles * MILES_TO_METERS;

        // PostGIS query: filter by radius and calculate distances
        String sql = """
                SELECT l.id,
                       ST_Distance(gc_listing.location, gc_center.location) / 1609.34 as distance_miles
                FROM marketplace_listings l
                JOIN geo_cities gc_listing ON gc_listing.id = l.geo_city_id
                CROSS JOIN geo_cities gc_center
                WHERE gc_center.id = :centerCityId
                  AND l.id IN (:listingIds)
                  AND ST_DWithin(gc_listing.location, gc_center.location, :radiusMeters)
                ORDER BY distance_miles ASC
                """;

        Query query = entityManager.createNativeQuery(sql, Tuple.class);
        query.setParameter("centerCityId", centerCityId);
        query.setParameter("listingIds", listingIds);
        query.setParameter("radiusMeters", radiusMeters);

        @SuppressWarnings("unchecked")
        List<Tuple> results = query.getResultList();

        // Build map of listing ID -> distance
        Map<UUID, Double> distanceMap = new HashMap<>();
        for (Tuple tuple : results) {
            UUID id = (UUID) tuple.get(0);
            Double distance = ((Number) tuple.get(1)).doubleValue();
            distanceMap.put(id, distance);
        }

        // Filter listings to those within radius and sort by distance
        return listings.stream().filter(l -> distanceMap.containsKey(l.id))
                .sorted((a, b) -> Double.compare(distanceMap.get(a.id), distanceMap.get(b.id)))
                .collect(Collectors.toList());
    }

    /**
     * Converts MarketplaceListing entities to search result DTOs with resolved city names and distances.
     *
     * @param listings
     *            Listings to convert
     * @param criteria
     *            Search criteria (for distance calculation)
     * @return List of search result DTOs
     */
    private List<ListingSearchResultType> convertToSearchResults(List<MarketplaceListing> listings,
            SearchCriteria criteria) {
        if (listings.isEmpty()) {
            return List.of();
        }

        // Fetch city names for all listings (single query, no N+1)
        List<Long> cityIds = listings.stream().map(l -> l.geoCityId).filter(id -> id != null).distinct()
                .collect(Collectors.toList());

        Map<Long, String> cityNames = new HashMap<>();
        if (!cityIds.isEmpty()) {
            String sql = """
                    SELECT gc.id,
                           CONCAT(gc.name, ', ', gs.state_code) as city_display
                    FROM geo_cities gc
                    JOIN geo_states gs ON gs.id = gc.state_id
                    WHERE gc.id IN (:cityIds)
                    """;

            Query query = entityManager.createNativeQuery(sql, Tuple.class);
            query.setParameter("cityIds", cityIds);

            @SuppressWarnings("unchecked")
            List<Tuple> results = query.getResultList();

            for (Tuple tuple : results) {
                Long id = ((Number) tuple.get(0)).longValue();
                String cityDisplay = (String) tuple.get(1);
                cityNames.put(id, cityDisplay);
            }
        }

        // Calculate distances if geographic search
        Map<UUID, Double> distanceMap = new HashMap<>();
        if (criteria.isGeographicSearch()) {
            distanceMap = calculateDistances(listings, criteria.geoCityId());
        }

        // Convert to DTOs
        List<ListingSearchResultType> results = new ArrayList<>();
        for (MarketplaceListing listing : listings) {
            String cityName = listing.geoCityId != null ? cityNames.get(listing.geoCityId) : null;
            Double distance = distanceMap.get(listing.id);
            int imageCount = 0; // TODO: implement in I4.T6 after marketplace_listing_images table created

            results.add(ListingSearchResultType.fromListing(listing, cityName, distance, imageCount));
        }

        return results;
    }

    /**
     * Calculates distances from center location for all listings.
     *
     * @param listings
     *            Listings to calculate distances for
     * @param centerCityId
     *            Center location (geo_cities.id)
     * @return Map of listing ID -> distance in miles
     */
    private Map<UUID, Double> calculateDistances(List<MarketplaceListing> listings, Long centerCityId) {
        if (listings.isEmpty()) {
            return Map.of();
        }

        List<UUID> listingIds = listings.stream().map(l -> l.id).collect(Collectors.toList());

        String sql = """
                SELECT l.id,
                       ST_Distance(gc_listing.location, gc_center.location) / 1609.34 as distance_miles
                FROM marketplace_listings l
                JOIN geo_cities gc_listing ON gc_listing.id = l.geo_city_id
                CROSS JOIN geo_cities gc_center
                WHERE gc_center.id = :centerCityId
                  AND l.id IN (:listingIds)
                """;

        Query query = entityManager.createNativeQuery(sql, Tuple.class);
        query.setParameter("centerCityId", centerCityId);
        query.setParameter("listingIds", listingIds);

        @SuppressWarnings("unchecked")
        List<Tuple> results = query.getResultList();

        Map<UUID, Double> distanceMap = new HashMap<>();
        for (Tuple tuple : results) {
            UUID id = (UUID) tuple.get(0);
            Double distance = ((Number) tuple.get(1)).doubleValue();
            distanceMap.put(id, distance);
        }

        return distanceMap;
    }

    /**
     * Fallback: Searches listings using Postgres ILIKE queries (when Elasticsearch is unavailable).
     *
     * @param criteria
     *            Search parameters
     * @return List of search results
     */
    private List<ListingSearchResultType> searchWithPostgres(SearchCriteria criteria) {
        // Build Panache query
        StringBuilder hql = new StringBuilder("status = 'active'");
        Parameters params = Parameters.with("status", "active");

        // Text search with ILIKE (slow but functional)
        if (criteria.isTextSearch()) {
            String searchPattern = "%" + criteria.query().toLowerCase() + "%";
            hql.append(" AND (LOWER(title) LIKE :query OR LOWER(description) LIKE :query)");
            params.and("query", searchPattern);
        }

        // Category filter
        if (criteria.categoryId() != null) {
            hql.append(" AND categoryId = :categoryId");
            params.and("categoryId", criteria.categoryId());
        }

        // Price range filter
        if (criteria.minPrice() != null) {
            hql.append(" AND price >= :minPrice");
            params.and("minPrice", criteria.minPrice());
        }
        if (criteria.maxPrice() != null) {
            hql.append(" AND price <= :maxPrice");
            params.and("maxPrice", criteria.maxPrice());
        }

        // Date range filter
        if (criteria.minDate() != null) {
            hql.append(" AND createdAt >= :minDate");
            params.and("minDate", criteria.minDate());
        }
        if (criteria.maxDate() != null) {
            hql.append(" AND createdAt <= :maxDate");
            params.and("maxDate", criteria.maxDate());
        }

        // Build sort
        Sort sort = buildPostgresSort(criteria);

        // Execute query
        PanacheQuery<MarketplaceListing> query = MarketplaceListing.find(hql.toString(), sort, params);

        // If geographic search, fetch more results before PostGIS filtering
        int fetchLimit = criteria.limit();
        int fetchOffset = criteria.offset();
        if (criteria.isGeographicSearch()) {
            fetchLimit = MAX_ES_RESULTS_BEFORE_RADIUS_FILTER;
            fetchOffset = 0;
        }

        List<MarketplaceListing> listings = query.page(fetchOffset / fetchLimit, fetchLimit).list();

        // Phase 2: PostGIS radius filtering (if geographic search)
        if (criteria.isGeographicSearch()) {
            listings = filterByRadius(listings, criteria.geoCityId(), criteria.radiusMiles());

            // Re-apply pagination
            int startIndex = criteria.offset();
            int endIndex = Math.min(startIndex + criteria.limit(), listings.size());

            if (startIndex >= listings.size()) {
                listings = List.of();
            } else {
                listings = listings.subList(startIndex, endIndex);
            }
        }

        return convertToSearchResults(listings, criteria);
    }

    /**
     * Builds Panache Sort for Postgres fallback queries.
     */
    private Sort buildPostgresSort(SearchCriteria criteria) {
        String sortBy = criteria.sortBy();

        // Distance sorting handled by PostGIS, use createdAt for Postgres phase
        if ("distance".equals(sortBy)) {
            sortBy = "newest";
        }

        return switch (sortBy) {
            case "price_asc" -> Sort.by("price").ascending();
            case "price_desc" -> Sort.by("price").descending();
            default -> Sort.by("createdAt").descending(); // newest (default)
        };
    }

    /**
     * Counts listings using Elasticsearch.
     *
     * @param criteria
     *            Search parameters
     * @return Total count
     */
    private long countWithElasticsearch(SearchCriteria criteria) {
        SearchSession searchSession = Search.session(entityManager);

        return searchSession.search(MarketplaceListing.class).where(f -> {
            var bool = f.bool();

            // Always filter to active listings only
            bool.must(f.match().field("status").matching("active"));

            // Text search on title + description (if query provided)
            if (criteria.isTextSearch()) {
                bool.must(f.match().fields("title", "description").matching(criteria.query()).fuzzy(2));
            }

            // Category filter
            if (criteria.categoryId() != null) {
                bool.must(f.match().field("categoryId").matching(criteria.categoryId()));
            }

            // Price range filter
            if (criteria.minPrice() != null || criteria.maxPrice() != null) {
                bool.must(f.range().field("price").between(criteria.minPrice(), criteria.maxPrice()));
            }

            // Date range filter
            if (criteria.minDate() != null || criteria.maxDate() != null) {
                bool.must(f.range().field("createdAt").between(criteria.minDate(), criteria.maxDate()));
            }

            return bool;
        }).fetchTotalHitCount();
    }

    /**
     * Counts listings using Postgres (fallback).
     *
     * @param criteria
     *            Search parameters
     * @return Total count
     */
    private long countWithPostgres(SearchCriteria criteria) {
        StringBuilder hql = new StringBuilder("status = 'active'");
        Parameters params = Parameters.with("status", "active");

        // Text search
        if (criteria.isTextSearch()) {
            String searchPattern = "%" + criteria.query().toLowerCase() + "%";
            hql.append(" AND (LOWER(title) LIKE :query OR LOWER(description) LIKE :query)");
            params.and("query", searchPattern);
        }

        // Filters (same as searchWithPostgres)
        if (criteria.categoryId() != null) {
            hql.append(" AND categoryId = :categoryId");
            params.and("categoryId", criteria.categoryId());
        }
        if (criteria.minPrice() != null) {
            hql.append(" AND price >= :minPrice");
            params.and("minPrice", criteria.minPrice());
        }
        if (criteria.maxPrice() != null) {
            hql.append(" AND price <= :maxPrice");
            params.and("maxPrice", criteria.maxPrice());
        }
        if (criteria.minDate() != null) {
            hql.append(" AND createdAt >= :minDate");
            params.and("minDate", criteria.minDate());
        }
        if (criteria.maxDate() != null) {
            hql.append(" AND createdAt <= :maxDate");
            params.and("maxDate", criteria.maxDate());
        }

        return MarketplaceListing.count(hql.toString(), params);
    }

    /**
     * Gets request counter with tags for metrics.
     */
    private Counter getRequestCounter(SearchCriteria criteria) {
        return meterRegistry.counter("marketplace.search.requests.total", "has_radius",
                String.valueOf(criteria.isGeographicSearch()), "has_category",
                String.valueOf(criteria.categoryId() != null), "has_filters", String.valueOf(criteria.hasFilters()));
    }

    /**
     * Gets Elasticsearch error counter.
     */
    private Counter getElasticsearchErrorCounter() {
        return meterRegistry.counter("marketplace.search.elasticsearch.errors.total");
    }
}
