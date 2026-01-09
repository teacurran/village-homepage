package villagecompute.homepage.data.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * User entity implementing the Panache ActiveRecord pattern for both anonymous and authenticated accounts.
 *
 * <p>
 * Supports Policy P1 (GDPR/CCPA) and P9 (Anonymous Cookie Security) requirements. Anonymous users are tracked via
 * secure {@code vu_anon_id} cookies; authenticated users link via OAuth provider (Google, Facebook, Apple).
 *
 * <p>
 * <b>Schema Mapping:</b>
 * <ul>
 * <li>{@code id} (UUID, PK) - Primary identifier</li>
 * <li>{@code email} (TEXT) - Email address (required for authenticated, null for anonymous)</li>
 * <li>{@code oauth_provider} (TEXT) - OAuth provider: google, facebook, apple</li>
 * <li>{@code oauth_id} (TEXT) - Provider-specific user ID</li>
 * <li>{@code display_name} (TEXT) - User's display name</li>
 * <li>{@code avatar_url} (TEXT) - Profile picture URL</li>
 * <li>{@code preferences} (JSONB) - User preferences (layout, topics, etc.)</li>
 * <li>{@code is_anonymous} (BOOLEAN) - Anonymous vs authenticated flag</li>
 * <li>{@code directory_karma} (INT) - Good Sites voting karma</li>
 * <li>{@code directory_trust_level} (TEXT) - Trust level: untrusted, trusted, moderator</li>
 * <li>{@code analytics_consent} (BOOLEAN) - Consent for analytics tracking (Policy P14)</li>
 * <li>{@code last_active_at} (TIMESTAMPTZ) - Last activity timestamp</li>
 * <li>{@code created_at} (TIMESTAMPTZ) - Record creation timestamp</li>
 * <li>{@code updated_at} (TIMESTAMPTZ) - Last modification timestamp</li>
 * <li>{@code deleted_at} (TIMESTAMPTZ) - Soft deletion timestamp (90-day retention per P1)</li>
 * </ul>
 *
 * @see AccountMergeAudit for merge audit trail
 */
@Entity
@Table(
        name = "users")
public class User extends PanacheEntityBase {

    private static final Logger LOG = Logger.getLogger(User.class);

    public static final String QUERY_FIND_BY_EMAIL = "User.findByEmail";
    public static final String QUERY_FIND_BY_OAUTH = "User.findByOAuth";
    public static final String QUERY_FIND_PENDING_PURGE = "User.findPendingPurge";

    @Id
    @GeneratedValue
    @Column(
            nullable = false)
    public UUID id;

    @Column
    public String email;

    @Column(
            name = "oauth_provider")
    public String oauthProvider;

    @Column(
            name = "oauth_id")
    public String oauthId;

    @Column(
            name = "display_name")
    public String displayName;

    @Column(
            name = "avatar_url")
    public String avatarUrl;

    @Column(
            nullable = false,
            columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Object> preferences;

    @Column(
            name = "is_anonymous",
            nullable = false)
    public boolean isAnonymous;

    @Column(
            name = "directory_karma",
            nullable = false)
    public int directoryKarma;

    @Column(
            name = "directory_trust_level",
            nullable = false)
    public String directoryTrustLevel;

    @Column(
            name = "analytics_consent",
            nullable = false)
    public boolean analyticsConsent;

    @Column(
            name = "last_active_at")
    public Instant lastActiveAt;

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public Instant updatedAt;

    @Column(
            name = "deleted_at")
    public Instant deletedAt;

    /**
     * Finds a user by email address (authenticated users only).
     *
     * @param email
     *            the user's email address
     * @return Optional containing the user if found and not deleted
     */
    public static Optional<User> findByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return find("#" + QUERY_FIND_BY_EMAIL + " WHERE email = ?1 AND deleted_at IS NULL AND is_anonymous = FALSE",
                email).firstResultOptional();
    }

    /**
     * Finds a user by OAuth provider and provider-specific ID.
     *
     * @param provider
     *            OAuth provider (google, facebook, apple)
     * @param providerId
     *            provider-specific user ID
     * @return Optional containing the user if found and not deleted
     */
    public static Optional<User> findByOAuth(String provider, String providerId) {
        if (provider == null || providerId == null) {
            return Optional.empty();
        }
        return find("#" + QUERY_FIND_BY_OAUTH
                + " WHERE oauth_provider = ?1 AND oauth_id = ?2 AND deleted_at IS NULL AND is_anonymous = FALSE",
                provider, providerId).firstResultOptional();
    }

    /**
     * Finds all soft-deleted users eligible for hard deletion (purge_after timestamp passed).
     *
     * <p>
     * Used by {@code AccountMergeCleanupJobHandler} to purge anonymous accounts after 90-day retention period per
     * Policy P1.
     *
     * @return list of users eligible for purge
     */
    public static java.util.List<User> findPendingPurge() {
        return find("#" + QUERY_FIND_PENDING_PURGE + " WHERE deleted_at IS NOT NULL AND deleted_at < NOW()").list();
    }

    /**
     * Creates a new anonymous user with default preferences.
     *
     * @return persisted anonymous user with generated UUID
     */
    public static User createAnonymous() {
        User user = new User();
        user.isAnonymous = true;
        user.preferences = Map.of();
        user.directoryKarma = 0;
        user.directoryTrustLevel = "untrusted";
        user.analyticsConsent = false;
        user.createdAt = Instant.now();
        user.updatedAt = Instant.now();
        user.lastActiveAt = Instant.now();

        QuarkusTransaction.requiringNew().run(() -> user.persist());
        LOG.infof("Created anonymous user with id: %s", user.id);
        return user;
    }

    /**
     * Creates a new authenticated user from OAuth profile data.
     *
     * @param email
     *            user's email address
     * @param provider
     *            OAuth provider (google, facebook, apple)
     * @param providerId
     *            provider-specific user ID
     * @param displayName
     *            user's display name
     * @param avatarUrl
     *            user's profile picture URL
     * @return persisted authenticated user with generated UUID
     */
    public static User createAuthenticated(String email, String provider, String providerId, String displayName,
            String avatarUrl) {
        User user = new User();
        user.email = email;
        user.oauthProvider = provider;
        user.oauthId = providerId;
        user.displayName = displayName;
        user.avatarUrl = avatarUrl;
        user.isAnonymous = false;
        user.preferences = Map.of();
        user.directoryKarma = 0;
        user.directoryTrustLevel = "untrusted";
        user.analyticsConsent = false;
        user.createdAt = Instant.now();
        user.updatedAt = Instant.now();
        user.lastActiveAt = Instant.now();

        QuarkusTransaction.requiringNew().run(() -> user.persist());
        LOG.infof("Created authenticated user %s with id: %s", email, user.id);
        return user;
    }

    /**
     * Soft-deletes this user by setting the deleted_at timestamp.
     *
     * <p>
     * Per Policy P1, soft-deleted anonymous accounts are hard-deleted after 90 days by the cleanup job.
     */
    public void softDelete() {
        this.deletedAt = Instant.now();
        this.updatedAt = Instant.now();
        QuarkusTransaction.requiringNew().run(() -> this.persist());
        LOG.infof("Soft-deleted user %s (anonymous: %s)", this.id, this.isAnonymous);
    }

    /**
     * Updates the last_active_at timestamp to current time.
     */
    public void updateLastActive() {
        this.lastActiveAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * Merges preferences from another user into this user's preferences.
     *
     * <p>
     * Used during anonymous-to-authenticated account merge. Existing keys in this user's preferences take precedence.
     *
     * @param sourcePreferences
     *            preferences to merge from
     */
    public void mergePreferences(Map<String, Object> sourcePreferences) {
        if (sourcePreferences == null || sourcePreferences.isEmpty()) {
            return;
        }

        // Create a new map with source preferences as base, then overlay existing preferences
        Map<String, Object> merged = new java.util.HashMap<>(sourcePreferences);
        if (this.preferences != null) {
            merged.putAll(this.preferences);
        }
        this.preferences = merged;
        this.updatedAt = Instant.now();
        LOG.debugf("Merged preferences for user %s: %d source keys", this.id, sourcePreferences.size());
    }

    /**
     * Immutable snapshot record for JSON serialization in audit logs.
     */
    public record UserSnapshot(@JsonProperty("user_id") UUID userId, String email,
            @JsonProperty("oauth_provider") String oauthProvider, @JsonProperty("display_name") String displayName,
            @JsonProperty("is_anonymous") boolean isAnonymous,
            @JsonProperty("preferences_keys") java.util.Set<String> preferencesKeys,
            @JsonProperty("updated_at") Instant updatedAt) {
    }

    /**
     * Creates a snapshot of this user's state for audit logging.
     *
     * @return immutable snapshot record
     */
    public UserSnapshot toSnapshot() {
        return new UserSnapshot(this.id, this.email, this.oauthProvider, this.displayName, this.isAnonymous,
                this.preferences != null ? this.preferences.keySet() : java.util.Set.of(), this.updatedAt);
    }
}
