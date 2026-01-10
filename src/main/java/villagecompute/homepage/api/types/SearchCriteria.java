package villagecompute.homepage.api.types;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Search criteria for marketplace listing queries combining Elasticsearch full-text search and PostGIS radius
 * filtering.
 *
 * <p>
 * This record encapsulates all possible search parameters for the {@code /api/marketplace/search} endpoint. The search
 * implementation uses a two-phase approach:
 * <ol>
 * <li><b>Phase 1 (Elasticsearch):</b> Text search on title/description with category and price filters</li>
 * <li><b>Phase 2 (PostGIS):</b> Radius filtering using ST_DWithin() for precise geographic queries</li>
 * </ol>
 *
 * <p>
 * <b>Validation Rules:</b>
 * <ul>
 * <li>offset must be >= 0</li>
 * <li>limit must be 1-100 (enforced by compact constructor)</li>
 * <li>radiusMiles must be one of: 5, 10, 25, 50, 100, 250, or null for "Any"</li>
 * <li>If radiusMiles is specified, geoCityId must also be provided</li>
 * <li>sortBy must be one of: "newest" (default), "price_asc", "price_desc", "distance" (for radius searches)</li>
 * </ul>
 *
 * <p>
 * <b>Performance Considerations (Policy P11):</b>
 * <ul>
 * <li>Limit capped at 100 to prevent excessive Elasticsearch result sets before PostGIS filtering</li>
 * <li>Radius queries target <200ms p99 latency through PostGIS GIST indexes</li>
 * <li>Text search uses Elasticsearch fuzzy matching (2-character typo tolerance)</li>
 * </ul>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P11: PostGIS-only geographic search (≤250 miles)</li>
 * <li>F12.2: Marketplace search with filters and sorting</li>
 * <li>F12.10: Saved searches (future enhancement - not required for I4.T5 MVP)</li>
 * </ul>
 *
 * @param query
 *            Full-text search query (searches title and description fields), null for no text filtering
 * @param categoryId
 *            Filter by marketplace category UUID, null for all categories
 * @param minPrice
 *            Minimum price in USD (inclusive), null for no minimum
 * @param maxPrice
 *            Maximum price in USD (inclusive), null for no maximum
 * @param geoCityId
 *            Center location for radius search (geo_cities.id), required if radiusMiles is set
 * @param radiusMiles
 *            Radius in miles (5, 10, 25, 50, 100, 250, or null for "Any")
 * @param hasImages
 *            Filter to listings with at least one image, null for no filtering (TODO: implement in I4.T6)
 * @param minDate
 *            Filter to listings created on or after this date, null for no date filtering
 * @param maxDate
 *            Filter to listings created on or before this date, null for no date filtering
 * @param sortBy
 *            Sorting option: "newest", "price_asc", "price_desc", "distance" (default: "newest")
 * @param offset
 *            Pagination offset (0-based), default: 0
 * @param limit
 *            Results per page (1-100), default: 25
 * @see villagecompute.homepage.services.MarketplaceSearchService
 * @see SearchResultsType
 */
public record SearchCriteria(String query, UUID categoryId, BigDecimal minPrice, BigDecimal maxPrice, Long geoCityId,
        Integer radiusMiles, Boolean hasImages, Instant minDate, Instant maxDate, String sortBy, int offset,
        int limit) {

    /** Valid radius values in miles. */
    private static final List<Integer> VALID_RADIUS_VALUES = List.of(5, 10, 25, 50, 100, 250);

    /** Valid sort options. */
    private static final List<String> VALID_SORT_OPTIONS = List.of("newest", "price_asc", "price_desc", "distance");

    /**
     * Compact constructor with validation.
     *
     * <p>
     * Validates that:
     * <ul>
     * <li>offset is non-negative</li>
     * <li>limit is between 1 and 100</li>
     * <li>radiusMiles (if provided) is a valid value (5, 10, 25, 50, 100, 250)</li>
     * <li>geoCityId is provided when radiusMiles is set</li>
     * <li>sortBy is a valid option</li>
     * </ul>
     *
     * @throws IllegalArgumentException
     *             if validation fails
     */
    public SearchCriteria {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        if (limit <= 0 || limit > 100) {
            throw new IllegalArgumentException("limit must be 1-100");
        }
        if (radiusMiles != null && !VALID_RADIUS_VALUES.contains(radiusMiles)) {
            throw new IllegalArgumentException("radiusMiles must be one of: 5, 10, 25, 50, 100, 250 (or null for Any)");
        }
        if (radiusMiles != null && geoCityId == null) {
            throw new IllegalArgumentException("geoCityId is required when radiusMiles is specified");
        }
        if (sortBy != null && !VALID_SORT_OPTIONS.contains(sortBy)) {
            throw new IllegalArgumentException(
                    "sortBy must be one of: newest, price_asc, price_desc, distance (or null for default)");
        }
        // Default sortBy to "newest" if null
        if (sortBy == null) {
            sortBy = "newest";
        }
    }

    /**
     * Returns true if this is a geographic search (location + radius specified).
     *
     * @return true if geoCityId and radiusMiles are both non-null
     */
    public boolean isGeographicSearch() {
        return geoCityId != null && radiusMiles != null;
    }

    /**
     * Returns true if this is a text search (query string provided).
     *
     * @return true if query is non-null and non-blank
     */
    public boolean isTextSearch() {
        return query != null && !query.isBlank();
    }

    /**
     * Returns true if any filters are applied (category, price, date, location).
     *
     * @return true if at least one filter is specified
     */
    public boolean hasFilters() {
        return categoryId != null || minPrice != null || maxPrice != null || minDate != null || maxDate != null
                || isGeographicSearch();
    }
}
