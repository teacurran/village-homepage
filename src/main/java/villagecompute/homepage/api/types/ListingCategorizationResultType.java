package villagecompute.homepage.api.types;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * AI-generated category suggestion for a marketplace listing.
 *
 * <p>
 * This result type represents the output of AI categorization for marketplace listings. It contains a suggested
 * category and subcategory pair from the marketplace taxonomy, along with a confidence score and reasoning for the
 * categorization decision.
 *
 * <p>
 * The result is stored in the {@code ai_category_suggestion} JSONB column of {@code
 * marketplace_listings} table and is intended for human review before being applied to the actual {@code category_id}
 * field.
 *
 * <p>
 * <b>Marketplace Category Taxonomy:</b>
 *
 * <ul>
 * <li><b>For Sale:</b> Electronics, Furniture, Clothing, Books, Sporting Goods, Other
 * <li><b>Housing:</b> Rent, Sale, Roommates, Sublets
 * <li><b>Jobs:</b> Full-time, Part-time, Contract, Internship
 * <li><b>Services:</b> Professional, Personal, Home Improvement
 * <li><b>Community:</b> Events, Activities, Rideshare, Lost & Found
 * </ul>
 *
 * <p>
 * <b>Low Confidence Handling:</b> Results with {@code confidenceScore < 0.7} are flagged for human review via warning
 * logs and should be reviewed by moderators before application.
 *
 * @param category
 *            Primary marketplace category (e.g., "For Sale", "Housing", "Jobs")
 * @param subcategory
 *            Specific subcategory within the primary category (e.g., "Electronics", "Rent")
 * @param confidenceScore
 *            AI confidence in the categorization decision (0.0 = no confidence, 1.0 = absolute certainty)
 * @param reasoning
 *            Brief explanation of why this category was selected (1-2 sentences)
 */
public record ListingCategorizationResultType(@NotNull String category, @NotNull String subcategory,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double confidenceScore, @NotNull String reasoning) {
}
