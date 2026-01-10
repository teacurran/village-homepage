package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import villagecompute.homepage.data.models.DirectorySite;
import villagecompute.homepage.data.models.DirectorySiteCategory;

import java.util.UUID;

/**
 * Response type for a site within a category listing.
 *
 * <p>
 * Combines site metadata with category-specific voting data (score, rank, bubbled status).
 * </p>
 *
 * <p>
 * Example response:
 *
 * <pre>{@code
 * {
 *   "site_category_id": "770e8400-e29b-41d4-a716-446655440020",
 *   "site": {
 *     "id": "550e8400-e29b-41d4-a716-446655440001",
 *     "url": "https://news.ycombinator.com",
 *     "title": "Hacker News",
 *     "description": "Social news website...",
 *     "screenshot_url": "https://r2.example.com/screenshots/..."
 *   },
 *   "score": 42,
 *   "upvotes": 45,
 *   "downvotes": 3,
 *   "rank": 1,
 *   "bubbled_from_category": null
 * }
 * }</pre>
 */
public record CategorySiteType(@JsonProperty("site_category_id") UUID siteCategoryId,

        @JsonProperty("site") DirectorySiteType site,

        @JsonProperty("score") int score,

        @JsonProperty("upvotes") int upvotes,

        @JsonProperty("downvotes") int downvotes,

        @JsonProperty("rank") Integer rank,

        @JsonProperty("bubbled_from_category") String bubbledFromCategory) {

    /**
     * Creates a CategorySiteType from entities (direct site, not bubbled).
     *
     * @param siteCategory
     *            Site-category membership entity
     * @param site
     *            Site entity
     * @return Category site DTO
     */
    public static CategorySiteType fromEntities(DirectorySiteCategory siteCategory, DirectorySite site) {
        return new CategorySiteType(siteCategory.id, DirectorySiteType.fromEntity(site), siteCategory.score,
                siteCategory.upvotes, siteCategory.downvotes, siteCategory.rankInCategory, null);
    }

    /**
     * Creates a CategorySiteType for a bubbled site (from child category).
     *
     * @param siteCategory
     *            Site-category membership entity
     * @param site
     *            Site entity
     * @param bubbledFromCategoryName
     *            Name of child category this site bubbled from
     * @return Category site DTO with bubbled flag
     */
    public static CategorySiteType fromEntitiesBubbled(DirectorySiteCategory siteCategory, DirectorySite site,
            String bubbledFromCategoryName) {
        return new CategorySiteType(siteCategory.id, DirectorySiteType.fromEntity(site), siteCategory.score,
                siteCategory.upvotes, siteCategory.downvotes, siteCategory.rankInCategory, bubbledFromCategoryName);
    }
}
