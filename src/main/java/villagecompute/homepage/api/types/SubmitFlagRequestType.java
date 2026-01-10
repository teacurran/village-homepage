package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request type for submitting a flag on a marketplace listing.
 *
 * <p>
 * Reason must be one of: spam, prohibited_item, fraud, duplicate, misleading, inappropriate, other. If reason is
 * "other", details must be provided.
 */
public record SubmitFlagRequestType(@JsonProperty("reason") @NotBlank(
        message = "Flag reason is required") @Pattern(
                regexp = "spam|prohibited_item|fraud|duplicate|misleading|inappropriate|other",
                message = "Invalid flag reason") String reason,

        @JsonProperty("details") @Size(
                max = 2000,
                message = "Details must not exceed 2000 characters") String details) {
    /**
     * Validates that details are provided when reason is "other".
     *
     * @return true if valid
     * @throws IllegalArgumentException
     *             if reason is "other" but details are blank
     */
    public boolean validate() {
        if ("other".equals(reason) && (details == null || details.isBlank())) {
            throw new IllegalArgumentException("Details are required when flag reason is 'other'");
        }
        return true;
    }
}
