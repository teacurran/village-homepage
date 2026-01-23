/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.api.types;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Result type for AI-powered feed item tagging operations.
 *
 * <p>
 * This record encapsulates the structured output from LangChain4j + Claude Sonnet 4 content analysis. Unlike the
 * persistent {@link AiTagsType}, this type focuses on summarization and categorization without sentiment analysis.
 *
 * <p>
 * <b>Field Descriptions:</b>
 * <ul>
 * <li>{@code topics}: 3-5 extracted keywords/topics from article content</li>
 * <li>{@code summary}: 1-2 sentence brief summary of the article</li>
 * <li>{@code categories}: 1-3 assigned categories from predefined list (Tech News, Politics, Sports, etc.)</li>
 * <li>{@code confidenceScore}: Model confidence in tagging quality (0.0-1.0)</li>
 * </ul>
 *
 * <p>
 * <b>Mapping to Persistence:</b> When storing to database via {@link FeedItem#updateAiTags}, this type is mapped to
 * {@link AiTagsType} with sentiment defaulting to "neutral".
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P2/P10 (AI Budget Control): Tag generation respects $500/month ceiling via batch processing</li>
 * </ul>
 *
 * @param topics
 *            extracted topics/keywords from article content
 * @param summary
 *            brief 1-2 sentence summary of the article
 * @param categories
 *            content categories (Tech News, Business, Science, etc.)
 * @param confidenceScore
 *            confidence score (0.0-1.0) for tag quality
 */
public record FeedItemTaggingResultType(@NotNull @Size(
        min = 1,
        max = 10) List<String> topics,
        @NotNull @Size(
                min = 1,
                max = 500) String summary,
        @NotNull @Size(
                min = 1,
                max = 5) List<String> categories,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double confidenceScore) {
}
