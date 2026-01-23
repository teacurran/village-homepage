/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.services;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.model.chat.ChatModel;
import io.quarkus.narayana.jta.QuarkusTransaction;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import villagecompute.homepage.api.types.AiCategorySuggestionType;
import villagecompute.homepage.api.types.ListingCategorizationResultType;
import villagecompute.homepage.data.models.AiUsageTracking;
import villagecompute.homepage.data.models.DirectoryCategory;
import villagecompute.homepage.data.models.MarketplaceListing;

/**
 * Service for AI-powered category suggestion using LangChain4j and Anthropic Claude.
 *
 * <p>
 * This service provides AI-powered categorization for two features:
 * <ul>
 * <li><b>Good Sites Directory:</b> Analyzes website metadata and suggests categories from hierarchical directory
 * taxonomy
 * <li><b>Marketplace Listings:</b> Analyzes listing title/description and suggests category from flat marketplace
 * taxonomy
 * </ul>
 *
 * <p>
 * <b>Directory Categorization Process:</b>
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
 * <b>Marketplace Categorization Process:</b>
 * <ol>
 * <li>Batch up to 50 listings per API call for cost efficiency</li>
 * <li>Construct prompt with flat marketplace taxonomy and listing data</li>
 * <li>Send to Claude Sonnet 4 via LangChain4j</li>
 * <li>Parse JSON array response into {@link ListingCategorizationResultType} records</li>
 * <li>Store suggestions in JSONB field for human review (not auto-applied)</li>
 * <li>Flag low confidence results (<0.7) for manual review</li>
 * </ol>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P2/P10 (AI Budget Control): All categorization operations respect budget limits via AiTaggingBudgetService</li>
 * <li>F12.4 (Marketplace): AI category suggestions stored in JSONB for review before applying</li>
 * <li>F13.14 (Bulk Import): Provides confidence scores and reasoning for admin review workflow</li>
 * </ul>
 *
 * @see AiCategorySuggestionType
 * @see ListingCategorizationResultType
 * @see AiUsageTracking
 * @see AiTaggingBudgetService
 * @see villagecompute.homepage.jobs.BulkImportJobHandler
 * @see villagecompute.homepage.jobs.AiCategorizationJobHandler
 */
@ApplicationScoped
public class AiCategorizationService {

    private static final Logger LOG = Logger.getLogger(AiCategorizationService.class);

    private static final String PROVIDER = AiUsageTracking.DEFAULT_PROVIDER;
    private static final int MARKETPLACE_BATCH_SIZE = 50;

    @Inject
    ChatModel chatModel;

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
            String response = chatModel.chat(prompt);

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

    // ========================================
    // Marketplace Listing Categorization
    // ========================================

    /**
     * Categorizes a single marketplace listing into appropriate category and subcategory.
     *
     * <p>
     * This method is a convenience wrapper around {@link #categorizeListingsBatch(List)} for single-listing
     * categorization. For efficiency, prefer batch processing when categorizing multiple listings.
     *
     * @param listing
     *            the marketplace listing to categorize
     * @return categorization result, or null if categorization failed
     */
    public ListingCategorizationResultType categorizeListing(MarketplaceListing listing) {
        if (listing == null) {
            LOG.warn("Attempted to categorize null listing");
            return null;
        }

        List<ListingCategorizationResultType> results = categorizeListingsBatch(List.of(listing));
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * Categorizes multiple marketplace listings in batch (up to 50 per API call).
     *
     * <p>
     * This method processes listings in batches to minimize API costs per P2/P10 budget policy. Each batch constructs a
     * single prompt with all listings and parses the JSON array response.
     *
     * <p>
     * <b>Batch Processing:</b>
     * <ul>
     * <li>Processes up to 50 listings per API call
     * <li>Constructs single prompt with indexed listings
     * <li>Parses JSON array response with one result per listing
     * <li>Tracks token usage and cost per batch
     * </ul>
     *
     * <p>
     * <b>Low Confidence Handling:</b> Results with confidence < 0.7 are logged as warnings and should be flagged for
     * human review before applying category.
     *
     * @param listings
     *            list of marketplace listings to categorize (max 50 per call recommended)
     * @return list of categorization results (one per listing), or empty list if batch failed
     */
    public List<ListingCategorizationResultType> categorizeListingsBatch(List<MarketplaceListing> listings) {
        if (listings == null || listings.isEmpty()) {
            return List.of();
        }

        // Split into batches of 50
        List<ListingCategorizationResultType> allResults = new ArrayList<>();

        for (int i = 0; i < listings.size(); i += MARKETPLACE_BATCH_SIZE) {
            int end = Math.min(i + MARKETPLACE_BATCH_SIZE, listings.size());
            List<MarketplaceListing> batch = listings.subList(i, end);

            try {
                // Build batch prompt
                String prompt = buildMarketplacePrompt(batch);

                LOG.debugf("Sending marketplace categorization request for %d listings", batch.size());

                // Call Claude via LangChain4j
                String response = chatModel.chat(prompt);

                // Parse response
                List<ListingCategorizationResultType> batchResults = parseMarketplaceResponse(response, batch.size());

                // Track token usage
                long estimatedInputTokens = prompt.length() / 4;
                long estimatedOutputTokens = response.length() / 4;
                int costCents = AiUsageTracking.calculateCostCents(estimatedInputTokens, estimatedOutputTokens);

                AiUsageTracking.recordUsage(YearMonth.now(), PROVIDER, batch.size(), estimatedInputTokens,
                        estimatedOutputTokens, costCents);

                LOG.debugf("Categorized %d listings, cost=%d cents, inputTokens=%d, outputTokens=%d",
                        batchResults.size(), costCents, estimatedInputTokens, estimatedOutputTokens);

                allResults.addAll(batchResults);

            } catch (Exception e) {
                LOG.errorf(e, "Failed to categorize marketplace listings batch (size=%d)", batch.size());
                // Add nulls for failed batch
                allResults.addAll(Collections.nCopies(batch.size(), null));
            }
        }

        return allResults;
    }

    /**
     * Builds marketplace categorization prompt for Claude.
     *
     * <p>
     * Constructs a prompt with flat marketplace taxonomy (5 categories with subcategories) and listing data indexed for
     * correlation with response array.
     *
     * @param listings
     *            batch of listings to categorize
     * @return formatted prompt string
     */
    private String buildMarketplacePrompt(List<MarketplaceListing> listings) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("""
                Categorize these marketplace listings into the appropriate category and subcategory.

                CATEGORY TAXONOMY:
                1. For Sale
                   - Electronics (computers, phones, cameras, gaming, tech)
                   - Furniture (tables, chairs, beds, sofas, home furnishings)
                   - Clothing (shirts, pants, shoes, accessories, fashion)
                   - Books (textbooks, novels, magazines, comics)
                   - Sporting Goods (bikes, gym equipment, outdoor gear, sports)
                   - Other (general items that don't fit above)

                2. Housing
                   - Rent (apartments, rooms for rent, rentals)
                   - Sale (houses, condos for sale, real estate)
                   - Roommates (seeking/offering roommate, shared housing)
                   - Sublets (temporary housing, short-term rentals)

                3. Jobs
                   - Full-time (permanent, full-time employment)
                   - Part-time (flexible hours, part-time work)
                   - Contract (project-based, fixed term, freelance)
                   - Internship (student positions, training, co-op)

                4. Services
                   - Professional (legal, accounting, consulting, business)
                   - Personal (cleaning, tutoring, personal training, care)
                   - Home Improvement (repairs, renovations, landscaping, maintenance)

                5. Community
                   - Events (meetups, workshops, concerts, gatherings)
                   - Activities (sports groups, book clubs, hobbies)
                   - Rideshare (carpools, travel companions, transport)
                   - Lost & Found (lost items, found pets, reunions)

                INSTRUCTIONS:
                - Select the MOST SPECIFIC subcategory that fits
                - Provide brief reasoning (1-2 sentences explaining your choice)
                - Assign confidence score 0.0-1.0 based on clarity of listing
                - If listing is ambiguous or doesn't clearly fit, use lower confidence

                LISTINGS TO CATEGORIZE:
                """);

        for (int i = 0; i < listings.size(); i++) {
            MarketplaceListing listing = listings.get(i);
            String title = listing.title != null ? listing.title : "";
            String description = listing.description != null ? listing.description : "";

            // Truncate description if too long to save tokens
            if (description.length() > 500) {
                description = description.substring(0, 500) + "...";
            }

            prompt.append(String.format("""

                    [%d] Title: %s
                        Description: %s
                    """, i, title, description));
        }

        prompt.append(
                """

                        Respond with ONLY a JSON array (no markdown, no explanation):
                        [
                          {"index": 0, "category": "For Sale", "subcategory": "Electronics", "confidenceScore": 0.95, "reasoning": "..."},
                          {"index": 1, "category": "Housing", "subcategory": "Rent", "confidenceScore": 0.88, "reasoning": "..."}
                        ]
                        """);

        return prompt.toString();
    }

    /**
     * Parses Claude response for marketplace categorization.
     *
     * <p>
     * Expects JSON array with one object per listing. Handles markdown code blocks and validates structure. Falls back
     * to defaults if parsing fails. Flags low confidence results (<0.7) with warnings.
     *
     * @param response
     *            raw Claude response string
     * @param expectedCount
     *            number of listings in batch
     * @return list of categorization results (padded to expectedCount if needed)
     */
    private List<ListingCategorizationResultType> parseMarketplaceResponse(String response, int expectedCount) {
        try {
            // Strip markdown code blocks if present
            String json = response.trim();
            if (json.startsWith("```json")) {
                json = json.substring(7);
            } else if (json.startsWith("```")) {
                json = json.substring(3);
            }
            if (json.endsWith("```")) {
                json = json.substring(0, json.length() - 3);
            }
            json = json.trim();

            // Parse JSON array
            List<Map<String, Object>> rawResults = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {
                    });

            List<ListingCategorizationResultType> results = new ArrayList<>();

            for (Map<String, Object> raw : rawResults) {
                String category = (String) raw.get("category");
                String subcategory = (String) raw.get("subcategory");
                Object confObj = raw.get("confidenceScore");
                Double confidenceScore = confObj instanceof Number ? ((Number) confObj).doubleValue() : 0.3;
                String reasoning = (String) raw.get("reasoning");

                // Validate required fields
                if (category == null || subcategory == null || reasoning == null) {
                    LOG.warnf("Incomplete categorization result: category=%s, subcategory=%s", category, subcategory);
                    results.add(new ListingCategorizationResultType("For Sale", "Other", 0.3,
                            "AI response incomplete, using default category"));
                } else {
                    ListingCategorizationResultType result = new ListingCategorizationResultType(category, subcategory,
                            confidenceScore, reasoning);
                    results.add(result);

                    // Flag low confidence
                    if (confidenceScore < 0.7) {
                        LOG.warnf(
                                "Low confidence marketplace categorization: confidence=%.2f, category=%s/%s, reasoning=%s",
                                confidenceScore, category, subcategory, reasoning);
                    }
                }
            }

            // Pad to expected count if response was short
            while (results.size() < expectedCount) {
                results.add(new ListingCategorizationResultType("For Sale", "Other", 0.3,
                        "AI response incomplete, using default category"));
            }

            return results;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to parse marketplace categorization response: response=%s", response);
            // Return defaults for all listings
            return Collections.nCopies(expectedCount,
                    new ListingCategorizationResultType("For Sale", "Other", 0.3, "AI response parsing failed"));
        }
    }

    /**
     * Stores AI category suggestion in marketplace listing's JSONB field.
     *
     * <p>
     * This method persists the categorization result to the {@code ai_category_suggestion} JSONB column. The suggestion
     * is NOT applied to {@code category_id} - it requires human review first.
     *
     * @param listing
     *            the marketplace listing
     * @param result
     *            the categorization result to store
     */
    public void storeCategorizationSuggestion(MarketplaceListing listing, ListingCategorizationResultType result) {
        if (listing == null || result == null) {
            LOG.warn("Attempted to store null categorization suggestion");
            return;
        }

        try {
            QuarkusTransaction.requiringNew().run(() -> {
                // Refetch to ensure managed
                MarketplaceListing managed = MarketplaceListing.findById(listing.id);
                if (managed == null) {
                    LOG.warnf("Listing not found when storing categorization: id=%s", listing.id);
                    return;
                }

                managed.aiCategorySuggestion = result;
                managed.persist();

                LOG.debugf(
                        "Stored AI category suggestion for listing: id=%s, category=%s/%s, confidence=%.2f, reasoning=%s",
                        managed.id, result.category(), result.subcategory(), result.confidenceScore(),
                        result.reasoning());
            });
        } catch (Exception e) {
            LOG.errorf(e, "Failed to store categorization suggestion for listing: id=%s", listing.id);
        }
    }
}
