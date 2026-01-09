package villagecompute.homepage.api.types;

import jakarta.validation.constraints.Size;

/**
 * Request type for admin refund actions (approve/reject).
 *
 * <p>
 * Used by admin endpoints to approve or reject pending refund requests with optional notes.
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P3: Marketplace payment & fraud policy (manual refund review)</li>
 * </ul>
 *
 * @param notes
 *            optional admin notes explaining decision (max 1000 chars)
 */
public record RefundActionRequestType(@Size(
        max = 1000) String notes) {
}
