/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/**
 * API type representing news widget response with pagination and metadata.
 *
 * <p>
 * Used by {@code GET /api/widgets/news} to return personalized feed items with pagination, feature flag status, and
 * click tracking information.
 *
 * <p>
 * <b>Response Structure:</b>
 *
 * <pre>
 * {
 *   "items": [ { FeedItemType... }, ... ],
 *   "total_count": 150,
 *   "limit": 20,
 *   "offset": 0,
 *   "metadata": {
 *     "click_tracking_enabled": true,
 *     "click_tracking_base_url": "/track/click",
 *     "feature_flags": {
 *       "ai_tags_enabled": true,
 *       "personalization_enabled": true
 *     }
 *   }
 * }
 * </pre>
 *
 * @param items
 *            list of feed items for current page
 * @param totalCount
 *            total number of items available (before pagination)
 * @param limit
 *            maximum items per page
 * @param offset
 *            pagination offset
 * @param metadata
 *            additional context (feature flags, click tracking config, etc.)
 */
public record NewsWidgetType(@NotNull List<FeedItemType> items, @JsonProperty("total_count") int totalCount, int limit,
        int offset, @NotNull Map<String, Object> metadata) {
}
