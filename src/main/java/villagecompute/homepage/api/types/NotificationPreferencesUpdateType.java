package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request type for updating notification preferences. All fields are required in the request payload.
 */
public record NotificationPreferencesUpdateType(@JsonProperty("emailEnabled") boolean emailEnabled,
        @JsonProperty("emailListingMessages") boolean emailListingMessages,
        @JsonProperty("emailSiteApproved") boolean emailSiteApproved,
        @JsonProperty("emailSiteRejected") boolean emailSiteRejected,
        @JsonProperty("emailDigest") boolean emailDigest) {
}
