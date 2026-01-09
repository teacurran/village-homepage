package villagecompute.homepage.api.types;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * API request type for assigning an admin role to a user.
 *
 * <p>
 * Used by PUT /admin/api/users/roles/{userId} endpoint to assign or update admin roles. Roles mirror village-storefront
 * constants per Foundation Blueprint Section 3.7.1.
 *
 * @param role
 *            the admin role to assign (super_admin, support, ops, read_only)
 */
public record AssignRoleRequestType(@NotBlank(
        message = "role is required") @Pattern(
                regexp = "^(super_admin|support|ops|read_only)$",
                message = "role must be one of: super_admin, support, ops, read_only") String role) {
}
