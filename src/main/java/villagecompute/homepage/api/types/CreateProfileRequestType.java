package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request type for creating a new user profile.
 *
 * <p>
 * Requires only username - all other fields are optional and can be set via update. Profile is created in draft
 * (unpublished) state.
 * </p>
 *
 * <p>
 * Username validation:
 * <ul>
 * <li>Length: 3-30 characters</li>
 * <li>Characters: a-z, 0-9, underscore, dash only</li>
 * <li>Case-insensitive (stored lowercase)</li>
 * <li>Reserved names blocked</li>
 * </ul>
 * </p>
 *
 * <p>
 * Example request:
 *
 * <pre>{@code
 * {
 *   "username": "johndoe"
 * }
 * }</pre>
 */
public record CreateProfileRequestType(@JsonProperty("username") @NotBlank(
        message = "Username is required") @Size(
                min = 3,
                max = 30,
                message = "Username must be 3-30 characters") @Pattern(
                        regexp = "^[a-zA-Z0-9_-]+$",
                        message = "Username can only contain letters, numbers, underscore, and dash") String username) {
}
