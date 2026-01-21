/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.jobs;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import villagecompute.homepage.data.models.ProfileCuratedArticle;
import villagecompute.homepage.observability.LoggingConfig;
import villagecompute.homepage.services.MetadataFetchService;
import villagecompute.homepage.services.MetadataFetchService.SiteMetadata;

/**
 * Job handler for refreshing OpenGraph metadata on manually-curated profile articles.
 *
 * <p>
 * This handler fetches updated OpenGraph metadata (title, description, og:image) for manually-entered curated articles
 * and updates the original_* fields in the database. Typically scheduled daily to keep article metadata current.
 *
 * <p>
 * <b>Execution Flow:</b>
 * <ol>
 * <li>Extract article_id from job payload</li>
 * <li>Load ProfileCuratedArticle from database</li>
 * <li>Fetch OpenGraph metadata via {@link MetadataFetchService}</li>
 * <li>Update original_title, original_description, original_image_url if changed</li>
 * <li>Export telemetry spans and metrics to Prometheus</li>
 * </ol>
 *
 * <p>
 * Fetch failures (timeout, 404, invalid HTML) are logged as warnings but do NOT fail the job - existing metadata is
 * preserved.
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P7 (Observability): OpenTelemetry traces and Micrometer metrics exported</li>
 * <li>F11.7 (Metadata Refresh): Ensures curated article metadata stays current</li>
 * </ul>
 *
 * @see JobType#PROFILE_METADATA_REFRESH
 * @see MetadataFetchService
 * @see ProfileCuratedArticle
 */
@ApplicationScoped
public class ProfileMetadataRefreshJobHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(ProfileMetadataRefreshJobHandler.class);

    @Inject
    Tracer tracer;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    MetadataFetchService metadataFetchService;

    // Metrics
    private Counter successCounter;
    private Counter failureCounter;

    @Override
    public JobType handlesType() {
        return JobType.PROFILE_METADATA_REFRESH;
    }

    @Override
    @Transactional
    public void execute(Long jobId, Map<String, Object> payload) throws Exception {
        // Initialize metrics lazily
        initializeMetrics();

        // Set job context for logging
        LoggingConfig.enrichWithTraceContext();
        LoggingConfig.setJobId(jobId);

        Span span = tracer.spanBuilder("job.profile_metadata_refresh").setAttribute("job.id", jobId.toString())
                .setAttribute("job.type", JobType.PROFILE_METADATA_REFRESH.name())
                .setAttribute("job.queue", JobQueue.DEFAULT.name()).startSpan();

        try {
            LOG.infof("Starting profile metadata refresh job: jobId=%s", jobId);

            // Extract article_id from payload
            String articleIdStr = (String) payload.get("article_id");
            if (articleIdStr == null || articleIdStr.isBlank()) {
                throw new IllegalArgumentException("Missing required payload field: article_id");
            }

            UUID articleId = UUID.fromString(articleIdStr);
            span.setAttribute("article.id", articleId.toString());

            // Load article from database
            Optional<ProfileCuratedArticle> articleOpt = ProfileCuratedArticle.findByIdOptional(articleId);
            if (articleOpt.isEmpty()) {
                LOG.warnf("Article not found, skipping: articleId=%s", articleId);
                span.addEvent("article_not_found");
                span.setStatus(StatusCode.OK, "Article not found (may have been deleted)");
                return;
            }

            ProfileCuratedArticle article = articleOpt.get();
            String url = article.originalUrl;
            span.setAttribute("article.url", url);

            LOG.debugf("Refreshing metadata for article: id=%s, url=%s", articleId, url);

            // Fetch OpenGraph metadata
            try {
                SiteMetadata metadata = metadataFetchService.fetchMetadata(url);

                // Track if any fields changed
                boolean changed = false;

                // Update original_title if changed
                if (metadata.title() != null && !metadata.title().equals(article.originalTitle)) {
                    LOG.debugf("Updating title: old=\"%s\", new=\"%s\"", article.originalTitle, metadata.title());
                    article.originalTitle = metadata.title();
                    changed = true;
                }

                // Update original_description if changed
                if (metadata.description() != null && !metadata.description().equals(article.originalDescription)) {
                    LOG.debugf("Updating description: old=\"%s\", new=\"%s\"", article.originalDescription,
                            metadata.description());
                    article.originalDescription = metadata.description();
                    changed = true;
                }

                // Update original_image_url if changed
                if (metadata.ogImageUrl() != null && !metadata.ogImageUrl().equals(article.originalImageUrl)) {
                    LOG.debugf("Updating image URL: old=\"%s\", new=\"%s\"", article.originalImageUrl,
                            metadata.ogImageUrl());
                    article.originalImageUrl = metadata.ogImageUrl();
                    changed = true;
                }

                if (changed) {
                    article.updatedAt = java.time.Instant.now();
                    article.persist();
                    LOG.infof("Updated metadata for article: id=%s", articleId);
                    span.addEvent("metadata_updated");
                } else {
                    LOG.debugf("No metadata changes detected for article: id=%s", articleId);
                    span.addEvent("metadata_unchanged");
                }

                successCounter.increment();
                span.setStatus(StatusCode.OK, "Metadata refreshed successfully");

            } catch (Exception e) {
                LOG.warnf(e, "Failed to fetch metadata for article: id=%s, url=%s (preserving existing metadata)",
                        articleId, url);
                failureCounter.increment();
                span.recordException(e);
                span.addEvent("metadata_fetch_failed");
                // Don't throw - preserve existing metadata and mark job as success
                span.setStatus(StatusCode.OK, "Fetch failed, existing metadata preserved");
            }

        } catch (Exception e) {
            LOG.errorf(e, "Profile metadata refresh job failed: jobId=%s", jobId);
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;

        } finally {
            span.end();
            LoggingConfig.clearMDC();
        }
    }

    /**
     * Initializes Micrometer metrics on first invocation.
     */
    private void initializeMetrics() {
        if (successCounter == null) {
            successCounter = Counter.builder("profile.metadata_refresh.total").tag("status", "success")
                    .description("Total profile articles successfully refreshed").register(meterRegistry);
        }

        if (failureCounter == null) {
            failureCounter = Counter.builder("profile.metadata_refresh.total").tag("status", "failure")
                    .description("Total profile articles failed to refresh").register(meterRegistry);
        }
    }
}
