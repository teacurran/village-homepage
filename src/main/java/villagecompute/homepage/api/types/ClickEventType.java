package villagecompute.homepage.api.types;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.Map;
import java.util.UUID;

/**
 * Request DTO for click tracking events (Policy F14.9).
 *
 * <p>
 * Used by {@code POST /track/click} endpoint to record user click events for analytics. Events are stored in
 * partitioned link_clicks table with 90-day retention.
 *
 * <p>
 * <b>Click Types:</b>
 * <ul>
 * <li>directory_site_click - Click on site link from category page</li>
 * <li>directory_category_view - View category page</li>
 * <li>directory_site_view - View site detail page</li>
 * <li>directory_vote - Cast vote on site</li>
 * <li>directory_search - Search Good Sites</li>
 * <li>directory_bubbled_click - Click bubbled site from parent category</li>
 * <li>marketplace_listing - Click on marketplace listing</li>
 * <li>marketplace_view - View marketplace listing detail</li>
 * <li>profile_view - View user profile</li>
 * <li>profile_curated - Click on curated article from profile page</li>
 * </ul>
 *
 * <p>
 * <b>Metadata Fields (context-specific):</b>
 * <ul>
 * <li>category_id - Category UUID (for directory events)</li>
 * <li>category_slug - Category slug (for context)</li>
 * <li>is_bubbled - Boolean (true if clicking bubbled site)</li>
 * <li>source_category_id - Child category UUID (for bubbled clicks)</li>
 * <li>rank_in_category - Site's rank when clicked (Integer)</li>
 * <li>score - Site's score when clicked (Integer)</li>
 * <li>search_query - Search term (for directory_search)</li>
 * <li>profile_id - Profile UUID (for profile events)</li>
 * <li>profile_username - Profile username (for context)</li>
 * <li>article_id - Curated article UUID (for profile_curated)</li>
 * <li>article_slot - Slot name (for profile_curated)</li>
 * <li>template - Profile template type (for profile events)</li>
 * </ul>
 *
 * <p>
 * <b>Usage Example:</b>
 *
 * <pre>
 * POST /track/click
 * Content-Type: application/json
 *
 * {
 *   "clickType": "directory_site_click",
 *   "targetId": "a3f8b9c0-1234-5678-abcd-1234567890ab",
 *   "targetUrl": "https://example.com",
 *   "metadata": {
 *     "category_id": "b4e9c1d1-2345-6789-bcde-2345678901bc",
 *     "is_bubbled": false,
 *     "rank_in_category": 2,
 *     "score": 15
 *   }
 * }
 * </pre>
 *
 * <p>
 * <b>Security Considerations:</b>
 * <ul>
 * <li>Rate limited: 60/minute for anonymous, 300/minute for logged_in (RateLimitService)</li>
 * <li>Target URLs validated to prevent tracking external malicious sites</li>
 * <li>IP addresses truncated before storage (privacy)</li>
 * <li>User agents stripped of detailed version info (privacy)</li>
 * </ul>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>F14.9: 90-day retention for raw click logs</li>
 * <li>P14: Consent-gated analytics tracking</li>
 * </ul>
 *
 * @param clickType
 *            Type of click event (validated against enum)
 * @param targetId
 *            UUID of clicked entity (site, listing, user)
 * @param targetUrl
 *            Destination URL
 * @param metadata
 *            Additional context (flexible JSONB)
 * @see villagecompute.homepage.api.rest.ClickTrackingResource for endpoint implementation
 * @see villagecompute.homepage.data.models.LinkClick for persistence
 */
public record ClickEventType(@NotBlank @Pattern(
        regexp = "directory_site_click|directory_category_view|directory_site_view|directory_vote|directory_search|directory_bubbled_click|marketplace_listing|marketplace_view|profile_view|profile_curated",
        message = "Invalid click type") String clickType, UUID targetId, @NotBlank String targetUrl,
        Map<String, Object> metadata) {
}
