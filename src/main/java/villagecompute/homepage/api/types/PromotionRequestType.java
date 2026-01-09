package villagecompute.homepage.api.types;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request type for creating a listing promotion (POST /api/marketplace/listings/{id}/promote).
 *
 * <p>
 * Specifies the promotion type (featured or bump) to purchase for a listing.
 *
 * <p>
 * <b>Promotion Types:</b>
 * <ul>
 * <li><b>featured:</b> $5 for 7 days, listing highlighted in search results and top of category</li>
 * <li><b>bump:</b> $2 per bump, listing reset to top of chronological order, limited to once per 24 hours</li>
 * </ul>
 *
 * <p>
 * <b>Business Rules:</b>
 * <ul>
 * <li>Listing must be in 'active' status to be promoted</li>
 * <li>For bump: listing must not have been bumped within last 24 hours</li>
 * <li>Payment processed via Stripe Payment Intent before promotion is applied</li>
 * </ul>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>F12.8: Listing fees & monetization (promotion pricing)</li>
 * </ul>
 *
 * @param type
 *            promotion type: "featured" or "bump"
 */
public record PromotionRequestType(@NotNull @Pattern(
        regexp = "featured|bump",
        message = "type must be 'featured' or 'bump'") String type) {
}
