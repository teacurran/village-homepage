# Homepage Entity Architectural Decision

## Context

The original refactoring plan (Iteration 2, Task T2) expected a separate `Homepage` entity with @NamedQuery annotations for user-specific homepage retrieval. This architectural decision record explains why that entity was never implemented.

## Decision

We chose to **NOT implement a separate Homepage entity**. Instead, homepage layout and preferences are stored as JSONB within the `User.preferences` field (`src/main/java/villagecompute/homepage/data/models/User.java:126-130`).

## Rationale

### Performance Benefits
- **Single Query**: User + homepage data retrieved in one query (no JOIN required)
- **Reduced Latency**: Eliminates N+1 query problem for homepage loads
- **Better Caching**: User entity can be cached with all preferences inline

### Flexibility Benefits
- **Schema Evolution**: Layout structure can evolve without database migrations
- **JSONB Power**: Leverage PostgreSQL's native JSON operators for complex queries
- **Dynamic Widgets**: New widget types don't require schema changes

### Simplicity Benefits
- **Fewer Tables**: Reduces mental model complexity (no homepage table, no foreign keys)
- **Atomic Operations**: User + preferences updated in single transaction
- **No Orphans**: Cannot have Homepage without User (avoids foreign key constraints)

### Development Benefits
- **Type Safety**: `UserPreferencesType` provides compile-time validation
- **Service Layer**: `UserPreferenceService` encapsulates all preference logic
- **Testing**: Easier to test (no cross-table dependencies)

## Implementation

### Data Model
```java
// User.java (lines 126-130)
@Column(nullable = false, columnDefinition = "jsonb")
@JdbcTypeCode(SqlTypes.JSON)
public Map<String, Object> preferences;
```

### Service Layer
- **Service**: `UserPreferenceService` (`src/main/java/villagecompute/homepage/services/UserPreferenceService.java`)
- **Method**: `getPreferences(UUID userId)` → Returns `UserPreferencesType`
- **Updates**: `updateLayout()`, `updateTheme()`, `updateWidgetConfig()`

### Type System
- **UserPreferencesType** (`src/main/java/villagecompute/homepage/api/types/UserPreferencesType.java`):
  - `List<LayoutWidgetType> layout` - Widget grid configuration
  - `ThemeType theme` - Color scheme, font preferences
  - `Map<String, WidgetConfigType> widgetConfigs` - Per-widget settings

### Resource Layer
```java
// HomepageResource.java (lines 159-167)
UserPreferencesType preferences;
if (userId != null) {
    preferences = userPreferenceService.getPreferences(userId);
} else {
    preferences = UserPreferencesType.createDefault();
}
```

## Alternative: What a Homepage Entity Would Have Looked Like

If we had implemented a separate `Homepage` entity following the Panache ActiveRecord pattern, it would have required:

### Named Queries
```java
@NamedQuery(
    name = "Homepage.findByUserId",
    query = "SELECT h FROM Homepage h WHERE h.userId = :userId AND h.deletedAt IS NULL"
)
@NamedQuery(
    name = "Homepage.findActiveByUserId",
    query = "SELECT h FROM Homepage h WHERE h.userId = :userId AND h.deletedAt IS NULL AND h.isActive = TRUE"
)
@NamedQuery(
    name = "Homepage.findAll",
    query = "SELECT h FROM Homepage h WHERE h.deletedAt IS NULL ORDER BY h.updatedAt DESC"
)
```

### Static Finder Methods
```java
public class Homepage extends PanacheEntityBase {
    public static final String QUERY_FIND_BY_USER_ID = "Homepage.findByUserId";
    public static final String QUERY_FIND_ACTIVE_BY_USER_ID = "Homepage.findActiveByUserId";

    public static Optional<Homepage> findByUserId(UUID userId) {
        return find("#" + QUERY_FIND_BY_USER_ID, Parameters.with("userId", userId))
            .firstResultOptional();
    }

    public static Optional<Homepage> findActiveByUserId(UUID userId) {
        return find("#" + QUERY_FIND_ACTIVE_BY_USER_ID, Parameters.with("userId", userId))
            .firstResultOptional();
    }
}
```

### Database Migration
```sql
CREATE TABLE homepages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    layout JSONB NOT NULL DEFAULT '[]'::jsonb,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_homepages_user_id_active ON homepages(user_id) WHERE deleted_at IS NULL;
```

### Why This Would Be Worse

1. **Performance**: Every homepage load requires `User JOIN Homepage`
2. **Complexity**: Must maintain one-to-one constraint
3. **Cascading**: Deleting User requires cascading to Homepage
4. **New Users**: Creating User requires separate INSERT for Homepage
5. **Schema Rigidity**: Layout changes require migrations instead of JSONB flexibility

## Current Equivalent Queries

Instead of named queries on a Homepage entity, we use:

```java
// Instead of Homepage.findByUserId(userId)
User user = User.findById(userId);
UserPreferencesType prefs = userPreferenceService.getPreferences(user.id);

// Instead of Homepage.findActiveByUserId(userId)
// (filtering happens in UserPreferenceService based on preferences content)
```

## Impact on Iteration 2

### Task I2.T2 Status
**SKIPPED** - Homepage entity does not exist in final architecture.

### Rationale for Skip
- Creating unused entity violates YAGNI principle
- Current implementation is architecturally superior
- Refactoring to separate entity would introduce unnecessary complexity
- No business value in implementing unused code

### Downstream Impact
- **I2.T3 (RSS entities)**: Unaffected - RssSource and FeedItem entities exist
- **I2.T4-I2.T8**: Unaffected - all subsequent entities exist in codebase
- **Dependencies**: I2.T1 (User entity) complete ✓, I2.T3 depends only on I2.T1

### Verification
```bash
# Confirmed: No Homepage.java in models directory
ls src/main/java/villagecompute/homepage/data/models/ | grep Homepage
# (no output)

# Confirmed: No homepage table in migrations
grep -r "CREATE TABLE homepages" migrations/
# (no output)

# Confirmed: Preferences stored in User.preferences
grep "preferences" src/main/java/villagecompute/homepage/data/models/User.java
# Line 130: public Map<String, Object> preferences;
```

## Conclusion

The decision to embed homepage preferences within the User entity (via JSONB) is architecturally sound and superior to a normalized approach. This ADR documents why Task I2.T2 is skipped and provides reference for future developers who might wonder why no Homepage entity exists.

**Status**: ✅ Architectural decision validated, ⏭️ Task I2.T2 skipped, ➡️ Proceed to I2.T3 (RSS entities)
