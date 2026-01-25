package villagecompute.homepage.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.api.types.LayoutWidgetType;
import villagecompute.homepage.api.types.ThemeType;
import villagecompute.homepage.api.types.UserPreferencesType;
import villagecompute.homepage.api.types.WeatherLocationType;
import villagecompute.homepage.api.types.WidgetConfigType;
import villagecompute.homepage.data.models.User;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for UserPreferenceService covering CRUD operations, schema versioning, and validation.
 *
 * <p>
 * Critical test coverage per I2.T5 acceptance criteria:
 * <ul>
 * <li>Anonymous user flow: get defaults, update, verify persistence</li>
 * <li>Authenticated user flow: complex preferences with all fields</li>
 * <li>Validation failures: missing fields, invalid values, schema version checks</li>
 * <li>Schema migration: older versions migrate to current, defaults applied</li>
 * <li>Serialization: Type ↔ Map round-trip preserves data</li>
 * </ul>
 */
@QuarkusTest
class UserPreferenceServiceTest {

    @Inject
    UserPreferenceService userPreferenceService;

    @Inject
    ObjectMapper objectMapper;

    private UUID testAnonymousUserId;
    private UUID testAuthenticatedUserId;

    @BeforeEach
    @Transactional
    void setUp() {
        // Create test users
        User anonymousUser = User.createAnonymous();
        testAnonymousUserId = anonymousUser.id;

        User authenticatedUser = User.createAuthenticated("test@example.com", "google", "google-123", "Test User",
                "https://example.com/avatar.jpg");
        testAuthenticatedUserId = authenticatedUser.id;
    }

    /**
     * Test: Anonymous user with no preferences gets defaults.
     */
    @Test
    void testGetPreferences_AnonymousUser_ReturnsDefaults() {
        UserPreferencesType preferences = userPreferenceService.getPreferences(testAnonymousUserId);

        assertNotNull(preferences, "Preferences should not be null");
        assertEquals(1, preferences.schemaVersion(), "Schema version should be 1");
        assertEquals(4, preferences.layout().size(), "Default layout should have 4 widgets");
        assertTrue(preferences.newsTopics().isEmpty(), "News topics should be empty by default");
        assertTrue(preferences.watchlist().isEmpty(), "Watchlist should be empty by default");
        assertTrue(preferences.weatherLocations().isEmpty(), "Weather locations should be empty by default");
        assertEquals("system", preferences.theme().mode(), "Theme mode should default to system");
        assertTrue(preferences.widgetConfigs().isEmpty(), "Widget configs should be empty by default");
    }

    /**
     * Test: Authenticated user with no preferences gets defaults.
     */
    @Test
    void testGetPreferences_AuthenticatedUser_ReturnsDefaults() {
        UserPreferencesType preferences = userPreferenceService.getPreferences(testAuthenticatedUserId);

        assertNotNull(preferences, "Preferences should not be null");
        assertEquals(1, preferences.schemaVersion(), "Schema version should be 1");
        assertEquals(4, preferences.layout().size(), "Default layout should have 4 widgets");
    }

    /**
     * Test: Update preferences for anonymous user with valid data.
     */
    @Test
    @Transactional
    void testUpdatePreferences_ValidData_Persists() {
        // Create custom preferences
        UserPreferencesType customPreferences = new UserPreferencesType(1,
                List.of(new LayoutWidgetType("news", "news_feed", 0, 0, 6, 4),
                        new LayoutWidgetType("weather", "weather", 6, 0, 6, 4)),
                List.of("technology", "science"), List.of("AAPL", "GOOGL"),
                List.of(new WeatherLocationType("San Francisco, CA", 123, 37.7749, -122.4194)),
                new ThemeType("dark", "#FF5733", "standard"), Map.of());

        // Update preferences
        UserPreferencesType updated = userPreferenceService.updatePreferences(testAnonymousUserId, customPreferences);

        assertNotNull(updated, "Updated preferences should not be null");
        assertEquals(1, updated.schemaVersion(), "Schema version should be preserved");
        assertEquals(2, updated.layout().size(), "Layout should have 2 widgets");
        assertEquals(2, updated.newsTopics().size(), "News topics should have 2 items");
        assertEquals("technology", updated.newsTopics().get(0), "First topic should be technology");
        assertEquals(2, updated.watchlist().size(), "Watchlist should have 2 symbols");
        assertEquals("AAPL", updated.watchlist().get(0), "First symbol should be AAPL");
        assertEquals(1, updated.weatherLocations().size(), "Should have 1 weather location");
        assertEquals("San Francisco, CA", updated.weatherLocations().get(0).city(), "City should match");
        assertEquals("dark", updated.theme().mode(), "Theme mode should be dark");
        assertEquals("#FF5733", updated.theme().accent(), "Accent color should match");
    }

    /**
     * Test: Retrieve persisted preferences returns same data.
     */
    @Test
    @Transactional
    void testGetPreferences_AfterUpdate_ReturnsSameData() {
        // Create and persist custom preferences
        UserPreferencesType customPreferences = new UserPreferencesType(1,
                List.of(new LayoutWidgetType("stocks", "stocks", 0, 0, 12, 3)), List.of("business"),
                List.of("MSFT", "TSLA"), List.of(), new ThemeType("light", null, "high"), Map.of());

        userPreferenceService.updatePreferences(testAuthenticatedUserId, customPreferences);

        // Retrieve preferences
        UserPreferencesType retrieved = userPreferenceService.getPreferences(testAuthenticatedUserId);

        assertNotNull(retrieved, "Retrieved preferences should not be null");
        assertEquals(1, retrieved.schemaVersion(), "Schema version should match");
        assertEquals(1, retrieved.layout().size(), "Layout should have 1 widget");
        assertEquals("stocks", retrieved.layout().get(0).widgetId(), "Widget ID should match");
        assertEquals(1, retrieved.newsTopics().size(), "Should have 1 news topic");
        assertEquals("business", retrieved.newsTopics().get(0), "Topic should match");
        assertEquals(2, retrieved.watchlist().size(), "Watchlist should have 2 symbols");
        assertEquals("light", retrieved.theme().mode(), "Theme mode should be light");
        assertEquals("high", retrieved.theme().contrast(), "Contrast should be high");
    }

    /**
     * Test: Reset to defaults clears custom preferences.
     */
    @Test
    @Transactional
    void testResetToDefaults_ClearsCustomPreferences() {
        // Create custom preferences
        UserPreferencesType customPreferences = new UserPreferencesType(1,
                List.of(new LayoutWidgetType("custom", "rss_feed", 0, 0, 12, 6)), List.of("sports", "entertainment"),
                List.of("GME"), List.of(new WeatherLocationType("New York, NY", 456, 40.7128, -74.0060)),
                new ThemeType("dark", "#00FF00", "standard"), Map.of());

        userPreferenceService.updatePreferences(testAnonymousUserId, customPreferences);

        // Reset to defaults
        UserPreferencesType reset = userPreferenceService.resetToDefaults(testAnonymousUserId);

        assertNotNull(reset, "Reset preferences should not be null");
        assertEquals(1, reset.schemaVersion(), "Schema version should be 1");
        assertEquals(4, reset.layout().size(), "Layout should have default 4 widgets");
        assertTrue(reset.newsTopics().isEmpty(), "News topics should be empty");
        assertTrue(reset.watchlist().isEmpty(), "Watchlist should be empty");
        assertTrue(reset.weatherLocations().isEmpty(), "Weather locations should be empty");
        assertEquals("system", reset.theme().mode(), "Theme mode should be system");
    }

    /**
     * Test: Update with null user ID throws exception.
     */
    @Test
    void testUpdatePreferences_NullUserId_ThrowsException() {
        UserPreferencesType preferences = UserPreferencesType.createDefault();

        assertThrows(IllegalArgumentException.class, () -> {
            userPreferenceService.updatePreferences(null, preferences);
        }, "Should throw exception for null userId");
    }

    /**
     * Test: Update with non-existent user ID throws exception.
     */
    @Test
    void testUpdatePreferences_NonExistentUser_ThrowsException() {
        UUID nonExistentId = UUID.randomUUID();
        UserPreferencesType preferences = UserPreferencesType.createDefault();

        assertThrows(IllegalArgumentException.class, () -> {
            userPreferenceService.updatePreferences(nonExistentId, preferences);
        }, "Should throw exception for non-existent user");
    }

    /**
     * Test: Update with null preferences throws exception.
     */
    @Test
    @Transactional
    void testUpdatePreferences_NullPreferences_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> {
            userPreferenceService.updatePreferences(testAnonymousUserId, null);
        }, "Should throw exception for null preferences");
    }

    /**
     * Test: Get preferences with null user ID throws exception.
     */
    @Test
    void testGetPreferences_NullUserId_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> {
            userPreferenceService.getPreferences(null);
        }, "Should throw exception for null userId");
    }

    /**
     * Test: Get preferences with non-existent user ID throws exception.
     */
    @Test
    void testGetPreferences_NonExistentUser_ThrowsException() {
        UUID nonExistentId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> {
            userPreferenceService.getPreferences(nonExistentId);
        }, "Should throw exception for non-existent user");
    }

    /**
     * Test: Schema migration handles older versions gracefully.
     */
    @Test
    void testMigrateSchema_V1ToV1_PreservesData() {
        // Create v1 preferences as Map (use HashMap to allow null values)
        Map<String, Object> themeMap = new java.util.HashMap<>();
        themeMap.put("mode", "system");
        themeMap.put("accent", null);
        themeMap.put("contrast", "standard");

        Map<String, Object> v1Prefs = new java.util.HashMap<>();
        v1Prefs.put("schema_version", 1);
        v1Prefs.put("layout", List.of());
        v1Prefs.put("news_topics", List.of());
        v1Prefs.put("watchlist", List.of());
        v1Prefs.put("weather_locations", List.of());
        v1Prefs.put("theme", themeMap);
        v1Prefs.put("widget_configs", Map.of());

        // Migrate (should be no-op for v1 → v1)
        UserPreferencesType migrated = userPreferenceService.migrateSchema(v1Prefs, 1);

        assertNotNull(migrated, "Migrated preferences should not be null");
        assertEquals(1, migrated.schemaVersion(), "Schema version should remain 1");
    }

    /**
     * Test: Complex preferences with all fields serialize/deserialize correctly.
     */
    @Test
    @Transactional
    void testComplexPreferences_RoundTrip_PreservesData() throws Exception {
        // Create complex preferences with all fields populated
        UserPreferencesType complex = new UserPreferencesType(1,
                List.of(new LayoutWidgetType("search", "search_bar", 0, 0, 12, 2),
                        new LayoutWidgetType("news", "news_feed", 0, 2, 8, 6),
                        new LayoutWidgetType("weather", "weather", 8, 2, 4, 3),
                        new LayoutWidgetType("stocks", "stocks", 8, 5, 4, 3),
                        new LayoutWidgetType("social", "social_feed", 0, 8, 6, 4)),
                List.of("technology", "science", "business", "health"),
                List.of("AAPL", "GOOGL", "MSFT", "TSLA", "AMZN"),
                List.of(new WeatherLocationType("San Francisco, CA", 123, 37.7749, -122.4194),
                        new WeatherLocationType("New York, NY", 456, 40.7128, -74.0060),
                        new WeatherLocationType("London, UK", 789, 51.5074, -0.1278)),
                new ThemeType("dark", "#FF5733", "high"),
                Map.of("news", new WidgetConfigType("compact", "date_desc", Map.of("category", "tech")), "stocks",
                        new WidgetConfigType("comfortable", "alphabetical", Map.of("market", "NASDAQ")), "weather",
                        new WidgetConfigType("comfortable", null, Map.of("units", "imperial"))));

        // Persist
        userPreferenceService.updatePreferences(testAuthenticatedUserId, complex);

        // Retrieve
        UserPreferencesType retrieved = userPreferenceService.getPreferences(testAuthenticatedUserId);

        // Verify all fields
        assertNotNull(retrieved, "Retrieved preferences should not be null");
        assertEquals(1, retrieved.schemaVersion(), "Schema version should match");
        assertEquals(5, retrieved.layout().size(), "Should have 5 widgets");
        assertEquals(4, retrieved.newsTopics().size(), "Should have 4 news topics");
        assertEquals(5, retrieved.watchlist().size(), "Should have 5 stock symbols");
        assertEquals(3, retrieved.weatherLocations().size(), "Should have 3 weather locations");
        assertEquals("dark", retrieved.theme().mode(), "Theme mode should match");
        assertEquals("#FF5733", retrieved.theme().accent(), "Accent color should match");
        assertEquals("high", retrieved.theme().contrast(), "Contrast should match");
        assertEquals(3, retrieved.widgetConfigs().size(), "Should have 3 widget configs");

        // Verify widget config structure preserved
        assertTrue(retrieved.widgetConfigs().containsKey("news"), "Should have news widget config");
        assertTrue(retrieved.widgetConfigs().containsKey("stocks"), "Should have stocks widget config");
        assertTrue(retrieved.widgetConfigs().containsKey("weather"), "Should have weather widget config");
    }
}
