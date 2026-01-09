# User Preferences Schema Documentation

## Overview

User homepage preferences are stored in the `users.preferences` JSONB column and define the layout, appearance, and content subscriptions for each user's personalized homepage. This document describes the current schema version (v1), the versioning strategy, and migration guidelines.

## Current Schema (v1)

### Top-Level Structure

```json
{
  "schema_version": 1,
  "layout": [...],
  "news_topics": [...],
  "watchlist": [...],
  "weather_locations": [...],
  "theme": {...},
  "widget_configs": {...}
}
```

### Field Definitions

#### `schema_version` (Integer, Required)

- **Type:** Integer
- **Constraints:** Must be >= 1
- **Description:** Version identifier for the schema. Used to detect older preference formats and apply migrations. Current version is 1.
- **Default:** 1

#### `layout` (Array of LayoutWidgetType, Required)

- **Type:** Array of objects
- **Description:** Defines the gridstack.js widget layout configuration. Each object represents a widget's position and dimensions in the grid.
- **Default:** Standard layout with search bar, news feed, weather, and stocks widgets

**LayoutWidgetType Structure:**

```json
{
  "widget_id": "news",           // Unique identifier for this widget instance
  "widget_type": "news_feed",    // Widget type (see Widget Types below)
  "x": 0,                        // Column offset (0-based)
  "y": 2,                        // Row offset (0-based)
  "width": 8,                    // Widget width in columns
  "height": 6                    // Widget height in rows
}
```

**Widget Types:**

- `search_bar` - Global search input
- `news_feed` - RSS feed aggregation with AI tagging
- `weather` - Weather widget (Open-Meteo/NWS)
- `stocks` - Stock market data (Alpha Vantage)
- `social_feed` - Instagram/Facebook integration
- `rss_feed` - Custom RSS feed widget
- `quick_links` - User-defined link shortcuts

**Grid System:**

- Desktop: 12 columns
- Tablet: 6 columns
- Mobile: 2 columns

**Constraints:**

- `widget_id`: Must not be blank
- `widget_type`: Must not be blank
- `x`, `y`: Must be >= 0
- `width`, `height`: Must be >= 1

#### `news_topics` (Array of Strings, Required)

- **Type:** Array of strings
- **Description:** Subscribed news categories/interests for feed personalization. These are used to filter and rank news articles in the news_feed widget.
- **Default:** Empty array
- **Examples:** `["technology", "science", "business", "health"]`

#### `watchlist` (Array of Strings, Required)

- **Type:** Array of strings
- **Description:** Stock symbols tracked by the user. Displayed in the stocks widget with real-time quotes from Alpha Vantage.
- **Default:** Empty array
- **Examples:** `["AAPL", "GOOGL", "MSFT", "TSLA"]`

#### `weather_locations` (Array of WeatherLocationType, Required)

- **Type:** Array of objects
- **Description:** Saved weather locations. Users can cycle through these in the weather widget. Each location includes coordinates for API calls.
- **Default:** Empty array

**WeatherLocationType Structure:**

```json
{
  "city": "San Francisco, CA",   // City name (display label)
  "city_id": 123,                // Optional: reference to cities table
  "latitude": 37.7749,           // Latitude for weather API
  "longitude": -122.4194         // Longitude for weather API
}
```

**Constraints:**

- `city`: Must not be blank
- `latitude`: Must be between -90 and 90
- `longitude`: Must be between -180 and 180

#### `theme` (ThemeType, Required)

- **Type:** Object
- **Description:** Theme preferences controlling the visual appearance of the homepage. The ExperienceShell reads these values to apply CSS theme tokens.
- **Default:** `{ "mode": "system", "accent": null, "contrast": "standard" }`

**ThemeType Structure:**

```json
{
  "mode": "system",        // "light", "dark", or "system"
  "accent": "#FF5733",     // Optional hex color (#RRGGBB), null for default
  "contrast": "standard"   // "standard" or "high"
}
```

**Constraints:**

- `mode`: Must be one of: `light`, `dark`, `system`
- `accent`: If provided, must match pattern `#[0-9A-Fa-f]{6}`
- `contrast`: Must be one of: `standard`, `high`

#### `widget_configs` (Map of WidgetConfigType, Required)

- **Type:** Object (map keyed by widget_id)
- **Description:** Per-widget configuration settings. Each widget can define its own schema, with common fields for density and sorting.
- **Default:** Empty object `{}`

**WidgetConfigType Structure:**

```json
{
  "density": "compact",          // "comfortable" or "compact" (nullable)
  "sort": "date_desc",          // Widget-specific sorting (nullable)
  "filters": {                  // Arbitrary key-value pairs
    "category": "tech"
  }
}
```

**Common Fields:**

- `density`: Display density - "comfortable" (default padding) or "compact" (reduced padding)
- `sort`: Widget-specific sorting preference (e.g., "date_desc", "popularity", "alphabetical")
- `filters`: Flexible key-value map for widget-specific filters

**Example Configurations:**

```json
{
  "news": {
    "density": "compact",
    "sort": "date_desc",
    "filters": { "category": "tech" }
  },
  "stocks": {
    "density": "comfortable",
    "sort": "alphabetical",
    "filters": { "market": "NASDAQ" }
  },
  "weather": {
    "density": "comfortable",
    "sort": null,
    "filters": { "units": "imperial" }
  }
}
```

## Default Preferences

New users (both anonymous and authenticated) receive the following default preferences:

```json
{
  "schema_version": 1,
  "layout": [
    { "widget_id": "search", "widget_type": "search_bar", "x": 0, "y": 0, "width": 12, "height": 2 },
    { "widget_id": "news", "widget_type": "news_feed", "x": 0, "y": 2, "width": 8, "height": 6 },
    { "widget_id": "weather", "widget_type": "weather", "x": 8, "y": 2, "width": 4, "height": 3 },
    { "widget_id": "stocks", "widget_type": "stocks", "x": 8, "y": 5, "width": 4, "height": 3 }
  ],
  "news_topics": [],
  "watchlist": [],
  "weather_locations": [],
  "theme": {
    "mode": "system",
    "accent": null,
    "contrast": "standard"
  },
  "widget_configs": {}
}
```

## Schema Versioning Strategy

### Version Identification

The `schema_version` field at the top level of the preferences object identifies the schema version. Services MUST check this field when reading preferences and apply migrations if the version is older than the current version.

### Backward Compatibility

Services MUST support reading older schema versions gracefully. When an older version is detected:

1. The service deserializes the old structure
2. Applies migration logic to transform it to the current version
3. Returns the migrated preferences to the caller
4. Does NOT automatically persist the migrated version (user must update explicitly)

### Adding New Versions

When adding a new schema version:

1. Increment the `CURRENT_SCHEMA_VERSION` constant in `UserPreferenceService`
2. Document the changes in this file (see Version History below)
3. Implement migration logic in `UserPreferenceService.migrateSchema()`
4. Add migration methods (e.g., `migrateV1ToV2()`) that transform old data structures
5. Test migrations with real v1 data to ensure no data loss

### Migration Rules

- **Additive changes:** New fields should have sensible defaults. Old data continues to work without migration.
  - Example: Adding a new `quick_links` field in v2 → default to empty array
- **Breaking changes:** Require explicit migration logic to transform old structures.
  - Example: Changing `layout` from array to object → migration must convert each element
- **Deprecated fields:** Leave in place for at least one version to allow rollback.
  - Example: Deprecating `theme.accent` in v2 → keep field until v3, ignore in UI

### Data Retention

Per **Policy P1 (GDPR/CCPA)**, user preferences are:

- **Merged** during anonymous-to-authenticated account merges (existing keys take precedence)
- **Exported** in data portability requests (JSON format)
- **Deleted** on account purge (90-day soft delete + hard delete)

### Testing Strategy

All schema changes MUST include tests:

- Round-trip serialization: Type → Map → Type preserves all data
- Migration from previous version: v(n-1) → v(n) works correctly
- Default handling: Missing fields get sensible defaults
- Validation: Invalid data is rejected with clear error messages

## Version History

### v1 (2026-01-09)

**Initial Schema**

- Added `schema_version` field for tracking
- Added `layout` array for gridstack.js widget positioning
- Added `news_topics` for content personalization
- Added `watchlist` for stock tracking
- Added `weather_locations` for saved weather locations
- Added `theme` object for appearance customization
- Added `widget_configs` map for per-widget settings

**Default Layout:**

- Search bar (full width, 2 rows)
- News feed (8 columns, 6 rows)
- Weather widget (4 columns, 3 rows)
- Stocks widget (4 columns, 3 rows)

**Migrations:** None (initial version)

## API Endpoints

### GET /api/preferences

Retrieves the current user's preferences.

**Authentication:** Required (JWT)

**Rate Limits:**

- Anonymous: 10 requests/hour
- Logged-in: 100 requests/hour
- Trusted (karma >= 10): 500 requests/hour

**Response:**

```json
{
  "schema_version": 1,
  "layout": [...],
  "news_topics": [...],
  "watchlist": [...],
  "weather_locations": [...],
  "theme": {...},
  "widget_configs": {...}
}
```

**Status Codes:**

- 200 OK: Preferences retrieved successfully
- 401 Unauthorized: Authentication required
- 429 Too Many Requests: Rate limit exceeded

### PUT /api/preferences

Updates the current user's preferences.

**Authentication:** Required (JWT)

**Rate Limits:**

- Anonymous: 5 requests/hour
- Logged-in: 50 requests/hour
- Trusted (karma >= 10): 200 requests/hour

**Request Body:**

Complete `UserPreferencesType` object (see schema above). All fields are required. Partial updates are not supported.

**Response:**

```json
{
  "schema_version": 1,
  "layout": [...],
  "news_topics": [...],
  "watchlist": [...],
  "weather_locations": [...],
  "theme": {...},
  "widget_configs": {...}
}
```

**Status Codes:**

- 200 OK: Preferences updated successfully
- 400 Bad Request: Validation failed (see error message)
- 401 Unauthorized: Authentication required
- 429 Too Many Requests: Rate limit exceeded

## Implementation Notes

### Service Layer

The `UserPreferenceService` handles:

- Serialization between `UserPreferencesType` (API) and `Map<String, Object>` (database)
- Schema version detection and migration
- Default preference generation
- Validation (via Jakarta Bean Validation on Type records)

### Database Storage

Preferences are stored in the `users.preferences` JSONB column. The column has a default value of `'{}'::jsonb`, so new users start with an empty object. The service detects this and returns defaults without persisting them.

### Account Merges

During anonymous-to-authenticated account merges (Policy P1):

1. The `AccountMergeService` calls `User.mergePreferences()`
2. Anonymous user's preferences are merged into authenticated user
3. **Existing keys in authenticated user take precedence** (no overwrite)
4. Anonymous user record is soft-deleted after merge

Example merge:

```
Anonymous:      { "layout": [...], "watchlist": ["AAPL"] }
Authenticated:  { "layout": [...], "watchlist": ["GOOGL"] }
Result:         { "layout": [...], "watchlist": ["GOOGL"] }  // Authenticated wins
```

### Security Considerations

- **User Isolation:** Users can only access/modify their own preferences. The REST resource extracts user ID from JWT claims.
- **Validation:** All input is validated via Jakarta Bean Validation. Invalid data returns 400 Bad Request.
- **Rate Limiting:** All endpoints enforce tier-based rate limits to prevent abuse.
- **No Sensitive Data:** Preferences do not contain passwords, tokens, or PII beyond user-provided content (watchlist symbols, location names).

## Future Enhancements

Potential additions for future schema versions:

- **v2:** Add `quick_links` field for user-defined shortcuts
- **v2:** Add `density` setting at top level (global default for all widgets)
- **v3:** Add `notification_preferences` for widget-specific alerts
- **v3:** Add `layout_breakpoints` for custom responsive behavior
- **v4:** Add `widget_data` for persisting widget state (e.g., expanded/collapsed, selected tab)

## References

- **Policy P1:** GDPR/CCPA Data Governance
- **Policy P9:** Anonymous Cookie Security
- **Policy P14:** Rate Limiting
- **User Entity:** `src/main/java/villagecompute/homepage/data/models/User.java`
- **Type Definitions:** `src/main/java/villagecompute/homepage/api/types/UserPreferencesType.java`
- **Service:** `src/main/java/villagecompute/homepage/services/UserPreferenceService.java`
- **REST Resource:** `src/main/java/villagecompute/homepage/api/rest/PreferencesResource.java`
- **Tests:** `src/test/java/villagecompute/homepage/services/UserPreferenceServiceTest.java`
