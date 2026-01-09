package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
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
public record CategoryType(@NotNull UUID id, @JsonProperty("parent_id") UUID parentId, @NotNull String name,
        @NotNull String slug, @JsonProperty("sort_order") @NotNull Integer sortOrder,
        @JsonProperty("is_active") @NotNull Boolean isActive,
        @JsonProperty("fee_schedule") @NotNull FeeScheduleType feeSchedule,
        @JsonProperty("created_at") @NotNull Instant createdAt,
        @JsonProperty("updated_at") @NotNull Instant updatedAt) {

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
