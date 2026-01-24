package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import villagecompute.homepage.data.models.MarketplaceCategory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API type representing a marketplace category for JSON responses.
 *
 * <p>
 * Used in admin CRUD endpoints and public category browsing APIs. Converts from {@link MarketplaceCategory} entity.
 *
 * @param id
 *            unique identifier
 * @param parentId
 *            parent category UUID (null for root)
 * @param name
 *            display name (e.g., "Electronics")
 * @param slug
 *            URL-friendly identifier (e.g., "electronics")
 * @param sortOrder
 *            display order within same parent level
 * @param isActive
 *            active/disabled status
 * @param feeSchedule
 *            fee configuration (posting, featured, bump)
 * @param createdAt
 *            creation timestamp
 * @param updatedAt
 *            last modification timestamp
 */
@Schema(
        description = "Marketplace category with hierarchical structure and fee schedule")
public record CategoryType(@Schema(
        description = "Unique category identifier",
        example = "770e8400-e29b-41d4-a716-446655440002",
        required = true) @NotNull UUID id,

        @Schema(
                description = "Parent category UUID (null for root categories)",
                example = "880e8400-e29b-41d4-a716-446655440003",
                nullable = true) @JsonProperty("parent_id") UUID parentId,

        @Schema(
                description = "Category display name",
                example = "Electronics",
                required = true,
                maxLength = 100) @NotNull String name,

        @Schema(
                description = "URL-friendly identifier",
                example = "electronics",
                required = true,
                maxLength = 100) @NotNull String slug,

        @Schema(
                description = "Display order within same parent level",
                example = "10",
                required = true) @JsonProperty("sort_order") @NotNull Integer sortOrder,

        @Schema(
                description = "Whether category is active and visible",
                example = "true",
                required = true) @JsonProperty("is_active") @NotNull Boolean isActive,

        @Schema(
                description = "Fee configuration for posting, featured, and bump",
                required = true) @JsonProperty("fee_schedule") @NotNull FeeScheduleType feeSchedule,

        @Schema(
                description = "Creation timestamp",
                example = "2026-01-24T10:00:00Z",
                required = true) @JsonProperty("created_at") @NotNull Instant createdAt,

        @Schema(
                description = "Last modification timestamp",
                example = "2026-01-24T12:30:00Z",
                required = true) @JsonProperty("updated_at") @NotNull Instant updatedAt) {

    /**
     * Converts a MarketplaceCategory entity to API type.
     *
     * @param category
     *            the entity to convert
     * @return CategoryType for JSON response
     */
    public static CategoryType fromEntity(MarketplaceCategory category) {
        return new CategoryType(category.id, category.parentId, category.name, category.slug, category.sortOrder,
                category.isActive, category.feeSchedule, category.createdAt, category.updatedAt);
    }

    /**
     * Converts a list of MarketplaceCategory entities to API types.
     *
     * @param categories
     *            the entities to convert
     * @return List of CategoryType for JSON response
     */
    public static List<CategoryType> fromEntities(List<MarketplaceCategory> categories) {
        return categories.stream().map(CategoryType::fromEntity).toList();
    }
}
