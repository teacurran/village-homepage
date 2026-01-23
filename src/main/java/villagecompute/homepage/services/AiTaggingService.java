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

import dev.langchain4j.model.chat.ChatModel;

import io.smallrye.faulttolerance.api.CircuitBreakerName;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;

import villagecompute.homepage.api.types.AiTagsType;
import villagecompute.homepage.api.types.FeedItemTaggingResultType;
import villagecompute.homepage.config.AiCacheConfig;
import villagecompute.homepage.data.models.AiUsageTracking;
import villagecompute.homepage.data.models.FeedItem;

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

    @Inject
    @Named("haiku")
    ChatModel chatModel; // Use Haiku model for bulk tagging (10x cheaper)

    @Inject
    ObjectMapper objectMapper;

    @Inject
    AiCacheConfig cacheConfig;

    @Inject
    AiUsageTrackingService usageTrackingService;

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
            String response = chatModel.chat(prompt);

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
     * Tags a single feed item and returns structured tagging result.
     *
     * <p>
     * This method is the primary interface for tagging operations, returning {@link FeedItemTaggingResultType} which
     * includes summary instead of sentiment. Use this for single-item tagging when batch processing is not required.
     *
     * <p>
     * <b>Caching:</b> Results are cached by content hash (SHA-256) for 30 days to reduce AI API costs. Cache hit rate
     * target: &gt;50% (Feature I4.T6).
     *
     * <p>
     * <b>Circuit Breaker:</b> Opens after 5 consecutive failures, half-open after 30s, returns null on fallback.
     *
     * @param item
     *            the feed item to tag
     * @return tagging result with topics, summary, categories, and confidence, or null if tagging failed
     */
    @CircuitBreaker(
            requestVolumeThreshold = 5,
            failureRatio = 1.0,
            delay = 30000,
            successThreshold = 2)
    @CircuitBreakerName("ai-api-circuit-breaker")
    @Fallback(
            fallbackMethod = "tagFeedItemFallback")
    @Timeout(60000)
    public FeedItemTaggingResultType tagFeedItem(FeedItem item) {
        if (item == null) {
            LOG.warn("Cannot tag null feed item");
            return null;
        }

        // Generate content hash for cache key
        String content = (item.title != null ? item.title : "") + (item.description != null ? item.description : "")
                + (item.content != null ? item.content : "");
        String contentHash = cacheConfig.generateContentHash(content);

        // Check cache first
        FeedItemTaggingResultType cached = cacheConfig.getTaggingResult(contentHash);
        if (cached != null) {
            LOG.debugf("Cache HIT for feed item: id=%s, title=\"%s\", hash=%s", item.id, item.title, contentHash);
            usageTrackingService.recordCacheHit();
            return cached;
        }

        // Cache MISS - call AI API
        usageTrackingService.recordCacheMiss();
        LOG.debugf("Cache MISS for feed item: id=%s, title=\"%s\", hash=%s", item.id, item.title, contentHash);

        try {
            // Build prompt for new schema (with summary instead of sentiment)
            String prompt = buildBatchPrompt(item.title, item.description, item.content);

            LOG.debugf("Sending tagging request to Claude Haiku: title=\"%s\"", item.title);

            // Call Claude via LangChain4j (using Haiku model for cost efficiency)
            String response = chatModel.chat(prompt);

            // Parse response for new schema
            FeedItemTaggingResultType result = parseTaggingResponse(response);

            // Estimate token usage (rough approximation: 4 chars = 1 token)
            long estimatedInputTokens = prompt.length() / 4;
            long estimatedOutputTokens = response.length() / 4;

            // Record usage with Haiku model
            usageTrackingService.logUsage("tagging", "claude-3-haiku-20240307", (int) estimatedInputTokens,
                    (int) estimatedOutputTokens);

            LOG.debugf(
                    "Tagged feed item: title=\"%s\", topics=%s, categories=%s, confidence=%.2f, "
                            + "inputTokens=%d, outputTokens=%d",
                    item.title, result.topics(), result.categories(), result.confidenceScore(), estimatedInputTokens,
                    estimatedOutputTokens);

            // Store in cache
            cacheConfig.putTaggingResult(contentHash, result);

            return result;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to tag feed item: id=%s, title=\"%s\"", item.id, item.title);
            throw new RuntimeException("AI tagging failed", e); // Trigger circuit breaker
        }
    }

    /**
     * Fallback method when circuit breaker is OPEN or AI API fails.
     *
     * @param item
     *            the feed item to tag
     * @return null (tagging unavailable)
     */
    public FeedItemTaggingResultType tagFeedItemFallback(FeedItem item) {
        LOG.warnf("Circuit breaker OPEN or AI API unavailable - skipping tagging for item: id=%s, title=\"%s\"",
                item.id, item.title);
        return null;
    }

    /**
     * Tags multiple feed items in a single batch API call for token efficiency.
     *
     * <p>
     * This method constructs a single prompt containing up to 20 items and sends one API request. For lists larger than
     * 20 items, they are automatically split into sub-batches. This approach reduces token overhead and API costs per
     * P2/P10 budget policy.
     *
     * <p>
     * <b>Batch Processing Behavior:</b>
     * <ul>
     * <li>Items &lt;= 20: Single API call</li>
     * <li>Items &gt; 20: Split into batches of 20, process sequentially</li>
     * <li>Failed items: Return null in result list, continue with remaining items</li>
     * <li>Partial failures: Successfully tagged items are returned, failed items are null</li>
     * </ul>
     *
     * @param items
     *            list of feed items to tag (up to 20 per API call)
     * @return list of tagging results (null entries for failed items), or empty list if input is invalid
     */
    public List<FeedItemTaggingResultType> tagFeedItemsBatch(List<FeedItem> items) {
        if (items == null || items.isEmpty()) {
            LOG.debug("Empty or null items list, returning empty results");
            return List.of();
        }

        // If more than 20 items, split into sub-batches
        if (items.size() > 20) {
            LOG.debugf("Splitting %d items into batches of 20", items.size());
            List<FeedItemTaggingResultType> allResults = new java.util.ArrayList<>();

            for (int i = 0; i < items.size(); i += 20) {
                int endIndex = Math.min(i + 20, items.size());
                List<FeedItem> subBatch = items.subList(i, endIndex);
                List<FeedItemTaggingResultType> subResults = tagFeedItemsBatch(subBatch);
                allResults.addAll(subResults);
            }

            return allResults;
        }

        try {
            // Build batch prompt with indices
            String prompt = buildBatchPromptForMultiple(items);

            LOG.debugf("Sending batch tagging request to Claude: %d items", items.size());

            // Call Claude via LangChain4j (single API call for entire batch)
            String response = chatModel.chat(prompt);

            // Parse batch response (JSON array)
            List<FeedItemTaggingResultType> results = parseBatchResponse(response, items.size());

            // Estimate token usage
            long estimatedInputTokens = prompt.length() / 4;
            long estimatedOutputTokens = response.length() / 4;
            int costCents = AiUsageTracking.calculateCostCents(estimatedInputTokens, estimatedOutputTokens);

            // Record usage (1 request for entire batch)
            AiUsageTracking.recordUsage(YearMonth.now(), PROVIDER, 1, estimatedInputTokens, estimatedOutputTokens,
                    costCents);

            LOG.debugf("Tagged batch: items=%d, successfulTags=%d, inputTokens=%d, outputTokens=%d, costCents=%d",
                    items.size(), results.stream().filter(r -> r != null).count(), estimatedInputTokens,
                    estimatedOutputTokens, costCents);

            return results;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to tag batch of %d items, returning null results", items.size());
            // Return list of nulls (same size as input) so caller can identify failed items
            return java.util.Collections.nCopies(items.size(), null);
        }
    }

    /**
     * Persists tagging result to database by mapping to {@link AiTagsType}.
     *
     * <p>
     * This method bridges the new tagging schema (with summary) to the existing persistent schema (with sentiment). The
     * sentiment field is set to "neutral" as a default since it's not included in the tagging result.
     *
     * @param item
     *            the feed item to update
     * @param result
     *            the tagging result to persist
     */
    public void storeFeedItemTags(FeedItem item, FeedItemTaggingResultType result) {
        if (item == null || result == null) {
            LOG.warn("Cannot store tags: item or result is null");
            return;
        }

        try {
            // Map FeedItemTaggingResultType to AiTagsType (with default sentiment)
            AiTagsType aiTags = new AiTagsType(result.topics(), "neutral", // Default sentiment
                    result.categories(), result.confidenceScore());

            // Persist via existing method
            FeedItem.updateAiTags(item, aiTags);

            LOG.debugf("Stored tags for feed item: id=%s, topics=%d, categories=%d, confidence=%.2f", item.id,
                    result.topics().size(), result.categories().size(), result.confidenceScore());

        } catch (Exception e) {
            LOG.errorf(e, "Failed to store tags for item: id=%s", item.id);
        }
    }

    /**
     * Builds prompt for new tagging schema (with summary instead of sentiment).
     *
     * <p>
     * This prompt targets the updated category list from task specification: Tech News, Politics, Sports,
     * Entertainment, Science, Business, Health, Lifestyle.
     *
     * @param title
     *            article title
     * @param description
     *            article description
     * @param content
     *            article content (will be truncated)
     * @return formatted prompt string
     */
    private String buildBatchPrompt(String title, String description, String content) {
        // Truncate content if too long
        String contentSnippet = content;
        if (content != null && content.length() > MAX_CONTENT_LENGTH) {
            contentSnippet = content.substring(0, MAX_CONTENT_LENGTH) + "...";
        }

        return String.format("""
                Analyze this news article and extract structured metadata.

                INSTRUCTIONS:
                1. Extract 3-5 main topics/keywords from the content
                2. Write a brief 1-2 sentence summary of the article
                3. Assign 1-3 categories from this list ONLY:
                   - Tech News
                   - Politics
                   - Sports
                   - Entertainment
                   - Science
                   - Business
                   - Health
                   - Lifestyle
                4. Assign confidence score (0.0-1.0) for tagging quality

                ARTICLE:
                Title: %s
                Description: %s
                Content: %s

                Respond with ONLY valid JSON (no markdown, no explanation):
                {
                  "topics": ["topic1", "topic2", "topic3"],
                  "summary": "Brief 1-2 sentence summary of the article.",
                  "categories": ["Tech News", "Business"],
                  "confidenceScore": 0.95
                }
                """, title != null ? title : "", description != null ? description : "",
                contentSnippet != null ? contentSnippet : "");
    }

    /**
     * Builds batch prompt for multiple items with indices.
     *
     * @param items
     *            list of feed items (up to 20)
     * @return formatted batch prompt string
     */
    private String buildBatchPromptForMultiple(List<FeedItem> items) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Analyze these news articles and extract structured metadata for each.\n\n");
        prompt.append("INSTRUCTIONS:\n");
        prompt.append("1. Extract 3-5 main topics/keywords from the content\n");
        prompt.append("2. Write a brief 1-2 sentence summary of the article\n");
        prompt.append("3. Assign 1-3 categories from this list ONLY:\n");
        prompt.append("   - Tech News\n");
        prompt.append("   - Politics\n");
        prompt.append("   - Sports\n");
        prompt.append("   - Entertainment\n");
        prompt.append("   - Science\n");
        prompt.append("   - Business\n");
        prompt.append("   - Health\n");
        prompt.append("   - Lifestyle\n");
        prompt.append("4. Assign confidence score (0.0-1.0) for tagging quality\n\n");
        prompt.append("ARTICLES:\n");

        for (int i = 0; i < items.size(); i++) {
            FeedItem item = items.get(i);
            String contentSnippet = item.content;
            if (contentSnippet != null && contentSnippet.length() > MAX_CONTENT_LENGTH) {
                contentSnippet = contentSnippet.substring(0, MAX_CONTENT_LENGTH) + "...";
            }

            prompt.append(String.format("[%d] Title: %s\n", i, item.title != null ? item.title : ""));
            prompt.append(String.format("    Description: %s\n", item.description != null ? item.description : ""));
            prompt.append(String.format("    Content: %s\n\n", contentSnippet != null ? contentSnippet : ""));
        }

        prompt.append("Respond with a JSON array (one object per article, indexed 0-" + (items.size() - 1) + "):\n");
        prompt.append("[\n");
        prompt.append(
                "  {\"index\": 0, \"topics\": [...], \"summary\": \"...\", \"categories\": [...], \"confidenceScore\": 0.95},\n");
        prompt.append(
                "  {\"index\": 1, \"topics\": [...], \"summary\": \"...\", \"categories\": [...], \"confidenceScore\": 0.92}\n");
        prompt.append("]");

        return prompt.toString();
    }

    /**
     * Parses single-item tagging response into {@link FeedItemTaggingResultType}.
     *
     * @param response
     *            raw Claude response string
     * @return parsed tagging result, or default values if parsing failed
     */
    private FeedItemTaggingResultType parseTaggingResponse(String response) {
        try {
            // Strip markdown code blocks if present
            String json = stripMarkdown(response);

            // Parse JSON to FeedItemTaggingResultType
            FeedItemTaggingResultType result = objectMapper.readValue(json, FeedItemTaggingResultType.class);

            // Validate and apply defaults
            result = validateAndDefaultTaggingResult(result);

            // Log warning for low confidence
            if (result.confidenceScore() < 0.5) {
                LOG.warnf("Low confidence tagging: confidence=%.2f, response=%s", result.confidenceScore(), response);
            }

            return result;

        } catch (JsonProcessingException e) {
            LOG.errorf(e, "Failed to parse Claude response as JSON, using defaults: response=%s", response);
            // Return default tagging result on parse failure
            return new FeedItemTaggingResultType(List.of("Uncategorized"), "No summary available.",
                    List.of("Lifestyle"), 0.3);
        }
    }

    /**
     * Parses batch response (JSON array) into list of tagging results.
     *
     * @param response
     *            raw Claude response string
     * @param expectedSize
     *            expected number of results
     * @return list of tagging results (null entries for failed items)
     */
    private List<FeedItemTaggingResultType> parseBatchResponse(String response, int expectedSize) {
        try {
            // Strip markdown code blocks if present
            String json = stripMarkdown(response);

            // Parse JSON array
            FeedItemTaggingResultType[] resultsArray = objectMapper.readValue(json, FeedItemTaggingResultType[].class);

            // Convert to list and validate each result
            List<FeedItemTaggingResultType> results = new java.util.ArrayList<>();
            for (int i = 0; i < expectedSize; i++) {
                if (i < resultsArray.length) {
                    FeedItemTaggingResultType result = validateAndDefaultTaggingResult(resultsArray[i]);
                    results.add(result);
                } else {
                    // Missing result for this item
                    LOG.warnf("Missing result for item index %d in batch response", i);
                    results.add(null);
                }
            }

            return results;

        } catch (JsonProcessingException e) {
            LOG.errorf(e, "Failed to parse batch response as JSON array, returning null results: response=%s",
                    response);
            // Return list of nulls
            return java.util.Collections.nCopies(expectedSize, null);
        }
    }

    /**
     * Strips markdown code blocks from Claude response.
     *
     * @param response
     *            raw response string
     * @return cleaned JSON string
     */
    private String stripMarkdown(String response) {
        String json = response.trim();
        if (json.startsWith("```json")) {
            json = json.substring(7); // Remove ```json
        } else if (json.startsWith("```")) {
            json = json.substring(3); // Remove ```
        }
        if (json.endsWith("```")) {
            json = json.substring(0, json.length() - 3);
        }
        return json.trim();
    }

    /**
     * Validates tagging result and applies defaults for missing/invalid fields.
     *
     * @param result
     *            the result to validate
     * @return validated result with defaults applied
     */
    private FeedItemTaggingResultType validateAndDefaultTaggingResult(FeedItemTaggingResultType result) {
        if (result == null) {
            return new FeedItemTaggingResultType(List.of("Uncategorized"), "No summary available.",
                    List.of("Lifestyle"), 0.3);
        }

        List<String> topics = result.topics();
        if (topics == null || topics.isEmpty()) {
            topics = List.of("Uncategorized");
        }

        String summary = result.summary();
        if (summary == null || summary.isBlank()) {
            summary = "No summary available.";
        }

        List<String> categories = result.categories();
        if (categories == null || categories.isEmpty()) {
            categories = List.of("Lifestyle");
        }

        Double confidenceScore = result.confidenceScore();
        if (confidenceScore == null || confidenceScore < 0.0 || confidenceScore > 1.0) {
            confidenceScore = 0.5;
        }

        return new FeedItemTaggingResultType(topics, summary, categories, confidenceScore);
    }
}
