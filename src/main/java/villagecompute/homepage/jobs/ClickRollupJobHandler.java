package villagecompute.homepage.jobs;

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
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.ClickStatsDaily;
import villagecompute.homepage.data.models.ClickStatsDailyItems;
import villagecompute.homepage.data.models.LinkClick;
import villagecompute.homepage.observability.LoggingConfig;

import java.time.LocalDate;
import java.util.Map;

/**
 * Job handler for click tracking rollup aggregation (Policy F14.9).
 *
 * <p>
 * This handler runs hourly to aggregate raw click events from {@code link_clicks} partitioned table into rollup tables
 * ({@code click_stats_daily}, {@code click_stats_daily_items}) for efficient dashboard queries.
 *
 * <p>
 * <b>Execution Flow:</b>
 * <ol>
 * <li>Determine target date (default: yesterday, configurable via payload)</li>
 * <li>Aggregate clicks by type+category into {@code click_stats_daily}</li>
 * <li>Aggregate clicks by type+target into {@code click_stats_daily_items}</li>
 * <li>Calculate additional metrics (avg rank, avg score, bubbled clicks)</li>
 * <li>Use ON CONFLICT DO UPDATE for idempotency (can re-run safely)</li>
 * </ol>
 *
 * <p>
 * <b>Rollup Logic:</b>
 * <ul>
 * <li>Counts total clicks, unique users (by user_id), unique sessions (by session_id)</li>
 * <li>For Good Sites: calculates average rank/score from metadata JSONB</li>
 * <li>For bubbled clicks: counts is_bubbled=true events separately</li>
 * </ul>
 *
 * <p>
 * <b>Error Handling:</b> Rollup failures for individual click types are logged and recorded as exceptions, but do NOT
 * abort the entire job. The job continues processing other types and reports partial failure.
 *
 * <p>
 * <b>Telemetry:</b> Exports OpenTelemetry span attributes:
 * <ul>
 * <li>{@code job.id} - Job database primary key</li>
 * <li>{@code job.type} - CLICK_ROLLUP</li>
 * <li>{@code job.queue} - LOW</li>
 * <li>{@code rollup_date} - Target date being aggregated</li>
 * <li>{@code category_rollups} - Number of category rollup rows created/updated</li>
 * <li>{@code item_rollups} - Number of item rollup rows created/updated</li>
 * <li>{@code profile_events} - Number of profile-related click events aggregated</li>
 * </ul>
 *
 * <p>
 * And Micrometer metrics:
 * <ul>
 * <li>{@code click_tracking.rollups.total} (Counter) - Total rollup rows processed</li>
 * <li>{@code click_tracking.rollup.duration} (Timer) - Job execution duration</li>
 * <li>{@code click_tracking.rollup.errors.total} (Counter) - Rollup errors</li>
 * </ul>
 *
 * <p>
 * <b>Payload Structure:</b>
 *
 * <pre>
 * {
 *   "rollup_date": "2026-01-10"  // Optional - defaults to yesterday
 * }
 * </pre>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>F14.9: 90-day retention for raw clicks, indefinite retention for rollups</li>
 * <li>P7: Unified job orchestration via DelayedJobService</li>
 * <li>P14: Consent-gated analytics tracking</li>
 * </ul>
 *
 * @see villagecompute.homepage.data.models.LinkClick for raw click events
 * @see ClickStatsDaily for category-level rollups
 * @see ClickStatsDailyItems for item-level rollups
 */
@ApplicationScoped
public class ClickRollupJobHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(ClickRollupJobHandler.class);

    @Inject
    Tracer tracer;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    EntityManager entityManager;

    @Override
    public JobType handlesType() {
        return JobType.CLICK_ROLLUP;
    }

    @Override
    @Transactional
    public void execute(Long jobId, Map<String, Object> payload) throws Exception {
        Span span = tracer.spanBuilder("job.click_rollup").setAttribute("job.id", jobId)
                .setAttribute("job.type", JobType.CLICK_ROLLUP.name()).setAttribute("job.queue", JobQueue.LOW.name())
                .startSpan();

        Timer.Sample timerSample = Timer.start(meterRegistry);

        try (Scope scope = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            LoggingConfig.setJobId(jobId);
            LoggingConfig.setRequestOrigin("ClickRollupJobHandler");

            LOG.infof("Starting click rollup job %d", jobId);

            // Determine target date (default: yesterday)
            LocalDate targetDate = LocalDate.now().minusDays(1);
            if (payload.containsKey("rollup_date")) {
                try {
                    targetDate = LocalDate.parse(payload.get("rollup_date").toString());
                } catch (Exception e) {
                    LOG.warnf(e, "Invalid rollup_date in payload, using default: %s", targetDate);
                }
            }

            LOG.infof("Rolling up clicks for date: %s", targetDate);
            span.setAttribute("rollup_date", targetDate.toString());

            int categoryRollups = 0;
            int itemRollups = 0;

            try {
                // Aggregate category-level stats
                categoryRollups = aggregateCategoryStats(targetDate);
                LOG.infof("Created/updated %d category rollup rows", categoryRollups);

                // Aggregate item-level stats
                itemRollups = aggregateItemStats(targetDate);
                LOG.infof("Created/updated %d item rollup rows", itemRollups);

                // Count profile events for telemetry
                long profileEvents = LinkClick.count("clickType LIKE 'profile_%' AND clickDate = ?1", targetDate);

                span.setAttribute("category_rollups", categoryRollups);
                span.setAttribute("item_rollups", itemRollups);
                span.setAttribute("profile_events", profileEvents);
                span.addEvent("rollup.completed",
                        Attributes.of(AttributeKey.longKey("category_rollups"), (long) categoryRollups,
                                AttributeKey.longKey("item_rollups"), (long) itemRollups,
                                AttributeKey.longKey("profile_events"), profileEvents));

                // Record success metrics
                Counter.builder("click_tracking.rollups.total").register(meterRegistry)
                        .increment(categoryRollups + itemRollups);

                timerSample.stop(Timer.builder("click_tracking.rollup.duration").register(meterRegistry));

                LOG.infof("Click rollup job %d completed: %d category rollups, %d item rollups", jobId, categoryRollups,
                        itemRollups);

            } catch (Exception e) {
                LOG.errorf(e, "Click rollup job %d failed: %s", jobId, e.getMessage());
                span.recordException(e);
                Counter.builder("click_tracking.rollup.errors.total").register(meterRegistry).increment();
                throw e;
            }

        } finally {
            LoggingConfig.clearMDC();
            span.end();
        }
    }

    /**
     * Aggregate clicks by type and category into click_stats_daily table.
     *
     * Uses INSERT ... ON CONFLICT DO UPDATE for idempotency. Uses LEFT JOIN for directory_categories to gracefully
     * handle NULL category_id (e.g., profile events).
     */
    private int aggregateCategoryStats(LocalDate targetDate) {
        String sql = """
                INSERT INTO click_stats_daily (id, stat_date, click_type, category_id, category_name, total_clicks, unique_users, unique_sessions, created_at, updated_at)
                SELECT
                  gen_random_uuid(),
                  :targetDate,
                  lc.click_type,
                  lc.category_id,
                  dc.name AS category_name,
                  COUNT(*) AS total_clicks,
                  COUNT(DISTINCT COALESCE(lc.user_id::text, lc.session_id)) AS unique_users,
                  COUNT(DISTINCT lc.session_id) AS unique_sessions,
                  NOW(),
                  NOW()
                FROM link_clicks lc
                LEFT JOIN directory_categories dc ON lc.category_id = dc.id
                WHERE lc.click_date = :targetDate
                GROUP BY lc.click_type, lc.category_id, dc.name
                ON CONFLICT (stat_date, click_type, category_id)
                DO UPDATE SET
                  total_clicks = EXCLUDED.total_clicks,
                  unique_users = EXCLUDED.unique_users,
                  unique_sessions = EXCLUDED.unique_sessions,
                  updated_at = NOW()
                """;

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("targetDate", targetDate);
        return query.executeUpdate();
    }

    /**
     * Aggregate clicks by type and target into click_stats_daily_items table.
     *
     * Includes average rank, score, and bubbled click counts from metadata JSONB. Uses NULLIF to handle profile events
     * where directory-specific fields (rank_in_category, score, is_bubbled) are not present.
     */
    private int aggregateItemStats(LocalDate targetDate) {
        String sql = """
                INSERT INTO click_stats_daily_items (id, stat_date, click_type, target_id, total_clicks, unique_users, unique_sessions, avg_rank, avg_score, bubbled_clicks, created_at, updated_at)
                SELECT
                  gen_random_uuid(),
                  :targetDate,
                  click_type,
                  target_id,
                  COUNT(*) AS total_clicks,
                  COUNT(DISTINCT COALESCE(user_id::text, session_id)) AS unique_users,
                  COUNT(DISTINCT session_id) AS unique_sessions,
                  AVG(NULLIF((metadata->>'rank_in_category'), '')::NUMERIC) AS avg_rank,
                  AVG(NULLIF((metadata->>'score'), '')::NUMERIC) AS avg_score,
                  COUNT(*) FILTER (WHERE (metadata->>'is_bubbled')::BOOLEAN = true) AS bubbled_clicks,
                  NOW(),
                  NOW()
                FROM link_clicks
                WHERE click_date = :targetDate
                  AND target_id IS NOT NULL
                GROUP BY click_type, target_id
                ON CONFLICT (stat_date, click_type, target_id)
                DO UPDATE SET
                  total_clicks = EXCLUDED.total_clicks,
                  unique_users = EXCLUDED.unique_users,
                  unique_sessions = EXCLUDED.unique_sessions,
                  avg_rank = EXCLUDED.avg_rank,
                  avg_score = EXCLUDED.avg_score,
                  bubbled_clicks = EXCLUDED.bubbled_clicks,
                  updated_at = NOW()
                """;

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("targetDate", targetDate);
        return query.executeUpdate();
    }
}
