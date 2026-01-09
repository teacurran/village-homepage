/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.data.models;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Social OAuth token entity for Instagram and Facebook integration per Policy P5/P13.
 *
 * <p>
 * Implements Panache ActiveRecord pattern with static finder methods. Stores encrypted OAuth tokens with automatic
 * refresh 7 days before expiry. Supports graceful degradation when tokens become stale.
 *
 * <p>
 * <b>Schema Mapping:</b>
 * <ul>
 * <li>{@code id} (BIGINT, PK) - Primary identifier</li>
 * <li>{@code user_id} (UUID, FK) - References users.id</li>
 * <li>{@code platform} (VARCHAR) - instagram | facebook</li>
 * <li>{@code access_token} (VARCHAR) - OAuth access token (encrypted at rest via TDE)</li>
 * <li>{@code refresh_token} (VARCHAR) - OAuth refresh token (encrypted at rest via TDE)</li>
 * <li>{@code expires_at} (TIMESTAMPTZ) - Access token expiration timestamp</li>
 * <li>{@code granted_at} (TIMESTAMPTZ) - When user authorized the integration</li>
 * <li>{@code revoked_at} (TIMESTAMPTZ) - When user disconnected (NULL for active)</li>
 * <li>{@code scopes} (VARCHAR) - Granted OAuth scopes (comma-separated)</li>
 * <li>{@code last_refresh_attempt} (TIMESTAMPTZ) - Last token refresh attempt</li>
 * <li>{@code created_at} (TIMESTAMPTZ) - Record creation timestamp</li>
 * <li>{@code updated_at} (TIMESTAMPTZ) - Last modification timestamp</li>
 * </ul>
 *
 * <p>
 * <b>Security:</b> Tokens are encrypted at rest via database TDE. Never log full tokens - only log token prefixes or
 * user IDs for debugging. Token refresh logic scheduled via {@code SocialFeedRefreshScheduler}.
 *
 * @see SocialPost for cached posts
 */
@Entity
@Table(
        name = "social_tokens")
public class SocialToken extends PanacheEntityBase {

    private static final Logger LOG = Logger.getLogger(SocialToken.class);

    public static final String PLATFORM_INSTAGRAM = "instagram";
    public static final String PLATFORM_FACEBOOK = "facebook";

    public static final String QUERY_FIND_ACTIVE_BY_USER_AND_PLATFORM = "SocialToken.findActiveByUserAndPlatform";
    public static final String QUERY_FIND_ALL_ACTIVE = "SocialToken.findAllActive";
    public static final String QUERY_FIND_EXPIRING_SOON = "SocialToken.findExpiringSoon";

    @Id
    @GeneratedValue(
            strategy = GenerationType.IDENTITY)
    @Column(
            nullable = false)
    public Long id;

    @Column(
            name = "user_id",
            nullable = false)
    public UUID userId;

    @Column(
            nullable = false,
            length = 20)
    public String platform;

    @Column(
            name = "access_token",
            nullable = false,
            length = 1000)
    // TODO: Encrypt at rest using Quarkus Vault integration (Policy P5)
    public String accessToken;

    @Column(
            name = "refresh_token",
            length = 1000)
    // TODO: Encrypt at rest using Quarkus Vault integration (Policy P5)
    public String refreshToken;

    @Column(
            name = "expires_at",
            nullable = false)
    public Instant expiresAt;

    @Column(
            name = "granted_at",
            nullable = false)
    public Instant grantedAt;

    @Column(
            name = "revoked_at")
    public Instant revokedAt;

    @Column(
            length = 500)
    public String scopes;

    @Column(
            name = "last_refresh_attempt")
    public Instant lastRefreshAttempt;

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public Instant updatedAt;

    /**
     * Finds an active (non-revoked) token for a user and platform.
     *
     * @param userId
     *            user UUID
     * @param platform
     *            "instagram" or "facebook"
     * @return Optional containing the token if found and active
     */
    public static Optional<SocialToken> findActiveByUserAndPlatform(UUID userId, String platform) {
        if (userId == null || platform == null) {
            return Optional.empty();
        }
        return find("#" + QUERY_FIND_ACTIVE_BY_USER_AND_PLATFORM
                + " WHERE user_id = ?1 AND platform = ?2 AND revoked_at IS NULL", userId, platform)
                .firstResultOptional();
    }

    /**
     * Finds all active tokens for a user (all platforms).
     *
     * @param userId
     *            user UUID
     * @return list of active tokens
     */
    public static List<SocialToken> findActiveByUser(UUID userId) {
        if (userId == null) {
            return List.of();
        }
        return find("user_id = ?1 AND revoked_at IS NULL", userId).list();
    }

    /**
     * Finds all active (non-revoked) tokens across all users.
     * <p>
     * Used by {@code SocialFeedRefreshJobHandler} to process all active integrations.
     *
     * @return list of all active tokens
     */
    public static List<SocialToken> findAllActive() {
        return find("#" + QUERY_FIND_ALL_ACTIVE + " WHERE revoked_at IS NULL ORDER BY user_id, platform").list();
    }

    /**
     * Finds all tokens expiring within the next N days.
     * <p>
     * Used by token refresh job to proactively refresh tokens before expiration.
     *
     * @param daysAhead
     *            number of days to look ahead (typically 7)
     * @return list of tokens expiring soon
     */
    public static List<SocialToken> findExpiringSoon(int daysAhead) {
        Instant threshold = Instant.now().plus(daysAhead, ChronoUnit.DAYS);
        return find(
                "#" + QUERY_FIND_EXPIRING_SOON + " WHERE revoked_at IS NULL AND expires_at <= ?1 ORDER BY expires_at",
                threshold).list();
    }

    /**
     * Creates and persists a new social token for a user.
     *
     * @param userId
     *            user UUID
     * @param platform
     *            "instagram" or "facebook"
     * @param accessToken
     *            OAuth access token
     * @param refreshToken
     *            OAuth refresh token (optional)
     * @param expiresAt
     *            token expiration timestamp
     * @param scopes
     *            granted OAuth scopes (comma-separated)
     * @return persisted token entity
     */
    public static SocialToken create(UUID userId, String platform, String accessToken, String refreshToken,
            Instant expiresAt, String scopes) {
        SocialToken token = new SocialToken();
        token.userId = userId;
        token.platform = platform;
        token.accessToken = accessToken;
        token.refreshToken = refreshToken;
        token.expiresAt = expiresAt;
        token.scopes = scopes;
        token.grantedAt = Instant.now();
        token.createdAt = Instant.now();
        token.updatedAt = Instant.now();

        QuarkusTransaction.requiringNew().run(() -> token.persist());

        // NEVER log full tokens - only log prefixes or user IDs
        LOG.infof("Created social token for user %s platform %s (expires: %s)", userId, platform, expiresAt);
        return token;
    }

    /**
     * Revokes this token (soft delete via revoked_at timestamp).
     * <p>
     * User-initiated disconnection. Archives all associated posts.
     */
    public void revoke() {
        this.revokedAt = Instant.now();
        this.updatedAt = Instant.now();
        QuarkusTransaction.requiringNew().run(() -> this.persist());
        LOG.infof("Revoked social token id=%d for user %s platform %s", this.id, this.userId, this.platform);
    }

    /**
     * Updates this token with refreshed access token and new expiration.
     *
     * @param newAccessToken
     *            new OAuth access token
     * @param newExpiresAt
     *            new expiration timestamp
     */
    public void updateToken(String newAccessToken, Instant newExpiresAt) {
        this.accessToken = newAccessToken;
        this.expiresAt = newExpiresAt;
        this.lastRefreshAttempt = Instant.now();
        this.updatedAt = Instant.now();
        QuarkusTransaction.requiringNew().run(() -> this.persist());

        // NEVER log full tokens
        LOG.infof("Refreshed social token id=%d for user %s platform %s (new expiry: %s)", this.id, this.userId,
                this.platform, newExpiresAt);
    }

    /**
     * Marks that a token refresh was attempted (even if it failed).
     * <p>
     * Used to track refresh attempts for monitoring and alerting.
     */
    public void markRefreshAttempt() {
        this.lastRefreshAttempt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * Checks if this token is expired.
     *
     * @return true if token expiration time has passed
     */
    public boolean isExpired() {
        return this.expiresAt.isBefore(Instant.now());
    }

    /**
     * Checks if this token expires within N days.
     *
     * @param daysAhead
     *            number of days to check (typically 7)
     * @return true if token expires within the next N days
     */
    public boolean expiresWithin(int daysAhead) {
        Instant threshold = Instant.now().plus(daysAhead, ChronoUnit.DAYS);
        return this.expiresAt.isBefore(threshold);
    }

    /**
     * Checks if this token is stale (last refresh attempt was too long ago).
     * <p>
     * Considers token stale if last refresh attempt was more than 7 days ago.
     *
     * @return true if token should be considered stale
     */
    public boolean isStale() {
        if (this.lastRefreshAttempt == null) {
            // If never refreshed, check if granted more than 7 days ago
            return this.grantedAt.isBefore(Instant.now().minus(7, ChronoUnit.DAYS));
        }
        return this.lastRefreshAttempt.isBefore(Instant.now().minus(7, ChronoUnit.DAYS));
    }

    /**
     * Gets the number of days since last successful post fetch.
     * <p>
     * Used for staleness banner color per Policy P13.
     *
     * @return days since last refresh attempt (0 if never attempted)
     */
    public long getDaysSinceRefresh() {
        if (this.lastRefreshAttempt == null) {
            return ChronoUnit.DAYS.between(this.grantedAt, Instant.now());
        }
        return ChronoUnit.DAYS.between(this.lastRefreshAttempt, Instant.now());
    }
}
