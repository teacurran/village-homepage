package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

/**
 * Response type for site submission results.
 *
 * <p>
 * Returned after a user submits a site to the directory. Includes the submission status and whether the site was
 * auto-approved based on user karma trust level.
 * </p>
 *
 * <p>
 * Example response:
 *
 * <pre>{@code
 * {
 *   "site_id": "550e8400-e29b-41d4-a716-446655440001",
 *   "status": "approved",
 *   "title": "Hacker News",
 *   "description": "Social news website...",
 *   "categories_pending": [],
 *   "categories_approved": ["660e8400-e29b-41d4-a716-446655440000"],
 *   "message": "Site submitted and approved automatically (trusted user)"
 * }
 * }</pre>
 */
public record SiteSubmissionResultType(@JsonProperty("site_id") UUID siteId,

        @JsonProperty("status") String status,

        @JsonProperty("title") String title,

        @JsonProperty("description") String description,

        @JsonProperty("categories_pending") List<UUID> categoriesPending,

        @JsonProperty("categories_approved") List<UUID> categoriesApproved,

        @JsonProperty("message") String message) {
    /**
     * Creates a result for auto-approved submission (trusted user).
     *
     * @param siteId
     *            ID of created site
     * @param title
     *            Site title
     * @param description
     *            Site description
     * @param approvedCategories
     *            Categories that were auto-approved
     * @return Result DTO
     */
    public static SiteSubmissionResultType approved(UUID siteId, String title, String description,
            List<UUID> approvedCategories) {
        return new SiteSubmissionResultType(siteId, "approved", title, description, List.of(), approvedCategories,
                "Site submitted and approved automatically (trusted user)");
    }

    /**
     * Creates a result for pending submission (untrusted user).
     *
     * @param siteId
     *            ID of created site
     * @param title
     *            Site title
     * @param description
     *            Site description
     * @param pendingCategories
     *            Categories awaiting moderation
     * @return Result DTO
     */
    public static SiteSubmissionResultType pending(UUID siteId, String title, String description,
            List<UUID> pendingCategories) {
        return new SiteSubmissionResultType(siteId, "pending", title, description, pendingCategories, List.of(),
                "Site submitted and awaiting moderation approval");
    }
}
