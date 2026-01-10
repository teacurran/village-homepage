/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.services;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

// NOTE: LangChain4j integration - uncomment when anthropic API key is configured
// import dev.langchain4j.model.chat.ChatLanguageModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import villagecompute.homepage.api.types.AiCategorySuggestionType;
import villagecompute.homepage.data.models.AiUsageTracking;
import villagecompute.homepage.data.models.DirectoryCategory;

/**
 * Service for AI-powered category suggestion using LangChain4j and Anthropic Claude.
 *
 * <p>
 * This service analyzes website metadata (URL, title, description) and suggests 1-3 most appropriate categories from
 * the Good Sites directory hierarchy using Claude Sonnet 4. It handles prompt engineering with full category tree
 * context, response parsing, token tracking, and cost estimation per P2/P10 budget policies.
 *
 * <p>
 * <b>Categorization Process:</b>
 * <ol>
 * <li>Build category tree JSON from DirectoryCategory hierarchy</li>
 * <li>Construct prompt with taxonomy and website metadata</li>
 * <li>Send to Claude Sonnet 4 via LangChain4j abstraction</li>
 * <li>Parse JSON response into {@link AiCategorySuggestionType} record</li>
 * <li>Validate category slugs against database</li>
 * <li>Track token usage and cost in {@link AiUsageTracking}</li>
 * <li>Return suggestions for persistence in directory_ai_suggestions table</li>
 * </ol>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P2/P10 (AI Budget Control): All categorization operations respect budget limits via AiTaggingBudgetService</li>
 * <li>F13.14 (Bulk Import): Provides confidence scores and reasoning for admin review workflow</li>
 * </ul>
 *
 * @see AiCategorySuggestionType
 * @see AiUsageTracking
 * @see AiTaggingBudgetService
 * @see villagecompute.homepage.jobs.BulkImportJobHandler
 */
@ApplicationScoped
public class AiCategorizationService {

    private static final Logger LOG = Logger.getLogger(AiCategorizationService.class);

    private static final String PROVIDER = AiUsageTracking.DEFAULT_PROVIDER;

    // NOTE: Uncomment when LangChain4j and Anthropic API key are configured
    // @Inject
    // ChatLanguageModel chatModel;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Suggests categories for a website based on URL, title, and description.
     *
     * <p>
     * Returns AI-generated category suggestions with confidence score and reasoning. Returns null if categorization
     * fails (e.g., invalid response, network error).
     *
     * @param url
     *            website URL
     * @param title
     *            site title
     * @param description
     *            site description
     * @return AI-generated category suggestions, or null if categorization failed
     */
    public AiCategorySuggestionType suggestCategories(String url, String title, String description) {
        try {
            // Build category tree for prompt context
            String categoryTreeJson = buildCategoryTreeJson();

            // Build prompt
            String prompt = buildPrompt(url, title, description, categoryTreeJson);

            LOG.debugf("Sending categorization request to Claude: url=\"%s\", title=\"%s\"", url, title);

            // Call Claude via LangChain4j
            // NOTE: Uncomment when API key is configured
            // String response = chatModel.generate(prompt);

            // TODO: Remove this stub when LangChain4j is properly configured
            String response = generateStubResponse(url, title, description);

            // Parse response
            AiCategorySuggestionType suggestion = parseResponse(response);

            // Estimate token usage (rough approximation: 4 chars = 1 token)
            long estimatedInputTokens = prompt.length() / 4;
            long estimatedOutputTokens = response.length() / 4;
            int costCents = AiUsageTracking.calculateCostCents(estimatedInputTokens, estimatedOutputTokens);

            // Record usage
            AiUsageTracking.recordUsage(YearMonth.now(), PROVIDER, 1, estimatedInputTokens, estimatedOutputTokens,
                    costCents);

            LOG.debugf(
                    "Categorized site: url=\"%s\", categories=%d, confidence=%.2f, inputTokens=%d, outputTokens=%d, costCents=%d",
                    url, suggestion.suggestedCategories().size(), suggestion.confidence(), estimatedInputTokens,
                    estimatedOutputTokens, costCents);

            return suggestion;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to categorize site: url=\"%s\", title=\"%s\"", url, title);
            return null;
        }
    }

    /**
     * Builds category tree JSON for prompt context.
     *
     * <p>
     * Converts DirectoryCategory hierarchy into nested JSON structure with slug, name, description, and children. This
     * provides Claude with full taxonomy context for accurate categorization.
     *
     * @return JSON string representing category tree
     */
    private String buildCategoryTreeJson() {
        try {
            List<DirectoryCategory> allCategories = DirectoryCategory.findAllOrdered();

            // Build parent-child mapping
            Map<UUID, List<DirectoryCategory>> childrenMap = new HashMap<>();
            List<DirectoryCategory> roots = new ArrayList<>();

            for (DirectoryCategory cat : allCategories) {
                if (cat.parentId == null) {
                    roots.add(cat);
                } else {
                    childrenMap.computeIfAbsent(cat.parentId, k -> new ArrayList<>()).add(cat);
                }
            }

            // Build tree recursively
            List<Map<String, Object>> treeNodes = buildTreeNode(roots, childrenMap);

            // Convert to JSON
            return objectMapper.writeValueAsString(treeNodes);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to build category tree JSON, using fallback");
            return "[]"; // Return empty array as fallback
        }
    }

    /**
     * Recursively builds tree node structure.
     */
    private List<Map<String, Object>> buildTreeNode(List<DirectoryCategory> nodes,
            Map<UUID, List<DirectoryCategory>> childrenMap) {
        return nodes.stream().map(cat -> {
            Map<String, Object> node = new HashMap<>();
            node.put("slug", cat.slug);
            node.put("name", cat.name);
            node.put("description", cat.description != null ? cat.description : "");

            List<DirectoryCategory> children = childrenMap.get(cat.id);
            if (children != null && !children.isEmpty()) {
                node.put("children", buildTreeNode(children, childrenMap));
            }

            return node;
        }).toList();
    }

    /**
     * Builds categorization prompt for Claude Sonnet 4.
     *
     * <p>
     * Instructs model to analyze website and select 1-3 most specific categories from provided taxonomy. Emphasizes
     * preference for leaf categories over parents and requests reasoning for audit trail.
     *
     * @param url
     *            website URL
     * @param title
     *            site title
     * @param description
     *            site description
     * @param categoryTreeJson
     *            full category hierarchy as JSON
     * @return formatted prompt string
     */
    private String buildPrompt(String url, String title, String description, String categoryTreeJson) {
        return String.format(
                """
                        You are an expert web directory curator. Your task is to analyze a website and suggest the most appropriate categories from a hierarchical taxonomy.

                        TAXONOMY:
                        %s

                        WEBSITE TO CATEGORIZE:
                        URL: %s
                        Title: %s
                        Description: %s

                        INSTRUCTIONS:
                        1. Analyze the website's purpose, content, and target audience based on URL, title, and description
                        2. Select 1-3 most SPECIFIC categories from the taxonomy (prefer leaf categories over parents)
                        3. Provide clear reasoning for each category selection
                        4. Assign confidence score (0.0-1.0) based on clarity of website purpose and metadata quality

                        CONSTRAINTS:
                        - Categories MUST exist in the provided taxonomy (use exact slugs)
                        - Prefer the MOST SPECIFIC category (e.g., "computers-programming-java" over "computers-programming" or "computers")
                        - If website clearly fits multiple categories, suggest up to 3 (ordered by relevance)
                        - If purpose is ambiguous or metadata is insufficient, prefer broader categories with lower confidence
                        - If URL/title/description provide minimal information, suggest general categories with confidence < 0.5

                        OUTPUT FORMAT (strict JSON, no markdown):
                        {
                          "suggested_categories": [
                            {
                              "category_slug": "computers-programming-java",
                              "category_path": "Computers > Programming > Java",
                              "reasoning": "Website appears to be a Java-specific tutorial and documentation site based on URL and title"
                            }
                          ],
                          "confidence": 0.85,
                          "overall_reasoning": "Clear technical content focused on Java programming with specific keywords in title"
                        }

                        Respond with ONLY valid JSON (no markdown code blocks, no explanation text).
                        """,
                categoryTreeJson, url != null ? url : "", title != null ? title : "",
                description != null ? description : "");
    }

    /**
     * Parses Claude response into AiCategorySuggestionType.
     *
     * <p>
     * Handles various response formats (with/without markdown code blocks). Validates category slugs against database
     * to ensure AI didn't hallucinate invalid categories. Falls back to default values if parsing fails.
     *
     * @param response
     *            raw Claude response string
     * @return parsed category suggestions, or default values if parsing failed
     */
    private AiCategorySuggestionType parseResponse(String response) {
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

            // Parse JSON to AiCategorySuggestionType
            AiCategorySuggestionType suggestion = objectMapper.readValue(json, AiCategorySuggestionType.class);

            // Validate
            suggestion = AiCategorySuggestionType.validate(suggestion);

            // Log warning for low confidence
            if (suggestion.confidence() < 0.5) {
                LOG.warnf("Low confidence categorization: confidence=%.2f, response=%s", suggestion.confidence(),
                        response);
            }

            return suggestion;

        } catch (JsonProcessingException e) {
            LOG.errorf(e, "Failed to parse Claude response as JSON, using defaults: response=%s", response);

            // Return default suggestion (fallback to root "Computers" category)
            return new AiCategorySuggestionType(
                    List.of(new AiCategorySuggestionType.SuggestedCategory("computers", "Computers",
                            "Unable to parse AI response, using default category")),
                    0.3, "AI response parsing failed, manual categorization recommended");

        } catch (IllegalArgumentException e) {
            LOG.errorf(e, "AI suggestion validation failed, using defaults: response=%s", response);

            return new AiCategorySuggestionType(
                    List.of(new AiCategorySuggestionType.SuggestedCategory("computers", "Computers",
                            "Validation failed, using default category")),
                    0.3, "AI suggestion validation failed: " + e.getMessage());
        }
    }

    /**
     * Stub response generator for development/testing.
     *
     * <p>
     * Uses simple keyword-based heuristics to suggest categories. TODO: Remove this method when LangChain4j is properly
     * configured with Anthropic API key.
     *
     * @param url
     *            website URL
     * @param title
     *            site title
     * @param description
     *            site description
     * @return mock JSON response
     */
    private String generateStubResponse(String url, String title, String description) {
        // Simple keyword-based categorization for testing
        String categorySlug = "computers-internet";
        String categoryPath = "Computers > Internet";
        String reasoning = "Based on URL and title keywords (development stub)";
        double confidence = 0.65;

        // Combine all text for keyword matching
        String allText = (url + " " + title + " " + description).toLowerCase();

        if (allText.contains("github") || allText.contains("gitlab") || allText.contains("code")
                || allText.contains("programming")) {
            categorySlug = "computers-programming";
            categoryPath = "Computers > Programming";
            reasoning = "Contains programming/code-related keywords";
            confidence = 0.75;
        } else if (allText.contains("news") || allText.contains("breaking") || allText.contains("journalism")) {
            categorySlug = "news-technology";
            categoryPath = "News > Technology";
            reasoning = "Contains news-related keywords";
            confidence = 0.70;
        } else if (allText.contains("business") || allText.contains("startup") || allText.contains("commerce")) {
            categorySlug = "business-ecommerce";
            categoryPath = "Business > E-commerce";
            reasoning = "Contains business-related keywords";
            confidence = 0.70;
        } else if (allText.contains("science") || allText.contains("research") || allText.contains("academic")) {
            categorySlug = "science-research";
            categoryPath = "Science > Research";
            reasoning = "Contains science/research keywords";
            confidence = 0.70;
        }

        return String.format(
                """
                        {
                          "suggested_categories": [
                            {
                              "category_slug": "%s",
                              "category_path": "%s",
                              "reasoning": "%s"
                            }
                          ],
                          "confidence": %.2f,
                          "overall_reasoning": "Stub categorization based on keyword matching (replace with real AI when configured)"
                        }
                        """,
                categorySlug, categoryPath, reasoning, confidence);
    }
}
