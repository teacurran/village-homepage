/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.api.types;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API type representing AI categorization suggestion for admin review.
 *
 * <p>
 * Used by admin endpoints to display bulk import suggestions with AI recommendations and admin override capability per
 * Feature F13.14.
 *
 * @param id
 *            suggestion UUID
 * @param url
 *            website URL to categorize
 * @param domain
 *            extracted domain (e.g., "github.com")
 * @param title
 *            site title
 * @param description
 *            site description
 * @param ogImageUrl
 *            OpenGraph image URL (nullable)
 * @param aiSuggestedCategories
 *            AI-recommended categories with reasoning
 * @param adminSelectedCategories
 *            Admin-selected categories if overriding AI (nullable)
 * @param confidence
 *            AI confidence score (0.0-1.0)
 * @param reasoning
 *            AI reasoning for category selections
 * @param status
 *            pending, approved, or rejected
 * @param uploadedByUserId
 *            user who uploaded CSV
 * @param reviewedByUserId
 *            admin who reviewed (nullable)
 * @param tokensInput
 *            LangChain4j input token count
 * @param tokensOutput
 *            LangChain4j output token count
 * @param estimatedCostCents
 *            estimated cost in cents
 * @param createdAt
 *            creation timestamp
 * @param updatedAt
 *            last update timestamp
 */
public record DirectoryAiSuggestionType(UUID id, String url, String domain, String title, String description,
        String ogImageUrl, List<CategorySelectionType> aiSuggestedCategories,
        List<CategorySelectionType> adminSelectedCategories, Double confidence, String reasoning, String status,
        UUID uploadedByUserId, UUID reviewedByUserId, Long tokensInput, Long tokensOutput, Integer estimatedCostCents,
        Instant createdAt, Instant updatedAt) {

    /**
     * Category selection with full metadata.
     *
     * @param categoryId
     *            category UUID
     * @param categorySlug
     *            URL-friendly slug
     * @param categoryPath
     *            human-readable path (e.g., "Computers > Programming")
     * @param reasoning
     *            explanation for this selection (AI or admin)
     */
    public record CategorySelectionType(UUID categoryId, String categorySlug, String categoryPath, String reasoning) {
    }

    /**
     * Checks if admin has overridden AI suggestion.
     *
     * @return true if admin selected different categories
     */
    public boolean hasOverride() {
        return adminSelectedCategories != null && !adminSelectedCategories.isEmpty();
    }
}
