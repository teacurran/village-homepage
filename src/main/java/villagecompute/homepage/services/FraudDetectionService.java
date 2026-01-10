package villagecompute.homepage.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.FraudAnalysisResultType;
import villagecompute.homepage.data.models.AiUsageTracking;
import villagecompute.homepage.data.models.MarketplaceListing;
import villagecompute.homepage.data.models.MarketplaceCategory;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service for AI-powered fraud detection in marketplace listings.
 *
 * <p>
 * This service analyzes listing content for scam indicators, prohibited items, misleading claims, and spam patterns. It
 * integrates with {@link AiTaggingBudgetService} to respect P2/P10 budget limits, falling back to rule-based detection
 * when AI budget is exhausted.
 *
 * <p>
 * <b>Detection Process:</b>
 * <ol>
 * <li>Check AI budget via {@link AiTaggingBudgetService#getCurrentBudgetAction()}</li>
 * <li>If budget allows (NORMAL/REDUCE), use AI analysis with Claude</li>
 * <li>If budget exhausted (QUEUE/HARD_STOP), use rule-based keyword matching</li>
 * <li>Return {@link FraudAnalysisResultType} with fraud score and reasons</li>
 * </ol>
 *
 * <p>
 * <b>Budget Allocation:</b> Fraud detection receives 30% ($150/month) of total AI budget, with priority over Good Sites
 * categorization but below feed tagging. When budget enters REDUCE state, fraud detection continues at normal rate
 * (safety priority).
 *
 * @see FraudAnalysisResultType
 * @see AiTaggingBudgetService
 * @see villagecompute.homepage.data.models.ListingFlag
 */
@ApplicationScoped
public class FraudDetectionService {

    private static final Logger LOG = Logger.getLogger(FraudDetectionService.class);

    private static final String PROVIDER = AiUsageTracking.DEFAULT_PROVIDER;
    private static final String PROMPT_VERSION = "v1.0";

    // Content truncation to avoid exceeding token limits
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_DESCRIPTION_LENGTH = 2000;

    @Inject
    AiTaggingBudgetService budgetService;

    @Inject
    ObjectMapper objectMapper;

    // NOTE: LangChain4j integration - uncomment when anthropic API key is configured
    // @Inject
    // ChatLanguageModel chatModel;

    /**
     * Analyzes a marketplace listing for fraud indicators.
     *
     * <p>
     * Checks AI budget first. If budget allows, uses Claude for deep analysis. Otherwise, falls back to rule-based
     * keyword matching.
     *
     * @param listingId
     *            the listing UUID to analyze
     * @return fraud analysis result with score and reasons
     */
    public FraudAnalysisResultType analyzeListing(UUID listingId) {
        MarketplaceListing listing = MarketplaceListing.findById(listingId);
        if (listing == null) {
            LOG.warnf("Cannot analyze listing %s - not found", listingId);
            return FraudAnalysisResultType.clean(PROMPT_VERSION);
        }

        return analyzeListingContent(listing.title, listing.description, listing.price,
                getCategoryName(listing.categoryId));
    }

    /**
     * Analyzes listing content for fraud indicators.
     *
     * <p>
     * This is the main analysis method that handles budget checking and routing to AI or rule-based detection.
     *
     * @param title
     *            listing title
     * @param description
     *            listing description
     * @param price
     *            listing price (may be null for contact-for-price)
     * @param categoryName
     *            category name for context
     * @return fraud analysis result
     */
    public FraudAnalysisResultType analyzeListingContent(String title, String description, BigDecimal price,
            String categoryName) {
        // 1. Check budget first
        BudgetAction budgetAction = budgetService.getCurrentBudgetAction();

        if (budgetService.shouldStopProcessing(budgetAction)) {
            LOG.warnf("AI budget exhausted (action=%s), using rule-based fraud detection for listing: %s", budgetAction,
                    title);
            return performRuleBasedAnalysis(title, description, price, categoryName);
        }

        // 2. AI budget available - use Claude for analysis
        try {
            return performAiAnalysis(title, description, price, categoryName);
        } catch (Exception e) {
            LOG.errorf(e, "AI fraud analysis failed, falling back to rules: %s", title);
            return performRuleBasedAnalysis(title, description, price, categoryName);
        }
    }

    /**
     * Performs AI-based fraud analysis using Claude.
     *
     * @param title
     *            listing title
     * @param description
     *            listing description
     * @param price
     *            listing price
     * @param categoryName
     *            category name
     * @return AI analysis result
     */
    private FraudAnalysisResultType performAiAnalysis(String title, String description, BigDecimal price,
            String categoryName) {
        // Build prompt
        String prompt = buildFraudPrompt(title, description, price, categoryName);

        LOG.debugf("Sending fraud detection request to Claude: title=\"%s\"", title);

        // Call Claude via LangChain4j
        // NOTE: Uncomment when API key is configured
        // String response = chatModel.generate(prompt);

        // TODO: Remove this stub when LangChain4j is properly configured
        String response = generateStubResponse(title, description, price);

        // Parse response
        FraudAnalysisResultType result = parseAiResponse(response);

        // Estimate token usage (rough approximation: 4 chars = 1 token)
        long estimatedInputTokens = prompt.length() / 4;
        long estimatedOutputTokens = response.length() / 4;
        int costCents = AiUsageTracking.calculateCostCents(estimatedInputTokens, estimatedOutputTokens);

        // Record usage
        AiUsageTracking.recordUsage(YearMonth.now(), PROVIDER, 1, estimatedInputTokens, estimatedOutputTokens,
                costCents);

        LOG.debugf(
                "AI fraud analysis complete: title=\"%s\", suspicious=%s, confidence=%.2f, "
                        + "inputTokens=%d, outputTokens=%d, costCents=%d",
                title, result.isSuspicious(), result.confidence(), estimatedInputTokens, estimatedOutputTokens,
                costCents);

        return result;
    }

    /**
     * Builds fraud detection prompt for Claude.
     *
     * @param title
     *            listing title
     * @param description
     *            listing description
     * @param price
     *            listing price
     * @param categoryName
     *            category name
     * @return formatted prompt string
     */
    private String buildFraudPrompt(String title, String description, BigDecimal price, String categoryName) {
        // Truncate if too long
        String titleSnippet = title != null && title.length() > MAX_TITLE_LENGTH
                ? title.substring(0, MAX_TITLE_LENGTH) + "..."
                : title;
        String descSnippet = description != null && description.length() > MAX_DESCRIPTION_LENGTH
                ? description.substring(0, MAX_DESCRIPTION_LENGTH) + "..."
                : description;

        String priceStr = price != null ? "$" + price : "Contact for price";

        return String.format("""
                Analyze this marketplace listing for potential fraud or policy violations.

                LISTING DETAILS:
                Title: %s
                Description: %s
                Price: %s
                Category: %s

                CHECK FOR:
                1. Scam indicators:
                   - Too-good-to-be-true prices
                   - Urgency tactics ("act now", "limited time")
                   - Requests for wire transfer, Western Union, gift cards
                   - Broken English or suspicious grammar
                   - Vague or generic descriptions
                2. Prohibited items:
                   - Weapons, drugs, prescription medications
                   - Adult content or escort services
                   - Counterfeit goods
                   - Hazardous materials
                3. Duplicate/spam content:
                   - Excessive capitalization or punctuation
                   - Repeated text patterns
                   - Generic template language
                4. Misleading or false claims:
                   - "Work from home" schemes
                   - Pyramid schemes or MLM
                   - Unrealistic income promises
                   - Fake certifications or credentials

                Respond with ONLY valid JSON in this exact format (no markdown, no explanation):
                {
                  "is_suspicious": true,
                  "confidence": 0.85,
                  "reasons": ["Suspicious payment method mentioned", "Urgency tactics detected"]
                }

                If no fraud detected:
                {
                  "is_suspicious": false,
                  "confidence": 0.95,
                  "reasons": []
                }
                """, titleSnippet != null ? titleSnippet : "", descSnippet != null ? descSnippet : "", priceStr,
                categoryName != null ? categoryName : "Uncategorized");
    }

    /**
     * Parses Claude response into FraudAnalysisResultType.
     *
     * @param response
     *            raw Claude response string
     * @return parsed fraud analysis result
     */
    private FraudAnalysisResultType parseAiResponse(String response) {
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

            // Parse JSON to intermediate record for validation
            AiResponseType aiResponse = objectMapper.readValue(json, AiResponseType.class);

            // Validate and build result
            boolean suspicious = aiResponse.is_suspicious != null && aiResponse.is_suspicious;
            BigDecimal confidence = aiResponse.confidence != null ? aiResponse.confidence : BigDecimal.valueOf(0.5);
            List<String> reasons = aiResponse.reasons != null ? aiResponse.reasons : List.of();

            if (suspicious) {
                return FraudAnalysisResultType.suspicious(confidence, reasons, PROMPT_VERSION);
            } else {
                return FraudAnalysisResultType.clean(PROMPT_VERSION);
            }

        } catch (JsonProcessingException e) {
            LOG.errorf(e, "Failed to parse AI fraud response as JSON: %s", response);
            // Return low-confidence suspicious result on parse failure
            return FraudAnalysisResultType.suspicious(BigDecimal.valueOf(0.3), List.of("AI response parse failure"),
                    PROMPT_VERSION);
        }
    }

    /**
     * Performs rule-based fraud analysis using keyword matching.
     *
     * <p>
     * Fallback when AI budget is exhausted. Checks for common scam patterns and prohibited content.
     *
     * @param title
     *            listing title
     * @param description
     *            listing description
     * @param price
     *            listing price
     * @param categoryName
     *            category name
     * @return rule-based analysis result
     */
    private FraudAnalysisResultType performRuleBasedAnalysis(String title, String description, BigDecimal price,
            String categoryName) {
        boolean suspicious = false;
        List<String> reasons = new ArrayList<>();

        String combined = ((title != null ? title : "") + " " + (description != null ? description : "")).toLowerCase();

        // Check for suspicious payment methods
        if (combined.contains("western union") || combined.contains("wire transfer") || combined.contains("gift card")
                || combined.contains("cashier check")) {
            suspicious = true;
            reasons.add("Suspicious payment method mentioned");
        }

        // Check for urgency tactics
        if (combined.contains("act now") || combined.contains("limited time") || combined.contains("must sell today")
                || combined.contains("urgent")) {
            suspicious = true;
            reasons.add("Urgency tactics detected");
        }

        // Check for unusually low prices (likely scam)
        if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
            // Extremely low price for valuable categories
            if ((categoryName != null && (categoryName.contains("Electronics") || categoryName.contains("Vehicles")))
                    && price.compareTo(BigDecimal.valueOf(50)) < 0) {
                suspicious = true;
                reasons.add("Unusually low price for category");
            }
        }

        // Check for prohibited items
        if (combined.contains("prescription") || combined.contains("oxycontin") || combined.contains("adderall")
                || combined.contains("xanax")) {
            suspicious = true;
            reasons.add("Prohibited pharmaceutical items");
        }

        if (combined.contains("replica") || combined.contains("counterfeit") || combined.contains("knock-off")
                || combined.contains("fake")) {
            suspicious = true;
            reasons.add("Counterfeit goods mentioned");
        }

        // Check for work-from-home schemes
        if (combined.contains("work from home") && combined.contains("$")
                && (combined.contains("daily") || combined.contains("weekly"))) {
            suspicious = true;
            reasons.add("Potential work-from-home scheme");
        }

        // Check for MLM patterns
        if (combined.contains("passive income") || combined.contains("financial freedom")
                || combined.contains("be your own boss") || combined.contains("unlimited earning potential")) {
            suspicious = true;
            reasons.add("MLM or pyramid scheme indicators");
        }

        // Check for excessive capitalization (spam indicator)
        if (title != null) {
            long capsCount = title.chars().filter(Character::isUpperCase).count();
            if (capsCount > title.length() * 0.6 && title.length() > 10) {
                suspicious = true;
                reasons.add("Excessive capitalization (spam indicator)");
            }
        }

        BigDecimal confidence = suspicious ? BigDecimal.valueOf(0.7) : BigDecimal.valueOf(0.2);

        if (suspicious) {
            return FraudAnalysisResultType.suspicious(confidence, reasons, "rule-based-v1.0");
        } else {
            return FraudAnalysisResultType.clean("rule-based-v1.0");
        }
    }

    /**
     * Fetches category name for a category ID.
     *
     * @param categoryId
     *            category UUID
     * @return category name, or "Uncategorized" if not found
     */
    private String getCategoryName(UUID categoryId) {
        if (categoryId == null) {
            return "Uncategorized";
        }
        MarketplaceCategory category = MarketplaceCategory.findById(categoryId);
        return category != null ? category.name : "Uncategorized";
    }

    /**
     * Stub response generator for development/testing.
     *
     * <p>
     * TODO: Remove this method when LangChain4j is properly configured.
     *
     * @param title
     *            listing title
     * @param description
     *            listing description
     * @param price
     *            listing price
     * @return mock JSON response
     */
    private String generateStubResponse(String title, String description, BigDecimal price) {
        // Simple keyword-based fraud detection for testing
        String combined = ((title != null ? title : "") + " " + (description != null ? description : "")).toLowerCase();

        boolean suspicious = combined.contains("wire transfer") || combined.contains("act now")
                || combined.contains("limited time");

        if (suspicious) {
            return """
                    {
                      "is_suspicious": true,
                      "confidence": 0.75,
                      "reasons": ["Suspicious payment method or urgency tactics detected"]
                    }
                    """;
        } else {
            return """
                    {
                      "is_suspicious": false,
                      "confidence": 0.85,
                      "reasons": []
                    }
                    """;
        }
    }

    /**
     * Internal record for parsing AI JSON response.
     */
    private record AiResponseType(Boolean is_suspicious, BigDecimal confidence, List<String> reasons) {
    }
}
