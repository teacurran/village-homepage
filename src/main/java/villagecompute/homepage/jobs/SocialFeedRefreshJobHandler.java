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
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.SocialPost;
import villagecompute.homepage.data.models.SocialToken;
import villagecompute.homepage.observability.LoggingConfig;
import villagecompute.homepage.services.SocialIntegrationService;

import java.util.List;
import java.util.Map;

/**
 * Job handler for social feed refresh from Meta Graph API (Instagram and Facebook).
 *
 * <p>
 * This handler refreshes social media posts for all active users by:
 * <ol>
 * <li>Querying all active (non-revoked) social tokens</li>
 * <li>Grouping tokens by platform for batch processing</li>
 * <li>Calling {@link SocialIntegrationService#refreshPosts(Long)} for each token</li>
 * <li>Archiving posts for tokens expired > 7 days (Policy P13)</li>
 * <li>Exporting metrics and telemetry for observability</li>
 * </ol>
 *
 * <p>
 * <b>Execution Flow:</b>
 * <ul>
 * <li>Empty payload: refresh all active tokens (30-min scheduled job)</li>
 * <li>Payload with {@code token_id}: refresh specific token only</li>
 * <li>Payload with {@code archive_expired=true}: archive posts for very stale tokens</li>
 * </ul>
 *
 * <p>
 * <b>Error Handling:</b> Individual token failures do NOT abort batch processing. Failed tokens are logged and tracked
 * via metrics. Job continues with remaining tokens.
 *
 * <p>
 * <b>Telemetry:</b> Exports OpenTelemetry span attributes:
 * <ul>
 * <li>{@code job.id} - Job database primary key</li>
 * <li>{@code job.type} - SOCIAL_REFRESH</li>
 * <li>{@code tokens_total} - Total active tokens processed</li>
 * <li>{@code tokens_success} - Tokens refreshed successfully</li>
 * <li>{@code tokens_failed} - Tokens that failed to refresh</li>
 * <li>{@code posts_archived} - Posts archived due to token expiration</li>
 * </ul>
 *
 * <p>
 * And Micrometer metrics:
 * <ul>
 * <li>{@code social.refresh.total} (Counter) - Tagged by {@code platform, status={success|failure}}</li>
 * <li>{@code social.refresh.duration} (Timer) - Total job execution time</li>
 * <li>{@code social.posts.archived} (Counter) - Posts archived per platform</li>
 * </ul>
 *
 * <p>
 * <b>Payload Structure:</b>
 *
 * <pre>
 * {
 *   "token_id": 12345,           // Optional - refresh specific token
 *   "archive_expired": true      // Optional - archive posts for very stale tokens
 * }
 * </pre>
 */
@ApplicationScoped
public class SocialFeedRefreshJobHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(SocialFeedRefreshJobHandler.class);

    @Inject
    SocialIntegrationService socialIntegrationService;

    @Inject
    Tracer tracer;

    @Inject
    MeterRegistry meterRegistry;

    @Override
    public JobType handlesType() {
        return JobType.SOCIAL_REFRESH;
    }

    @Override
    @Transactional
    public void execute(Long jobId, Map<String, Object> payload) throws Exception {
        LoggingConfig.enrichWithTraceContext();
        LoggingConfig.setJobId(jobId);

        Span span = tracer.spanBuilder("job.social_refresh").setAttribute("job.id", jobId)
                .setAttribute("job.type", "SOCIAL_REFRESH").startSpan();

        Timer.Sample sample = Timer.start(meterRegistry);
        int successCount = 0;
        int failureCount = 0;
        int archivedCount = 0;

        try (Scope scope = span.makeCurrent()) {
            LOG.infof("Starting social feed refresh job %d", jobId);

            // Extract payload parameters
            Long targetTokenId = payload.containsKey("token_id") ? Long.valueOf(payload.get("token_id").toString())
                    : null;
            boolean archiveExpired = payload.containsKey("archive_expired") && (Boolean) payload.get("archive_expired");

            span.setAttribute("target_token_id", targetTokenId != null ? targetTokenId.toString() : "all");
            span.setAttribute("archive_expired", archiveExpired);

            // Collect all active social tokens
            List<SocialToken> tokens;
            if (targetTokenId != null) {
                SocialToken token = SocialToken.findById(targetTokenId);
                if (token == null) {
                    throw new IllegalArgumentException("Token " + targetTokenId + " not found");
                }
                tokens = List.of(token);
            } else {
                tokens = SocialToken.findAllActive();
            }

            span.setAttribute("tokens_total", tokens.size());
            LOG.infof("Found %d active social tokens to refresh", tokens.size());

            // Refresh each token independently
            for (SocialToken token : tokens) {
                try {
                    // Check if token is very stale (> 7 days)
                    if (token.getDaysSinceRefresh() > 7) {
                        LOG.warnf("Token %d is very stale (%d days), skipping refresh", token.id,
                                token.getDaysSinceRefresh());
                        incrementCounter("social.refresh.total", token.platform, "skipped_stale");

                        // Archive posts if requested
                        if (archiveExpired) {
                            int archived = SocialPost.archiveAllByToken(token.id);
                            archivedCount += archived;
                            incrementCounter("social.posts.archived", token.platform, String.valueOf(archived));
                        }
                        continue;
                    }

                    // Refresh posts for this token
                    socialIntegrationService.refreshPosts(token.id);
                    successCount++;
                    incrementCounter("social.refresh.total", token.platform, "success");
                    LOG.debugf("Refreshed posts for token %d (user %s platform %s)", token.id, token.userId,
                            token.platform);

                } catch (Exception e) {
                    failureCount++;
                    incrementCounter("social.refresh.total", token.platform, "failure");
                    LOG.errorf(e, "Failed to refresh token %d (continuing)", token.id);
                    // Don't abort batch processing on individual failures
                }
            }

            span.setAttribute("tokens_success", successCount);
            span.setAttribute("tokens_failed", failureCount);
            span.setAttribute("posts_archived", archivedCount);

            LOG.infof("Social feed refresh job %d completed: %d success, %d failures, %d posts archived", jobId,
                    successCount, failureCount, archivedCount);

        } catch (Exception e) {
            span.recordException(e);
            LOG.errorf(e, "Social feed refresh job %d failed", jobId);
            throw e;
        } finally {
            sample.stop(Timer.builder("social.refresh.duration").register(meterRegistry));
            span.end();
            LoggingConfig.clearMDC();
        }
    }

    /**
     * Helper to increment Micrometer counter.
     */
    private void incrementCounter(String name, String platform, String status) {
        Counter.builder(name).tag("platform", platform).tag("status", status).register(meterRegistry).increment();
    }
}
