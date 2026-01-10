package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Response type for category page view data.
 *
 * <p>
 * Includes category metadata, direct sites (submitted directly to this category), bubbled sites (top-ranked sites from
 * child categories), user vote states, and pagination metadata.
 * </p>
 *
 * <p>
 * Example response:
 *
 * <pre>{@code
 * {
 *   "category": {
 *     "id": "550e8400-e29b-41d4-a716-446655440001",
 *     "name": "Computers",
 *     "slug": "computers",
 *     "description": "Sites about computing and technology",
 *     "link_count": 42
 *   },
 *   "direct_sites": [...],
 *   "bubbled_sites": [...],
 *   "user_votes": {
 *     "660e8400-e29b-41d4-a716-446655440010": 1,
 *     "660e8400-e29b-41d4-a716-446655440011": -1
 *   },
 *   "total_sites": 42,
 *   "page": 1,
 *   "page_size": 50,
 *   "total_pages": 1
 * }
 * }</pre>
 */
public record CategoryViewType(@JsonProperty("category") DirectoryCategoryType category,

        @JsonProperty("direct_sites") List<CategorySiteType> directSites,

        @JsonProperty("bubbled_sites") List<CategorySiteType> bubbledSites,

        @JsonProperty("user_votes") Map<UUID, Short> userVotes,

        @JsonProperty("total_sites") int totalSites,

        @JsonProperty("page") int page,

        @JsonProperty("page_size") int pageSize,

        @JsonProperty("total_pages") int totalPages) {
}
