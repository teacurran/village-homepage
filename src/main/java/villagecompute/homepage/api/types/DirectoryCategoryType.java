package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import villagecompute.homepage.data.models.DirectoryCategory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API type representing a directory category for JSON responses.
 *
 * <p>
 * Used in admin CRUD endpoints and public directory browsing APIs. Converts from {@link DirectoryCategory} entity for
 * Good Sites web directory (Feature F13.1).
 *
 * @param id
 *            unique identifier
 * @param parentId
 *            parent category UUID (null for root)
 * @param name
 *            display name (e.g., "Computers & Internet", "Open Source")
 * @param slug
 *            URL-friendly identifier (e.g., "computers", "computers-opensource")
 * @param description
 *            category description for directory pages
 * @param iconUrl
 *            URL to 32px category icon (nullable)
 * @param sortOrder
 *            display order within same parent level
 * @param linkCount
 *            cached count of approved sites in this category
 * @param isActive
 *            active/disabled status
 * @param createdAt
 *            creation timestamp
 * @param updatedAt
 *            last modification timestamp
 */
public record DirectoryCategoryType(@NotNull UUID id, @JsonProperty("parent_id") UUID parentId, @NotNull String name,
        @NotNull String slug, String description, @JsonProperty("icon_url") String iconUrl,
        @JsonProperty("sort_order") @NotNull Integer sortOrder, @JsonProperty("link_count") @NotNull Integer linkCount,
        @JsonProperty("is_active") @NotNull Boolean isActive, @JsonProperty("created_at") @NotNull Instant createdAt,
        @JsonProperty("updated_at") @NotNull Instant updatedAt) {

    /**
     * Converts a DirectoryCategory entity to API type.
     *
     * @param category
     *            the entity to convert
     * @return DirectoryCategoryType for JSON response
     */
    public static DirectoryCategoryType fromEntity(DirectoryCategory category) {
        return new DirectoryCategoryType(category.id, category.parentId, category.name, category.slug,
                category.description, category.iconUrl, category.sortOrder, category.linkCount, category.isActive,
                category.createdAt, category.updatedAt);
    }

    /**
     * Converts a list of DirectoryCategory entities to API types.
     *
     * @param categories
     *            the entities to convert
     * @return List of DirectoryCategoryType for JSON response
     */
    public static List<DirectoryCategoryType> fromEntities(List<DirectoryCategory> categories) {
        return categories.stream().map(DirectoryCategoryType::fromEntity).toList();
    }
}
