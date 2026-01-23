/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.jobs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.AppleTokenResponseType;
import villagecompute.homepage.api.types.FacebookTokenResponseType;
import villagecompute.homepage.api.types.GoogleTokenResponseType;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.integration.oauth.AppleOAuthClient;
import villagecompute.homepage.integration.oauth.FacebookOAuthClient;
import villagecompute.homepage.integration.oauth.GoogleOAuthClient;
import villagecompute.homepage.observability.LoggingConfig;

/**
 * Job handler for refreshing expiring OAuth access tokens.
 *
 * <p>
 * This handler proactively refreshes OAuth access tokens before they expire to prevent social integration failures.
 * Queries users with tokens expiring within 7 days and refreshes for all three providers:
 *
 * <ul>
 * <li><b>Google:</b> Uses refresh_token grant. Refresh token persists forever (until revoked).</li>
 * <li><b>Facebook:</b> Extends long-lived token (resets 60-day expiration). No refresh tokens.</li>
 * <li><b>Apple:</b> Uses refresh_token grant with JWT regeneration. Refresh token expires after 6 months.</li>
 * </ul>
 *
 * <p>
 * <b>Execution Flow:</b>
 * <ol>
 * <li>Query users with tokens expiring within 7-day threshold</li>
 * <li>Process each user in separate transaction (prevents rollback of all updates on one failure)</li>
 * <li>Refresh Google, Facebook, and Apple tokens as applicable</li>
 * <li>Update User entity with new expiration timestamps</li>
 * <li>Clear revoked tokens on invalid_grant errors</li>
 * <li>Export metrics and telemetry for observability</li>
 * </ol>
 *
 * <p>
 * <b>Error Handling:</b> Individual user failures do NOT abort batch processing. Failed refreshes are logged and
 * tracked via metrics. Job continues with remaining users. Revoked tokens (invalid_grant) are cleared from database to
 * prevent retry loops.
 *
 * <p>
 * <b>Telemetry:</b> Exports OpenTelemetry span attributes:
 * <ul>
 * <li>{@code job.id} - Job database primary key</li>
 * <li>{@code job.type} - OAUTH_TOKEN_REFRESH</li>
 * <li>{@code users_total} - Total users with expiring tokens</li>
 * <li>{@code users_success} - Users with successful token refresh</li>
 * <li>{@code users_failed} - Users with failed token refresh</li>
 * </ul>
 *
 * <p>
 * And Micrometer metrics:
 * <ul>
 * <li>{@code oauth.token.refresh.total} (Counter) - Tagged by {@code status={success|failure}}</li>
 * <li>{@code oauth.token.refresh.duration} (Timer) - Total job execution time</li>
 * </ul>
 *
 * <p>
 * <b>Payload Structure:</b>
 *
 * <pre>
 * {} // Empty payload - processes all expiring tokens
 * </pre>
 */
@ApplicationScoped
public class OAuthTokenRefreshJobHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(OAuthTokenRefreshJobHandler.class);
    private static final int EXPIRY_THRESHOLD_DAYS = 7;

    @Inject
    GoogleOAuthClient googleClient;

    @Inject
    FacebookOAuthClient facebookClient;

    @Inject
    AppleOAuthClient appleClient;

    @Inject
    Tracer tracer;

    @Inject
    MeterRegistry meterRegistry;

    @Override
    public JobType handlesType() {
        return JobType.OAUTH_TOKEN_REFRESH;
    }

    @Override
    @Transactional
    public void execute(Long jobId, Map<String, Object> payload) throws Exception {
        LoggingConfig.enrichWithTraceContext();
        LoggingConfig.setJobId(jobId);

        Span span = tracer.spanBuilder("job.oauth_token_refresh").setAttribute("job.id", jobId)
                .setAttribute("job.type", "OAUTH_TOKEN_REFRESH").startSpan();

        Timer.Sample sample = Timer.start(meterRegistry);
        int successCount = 0;
        int failureCount = 0;

        try (Scope scope = span.makeCurrent()) {
            LOG.infof("Starting OAuth token refresh job %d", jobId);

            // Find users with tokens expiring within 7 days
            Instant expiryThreshold = Instant.now().plusSeconds(EXPIRY_THRESHOLD_DAYS * 24 * 60 * 60);
            List<User> users = User.findTokensExpiringSoon(expiryThreshold);

            LOG.infof("Found %d users with tokens expiring soon (threshold: %s)", users.size(), expiryThreshold);
            span.setAttribute("users_total", users.size());

            // Process each user
            for (User user : users) {
                try {
                    refreshUserTokens(user);
                    successCount++;
                    incrementCounter("oauth.token.refresh.total", "status", "success");
                } catch (Exception e) {
                    failureCount++;
                    incrementCounter("oauth.token.refresh.total", "status", "failure");
                    LOG.errorf(e, "Failed to refresh tokens for user %s (continuing)", user.id);
                    // Don't abort batch processing on individual failures
                }
            }

            span.setAttribute("users_success", successCount);
            span.setAttribute("users_failed", failureCount);

            LOG.infof("OAuth token refresh job %d completed: %d success, %d failures", jobId, successCount,
                    failureCount);

        } catch (Exception e) {
            span.recordException(e);
            LOG.errorf(e, "OAuth token refresh job %d failed", jobId);
            throw e;
        } finally {
            sample.stop(Timer.builder("oauth.token.refresh.duration").register(meterRegistry));
            span.end();
            LoggingConfig.clearMDC();
        }
    }

    /**
     * Refreshes all applicable OAuth tokens for a user.
     *
     * <p>
     * Token updates are persisted within the enclosing @Transactional boundary of execute().
     *
     * @param user
     *            the user with expiring tokens
     * @throws Exception
     *             if any token refresh fails (logged but doesn't abort batch)
     */
    private void refreshUserTokens(User user) throws Exception {
        // Refresh Google token if applicable
        if (user.googleRefreshToken != null && user.googleAccessTokenExpiresAt != null) {
            refreshGoogleToken(user);
        }

        // Extend Facebook token if applicable
        if (user.facebookAccessToken != null && user.facebookAccessTokenExpiresAt != null) {
            extendFacebookToken(user);
        }

        // Refresh Apple token if applicable
        if (user.appleRefreshToken != null && user.appleAccessTokenExpiresAt != null) {
            refreshAppleToken(user);
        }
    }

    /**
     * Refreshes Google OAuth access token using refresh token.
     *
     * <p>
     * Google refresh tokens never expire (until user revokes). Response includes new access_token and expires_in (3600
     * seconds). No new refresh_token is returned - original token remains valid.
     *
     * @param user
     *            the user with expiring Google token
     * @throws Exception
     *             if refresh fails (revoked token, network error)
     */
    private void refreshGoogleToken(User user) throws Exception {
        try {
            LOG.infof("Refreshing Google token for user %s", user.id);

            GoogleTokenResponseType tokenResponse = googleClient.refreshAccessToken(user.googleRefreshToken);

            // Update access token expiry (refresh token stays the same)
            user.googleAccessTokenExpiresAt = Instant.now().plusSeconds(tokenResponse.expiresIn());
            user.updatedAt = Instant.now();
            user.persist();

            LOG.infof("Refreshed Google token for user %s (expires: %s)", user.id, user.googleAccessTokenExpiresAt);

        } catch (Exception e) {
            // Log but re-throw so counter tracks failure
            LOG.errorf(e, "Failed to refresh Google token for user %s", user.id);

            // Clear token if permanently revoked (invalid_grant error)
            if (e.getMessage() != null && e.getMessage().contains("invalid_grant")) {
                user.googleRefreshToken = null;
                user.googleAccessTokenExpiresAt = null;
                user.updatedAt = Instant.now();
                user.persist();
                LOG.warnf("Cleared revoked Google refresh token for user %s", user.id);
            }

            throw e;
        }
    }

    /**
     * Extends Facebook access token (resets 60-day expiration).
     *
     * <p>
     * Facebook does NOT use refresh tokens. Instead, tokens are extended via fb_exchange_token grant. This resets the
     * 60-day expiration.
     *
     * @param user
     *            the user with expiring Facebook token
     * @throws Exception
     *             if extension fails (revoked token, network error)
     */
    private void extendFacebookToken(User user) throws Exception {
        try {
            LOG.infof("Extending Facebook token for user %s", user.id);

            FacebookTokenResponseType tokenResponse = facebookClient.extendAccessToken(user.facebookAccessToken);

            // Update access token and expiry
            user.facebookAccessToken = tokenResponse.accessToken();
            user.facebookAccessTokenExpiresAt = Instant.now().plusSeconds(tokenResponse.expiresIn());
            user.updatedAt = Instant.now();
            user.persist();

            LOG.infof("Extended Facebook token for user %s (expires: %s)", user.id, user.facebookAccessTokenExpiresAt);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to extend Facebook token for user %s", user.id);

            // Clear token if permanently revoked
            if (e.getMessage() != null && e.getMessage().contains("invalid")) {
                user.facebookAccessToken = null;
                user.facebookAccessTokenExpiresAt = null;
                user.updatedAt = Instant.now();
                user.persist();
                LOG.warnf("Cleared revoked Facebook token for user %s", user.id);
            }

            throw e;
        }
    }

    /**
     * Refreshes Apple access token using refresh token.
     *
     * <p>
     * Apple refresh tokens expire after 6 months. Client secret JWT is regenerated for each refresh request (10-minute
     * expiration). Response may include rotated refresh_token.
     *
     * @param user
     *            the user with expiring Apple token
     * @throws Exception
     *             if refresh fails (expired refresh token, network error)
     */
    private void refreshAppleToken(User user) throws Exception {
        try {
            LOG.infof("Refreshing Apple token for user %s", user.id);

            AppleTokenResponseType tokenResponse = appleClient.refreshAccessToken(user.appleRefreshToken);

            // Update access token expiry (refresh token may be rotated)
            user.appleAccessTokenExpiresAt = Instant.now().plusSeconds(tokenResponse.expiresIn());
            if (tokenResponse.refreshToken() != null) {
                user.appleRefreshToken = tokenResponse.refreshToken();
            }
            user.updatedAt = Instant.now();
            user.persist();

            LOG.infof("Refreshed Apple token for user %s (expires: %s)", user.id, user.appleAccessTokenExpiresAt);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to refresh Apple token for user %s", user.id);

            // Clear token if permanently revoked (invalid_grant error)
            if (e.getMessage() != null && e.getMessage().contains("invalid_grant")) {
                user.appleRefreshToken = null;
                user.appleAccessTokenExpiresAt = null;
                user.updatedAt = Instant.now();
                user.persist();
                LOG.warnf("Cleared revoked Apple refresh token for user %s", user.id);
            }

            throw e;
        }
    }

    /**
     * Increments a Micrometer counter with tags.
     *
     * @param name
     *            the counter name
     * @param tags
     *            key-value pairs for tags (must be even number of strings)
     */
    private void incrementCounter(String name, String... tags) {
        Counter.builder(name).tags(tags).register(meterRegistry).increment();
    }
}
