package villagecompute.homepage.jobs;

/**
 * Enumeration of all async job types with their queue assignments and execution cadence.
 *
 * <p>
 * Each job type maps to exactly one {@link JobQueue} family. Handler implementations must register themselves with the
 * corresponding job type for CDI discovery.
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P7: All job types participate in unified orchestration framework</li>
 * <li>P10: AI_TAGGING enforces $500/month budget ceiling via AiTaggingService</li>
 * <li>P12: SCREENSHOT_CAPTURE runs in dedicated SCREENSHOT queue with concurrency limits</li>
 * <li>P14: CLICK_ROLLUP respects consent gating and 90-day data retention</li>
 * </ul>
 *
 * @see JobQueue for queue family descriptions
 * @see JobHandler for handler contract
 */
public enum JobType {

    // ========== DEFAULT QUEUE ==========

    /**
     * Fetches and parses RSS feeds for news/content aggregation.
     * <p>
     * <b>Cadence:</b> 15min to daily (configurable per feed)
     * <p>
     * <b>Handler:</b> RssFeedRefreshHandler (future)
     */
    RSS_FEED_REFRESH(JobQueue.DEFAULT, "Feed refresh (15min-daily)"),

    /**
     * Updates weather forecasts from Open-Meteo and NWS APIs.
     * <p>
     * <b>Cadence:</b> Every 1 hour
     * <p>
     * <b>Handler:</b> WeatherRefreshHandler (future)
     */
    WEATHER_REFRESH(JobQueue.DEFAULT, "Weather refresh (1 hour)"),

    /**
     * Expires old marketplace listings past their configured TTL.
     * <p>
     * <b>Cadence:</b> Daily at 2am UTC
     * <p>
     * <b>Handler:</b> ListingExpirationJobHandler
     */
    LISTING_EXPIRATION(JobQueue.DEFAULT, "Listing expiration (daily)"),

    /**
     * Sends reminder emails to sellers for listings expiring within 2-3 days.
     * <p>
     * <b>Cadence:</b> Daily at 10am UTC
     * <p>
     * <b>Handler:</b> ListingReminderJobHandler
     */
    LISTING_REMINDER(JobQueue.DEFAULT, "Listing reminder (daily)"),

    /**
     * Expires old featured listing promotions past their 7-day duration.
     * <p>
     * <b>Cadence:</b> Daily at 3am UTC
     * <p>
     * <b>Handler:</b> PromotionExpirationJobHandler
     */
    PROMOTION_EXPIRATION(JobQueue.DEFAULT, "Promotion expiration (daily)"),

    /**
     * Recalculates Good Sites ranking scores based on votes and category position.
     * <p>
     * <b>Cadence:</b> Every 1 hour
     * <p>
     * <b>Handler:</b> RankRecalculationHandler (future)
     */
    RANK_RECALCULATION(JobQueue.DEFAULT, "Rank recalculation (hourly)"),

    /**
     * Polls IMAP inbox for marketplace reply-relay messages.
     * <p>
     * <b>Cadence:</b> Every 1 minute
     * <p>
     * <b>Handler:</b> InboundEmailProcessor
     */
    INBOUND_EMAIL(JobQueue.DEFAULT, "Inbound email parsing (1 minute)"),

    /**
     * Purges soft-deleted anonymous user records after 90-day retention period.
     * <p>
     * <b>Cadence:</b> Daily at 4am UTC
     * <p>
     * <b>Handler:</b> AccountMergeCleanupJobHandler
     * <p>
     * <b>Policy P1:</b> GDPR/CCPA compliance - hard-deletes anonymous accounts merged into authenticated accounts
     */
    ACCOUNT_MERGE_CLEANUP(JobQueue.DEFAULT, "Account merge cleanup (daily, P1 enforced)"),

    // ========== HIGH QUEUE ==========

    /**
     * Fetches real-time stock quotes from Alpha Vantage during market hours.
     * <p>
     * <b>Cadence:</b> Every 5 minutes (9:30am-4pm ET)
     * <p>
     * <b>Handler:</b> StockRefreshHandler (future)
     */
    STOCK_REFRESH(JobQueue.HIGH, "Stock refresh (5 min market hours)"),

    /**
     * Relays marketplace inquiry messages to seller via masked email.
     * <p>
     * <b>Cadence:</b> On-demand (triggered by user action)
     * <p>
     * <b>Handler:</b> MessageRelayJobHandler
     */
    MESSAGE_RELAY(JobQueue.HIGH, "Message relay (on-demand)"),

    // ========== LOW QUEUE ==========

    /**
     * Syncs Instagram/Facebook posts via Meta Graph API.
     * <p>
     * <b>Cadence:</b> Every 30 minutes
     * <p>
     * <b>Handler:</b> SocialRefreshHandler (future)
     * <p>
     * <b>Policy P5/P13:</b> Secure token storage required
     */
    SOCIAL_REFRESH(JobQueue.LOW, "Social refresh (30 min)"),

    /**
     * Checks Good Sites links for HTTP errors and updates health status.
     * <p>
     * <b>Cadence:</b> Weekly
     * <p>
     * <b>Handler:</b> LinkHealthCheckHandler (future)
     */
    LINK_HEALTH_CHECK(JobQueue.LOW, "Link health check (weekly)"),

    /**
     * Generates XML sitemaps for SEO indexing.
     * <p>
     * <b>Cadence:</b> Daily at 3am UTC
     * <p>
     * <b>Handler:</b> SitemapGenerationHandler (future)
     */
    SITEMAP_GENERATION(JobQueue.LOW, "Sitemap generation (daily)"),

    /**
     * Rolls up click tracking events into aggregated metrics.
     * <p>
     * <b>Cadence:</b> Hourly
     * <p>
     * <b>Handler:</b> ClickRollupHandler (future)
     * <p>
     * <b>Policy P14:</b> Consent-gated, 90-day retention enforced
     */
    CLICK_ROLLUP(JobQueue.LOW, "Click rollup (hourly)"),

    // ========== BULK QUEUE ==========

    /**
     * Applies LangChain4j topic tagging to news/feed items.
     * <p>
     * <b>Cadence:</b> On-demand (batch processing)
     * <p>
     * <b>Handler:</b> AiTaggingHandler (future)
     * <p>
     * <b>Policy P10:</b> Must check AiTaggingService budget ceiling before execution
     */
    AI_TAGGING(JobQueue.BULK, "AI tagging (on-demand, P10 budget enforced)"),

    /**
     * Resizes and optimizes uploaded marketplace listing images.
     * <p>
     * Generates 3 variants: thumbnail (150x150), list (300x225), full (1200x900). WebP conversion is currently stubbed
     * - variants use original format until conversion implemented.
     * <p>
     * <b>Cadence:</b> On-demand (triggered by upload)
     * <p>
     * <b>Handler:</b> ListingImageProcessingJobHandler
     */
    LISTING_IMAGE_PROCESSING(JobQueue.BULK, "Listing image processing (on-demand)"),

    /**
     * Cleans up listing images from R2 storage when listing is deleted or expired.
     * <p>
     * Deletes both database records and R2 objects for all variants (original, thumbnail, list, full).
     * <p>
     * <b>Cadence:</b> On-demand (triggered by listing soft-delete or expiration)
     * <p>
     * <b>Handler:</b> ListingImageCleanupJobHandler
     * <p>
     * <b>Policy P1:</b> CASCADE delete ensures GDPR compliance
     */
    LISTING_IMAGE_CLEANUP(JobQueue.BULK, "Listing image cleanup (on-demand, P1 enforced)"),

    // ========== SCREENSHOT QUEUE ==========

    /**
     * Captures website screenshots using jvppeteer/Chromium for Good Sites preview.
     * <p>
     * <b>Cadence:</b> On-demand (triggered by site submission)
     * <p>
     * <b>Handler:</b> ScreenshotCaptureHandler (future)
     * <p>
     * <b>Policy P12:</b> Dedicated worker pool with semaphore-limited concurrency (3 workers)
     */
    SCREENSHOT_CAPTURE(JobQueue.SCREENSHOT, "Screenshot capture (on-demand, P12 enforced)");

    private final JobQueue queue;
    private final String description;

    JobType(JobQueue queue, String description) {
        this.queue = queue;
        this.description = description;
    }

    /**
     * Returns the queue family this job type executes in.
     */
    public JobQueue getQueue() {
        return queue;
    }

    /**
     * Returns a human-readable description including cadence and policy notes.
     */
    public String getDescription() {
        return description;
    }
}
