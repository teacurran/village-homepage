package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response type for notification preferences. Represents the current state of a user's email notification settings.
 */
public record NotificationPreferencesType(@JsonProperty("emailEnabled") boolean emailEnabled,
        @JsonProperty("emailListingMessages") boolean emailListingMessages,
        @JsonProperty("emailSiteApproved") boolean emailSiteApproved,
        @JsonProperty("emailSiteRejected") boolean emailSiteRejected,
        @JsonProperty("emailDigest") boolean emailDigest) {
}
