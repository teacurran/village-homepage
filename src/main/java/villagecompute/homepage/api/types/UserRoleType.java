package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * API response type for user role information.
 *
 * <p>
 * Returns full user identity and role assignment details for admin role management endpoints.
 *
 * @param userId
 *            the user's unique identifier
 * @param email
 *            the user's email address
 * @param displayName
 *            the user's display name
 * @param adminRole
 *            the assigned admin role (super_admin, support, ops, read_only)
 * @param grantedAt
 *            when the role was granted
 * @param grantedBy
 *            user ID who granted the role (null for bootstrap user)
 */
public record UserRoleType(@JsonProperty("user_id") UUID userId, String email,
        @JsonProperty("display_name") String displayName, @JsonProperty("admin_role") String adminRole,
        @JsonProperty("granted_at") Instant grantedAt, @JsonProperty("granted_by") UUID grantedBy) {
}
