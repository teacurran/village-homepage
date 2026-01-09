package villagecompute.homepage.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.WeatherCache;
import villagecompute.homepage.jobs.JobQueue;
import villagecompute.homepage.services.DelayedJobService;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Registers and manages custom observability metrics for the Village Homepage application.
 *
 * <p>
 * This producer creates gauges, counters, and timers as specified in the Observability Implementation Blueprint
 * (Section 3.1). All metrics follow the naming convention: {@code homepage_<category>_<metric>} with standardized tags
 * for filtering and aggregation.
 *
 * <p>
 * <b>Metrics Catalog (per Section 3.1):</b>
 * <ul>
 * <li><b>Gauges:</b> {@code homepage_jobs_depth{queue}} - Delayed job backlog per queue family</li>
 * <li><b>Gauges:</b> {@code homepage_screenshot_slots_available} - Available screenshot worker permits (P12)</li>
 * <li><b>Gauges:</b> {@code homepage_ai_budget_consumed_percent} - AI tagging budget utilization (0-100)</li>
 * <li><b>Gauges:</b> {@code homepage_weather_cache_staleness_minutes} - Age of oldest weather cache entry in
 * minutes</li>
 * <li><b>Timers:</b> Feed ingestion latency (future - requires feed service integration)</li>
 * <li><b>Counters:</b> AI tag batches processed (future - requires AI service integration)</li>
 * <li><b>Histograms:</b> Screenshot capture duration (future - requires screenshot service integration)</li>
 * <li><b>Counters:</b> {@code homepage_rate_limit_checks_total{action,tier,result}} - Rate limit check outcomes</li>
 * <li><b>Counters:</b> {@code homepage_rate_limit_violations_total{action,tier}} - Rate limit violations</li>
 * </ul>
 *
 * <p>
 * <b>HTTP Metrics:</b> Quarkus automatically exposes HTTP request metrics (response times, status codes, throughput)
 * via the {@code http_server_*} prefix. No custom registration required.
 *
 * <p>
 * <b>Dashboard Integration:</b> Metrics are exported in Prometheus format at {@code /q/metrics}. Ops teams can
 * configure Grafana dashboards using the metric names and tags defined here. See {@code docs/ops/observability.md} for
 * dashboard seed queries.
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P10: AI budget metrics trigger alerts at >90% consumption</li>
 * <li>P12: Screenshot slot metrics monitor concurrency enforcement</li>
 * </ul>
 *
 * @see LoggingConfig for structured logging field definitions
 */
@ApplicationScoped
public class ObservabilityMetrics {

    private static final Logger LOG = Logger.getLogger(ObservabilityMetrics.class);

    @Inject
    MeterRegistry registry;

    @Inject
    DelayedJobService delayedJobService;

    @ConfigProperty(
            name = "villagecompute.ai.monthly-budget-dollars",
            defaultValue = "500")
    double aiMonthlyBudgetDollars;

    /**
     * Placeholder for current AI budget consumption tracking. Will be replaced with actual service integration in
     * future iterations.
     */
    private final AtomicInteger aiTaggingBudgetConsumedCents = new AtomicInteger(0);

    /**
     * Rate limit check counters indexed by action:tier:result.
     *
     * <p>
     * Tracks outcomes of all rate limit checks for monitoring and alerting. Result values: "allowed", "denied".
     */
    private final Map<String, Counter> rateLimitCheckCounters = new ConcurrentHashMap<>();

    /**
     * Rate limit violation counters indexed by action:tier.
     *
     * <p>
     * Tracks violation events for abuse detection and capacity planning.
     */
    private final Map<String, Counter> rateLimitViolationCounters = new ConcurrentHashMap<>();

    /**
     * Registers all custom metrics at application startup.
     *
     * <p>
     * This method is invoked automatically by the CDI container during the {@code ApplicationScoped} bean
     * initialization phase. Metrics are registered once and remain active for the application lifetime.
     *
     * <p>
     * <b>Implementation Note:</b> Job depth gauges currently return 0 because the DelayedJob entity and repository
     * don't exist yet. Future work will wire these gauges to actual database queries via Panache static methods.
     */
    public void registerMetrics(
            @jakarta.enterprise.event.Observes @jakarta.enterprise.context.Initialized(ApplicationScoped.class) Object init) {
        LOG.info("Registering observability metrics");

        // Register job depth gauges for each queue family
        for (JobQueue queue : JobQueue.values()) {
            Gauge.builder("homepage_jobs_depth", this, m -> getJobDepth(queue))
                    .description("Number of pending jobs in the " + queue.name() + " queue").tags(List
                            .of(Tag.of("queue", queue.name()), Tag.of("priority", String.valueOf(queue.getPriority()))))
                    .register(registry);

            LOG.debugf("Registered gauge: homepage_jobs_depth{queue=%s,priority=%d}", queue.name(),
                    queue.getPriority());
        }

        // Register screenshot worker slot availability (P12 enforcement metric)
        Gauge.builder("homepage_screenshot_slots_available", delayedJobService,
                DelayedJobService::getAvailableScreenshotSlots)
                .description("Available screenshot worker permits (max 3 per pod per P12)")
                .tags(List.of(Tag.of("queue", JobQueue.SCREENSHOT.name()))).register(registry);
        LOG.debug("Registered gauge: homepage_screenshot_slots_available");

        // Register AI budget consumption gauge (P10 enforcement metric)
        Gauge.builder("homepage_ai_budget_consumed_percent", this, m -> getAiBudgetConsumedPercent())
                .description("AI tagging budget consumption as percentage of monthly ceiling (triggers alert at >90%)")
                .tags(List.of(Tag.of("budget_ceiling_dollars", String.valueOf((int) aiMonthlyBudgetDollars))))
                .register(registry);
        LOG.debug("Registered gauge: homepage_ai_budget_consumed_percent");

        // Register weather cache staleness gauge (I3.T9 content services monitoring)
        Gauge.builder("homepage_weather_cache_staleness_minutes", this, m -> getWeatherCacheStalenessMinutes())
                .description("Age of oldest weather cache entry in minutes (alert at >90 min)").register(registry);
        LOG.debug("Registered gauge: homepage_weather_cache_staleness_minutes");

        LOG.infof("Observability metrics registration complete. Access metrics at /q/metrics");
    }

    /**
     * Returns the current depth (pending job count) for the specified queue.
     *
     * <p>
     * <b>Future Implementation:</b> This method will query the DelayedJob entity using:
     *
     * <pre>
     * return DelayedJob.count("queue = ?1 AND locked_at IS NULL AND failed_at IS NULL", queue.name());
     * </pre>
     *
     * @param queue
     *            the queue family to measure
     * @return pending job count (currently always 0)
     */
    private long getJobDepth(JobQueue queue) {
        // TODO: Replace with actual database query when DelayedJob entity exists
        // return DelayedJob.count("queue = ?1 AND locked_at IS NULL AND failed_at IS NULL", queue.name());
        return 0L;
    }

    /**
     * Returns the AI tagging budget consumption as a percentage (0-100).
     *
     * <p>
     * <b>Future Implementation:</b> This method will integrate with AiTaggingService to track OpenAI API costs:
     *
     * <pre>
     * double consumedCents = aiTaggingService.getCurrentMonthSpendCents();
     * return (consumedCents / (aiMonthlyBudgetDollars * 100.0)) * 100.0;
     * </pre>
     *
     * <p>
     * <b>Alerting:</b> Ops teams should configure alerts to trigger when this metric exceeds 90% (per Section 3.1).
     *
     * @return budget consumption percentage (currently returns mock value)
     */
    private double getAiBudgetConsumedPercent() {
        // TODO: Replace with actual AiTaggingService integration
        // double consumedCents = aiTaggingService.getCurrentMonthSpendCents();
        // return (consumedCents / (aiMonthlyBudgetDollars * 100.0)) * 100.0;

        // Mock value for initial deployment
        double consumedCents = aiTaggingBudgetConsumedCents.get();
        return (consumedCents / (aiMonthlyBudgetDollars * 100.0)) * 100.0;
    }

    /**
     * Returns the age of the oldest weather cache entry in minutes.
     *
     * <p>
     * Used to monitor weather cache staleness and trigger alerts when data becomes too outdated. Per I3.T9 acceptance
     * criteria, alerts should fire when staleness exceeds 90 minutes.
     *
     * <p>
     * <b>Implementation:</b> Queries WeatherCache to find the minimum fetchedAt timestamp and calculates the age in
     * minutes from the current time.
     *
     * <p>
     * <b>Alerting:</b> Ops teams should configure alerts to trigger when this metric exceeds 90 minutes.
     *
     * @return staleness in minutes (0.0 if no cache entries exist)
     */
    private double getWeatherCacheStalenessMinutes() {
        try {
            // Find oldest cache entry by fetchedAt timestamp
            Optional<WeatherCache> oldestEntry = WeatherCache.find("ORDER BY fetchedAt ASC").firstResultOptional();

            if (oldestEntry.isEmpty()) {
                return 0.0; // No cache entries exist
            }

            // Calculate age in minutes
            Duration age = Duration.between(oldestEntry.get().fetchedAt, Instant.now());
            return age.toMinutes();
        } catch (Exception e) {
            // Return 0 on query errors to avoid breaking metrics collection
            LOG.warnf(e, "Failed to calculate weather cache staleness, returning 0");
            return 0.0;
        }
    }

    /**
     * Updates the AI budget consumption tracking. Exposed for testing and future service integration.
     *
     * <p>
     * <b>Note:</b> This method is a placeholder and will be removed when AiTaggingService provides real-time budget
     * tracking.
     *
     * @param cents
     *            total cents consumed in current month
     */
    public void setAiBudgetConsumedCents(int cents) {
        aiTaggingBudgetConsumedCents.set(cents);
    }

    /**
     * Increments the rate limit check counter for a specific action, tier, and result.
     *
     * <p>
     * Called by RateLimitService to track all rate limit check outcomes.
     *
     * @param actionType
     *            action identifier (e.g., "login", "search")
     * @param tier
     *            user tier ("anonymous", "logged_in", "trusted")
     * @param allowed
     *            true if request was allowed, false if denied
     */
    public void incrementRateLimitCheck(String actionType, String tier, boolean allowed) {
        String result = allowed ? "allowed" : "denied";
        String key = actionType + ":" + tier + ":" + result;

        Counter counter = rateLimitCheckCounters.computeIfAbsent(key, k -> {
            return Counter.builder("homepage_rate_limit_checks_total").description("Total rate limit checks performed")
                    .tags(List.of(Tag.of("action", actionType), Tag.of("tier", tier), Tag.of("result", result)))
                    .register(registry);
        });

        counter.increment();
    }

    /**
     * Increments the rate limit violation counter for a specific action and tier.
     *
     * <p>
     * Called by RateLimitService when a violation is recorded.
     *
     * @param actionType
     *            action identifier
     * @param tier
     *            user tier
     */
    public void incrementRateLimitViolation(String actionType, String tier) {
        String key = actionType + ":" + tier;

        Counter counter = rateLimitViolationCounters.computeIfAbsent(key, k -> {
            return Counter.builder("homepage_rate_limit_violations_total")
                    .description("Total rate limit violations recorded")
                    .tags(List.of(Tag.of("action", actionType), Tag.of("tier", tier))).register(registry);
        });

        counter.increment();
    }

    /**
     * Increments the payment intent created counter.
     *
     * <p>
     * Called by PaymentService when a Stripe Payment Intent is created.
     *
     * @param paymentType
     *            payment type: "posting_fee" or "promotion_featured" or "promotion_bump"
     */
    public void incrementPaymentIntentCreated(String paymentType) {
        Counter.builder("homepage_payments_intents_created_total")
                .description("Total Payment Intents created via Stripe").tag("type", paymentType).register(registry)
                .increment();
    }

    /**
     * Increments the payment succeeded counter.
     *
     * <p>
     * Called by PaymentService when a payment_intent.succeeded webhook is processed.
     *
     * @param paymentType
     *            payment type
     */
    public void incrementPaymentSucceeded(String paymentType) {
        Counter.builder("homepage_payments_succeeded_total").description("Total successful payments")
                .tag("type", paymentType).register(registry).increment();
    }

    /**
     * Increments the payment failed counter.
     *
     * <p>
     * Called by PaymentService when a payment_intent.payment_failed webhook is processed.
     *
     * @param paymentType
     *            payment type
     */
    public void incrementPaymentFailed(String paymentType) {
        Counter.builder("homepage_payments_failed_total").description("Total failed payments").tag("type", paymentType)
                .register(registry).increment();
    }

    /**
     * Increments the refund processed counter.
     *
     * <p>
     * Called by PaymentService when a refund is successfully processed via Stripe.
     */
    public void incrementRefundProcessed() {
        Counter.builder("homepage_refunds_processed_total").description("Total refunds processed via Stripe")
                .register(registry).increment();
    }

    /**
     * Increments the webhook received counter.
     *
     * <p>
     * Called by StripeWebhookResource when a webhook event is received.
     *
     * @param eventType
     *            Stripe event type (e.g., "payment_intent.succeeded")
     */
    public void incrementWebhookReceived(String eventType) {
        Counter.builder("homepage_webhooks_received_total").description("Total Stripe webhook events received")
                .tag("event_type", eventType).register(registry).increment();
    }
}
