package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * KarmaSummaryType represents a user's karma status for API responses.
 *
 * <p>
 * Provides frontend with current karma, trust level, next milestone, and privilege descriptions for UI display.
 * </p>
 *
 * @param karma
 *            Current karma points (0+)
 * @param trustLevel
 *            Current trust level (untrusted, trusted, moderator)
 * @param karmaToNextLevel
 *            Points needed to reach next level (null if at max)
 * @param privilegeDescription
 *            Human-readable description of current privileges
 * @param canAutoPublish
 *            True if user's submissions auto-publish without moderation
 * @param isModerator
 *            True if user has moderator privileges
 */
public record KarmaSummaryType(@JsonProperty("karma") int karma, @JsonProperty("trust_level") String trustLevel,
        @JsonProperty("karma_to_next_level") Integer karmaToNextLevel,
        @JsonProperty("privilege_description") String privilegeDescription,
        @JsonProperty("can_auto_publish") boolean canAutoPublish, @JsonProperty("is_moderator") boolean isModerator) {
}
