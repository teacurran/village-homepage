/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Type representing a single social media post from Instagram or Facebook.
 *
 * <p>
 * All JSON-marshalled types in this project use the Type suffix and record classes.
 *
 * @param platformPostId
 *            platform-specific post ID (e.g., Instagram media ID)
 * @param postType
 *            post type: "image", "video", "carousel", "story", "reel"
 * @param caption
 *            post caption text (may be null)
 * @param mediaUrls
 *            list of media URLs with type and thumbnail URLs
 * @param postedAt
 *            ISO 8601 timestamp when post was created on platform
 * @param engagement
 *            engagement metrics (likes, comments, shares, views)
 * @param permalink
 *            direct link to post on platform (may be null for Facebook)
 */
public record SocialPostType(@JsonProperty("platform_post_id") @NotBlank String platformPostId,
        @JsonProperty("post_type") @NotBlank String postType, String caption,
        @JsonProperty("media_urls") @NotNull List<Map<String, Object>> mediaUrls,
        @JsonProperty("posted_at") @NotBlank String postedAt, @NotNull Map<String, Object> engagement,
        String permalink) {
}
