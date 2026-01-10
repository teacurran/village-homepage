package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response type for Good Sites directory homepage.
 *
 * <p>
 * Includes root categories for navigation and optionally popular sites for featured display.
 * </p>
 *
 * <p>
 * Example response:
 *
 * <pre>{@code
 * {
 *   "root_categories": [
 *     {
 *       "id": "550e8400-e29b-41d4-a716-446655440001",
 *       "name": "Arts",
 *       "slug": "arts",
 *       "description": "Museums, galleries...",
 *       "link_count": 87
 *     },
 *     ...
 *   ],
 *   "popular_sites": [...]
 * }
 * }</pre>
 */
public record DirectoryHomeType(@JsonProperty("root_categories") List<DirectoryCategoryType> rootCategories,

        @JsonProperty("popular_sites") List<CategorySiteType> popularSites) {
}
