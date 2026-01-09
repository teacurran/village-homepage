package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * API response type for impersonation audit entries.
 *
 * <p>
 * Returns details about admin impersonation sessions for audit and compliance purposes per Section 3.7.1.
 *
 * @param id
 *            the audit entry's unique identifier
 * @param impersonatorId
 *            user ID who performed the impersonation
 * @param targetUserId
 *            user ID who was impersonated
 * @param startedAt
 *            when the impersonation session started
 * @param endedAt
 *            when the impersonation session ended (null for active sessions)
 * @param ipAddress
 *            IP address of the impersonator
 * @param userAgent
 *            user agent string of the impersonator
 * @param createdAt
 *            record creation timestamp
 */
public record ImpersonationAuditType(UUID id, @JsonProperty("impersonator_id") UUID impersonatorId,
        @JsonProperty("target_user_id") UUID targetUserId, @JsonProperty("started_at") Instant startedAt,
        @JsonProperty("ended_at") Instant endedAt, @JsonProperty("ip_address") String ipAddress,
        @JsonProperty("user_agent") String userAgent, @JsonProperty("created_at") Instant createdAt) {
}
