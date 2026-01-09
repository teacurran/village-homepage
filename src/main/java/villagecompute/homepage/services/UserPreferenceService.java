package villagecompute.homepage.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.UserPreferencesType;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.observability.LoggingConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Application-scoped service for managing user homepage preferences with schema versioning.
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>CRUD operations on user preferences stored in {@code users.preferences} JSONB field</li>
 * <li>Serialization/deserialization between {@link UserPreferencesType} and {@code Map<String, Object>}</li>
 * <li>Schema version migration with forward compatibility</li>
 * <li>Default preference generation for new users</li>
 * <li>Validation of preference structure and widget configurations</li>
 * </ul>
 *
 * <p>
 * <b>Schema Versioning:</b> The service handles multiple schema versions gracefully. When reading preferences with
 * older schema versions, missing fields are populated with sensible defaults. The {@code migrateSchema} method applies
 * transformations to bring old preferences up to the current version.
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P1 (GDPR/CCPA): Preferences are included in account merge operations and purged on deletion</li>
 * <li>P9 (Anonymous Cookie Security): Anonymous users can store/retrieve preferences before authentication</li>
 * <li>P14 (Rate Limiting): GET/PUT operations should be rate-limited at the REST layer</li>
 * </ul>
 *
 * @see UserPreferencesType for schema definition
 * @see User for entity with preferences field
 */
@ApplicationScoped
public class UserPreferenceService {

    private static final Logger LOG = Logger.getLogger(UserPreferenceService.class);

    private static final int CURRENT_SCHEMA_VERSION = 1;

    @Inject
    Tracer tracer;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Retrieves user preferences, applying defaults if preferences are empty or invalid.
     *
     * <p>
     * If the user has no preferences stored (empty JSONB), returns the default preferences. If the preferences have an
     * older schema version, applies migration logic to bring them up to date.
     *
     * @param userId
     *            user ID to retrieve preferences for
     * @return user preferences (defaults if none exist)
     * @throws IllegalArgumentException
     *             if user not found
     */
    public UserPreferencesType getPreferences(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }

        Span span = tracer.spanBuilder("user_preference.get").setAttribute("user_id", userId.toString()).startSpan();

        try (Scope scope = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            LoggingConfig.setRequestOrigin("UserPreferenceService.getPreferences");

            Optional<User> userOpt = User.findByIdOptional(userId);
            if (userOpt.isEmpty()) {
                throw new IllegalArgumentException("User not found: " + userId);
            }

            User user = userOpt.get();

            // If preferences are empty or null, return defaults
            if (user.preferences == null || user.preferences.isEmpty()) {
                LOG.debugf("User %s has no preferences, returning defaults", userId);
                span.addEvent("preferences.default_returned", Attributes.of(AttributeKey.stringKey("user_id"),
                        userId.toString(), AttributeKey.longKey("schema_version"), (long) CURRENT_SCHEMA_VERSION));
                return UserPreferencesType.createDefault();
            }

            // Deserialize from Map to Type
            try {
                String json = objectMapper.writeValueAsString(user.preferences);
                UserPreferencesType preferences = objectMapper.readValue(json, UserPreferencesType.class);

                // Check schema version and migrate if needed
                if (preferences.schemaVersion() < CURRENT_SCHEMA_VERSION) {
                    LOG.infof("Migrating preferences for user %s from v%d to v%d", userId, preferences.schemaVersion(),
                            CURRENT_SCHEMA_VERSION);
                    preferences = migrateSchema(user.preferences, preferences.schemaVersion());
                }

                span.addEvent("preferences.retrieved",
                        Attributes.of(AttributeKey.stringKey("user_id"), userId.toString(),
                                AttributeKey.longKey("schema_version"), (long) preferences.schemaVersion(),
                                AttributeKey.longKey("layout_widget_count"), (long) preferences.layout().size()));

                return preferences;

            } catch (JsonProcessingException e) {
                LOG.errorf(e, "Failed to deserialize preferences for user %s, returning defaults", userId);
                span.recordException(e);
                return UserPreferencesType.createDefault();
            }

        } finally {
            LoggingConfig.clearMDC();
            span.end();
        }
    }

    /**
     * Updates user preferences with validation and persistence.
     *
     * <p>
     * The preferences are validated by Jackson Bean Validation before persistence. The service serializes the Type to a
     * Map and stores it in the user's preferences JSONB field.
     *
     * @param userId
     *            user ID to update preferences for
     * @param preferences
     *            new preferences (must be valid per Type constraints)
     * @return updated preferences
     * @throws IllegalArgumentException
     *             if user not found or validation fails
     */
    @Transactional
    public UserPreferencesType updatePreferences(UUID userId, UserPreferencesType preferences) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }

        if (preferences == null) {
            throw new IllegalArgumentException("preferences is required");
        }

        Span span = tracer.spanBuilder("user_preference.update").setAttribute("user_id", userId.toString())
                .setAttribute("schema_version", preferences.schemaVersion()).startSpan();

        try (Scope scope = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            LoggingConfig.setRequestOrigin("UserPreferenceService.updatePreferences");

            Optional<User> userOpt = User.findByIdOptional(userId);
            if (userOpt.isEmpty()) {
                throw new IllegalArgumentException("User not found: " + userId);
            }

            User user = userOpt.get();

            // Serialize Type to Map
            try {
                String json = objectMapper.writeValueAsString(preferences);
                @SuppressWarnings("unchecked")
                Map<String, Object> preferencesMap = objectMapper.readValue(json, Map.class);

                user.preferences = preferencesMap;
                user.updatedAt = java.time.Instant.now();
                user.persist();

                span.addEvent("preferences.updated",
                        Attributes.of(AttributeKey.stringKey("user_id"), userId.toString(),
                                AttributeKey.longKey("schema_version"), (long) preferences.schemaVersion(),
                                AttributeKey.longKey("layout_widget_count"), (long) preferences.layout().size()));

                LOG.infof("Updated preferences for user %s (schema v%d, %d widgets)", userId,
                        preferences.schemaVersion(), preferences.layout().size());

                return preferences;

            } catch (JsonProcessingException e) {
                LOG.errorf(e, "Failed to serialize preferences for user %s", userId);
                span.recordException(e);
                throw new IllegalArgumentException("Invalid preferences structure", e);
            }

        } finally {
            LoggingConfig.clearMDC();
            span.end();
        }
    }

    /**
     * Resets user preferences to defaults.
     *
     * <p>
     * Useful for "reset to factory defaults" functionality or clearing corrupted preference data.
     *
     * @param userId
     *            user ID to reset preferences for
     * @return default preferences
     * @throws IllegalArgumentException
     *             if user not found
     */
    @Transactional
    public UserPreferencesType resetToDefaults(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }

        Span span = tracer.spanBuilder("user_preference.reset").setAttribute("user_id", userId.toString()).startSpan();

        try (Scope scope = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            LoggingConfig.setRequestOrigin("UserPreferenceService.resetToDefaults");

            UserPreferencesType defaults = UserPreferencesType.createDefault();
            updatePreferences(userId, defaults);

            span.addEvent("preferences.reset", Attributes.of(AttributeKey.stringKey("user_id"), userId.toString()));

            LOG.infof("Reset preferences to defaults for user %s", userId);

            return defaults;

        } finally {
            LoggingConfig.clearMDC();
            span.end();
        }
    }

    /**
     * Migrates preferences from an older schema version to the current version.
     *
     * <p>
     * This method applies transformations to bring old preferences up to date. New fields are populated with sensible
     * defaults. Existing data is preserved where possible.
     *
     * <p>
     * <b>Migration Examples:</b>
     * <ul>
     * <li>v1 → v2: Add new field {@code widget_configs} with empty map</li>
     * <li>v2 → v3: Convert old {@code layout} format to new gridstack schema</li>
     * </ul>
     *
     * @param oldPreferences
     *            raw preferences map from database
     * @param currentVersion
     *            current schema version in the preferences
     * @return migrated preferences at CURRENT_SCHEMA_VERSION
     */
    public UserPreferencesType migrateSchema(Map<String, Object> oldPreferences, int currentVersion) {
        LOG.infof("Migrating preferences from schema v%d to v%d", currentVersion, CURRENT_SCHEMA_VERSION);

        // Currently only v1 exists, so no migrations needed yet
        // Future migrations would chain transformations here:
        // if (currentVersion == 1) {
        // oldPreferences = migrateV1ToV2(oldPreferences);
        // currentVersion = 2;
        // }
        // if (currentVersion == 2) {
        // oldPreferences = migrateV2ToV3(oldPreferences);
        // currentVersion = 3;
        // }

        // For now, just deserialize directly (v1 is current)
        try {
            String json = objectMapper.writeValueAsString(oldPreferences);
            return objectMapper.readValue(json, UserPreferencesType.class);
        } catch (JsonProcessingException e) {
            LOG.errorf(e, "Failed to migrate preferences from v%d, returning defaults", currentVersion);
            return UserPreferencesType.createDefault();
        }
    }

    /**
     * Example migration method (placeholder for future v1 → v2 migration).
     *
     * <p>
     * This method would transform v1 preferences to v2 format by adding new fields or restructuring existing data.
     *
     * @param v1Prefs
     *            preferences in v1 format
     * @return preferences in v2 format
     */
    @SuppressWarnings("unused")
    private Map<String, Object> migrateV1ToV2(Map<String, Object> v1Prefs) {
        Map<String, Object> v2Prefs = new HashMap<>(v1Prefs);
        v2Prefs.put("schema_version", 2);

        // Add new fields with defaults
        if (!v2Prefs.containsKey("widget_configs")) {
            v2Prefs.put("widget_configs", Map.of());
        }

        return v2Prefs;
    }
}
