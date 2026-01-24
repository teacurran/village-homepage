package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
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
@Schema(
        description = "RSS feed item with AI-generated tags and metadata")
public record FeedItemType(@Schema(
        description = "Unique feed item identifier",
        example = "550e8400-e29b-41d4-a716-446655440000",
        required = true) @NotNull UUID id,

        @Schema(
                description = "RSS source UUID",
                example = "660e8400-e29b-41d4-a716-446655440001",
                required = true) @JsonProperty("source_id") @NotNull UUID sourceId,

        @Schema(
                description = "Article title",
                example = "Breaking: New AI Model Released",
                required = true,
                maxLength = 500) @NotNull String title,

        @Schema(
                description = "Article URL",
                example = "https://techcrunch.com/2026/01/24/new-ai-model",
                required = true) @NotNull String url,

        @Schema(
                description = "Article summary/description",
                example = "A groundbreaking new AI model was announced today...",
                nullable = true) String description,

        @Schema(
                description = "Article author",
                example = "Jane Smith",
                nullable = true) String author,

        @Schema(
                description = "Publication timestamp",
                example = "2026-01-24T09:00:00Z",
                required = true) @JsonProperty("published_at") @NotNull Instant publishedAt,

        @Schema(
                description = "AI-generated tags for categorization and search",
                nullable = true) @JsonProperty("ai_tags") AiTagsType aiTags,

        @Schema(
                description = "Whether AI tagging has been completed",
                example = "true",
                required = true) @JsonProperty("ai_tagged") @NotNull Boolean aiTagged,

        @Schema(
                description = "Timestamp when item was fetched from RSS feed",
                example = "2026-01-24T10:00:00Z",
                required = true) @JsonProperty("fetched_at") @NotNull Instant fetchedAt) {

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
