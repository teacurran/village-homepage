package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Response type for site detail page.
 *
 * <p>
 * Includes site metadata, all categories this site exists in (with scores/votes), and user's votes across categories.
 * </p>
 *
 * <p>
 * Example response:
 *
 * <pre>{@code
 * {
 *   "site": {
 *     "id": "550e8400-e29b-41d4-a716-446655440001",
 *     "url": "https://news.ycombinator.com",
 *     "title": "Hacker News",
 *     "description": "Social news website..."
 *   },
 *   "categories": [
 *     {
 *       "category": {
 *         "id": "660e8400-e29b-41d4-a716-446655440010",
 *         "name": "Computers",
 *         "slug": "computers"
 *       },
 *       "site_category_id": "770e8400-e29b-41d4-a716-446655440020",
 *       "score": 42,
 *       "upvotes": 45,
 *       "downvotes": 3,
 *       "rank": 1
 *     },
 *     ...
 *   ],
 *   "user_votes": {
 *     "770e8400-e29b-41d4-a716-446655440020": 1
 *   }
 * }
 * }</pre>
 */
public record SiteDetailType(@JsonProperty("site") DirectorySiteType site,

        @JsonProperty("categories") List<SiteCategoryMembership> categories,

        @JsonProperty("user_votes") Map<UUID, Short> userVotes) {

    /**
     * Site's membership in a category (for detail page).
     */
    public record SiteCategoryMembership(@JsonProperty("category") DirectoryCategoryType category,

            @JsonProperty("site_category_id") UUID siteCategoryId,

            @JsonProperty("score") int score,

            @JsonProperty("upvotes") int upvotes,

            @JsonProperty("downvotes") int downvotes,

            @JsonProperty("rank") Integer rank,

            @JsonProperty("status") String status) {
    }
}
