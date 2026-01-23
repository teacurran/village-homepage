package villagecompute.homepage.data.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQuery;
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
 * <li>{@code admin_role} (TEXT) - Admin role: super_admin, support, ops, read_only (null for non-admin)</li>
 * <li>{@code admin_role_granted_by} (UUID) - User ID who granted the admin role</li>
 * <li>{@code admin_role_granted_at} (TIMESTAMPTZ) - When admin role was granted</li>
 * <li>{@code analytics_consent} (BOOLEAN) - Consent for analytics tracking (Policy P14)</li>
 * <li>{@code is_banned} (BOOLEAN) - User banned from platform (typically for 2+ chargebacks per P3)</li>
 * <li>{@code banned_at} (TIMESTAMPTZ) - Timestamp when user was banned</li>
 * <li>{@code ban_reason} (TEXT) - Reason for ban (e.g., "Repeated chargebacks (3)")</li>
 * <li>{@code google_refresh_token} (TEXT) - Google OAuth refresh token (never expires)</li>
 * <li>{@code google_access_token_expires_at} (TIMESTAMPTZ) - Google access token expiration</li>
 * <li>{@code facebook_access_token} (TEXT) - Facebook long-lived access token (60 days)</li>
 * <li>{@code facebook_access_token_expires_at} (TIMESTAMPTZ) - Facebook token expiration</li>
 * <li>{@code apple_refresh_token} (TEXT) - Apple refresh token (6 months)</li>
 * <li>{@code apple_access_token_expires_at} (TIMESTAMPTZ) - Apple access token expiration</li>
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
@NamedQuery(
        name = User.QUERY_FIND_BY_EMAIL,
        query = "SELECT u FROM User u WHERE u.email = :email AND u.deletedAt IS NULL AND u.isAnonymous = FALSE")
@NamedQuery(
        name = User.QUERY_FIND_BY_OAUTH,
        query = "SELECT u FROM User u WHERE u.oauthProvider = :provider AND u.oauthId = :providerId "
                + "AND u.deletedAt IS NULL AND u.isAnonymous = FALSE")
@NamedQuery(
        name = User.QUERY_FIND_PENDING_PURGE,
        query = "SELECT u FROM User u WHERE u.deletedAt IS NOT NULL AND u.deletedAt < CURRENT_TIMESTAMP")
@NamedQuery(
        name = User.QUERY_FIND_ADMINS,
        query = "SELECT u FROM User u WHERE u.adminRole IS NOT NULL AND u.deletedAt IS NULL")
@NamedQuery(
        name = User.QUERY_FIND_BY_ADMIN_ROLE,
        query = User.JPQL_FIND_BY_ADMIN_ROLE)
@NamedQuery(
        name = User.QUERY_FIND_TOKENS_EXPIRING_SOON,
        query = "SELECT u FROM User u WHERE "
                + "(u.googleRefreshToken IS NOT NULL AND u.googleAccessTokenExpiresAt < :expiryThreshold) OR "
                + "(u.facebookAccessToken IS NOT NULL AND u.facebookAccessTokenExpiresAt < :expiryThreshold) OR "
                + "(u.appleRefreshToken IS NOT NULL AND u.appleAccessTokenExpiresAt < :expiryThreshold)")
public class User extends PanacheEntityBase {

    private static final Logger LOG = Logger.getLogger(User.class);

    // Roles mirror village-storefront constants per Foundation Blueprint Section 3.7.1
    public static final String ROLE_SUPER_ADMIN = "super_admin";
    public static final String ROLE_SUPPORT = "support";
    public static final String ROLE_OPS = "ops";
    public static final String ROLE_READ_ONLY = "read_only";

    // Directory trust levels
    public static final String TRUST_LEVEL_UNTRUSTED = "untrusted";
    public static final String TRUST_LEVEL_TRUSTED = "trusted";
    public static final String TRUST_LEVEL_MODERATOR = "moderator";

    // Karma thresholds aligned with RateLimitService.Tier
    public static final int KARMA_THRESHOLD_TRUSTED = 10;

    public static final String JPQL_FIND_BY_ADMIN_ROLE = "FROM User u WHERE u.adminRole = ?1 AND u.deletedAt IS NULL ORDER BY u.createdAt DESC";

    public static final String QUERY_FIND_BY_EMAIL = "User.findByEmail";
    public static final String QUERY_FIND_BY_OAUTH = "User.findByOAuth";
    public static final String QUERY_FIND_PENDING_PURGE = "User.findPendingPurge";
    public static final String QUERY_FIND_ADMINS = "User.findAdmins";
    public static final String QUERY_FIND_BY_ADMIN_ROLE = "User.findByAdminRole";
    public static final String QUERY_FIND_TOKENS_EXPIRING_SOON = "User.findTokensExpiringSoon";

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
            name = "admin_role")
    public String adminRole;

    @Column(
            name = "admin_role_granted_by")
    public UUID adminRoleGrantedBy;

    @Column(
            name = "admin_role_granted_at")
    public Instant adminRoleGrantedAt;

    @Column(
            name = "analytics_consent",
            nullable = false)
    public boolean analyticsConsent;

    @Column(
            name = "is_banned",
            nullable = false)
    public boolean isBanned;

    @Column(
            name = "banned_at")
    public Instant bannedAt;

    @Column(
            name = "ban_reason")
    public String banReason;

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

    @Column(
            name = "google_refresh_token")
    public String googleRefreshToken;

    @Column(
            name = "google_access_token_expires_at")
    public Instant googleAccessTokenExpiresAt;

    @Column(
            name = "facebook_access_token")
    public String facebookAccessToken;

    @Column(
            name = "facebook_access_token_expires_at")
    public Instant facebookAccessTokenExpiresAt;

    @Column(
            name = "apple_refresh_token")
    public String appleRefreshToken;

    @Column(
            name = "apple_access_token_expires_at")
    public Instant appleAccessTokenExpiresAt;

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
        return find("#" + QUERY_FIND_BY_EMAIL, io.quarkus.panache.common.Parameters.with("email", email))
                .firstResultOptional();
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
        return find("#" + QUERY_FIND_BY_OAUTH,
                io.quarkus.panache.common.Parameters.with("provider", provider).and("providerId", providerId))
                .firstResultOptional();
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
        return find("#" + QUERY_FIND_PENDING_PURGE).list();
    }

    /**
     * Finds all users with admin roles.
     *
     * @return list of users with admin roles (super_admin, support, ops, read_only)
     */
    public static java.util.List<User> findAdmins() {
        return find("#" + QUERY_FIND_ADMINS).list();
    }

    /**
     * Finds all users with a specific admin role.
     *
     * @param role
     *            the admin role to filter by (super_admin, support, ops, read_only)
     * @return list of users with the specified admin role, ordered by creation date (newest first)
     */
    public static java.util.List<User> findByAdminRole(String role) {
        if (role == null || role.isBlank()) {
            return java.util.List.of();
        }
        return find("#" + QUERY_FIND_BY_ADMIN_ROLE, role).list();
    }

    /**
     * Finds all users with OAuth tokens expiring before the given threshold.
     *
     * <p>
     * Used by {@code OAuthTokenRefreshJobHandler} to identify users whose access tokens need refreshing. Returns users
     * with any of the following conditions:
     * <ul>
     * <li>Google refresh token exists and access token expires before threshold</li>
     * <li>Facebook access token exists and expires before threshold</li>
     * <li>Apple refresh token exists and access token expires before threshold</li>
     * </ul>
     *
     * <p>
     * Typical threshold: 7 days from now (provides buffer for refresh before actual expiration).
     *
     * @param expiryThreshold
     *            the expiration timestamp threshold (e.g., Instant.now().plusSeconds(7 * 24 * 60 * 60))
     * @return list of users with tokens expiring before threshold
     */
    public static java.util.List<User> findTokensExpiringSoon(Instant expiryThreshold) {
        return find("#" + QUERY_FIND_TOKENS_EXPIRING_SOON,
                io.quarkus.panache.common.Parameters.with("expiryThreshold", expiryThreshold)).list();
    }

    /**
     * Creates a new anonymous user with default preferences.
     *
     * @return persisted anonymous user with generated UUID
     */
    public static User createAnonymous() {
        User user = new User();
        user.isAnonymous = true;
        user.preferences = new java.util.HashMap<>();
        user.directoryKarma = 0;
        user.directoryTrustLevel = TRUST_LEVEL_UNTRUSTED;
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
        user.preferences = new java.util.HashMap<>();
        user.directoryKarma = 0;
        user.directoryTrustLevel = TRUST_LEVEL_UNTRUSTED;
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

    /**
     * Checks if this user has any admin role.
     *
     * @return true if user has an admin role, false otherwise
     */
    public boolean isAdmin() {
        return this.adminRole != null && !this.adminRole.isBlank();
    }

    /**
     * Checks if this user has the super_admin role.
     *
     * @return true if user is a super admin, false otherwise
     */
    public boolean isSuperAdmin() {
        return ROLE_SUPER_ADMIN.equals(this.adminRole);
    }

    /**
     * Checks if this user has the support role.
     *
     * @return true if user is a support admin, false otherwise
     */
    public boolean isSupport() {
        return ROLE_SUPPORT.equals(this.adminRole);
    }

    /**
     * Checks if this user has the ops role.
     *
     * @return true if user is an ops admin, false otherwise
     */
    public boolean isOps() {
        return ROLE_OPS.equals(this.adminRole);
    }

    /**
     * Checks if this user has the read_only role.
     *
     * @return true if user is a read-only admin, false otherwise
     */
    public boolean isReadOnly() {
        return ROLE_READ_ONLY.equals(this.adminRole);
    }

    /**
     * Checks if this user has a specific admin role.
     *
     * @param role
     *            the role to check (super_admin, support, ops, read_only)
     * @return true if user has the specified role, false otherwise
     */
    public boolean hasRole(String role) {
        return role != null && role.equals(this.adminRole);
    }

    /**
     * Bans a user from the platform.
     *
     * <p>
     * Per Policy P3, users are banned for repeated chargebacks (2+ threshold). Banned users are blocked from:
     * <ul>
     * <li>Creating marketplace listings</li>
     * <li>Submitting flags or votes</li>
     * <li>Posting marketplace messages</li>
     * <li>Any marketplace actions</li>
     * </ul>
     *
     * <p>
     * Ban is recorded with timestamp and reason for audit trail. User data is NOT deleted (GDPR requires export
     * capability).
     *
     * @param userId
     *            the user UUID to ban
     * @param reason
     *            the ban reason (e.g., "Repeated chargebacks (3)")
     */
    public static void banUser(UUID userId, String reason) {
        if (userId == null) {
            LOG.warnf("Cannot ban user - userId is null");
            return;
        }

        User user = User.findById(userId);
        if (user == null) {
            LOG.warnf("Cannot ban user %s - user not found", userId);
            return;
        }

        if (user.isBanned) {
            LOG.infof("User %s already banned (reason: %s)", userId, user.banReason);
            return;
        }

        user.isBanned = true;
        user.bannedAt = Instant.now();
        user.banReason = reason;
        user.updatedAt = Instant.now();
        user.persist();

        LOG.warnf("BANNED USER: userId=%s, reason=%s, bannedAt=%s", userId, reason, user.bannedAt);

        // TODO: Send email notification to user explaining ban + appeal process
        // TODO: Mark all active listings as 'removed'
    }

    /**
     * Checks if a user is banned.
     *
     * @param userId
     *            the user UUID to check
     * @return true if user is banned, false otherwise
     */
    public static boolean checkIfBanned(UUID userId) {
        if (userId == null) {
            return false;
        }

        User user = User.findById(userId);
        return user != null && user.isBanned;
    }

    /**
     * Checks if user is trusted (has auto-publish privileges).
     *
     * @return true if user trust level is trusted or moderator
     */
    public boolean isTrusted() {
        return TRUST_LEVEL_TRUSTED.equals(this.directoryTrustLevel)
                || TRUST_LEVEL_MODERATOR.equals(this.directoryTrustLevel);
    }

    /**
     * Checks if user is a directory moderator.
     *
     * @return true if user has moderator trust level
     */
    public boolean isDirectoryModerator() {
        return TRUST_LEVEL_MODERATOR.equals(this.directoryTrustLevel);
    }

    /**
     * Checks if user should be auto-promoted to trusted based on current karma.
     *
     * @return true if karma meets or exceeds trusted threshold and user is currently untrusted
     */
    public boolean shouldPromoteToTrusted() {
        return TRUST_LEVEL_UNTRUSTED.equals(this.directoryTrustLevel) && this.directoryKarma >= KARMA_THRESHOLD_TRUSTED;
    }

    /**
     * Calculates karma needed to reach next trust level milestone.
     *
     * @return karma points needed for next level, or null if at max level
     */
    public Integer getKarmaToNextLevel() {
        if (TRUST_LEVEL_UNTRUSTED.equals(this.directoryTrustLevel)) {
            return Math.max(0, KARMA_THRESHOLD_TRUSTED - this.directoryKarma);
        }
        // Trusted and Moderator have no further karma milestones
        return null;
    }

    /**
     * Gets a human-readable description of current karma privileges.
     *
     * @return privilege description text
     */
    public String getKarmaPrivilegeDescription() {
        if (TRUST_LEVEL_MODERATOR.equals(this.directoryTrustLevel)) {
            return "Moderator: Can approve submissions, auto-publish, and edit directory sites";
        } else if (TRUST_LEVEL_TRUSTED.equals(this.directoryTrustLevel)) {
            return "Trusted: Submissions auto-publish without moderation";
        } else {
            return "Untrusted: Submissions require moderator approval";
        }
    }
}
