package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import villagecompute.homepage.data.models.FeedItem;

import java.time.Instant;
import java.util.UUID;

/**
 * API type representing a feed item for JSON responses.
 *
 * <p>
 * Used in admin endpoints and user-facing news feed APIs. Converts from {@link FeedItem} entity.
 *
 * @param id
 *            unique identifier
 * @param sourceId
 *            RSS source UUID
 * @param title
 *            article title
 * @param url
 *            article URL
 * @param description
 *            article summary
 * @param author
 *            article author
 * @param publishedAt
 *            publication timestamp
 * @param aiTags
 *            AI-generated tags (topics, sentiment, categories)
 * @param aiTagged
 *            AI tagging completion flag
 * @param fetchedAt
 *            fetch timestamp
 */
public record FeedItemType(@NotNull UUID id, @JsonProperty("source_id") @NotNull UUID sourceId, @NotNull String title,
        @NotNull String url, String description, String author,
        @JsonProperty("published_at") @NotNull Instant publishedAt, @JsonProperty("ai_tags") AiTagsType aiTags,
        @JsonProperty("ai_tagged") @NotNull Boolean aiTagged, @JsonProperty("fetched_at") @NotNull Instant fetchedAt) {

    /**
     * Converts a FeedItem entity to API type.
     *
     * @param item
     *            the entity to convert
     * @return FeedItemType for JSON response
     */
    public static FeedItemType fromEntity(FeedItem item) {
        return new FeedItemType(item.id, item.sourceId, item.title, item.url, item.description, item.author,
                item.publishedAt, item.aiTags, item.aiTagged, item.fetchedAt);
    }
}
