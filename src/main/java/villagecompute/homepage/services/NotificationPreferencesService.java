package villagecompute.homepage.services;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.NotificationPreferences;
import villagecompute.homepage.data.models.User;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing notification preferences and unsubscribe token generation/validation. Handles one-click
 * unsubscribe links with HMAC-signed tokens and preference persistence.
 */
@ApplicationScoped
public class NotificationPreferencesService {

    private static final Logger LOG = Logger.getLogger(NotificationPreferencesService.class);

    // Token expiration: 30 days in seconds
    private static final long TOKEN_MAX_AGE_SECONDS = 30L * 24 * 60 * 60;

    @ConfigProperty(
            name = "email.unsubscribe.secret",
            defaultValue = "default-dev-secret-change-in-prod")
    String unsubscribeSecret;

    /**
     * Data extracted from a validated unsubscribe token.
     */
    public record UnsubscribeTokenData(UUID userId, String notificationType, long timestamp) {
    }

    /**
     * Generates an HMAC-signed unsubscribe token for a user and notification type. Token format:
     * Base64(userId:notificationType:timestamp:hmacSignature)
     *
     * @param user
     *            The user to generate the token for
     * @param notificationType
     *            The notification type (snake_case format, e.g., "email_listing_messages")
     * @return Base64-encoded token string, or empty string on error
     */
    public String generateUnsubscribeToken(User user, String notificationType) {
        try {
            // Token payload: userId:notificationType:timestamp
            long timestamp = Instant.now().getEpochSecond();
            String payload = user.id + ":" + notificationType + ":" + timestamp;

            // Generate HMAC signature
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(unsubscribeSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac);

            // Final token: base64(payload:signature)
            String token = payload + ":" + signature;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(token.getBytes(StandardCharsets.UTF_8));

        } catch (Exception e) {
            LOG.errorf(e, "Failed to generate unsubscribe token for user %s", user.id);
            return ""; // Return empty token on error
        }
    }

    /**
     * Parses and validates an unsubscribe token. Validates HMAC signature, expiration (30 days), and token format.
     *
     * @param token
     *            The Base64-encoded token to validate
     * @return Optional containing token data if valid, empty if invalid or expired
     */
    public Optional<UnsubscribeTokenData> parseUnsubscribeToken(String token) {
        try {
            // 1. Base64 decode
            byte[] decodedBytes = Base64.getUrlDecoder().decode(token);
            String decodedToken = new String(decodedBytes, StandardCharsets.UTF_8);

            // 2. Split into components
            String[] parts = decodedToken.split(":");
            if (parts.length != 4) {
                LOG.warnf("Invalid token format: expected 4 parts, got %d", parts.length);
                return Optional.empty();
            }

            String userIdStr = parts[0];
            String notificationType = parts[1];
            String timestampStr = parts[2];
            String providedSignature = parts[3];

            // 3. Verify HMAC signature (constant-time comparison)
            String payload = userIdStr + ":" + notificationType + ":" + timestampStr;
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(unsubscribeSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] expectedHmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expectedSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(expectedHmac);

            // Use constant-time comparison to prevent timing attacks
            if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.UTF_8),
                    providedSignature.getBytes(StandardCharsets.UTF_8))) {
                LOG.warnf("Token signature mismatch");
                return Optional.empty();
            }

            // 4. Check expiration (30 days)
            long tokenTimestamp = Long.parseLong(timestampStr);
            long now = Instant.now().getEpochSecond();
            long ageSeconds = now - tokenTimestamp;

            if (ageSeconds > TOKEN_MAX_AGE_SECONDS) {
                LOG.warnf("Token expired: age=%d seconds, max=%d", ageSeconds, TOKEN_MAX_AGE_SECONDS);
                return Optional.empty();
            }

            // Also check for tokens from the future (clock skew protection)
            if (ageSeconds < -300) { // 5 minute grace period
                LOG.warnf("Token timestamp in future: age=%d seconds", ageSeconds);
                return Optional.empty();
            }

            // 5. Parse UUID
            UUID userId = UUID.fromString(userIdStr);

            LOG.infof("Successfully validated unsubscribe token for user %s", userId);
            return Optional.of(new UnsubscribeTokenData(userId, notificationType, tokenTimestamp));

        } catch (IllegalArgumentException e) {
            LOG.warnf("Invalid token format: %s", e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to parse unsubscribe token");
            return Optional.empty();
        }
    }

    /**
     * Unsubscribes a user from a specific notification type. Creates default preferences if user has none. Maps
     * snake_case notification types to camelCase entity fields.
     *
     * @param user
     *            The user to unsubscribe
     * @param notificationType
     *            The notification type in snake_case format
     */
    public void unsubscribe(User user, String notificationType) {
        // Find or create preferences
        NotificationPreferences prefs = NotificationPreferences.findByUserId(user.id)
                .orElseGet(() -> NotificationPreferences.create(user.id));

        // Update specific preference based on type (map snake_case to camelCase)
        switch (notificationType) {
            case "email_listing_messages" -> prefs.emailListingMessages = false;
            case "email_site_approved" -> prefs.emailSiteApproved = false;
            case "email_site_rejected" -> prefs.emailSiteRejected = false;
            case "email_digest" -> prefs.emailDigest = false;
            case "email_all" -> prefs.emailEnabled = false; // Global toggle
            default -> {
                LOG.warnf("Unknown notification type for unsubscribe: %s", notificationType);
                return; // Don't update for unknown types
            }
        }

        // Persist changes (uses static update method that sets updatedAt)
        NotificationPreferences.update(prefs);

        LOG.infof("User %s unsubscribed from %s", user.id, notificationType);
    }

    /**
     * Gets notification preferences for a user. Creates default preferences if user has none.
     *
     * @param user
     *            The user to get preferences for
     * @return The user's notification preferences
     */
    public NotificationPreferences getPreferences(User user) {
        return NotificationPreferences.findByUserId(user.id).orElseGet(() -> {
            LOG.infof("Creating default notification preferences for user %s", user.id);
            return NotificationPreferences.create(user.id);
        });
    }

    /**
     * Updates multiple notification preferences at once. Only updates preferences that are present in the map.
     *
     * @param user
     *            The user whose preferences to update
     * @param preferences
     *            Map of preference names to boolean values (camelCase keys)
     */
    public void updatePreferences(User user, Map<String, Boolean> preferences) {
        NotificationPreferences prefs = NotificationPreferences.findByUserId(user.id)
                .orElseGet(() -> NotificationPreferences.create(user.id));

        // Update each preference if present in map
        if (preferences.containsKey("emailEnabled")) {
            prefs.emailEnabled = preferences.get("emailEnabled");
        }
        if (preferences.containsKey("emailListingMessages")) {
            prefs.emailListingMessages = preferences.get("emailListingMessages");
        }
        if (preferences.containsKey("emailSiteApproved")) {
            prefs.emailSiteApproved = preferences.get("emailSiteApproved");
        }
        if (preferences.containsKey("emailSiteRejected")) {
            prefs.emailSiteRejected = preferences.get("emailSiteRejected");
        }
        if (preferences.containsKey("emailDigest")) {
            prefs.emailDigest = preferences.get("emailDigest");
        }

        NotificationPreferences.update(prefs);

        LOG.infof("Updated notification preferences for user %s", user.id);
    }
}
