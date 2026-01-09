package villagecompute.homepage.jobs;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.FeedItem;
import villagecompute.homepage.data.models.RssSource;
import villagecompute.homepage.observability.LoggingConfig;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Job handler for RSS/Atom feed refresh with deduplication and error tracking.
 *
 * <p>
 * This handler fetches RSS/Atom feeds from configured sources, parses feed items using Rome Tools, deduplicates by
 * GUID, and persists new items with {@code ai_tagged = false} for downstream AI tagging pipeline (I3.T3).
 *
 * <p>
 * <b>Execution Flow:</b>
 * <ol>
 * <li>Extract {@code source_id} from payload (or null to refresh all due feeds)</li>
 * <li>Query {@link RssSource#findDueForRefresh()} for sources ready for refresh</li>
 * <li>For each source:
 * <ul>
 * <li>Fetch RSS XML via HTTP with 5-second connect timeout</li>
 * <li>Parse with Rome Tools {@link SyndFeedInput}</li>
 * <li>Extract {@link SyndEntry} list</li>
 * <li>For each entry: check {@link FeedItem#findByGuid(String)}, skip duplicates, persist new items</li>
 * <li>Call {@link RssSource#recordSuccess(RssSource)} to reset error count</li>
 * </ul>
 * </li>
 * <li>On error: call {@link RssSource#recordError(RssSource, String)} to increment error count</li>
 * <li>Export OpenTelemetry spans and Micrometer metrics for observability</li>
 * </ol>
 *
 * <p>
 * <b>Deduplication Strategy:</b> Primary deduplication via RSS GUID ({@code item_guid} unique constraint). Fallback to
 * URL + published_at hash if GUID is missing. Content hash (MD5 of title + description) stored for future similarity
 * detection.
 *
 * <p>
 * <b>Error Handling:</b> Network timeout, HTTP 4xx/5xx, invalid XML, and parse errors increment
 * {@code rss_sources.error_count}. After 5 consecutive errors, feed is auto-disabled per Policy P2 (Feed Governance).
 * Individual feed failures do NOT abort batch processing - job continues with remaining feeds.
 *
 * <p>
 * <b>Telemetry:</b> Exports the following OpenTelemetry span attributes:
 * <ul>
 * <li>{@code job.id} - Job database primary key</li>
 * <li>{@code job.type} - RSS_FEED_REFRESH</li>
 * <li>{@code source_id} - RSS source UUID</li>
 * <li>{@code items_fetched} - Total feed items parsed</li>
 * <li>{@code items_new} - New items persisted</li>
 * <li>{@code items_duplicate} - Duplicate items skipped</li>
 * </ul>
 *
 * <p>
 * And Micrometer metrics:
 * <ul>
 * <li>{@code rss.fetch.items.total} (Counter) - Tagged by {@code source_id}, {@code status={new|duplicate}}</li>
 * <li>{@code rss.fetch.duration} (Timer) - Tagged by {@code source_id}, {@code result={success|failure}}</li>
 * <li>{@code rss.fetch.errors.total} (Counter) - Tagged by {@code source_id}, {@code error_type}</li>
 * </ul>
 *
 * <p>
 * <b>Payload Structure:</b>
 *
 * <pre>
 * {
 *   "source_id": "uuid-string",  // Optional - if null, refresh all due feeds
 *   "force_refresh": false        // Optional - bypass interval check
 * }
 * </pre>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P2: Feed Governance - 5-error auto-disable, refresh intervals (15min-6hrs)</li>
 * <li>P7: Unified job orchestration via {@link DelayedJobService}</li>
 * <li>P10: AI tagging budget control - items marked {@code ai_tagged = false} for I3.T3 pipeline</li>
 * </ul>
 *
 * @see RssSource for feed source entity
 * @see FeedItem for feed item entity
 * @see villagecompute.homepage.jobs.RssFeedRefreshScheduler for scheduler
 */
@ApplicationScoped
public class RssFeedRefreshJobHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(RssFeedRefreshJobHandler.class);

    @Inject
    Tracer tracer;

    @Inject
    MeterRegistry meterRegistry;

    private final HttpClient httpClient;

    public RssFeedRefreshJobHandler() {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    @Override
    public JobType handlesType() {
        return JobType.RSS_FEED_REFRESH;
    }

    @Override
    public void execute(Long jobId, Map<String, Object> payload) throws Exception {
        Span span = tracer.spanBuilder("job.rss_feed_refresh").setAttribute("job.id", jobId)
                .setAttribute("job.type", JobType.RSS_FEED_REFRESH.name())
                .setAttribute("job.queue", JobQueue.DEFAULT.name()).startSpan();

        try (Scope scope = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            LoggingConfig.setJobId(jobId);
            LoggingConfig.setRequestOrigin("RssFeedRefreshJobHandler");

            LOG.infof("Starting RSS feed refresh job %d", jobId);

            // Extract payload parameters
            UUID sourceId = null;
            if (payload.containsKey("source_id") && payload.get("source_id") != null) {
                sourceId = UUID.fromString(payload.get("source_id").toString());
            }

            boolean forceRefresh = false;
            if (payload.containsKey("force_refresh")) {
                forceRefresh = Boolean.parseBoolean(payload.get("force_refresh").toString());
            }

            // Determine which sources to refresh
            List<RssSource> sources;
            if (sourceId != null) {
                RssSource source = RssSource.findById(sourceId);
                if (source == null) {
                    LOG.warnf("RSS source %s not found - skipping", sourceId);
                    span.addEvent("source.not_found");
                    return;
                }
                if (!source.isActive && !forceRefresh) {
                    LOG.warnf("RSS source %s is inactive - skipping", sourceId);
                    span.addEvent("source.inactive");
                    return;
                }
                sources = List.of(source);
            } else {
                sources = RssSource.findDueForRefresh();
            }

            if (sources.isEmpty()) {
                LOG.infof("No RSS sources due for refresh");
                span.addEvent("refresh.no_sources");
                return;
            }

            LOG.infof("Refreshing %d RSS source(s)", sources.size());
            span.setAttribute("sources.count", sources.size());

            int successCount = 0;
            int failureCount = 0;

            for (RssSource source : sources) {
                try {
                    refreshSingleSource(source);
                    successCount++;
                } catch (Exception e) {
                    failureCount++;
                    LOG.errorf(e, "Failed to refresh RSS source %s (%s)", source.id, source.name);
                    span.recordException(e);
                    // Continue processing other sources even if one fails
                }
            }

            span.addEvent("refresh.completed", Attributes.of(AttributeKey.longKey("sources.success"),
                    (long) successCount, AttributeKey.longKey("sources.failure"), (long) failureCount));

            LOG.infof("RSS feed refresh job %d completed: %d succeeded, %d failed", jobId, successCount, failureCount);

            if (failureCount > 0) {
                throw new RuntimeException(String.format("Refresh job partially failed: %d/%d sources failed",
                        failureCount, sources.size()));
            }

        } finally {
            LoggingConfig.clearMDC();
            span.end();
        }
    }

    /**
     * Refreshes a single RSS source by fetching, parsing, and persisting new feed items.
     *
     * @param source
     *            the RSS source to refresh
     * @throws Exception
     *             if refresh fails
     */
    private void refreshSingleSource(RssSource source) throws Exception {
        Span span = tracer.spanBuilder("refresh.fetch_source").setAttribute("source_id", source.id.toString())
                .setAttribute("source_name", source.name).setAttribute("source_url", source.url).startSpan();

        Timer.Sample timerSample = Timer.start(meterRegistry);

        try (Scope scope = span.makeCurrent()) {
            LOG.debugf("Fetching RSS source: id=%s, name=%s, url=%s", source.id, source.name, source.url);
            span.addEvent("fetch.started");

            // Fetch RSS XML via HTTP
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(source.url)).timeout(Duration.ofSeconds(30))
                    .GET().build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            span.setAttribute("http.status_code", response.statusCode());

            if (response.statusCode() != 200) {
                String errorMessage = String.format("HTTP %d: %s", response.statusCode(), source.url);
                recordError(source, errorMessage);

                Counter.builder("rss.fetch.errors.total").tag("source_id", source.id.toString())
                        .tag("error_type", "http_" + response.statusCode()).register(meterRegistry).increment();

                timerSample.stop(Timer.builder("rss.fetch.duration").tag("source_id", source.id.toString())
                        .tag("result", "failure").register(meterRegistry));

                throw new RuntimeException(errorMessage);
            }

            // Parse RSS/Atom feed with Rome Tools
            SyndFeedInput input = new SyndFeedInput();
            SyndFeed feed;

            try (InputStream inputStream = response.body()) {
                feed = input.build(new XmlReader(inputStream));
            }

            span.addEvent("parse.completed");

            List<SyndEntry> entries = feed.getEntries();

            LOG.debugf("Parsed %d entries from RSS source %s (%s)", entries.size(), source.id, source.name);
            span.setAttribute("items_fetched", entries.size());

            int newItemCount = 0;
            int duplicateCount = 0;

            for (SyndEntry entry : entries) {
                try {
                    // Extract entry fields
                    String guid = extractGuid(entry, source);
                    String title = entry.getTitle();
                    String url = entry.getLink();
                    String description = extractDescription(entry);
                    String content = extractContent(entry);
                    String author = entry.getAuthor();
                    Instant publishedAt = extractPublishedAt(entry);

                    // Validate required fields
                    if (title == null || title.isBlank() || url == null || url.isBlank()) {
                        LOG.warnf("Skipping entry with missing title/url: source=%s, guid=%s", source.id, guid);
                        span.addEvent("entry.missing_fields");
                        continue;
                    }

                    // Check for duplicate by GUID
                    if (FeedItem.findByGuid(guid).isPresent()) {
                        duplicateCount++;
                        continue;
                    }

                    // Create new feed item
                    FeedItem item = new FeedItem();
                    item.sourceId = source.id;
                    item.title = title;
                    item.url = url;
                    item.description = description;
                    item.content = content;
                    item.itemGuid = guid;
                    item.contentHash = calculateContentHash(title, description);
                    item.author = author;
                    item.publishedAt = publishedAt;
                    item.aiTagged = false; // Mark for AI tagging pipeline (I3.T3)

                    FeedItem.create(item);
                    newItemCount++;

                } catch (Exception e) {
                    LOG.warnf(e, "Failed to process entry from source %s: %s", source.id, e.getMessage());
                    span.recordException(e);
                    // Continue with other entries
                }
            }

            span.setAttribute("items_new", newItemCount);
            span.setAttribute("items_duplicate", duplicateCount);
            span.addEvent("dedupe.completed", Attributes.of(AttributeKey.longKey("new"), (long) newItemCount,
                    AttributeKey.longKey("duplicate"), (long) duplicateCount));

            // Record success metrics
            Counter.builder("rss.fetch.items.total").tag("source_id", source.id.toString()).tag("status", "new")
                    .register(meterRegistry).increment(newItemCount);

            Counter.builder("rss.fetch.items.total").tag("source_id", source.id.toString()).tag("status", "duplicate")
                    .register(meterRegistry).increment(duplicateCount);

            timerSample.stop(Timer.builder("rss.fetch.duration").tag("source_id", source.id.toString())
                    .tag("result", "success").register(meterRegistry));

            // Update source with successful fetch timestamp
            RssSource.recordSuccess(source);

            LOG.infof("Refreshed RSS source %s (%s): %d new items, %d duplicates", source.id, source.name, newItemCount,
                    duplicateCount);

        } catch (Exception e) {
            span.recordException(e);
            recordError(source, e.getMessage());

            Counter.builder("rss.fetch.errors.total").tag("source_id", source.id.toString())
                    .tag("error_type", e.getClass().getSimpleName()).register(meterRegistry).increment();

            timerSample.stop(Timer.builder("rss.fetch.duration").tag("source_id", source.id.toString())
                    .tag("result", "failure").register(meterRegistry));

            throw e;

        } finally {
            span.end();
        }
    }

    /**
     * Extracts entry GUID with fallback to URL + published_at hash.
     *
     * @param entry
     *            the RSS entry
     * @param source
     *            the RSS source
     * @return the entry GUID
     */
    private String extractGuid(SyndEntry entry, RssSource source) {
        String guid = entry.getUri();
        if (guid != null && !guid.isBlank()) {
            return guid;
        }

        // Fallback: URL + published_at
        String link = entry.getLink();
        Date pubDate = entry.getPublishedDate();

        if (link != null) {
            if (pubDate != null) {
                return link + "|" + pubDate.toInstant().toString();
            }
            return link;
        }

        // Last resort: source ID + title hash
        return source.id.toString() + "|" + (entry.getTitle() != null ? entry.getTitle().hashCode() : "");
    }

    /**
     * Extracts entry description.
     *
     * @param entry
     *            the RSS entry
     * @return the description text, or null if not available
     */
    private String extractDescription(SyndEntry entry) {
        if (entry.getDescription() != null && entry.getDescription().getValue() != null) {
            return entry.getDescription().getValue();
        }
        return null;
    }

    /**
     * Extracts entry full content.
     *
     * @param entry
     *            the RSS entry
     * @return the content text, or null if not available
     */
    private String extractContent(SyndEntry entry) {
        if (entry.getContents() != null && !entry.getContents().isEmpty()) {
            StringBuilder content = new StringBuilder();
            for (SyndContent syndContent : entry.getContents()) {
                if (syndContent.getValue() != null) {
                    content.append(syndContent.getValue()).append("\n");
                }
            }
            return content.toString().trim();
        }
        return null;
    }

    /**
     * Extracts entry published timestamp with fallback to current time.
     *
     * @param entry
     *            the RSS entry
     * @return the published timestamp
     */
    private Instant extractPublishedAt(SyndEntry entry) {
        Date pubDate = entry.getPublishedDate();
        if (pubDate != null) {
            return pubDate.toInstant();
        }

        Date updatedDate = entry.getUpdatedDate();
        if (updatedDate != null) {
            return updatedDate.toInstant();
        }

        // Fallback to current time if no date available
        return Instant.now();
    }

    /**
     * Calculates MD5 content hash for similarity detection.
     *
     * @param title
     *            the article title
     * @param description
     *            the article description
     * @return Base64-encoded MD5 hash
     */
    private String calculateContentHash(String title, String description) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            String combined = (title != null ? title : "") + "\n" + (description != null ? description : "");
            byte[] digest = md.digest(combined.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to calculate content hash");
            return null;
        }
    }

    /**
     * Records a fetch error and increments error count.
     *
     * @param source
     *            the RSS source
     * @param errorMessage
     *            the error message
     */
    private void recordError(RssSource source, String errorMessage) {
        try {
            RssSource.recordError(source, errorMessage);
            LOG.warnf("Recorded error for RSS source %s (%s): %s (error_count=%d)", source.id, source.name,
                    errorMessage, source.errorCount + 1);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to record error for RSS source %s", source.id);
        }
    }
}
