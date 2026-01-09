/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.services;

import java.time.YearMonth;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

// NOTE: LangChain4j integration - uncomment when anthropic API key is configured
// import dev.langchain4j.model.chat.ChatLanguageModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import villagecompute.homepage.api.types.AiTagsType;
import villagecompute.homepage.data.models.AiUsageTracking;

/**
 * Service for AI-powered content tagging using LangChain4j and Anthropic Claude.
 *
 * <p>
 * This service extracts topics, sentiment, categories, and confidence scores from feed item content using Claude Sonnet
 * 4. It handles prompt engineering, response parsing, token tracking, and cost estimation per P2/P10 budget policies.
 *
 * <p>
 * <b>Tagging Process:</b>
 * <ol>
 * <li>Construct prompt with article title, description, and content snippet</li>
 * <li>Send to Claude Sonnet 4 via LangChain4j abstraction</li>
 * <li>Parse JSON response into {@link AiTagsType} record</li>
 * <li>Track token usage and cost in {@link AiUsageTracking}</li>
 * <li>Return tags for persistence in feed_items.ai_tags JSONB column</li>
 * </ol>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P2/P10 (AI Budget Control): All tagging operations respect budget limits via AiTaggingBudgetService</li>
 * </ul>
 *
 * @see AiTagsType
 * @see AiUsageTracking
 * @see AiTaggingBudgetService
 */
@ApplicationScoped
public class AiTaggingService {

    private static final Logger LOG = Logger.getLogger(AiTaggingService.class);

    private static final String PROVIDER = AiUsageTracking.DEFAULT_PROVIDER;

    // Content truncation to avoid exceeding token limits
    private static final int MAX_CONTENT_LENGTH = 3000;

    // NOTE: Uncomment when LangChain4j and Anthropic API key are configured
    // @Inject
    // ChatLanguageModel chatModel;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Tags a feed item with AI-generated metadata.
     *
     * <p>
     * Extracts topics, sentiment, categories, and confidence from article content. Returns null if tagging fails (e.g.,
     * invalid response, network error).
     *
     * @param title
     *            article title
     * @param description
     *            article description/summary
     * @param content
     *            article full content (will be truncated if too long)
     * @return AI-generated tags, or null if tagging failed
     */
    public AiTagsType tagArticle(String title, String description, String content) {
        try {
            // Build prompt
            String prompt = buildPrompt(title, description, content);

            LOG.debugf("Sending tagging request to Claude: title=\"%s\"", title);

            // Call Claude via LangChain4j
            // NOTE: Uncomment when API key is configured
            // String response = chatModel.generate(prompt);

            // TODO: Remove this stub when LangChain4j is properly configured
            String response = generateStubResponse(title);

            // Parse response
            AiTagsType tags = parseResponse(response);

            // Estimate token usage (rough approximation: 4 chars = 1 token)
            long estimatedInputTokens = prompt.length() / 4;
            long estimatedOutputTokens = response.length() / 4;
            int costCents = AiUsageTracking.calculateCostCents(estimatedInputTokens, estimatedOutputTokens);

            // Record usage
            AiUsageTracking.recordUsage(YearMonth.now(), PROVIDER, 1, estimatedInputTokens, estimatedOutputTokens,
                    costCents);

            LOG.debugf(
                    "Tagged article: title=\"%s\", topics=%s, sentiment=%s, categories=%s, confidence=%.2f, "
                            + "inputTokens=%d, outputTokens=%d, costCents=%d",
                    title, tags.topics(), tags.sentiment(), tags.categories(), tags.confidence(), estimatedInputTokens,
                    estimatedOutputTokens, costCents);

            return tags;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to tag article: title=\"%s\"", title);
            return null;
        }
    }

    /**
     * Builds tagging prompt for Claude Sonnet 4.
     *
     * <p>
     * Instructs model to extract structured metadata in JSON format. Categories are constrained to predefined list to
     * ensure consistency.
     *
     * @param title
     *            article title
     * @param description
     *            article description
     * @param content
     *            article content (will be truncated)
     * @return formatted prompt string
     */
    private String buildPrompt(String title, String description, String content) {
        // Truncate content if too long
        String contentSnippet = content;
        if (content != null && content.length() > MAX_CONTENT_LENGTH) {
            contentSnippet = content.substring(0, MAX_CONTENT_LENGTH) + "...";
        }

        return String.format("""
                Analyze this news article and extract structured metadata.

                INSTRUCTIONS:
                1. Extract 3-5 main topics/keywords from the content
                2. Determine overall sentiment (positive, negative, neutral, or mixed)
                3. Assign 1-3 categories from this list ONLY:
                   - Technology
                   - Business
                   - Science
                   - Health
                   - Politics
                   - Entertainment
                   - Sports
                   - World
                   - Lifestyle
                   - Opinion
                   - Environment
                   - Education
                   - Finance
                   - Arts
                   - Travel
                   - Food
                4. Assign confidence score (0.0-1.0) for tagging quality

                ARTICLE:
                Title: %s
                Description: %s
                Content: %s

                Respond with ONLY valid JSON in this exact format (no markdown, no explanation):
                {
                  "topics": ["topic1", "topic2", "topic3"],
                  "sentiment": "positive",
                  "categories": ["Technology", "Business"],
                  "confidence": 0.85
                }
                """, title != null ? title : "", description != null ? description : "",
                contentSnippet != null ? contentSnippet : "");
    }

    /**
     * Parses Claude response into AiTagsType.
     *
     * <p>
     * Handles various response formats (with/without markdown code blocks). Falls back to default values if parsing
     * fails.
     *
     * @param response
     *            raw Claude response string
     * @return parsed tags, or default values if parsing failed
     */
    private AiTagsType parseResponse(String response) {
        try {
            // Strip markdown code blocks if present
            String json = response.trim();
            if (json.startsWith("```json")) {
                json = json.substring(7); // Remove ```json
            } else if (json.startsWith("```")) {
                json = json.substring(3); // Remove ```
            }
            if (json.endsWith("```")) {
                json = json.substring(0, json.length() - 3);
            }
            json = json.trim();

            // Parse JSON to AiTagsType
            AiTagsType tags = objectMapper.readValue(json, AiTagsType.class);

            // Validate and apply defaults
            if (tags.topics() == null || tags.topics().isEmpty()) {
                tags = new AiTagsType(List.of("Uncategorized"), tags.sentiment() != null ? tags.sentiment() : "neutral",
                        tags.categories() != null && !tags.categories().isEmpty() ? tags.categories()
                                : List.of("Uncategorized"),
                        tags.confidence() != null ? tags.confidence() : 0.5);
            }

            if (tags.sentiment() == null || tags.sentiment().isBlank()) {
                tags = new AiTagsType(tags.topics(), "neutral", tags.categories(), tags.confidence());
            }

            if (tags.categories() == null || tags.categories().isEmpty()) {
                tags = new AiTagsType(tags.topics(), tags.sentiment(), List.of("Uncategorized"), tags.confidence());
            }

            if (tags.confidence() == null || tags.confidence() < 0.0 || tags.confidence() > 1.0) {
                tags = new AiTagsType(tags.topics(), tags.sentiment(), tags.categories(), 0.5);
            }

            // Log warning for low confidence
            if (tags.confidence() < 0.5) {
                LOG.warnf("Low confidence tagging: confidence=%.2f, response=%s", tags.confidence(), response);
            }

            return tags;

        } catch (JsonProcessingException e) {
            LOG.errorf(e, "Failed to parse Claude response as JSON, using defaults: response=%s", response);
            // Return default tags on parse failure
            return new AiTagsType(List.of("Uncategorized"), "neutral", List.of("Uncategorized"), 0.3);
        }
    }

    /**
     * Stub response generator for development/testing.
     *
     * <p>
     * TODO: Remove this method when LangChain4j is properly configured with Anthropic API key.
     *
     * @param title
     *            article title for basic categorization
     * @return mock JSON response
     */
    private String generateStubResponse(String title) {
        // Simple keyword-based categorization for testing
        String category = "Technology";
        String sentiment = "neutral";
        List<String> topics = List.of("General", "News");

        if (title != null) {
            String lowerTitle = title.toLowerCase();
            if (lowerTitle.contains("tech") || lowerTitle.contains("ai") || lowerTitle.contains("software")) {
                category = "Technology";
                topics = List.of("Technology", "Innovation");
            } else if (lowerTitle.contains("business") || lowerTitle.contains("market")
                    || lowerTitle.contains("economy")) {
                category = "Business";
                topics = List.of("Business", "Markets");
            } else if (lowerTitle.contains("science") || lowerTitle.contains("research")) {
                category = "Science";
                topics = List.of("Science", "Research");
            } else if (lowerTitle.contains("health") || lowerTitle.contains("medical")) {
                category = "Health";
                topics = List.of("Health", "Wellness");
            }

            if (lowerTitle.contains("breaking") || lowerTitle.contains("urgent")) {
                sentiment = "neutral";
            }
        }

        return String.format("""
                {
                  "topics": %s,
                  "sentiment": "%s",
                  "categories": ["%s"],
                  "confidence": 0.65
                }
                """, topics.toString().replace("[", "[\"").replace("]", "\"]").replace(", ", "\", \""), sentiment,
                category);
    }
}
