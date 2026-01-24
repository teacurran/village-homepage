package villagecompute.homepage.api.types;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Response wrapper for marketplace search results with pagination metadata.
 *
 * <p>
 * This type encapsulates the search results list along with pagination information needed for client-side navigation.
 * The structure follows the standard REST pagination pattern used throughout the Village Homepage API.
 *
 * <p>
 * <b>Response Structure:</b>
 *
 * <pre>
 * {
 *   "results": [
 *     { "id": "...", "title": "...", ... },
 *     ...
 *   ],
 *   "totalCount": 142,
 *   "offset": 50,
 *   "limit": 25,
 *   "hasMore": true
 * }
 * </pre>
 *
 * <p>
 * <b>Pagination Metadata:</b>
 * <ul>
 * <li>{@code totalCount} - Total number of matching listings across all pages (used for "Showing 51-75 of 142")</li>
 * <li>{@code offset} - Current page offset (0-based, e.g., 0, 25, 50)</li>
 * <li>{@code limit} - Results per page (1-100, default 25)</li>
 * <li>{@code hasMore} - Convenience flag: true if there are more results beyond this page</li>
 * </ul>
 *
 * <p>
 * <b>Client Pagination Example:</b>
 *
 * <pre>
 * // Page 1 (first 25 results)
 * GET /api/marketplace/search?q=bicycle&offset=0&limit=25
 * // Response: totalCount=142, offset=0, limit=25, hasMore=true
 *
 * // Page 2 (next 25 results)
 * GET /api/marketplace/search?q=bicycle&offset=25&limit=25
 * // Response: totalCount=142, offset=25, limit=25, hasMore=true
 *
 * // Page 6 (last partial page)
 * GET /api/marketplace/search?q=bicycle&offset=125&limit=25
 * // Response: totalCount=142, offset=125, limit=25, hasMore=false (showing last 17 results)
 * </pre>
 *
 * <p>
 * <b>Performance Note:</b> {@code totalCount} is calculated via separate Elasticsearch count query. For very large
 * result sets (10,000+), this may be capped at 10,000 (Elasticsearch index.max_result_window setting).
 *
 * @param results
 *            List of search result listings (empty list if no matches)
 * @param totalCount
 *            Total number of matching listings across all pages
 * @param offset
 *            Current page offset (0-based)
 * @param limit
 *            Results per page
 * @see ListingSearchResultType
 * @see SearchCriteria
 */
@Schema(
        description = "Search results wrapper with pagination metadata")
public record SearchResultsType(@Schema(
        description = "List of search result listings",
        required = true) List<ListingSearchResultType> results,

        @Schema(
                description = "Total number of matching listings across all pages",
                example = "142",
                required = true) long totalCount,

        @Schema(
                description = "Current page offset (0-based)",
                example = "0",
                required = true) int offset,

        @Schema(
                description = "Results per page",
                example = "25",
                required = true) int limit) {

    /**
     * Returns true if there are more results beyond the current page.
     *
     * <p>
     * Calculated as: {@code (offset + limit) < totalCount}
     *
     * @return true if more results exist, false if this is the last page
     */
    public boolean hasMore() {
        return (offset + limit) < totalCount;
    }

    /**
     * Returns the page number (1-based) for display purposes.
     *
     * <p>
     * Calculated as: {@code (offset / limit) + 1}
     *
     * @return Current page number (1-based)
     */
    public int currentPage() {
        if (limit == 0) {
            return 1;
        }
        return (offset / limit) + 1;
    }

    /**
     * Returns the total number of pages.
     *
     * <p>
     * Calculated as: {@code ceil(totalCount / limit)}
     *
     * @return Total page count
     */
    public int totalPages() {
        if (limit == 0) {
            return 0;
        }
        return (int) Math.ceil((double) totalCount / limit);
    }
}
