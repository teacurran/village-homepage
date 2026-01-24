package villagecompute.homepage.api.types;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Simplified marketplace listing DTO for search results.
 *
 * <p>
 * This type returns a subset of listing fields optimized for search result display. Unlike the full
 * {@link MarketplaceListingType}, this omits sensitive contact information and internal fields (payment_intent_id,
 * reminder_sent, etc.) to reduce payload size and improve performance.
 *
 * <p>
 * <b>Included Fields:</b>
 * <ul>
 * <li>id - Listing UUID for detail page navigation</li>
 * <li>title - Listing title (highlighted in search results)</li>
 * <li>description - Truncated to first 200 characters for preview</li>
 * <li>price - Display price in USD</li>
 * <li>categoryId - Category UUID for filtering/navigation</li>
 * <li>geoCityId - Location for "near you" display</li>
 * <li>cityName - Resolved city name for display (e.g., "Seattle, WA")</li>
 * <li>createdAt - Timestamp for "posted N days ago" display</li>
 * <li>imageCount - Number of images attached (0 if none)</li>
 * <li>distance - Distance in miles from search center (null if not a radius search)</li>
 * </ul>
 *
 * <p>
 * <b>Omitted Fields (vs. Full Listing):</b>
 * <ul>
 * <li>contactInfo - Never exposed in search results (privacy)</li>
 * <li>userId - Not needed for public search display</li>
 * <li>status - All search results are 'active' (filtered at query time)</li>
 * <li>expiresAt, lastBumpedAt, reminderSent - Internal fields not shown in search</li>
 * <li>paymentIntentId - Internal payment tracking</li>
 * </ul>
 *
 * <p>
 * <b>Usage Example:</b>
 *
 * <pre>
 * GET /api/marketplace/search?q=bicycle&location=123&radius=25
 *
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
 *     }
 *   ],
 *   "totalCount": 47,
 *   "offset": 0,
 *   "limit": 25
 * }
 * </pre>
 *
 * <p>
 * <b>Performance Optimizations:</b>
 * <ul>
 * <li>Description truncated to 200 chars (vs. full 8000 char limit) to reduce JSON payload</li>
 * <li>City name resolved via JOIN during query (no N+1 problem)</li>
 * <li>Image count pre-aggregated (future I4.T6 - defaults to 0 for now)</li>
 * </ul>
 *
 * @param id
 *            Listing UUID
 * @param title
 *            Listing title
 * @param description
 *            Description preview (truncated to 200 characters)
 * @param price
 *            Price in USD (null for free categories)
 * @param categoryId
 *            Marketplace category UUID
 * @param geoCityId
 *            Location city ID (geo_cities.id), null if location not specified
 * @param cityName
 *            Resolved city name for display (e.g., "Seattle, WA"), null if geoCityId is null
 * @param createdAt
 *            Listing creation timestamp
 * @param imageCount
 *            Number of images attached (0 if none) - TODO: implement in I4.T6
 * @param distance
 *            Distance in miles from search center (null if not a radius search)
 * @see SearchResultsType
 * @see SearchCriteria
 */
@Schema(
        description = "Simplified marketplace listing for search results with distance and preview data")
public record ListingSearchResultType(@Schema(
        description = "Listing UUID",
        example = "550e8400-e29b-41d4-a716-446655440000",
        required = true) UUID id,

        @Schema(
                description = "Listing title",
                example = "Vintage Road Bicycle",
                required = true,
                maxLength = 100) String title,

        @Schema(
                description = "Description preview (truncated to 200 characters)",
                example = "Lightly used vintage road bike from the 1980s...",
                nullable = true) String description,

        @Schema(
                description = "Price in USD",
                example = "350.00",
                nullable = true) BigDecimal price,

        @Schema(
                description = "Marketplace category UUID",
                example = "770e8400-e29b-41d4-a716-446655440002",
                required = true) UUID categoryId,

        @Schema(
                description = "Location city ID (geo_cities.id)",
                example = "5128581",
                nullable = true) Long geoCityId,

        @Schema(
                description = "Resolved city name for display",
                example = "Seattle, WA",
                nullable = true) String cityName,

        @Schema(
                description = "Listing creation timestamp",
                example = "2026-01-05T10:30:00Z",
                required = true) Instant createdAt,

        @Schema(
                description = "Number of images attached",
                example = "5",
                required = true) int imageCount,

        @Schema(
                description = "Distance in miles from search center (null if not a radius search)",
                example = "12.3",
                nullable = true) Double distance) {

    /**
     * Maximum description preview length for search results.
     */
    private static final int DESCRIPTION_PREVIEW_LENGTH = 200;

    /**
     * Compact constructor with description truncation.
     *
     * <p>
     * If description exceeds 200 characters, truncates and appends "..." ellipsis.
     */
    public ListingSearchResultType {
        if (description != null && description.length() > DESCRIPTION_PREVIEW_LENGTH) {
            description = description.substring(0, DESCRIPTION_PREVIEW_LENGTH) + "...";
        }
    }

    /**
     * Factory method to create a search result from a full MarketplaceListing entity.
     *
     * <p>
     * Extracts relevant fields for search result display. Distance and cityName must be provided separately (resolved
     * during search query).
     *
     * @param listing
     *            The full marketplace listing entity
     * @param cityName
     *            Resolved city name (e.g., "Seattle, WA"), or null if location unknown
     * @param distance
     *            Distance in miles from search center, or null if not a radius search
     * @param imageCount
     *            Number of images attached (future I4.T6)
     * @return Search result DTO
     */
    public static ListingSearchResultType fromListing(villagecompute.homepage.data.models.MarketplaceListing listing,
            String cityName, Double distance, int imageCount) {
        return new ListingSearchResultType(listing.id, listing.title, listing.description, listing.price,
                listing.categoryId, listing.geoCityId, cityName, listing.createdAt, imageCount, distance);
    }
}
