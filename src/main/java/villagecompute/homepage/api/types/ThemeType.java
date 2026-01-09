package villagecompute.homepage.api.types;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * API type representing theme preferences for the user's homepage.
 *
 * <p>
 * Theme configuration is stored in {@code UserPreferencesType.theme} and controls the visual appearance of the
 * homepage. The ExperienceShell reads these values to apply the appropriate CSS theme tokens.
 *
 * <p>
 * <b>Mode Values:</b>
 * <ul>
 * <li>{@code light} - Light theme always</li>
 * <li>{@code dark} - Dark theme always</li>
 * <li>{@code system} - Follow browser/OS preference ({@code prefers-color-scheme})</li>
 * </ul>
 *
 * <p>
 * <b>Contrast Values:</b>
 * <ul>
 * <li>{@code standard} - Normal contrast (default)</li>
 * <li>{@code high} - High contrast for accessibility</li>
 * </ul>
 *
 * @param mode
 *            theme mode: "light", "dark", or "system"
 * @param accent
 *            optional accent color (hex format: #RRGGBB), null for default
 * @param contrast
 *            contrast level: "standard" or "high"
 */
public record ThemeType(@NotBlank @Pattern(
        regexp = "^(light|dark|system)$") String mode,
        @Pattern(
                regexp = "^#[0-9A-Fa-f]{6}$") String accent,
        @NotBlank @Pattern(
                regexp = "^(standard|high)$") String contrast) {
}
