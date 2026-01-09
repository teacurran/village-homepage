package villagecompute.homepage.jobs;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.logging.Logger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import villagecompute.homepage.observability.LoggingConfig;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.exceptions.RateLimitException;
import villagecompute.homepage.services.StockService;

/**
 * Job handler for refreshing stock market quotes in the background.
 *
 * <p>
 * This handler follows the same pattern as WeatherRefreshJobHandler:
 * <ul>
 * <li>Collect unique symbols from all users' watchlists</li>
 * <li>Process symbols independently (errors don't abort batch)</li>
 * <li>Export OpenTelemetry spans per symbol</li>
 * <li>Export Micrometer metrics (success/failure counters)</li>
 * </ul>
 *
 * <p>
 * Market hours awareness is handled by StockService and StockRefreshScheduler.
 */
@ApplicationScoped
public class StockRefreshJobHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(StockRefreshJobHandler.class);

    @Inject
    StockService stockService;

    @Inject
    Tracer tracer;

    @Inject
    MeterRegistry meterRegistry;

    @Override
    public JobType handlesType() {
        return JobType.STOCK_REFRESH;
    }

    @Override
    @Transactional
    public void execute(Long jobId, Map<String, Object> payload) throws Exception {
        Span jobSpan = tracer.spanBuilder("job.stock_refresh").setAttribute("job.id", jobId.toString())
                .setAttribute("market_status", stockService.getMarketStatus()).startSpan();

        try (Scope jobScope = jobSpan.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            LoggingConfig.setJobId(jobId);

            LOG.infof("Starting stock refresh job %s", jobId);

            // Collect unique symbols from all users' watchlists
            Set<String> symbols = collectUniqueSymbols();

            LOG.infof("Collected %d unique symbols from user watchlists", symbols.size());
            jobSpan.setAttribute("symbols.count", symbols.size());

            int successCount = 0;
            int failureCount = 0;
            int rateLimitCount = 0;

            // Process each symbol independently
            for (String symbol : symbols) {
                Span symbolSpan = tracer.spanBuilder("stock.refresh_symbol").setAttribute("job.id", jobId.toString())
                        .setAttribute("symbol", symbol).startSpan();

                try (Scope symbolScope = symbolSpan.makeCurrent()) {
                    stockService.refreshCache(symbol);
                    successCount++;
                    symbolSpan.setAttribute("status", "success");
                    LOG.debugf("Successfully refreshed quote for %s", symbol);

                } catch (RateLimitException e) {
                    rateLimitCount++;
                    symbolSpan.setAttribute("status", "rate_limited");
                    symbolSpan.recordException(e);
                    LOG.warnf("Rate limit exceeded while refreshing %s: %s", symbol, e.getMessage());

                    // Don't abort batch - continue with remaining symbols
                    incrementCounter("stock.refresh.rate_limited", "symbol", symbol);

                } catch (Exception e) {
                    failureCount++;
                    symbolSpan.setAttribute("status", "error");
                    symbolSpan.recordException(e);
                    LOG.errorf(e, "Failed to refresh quote for %s", symbol);

                    // Don't abort batch - continue with remaining symbols
                    incrementCounter("stock.refresh.failed", "symbol", symbol);

                } finally {
                    symbolSpan.end();
                }
            }

            jobSpan.setAttribute("symbols.success", successCount);
            jobSpan.setAttribute("symbols.failure", failureCount);
            jobSpan.setAttribute("symbols.rate_limited", rateLimitCount);

            LOG.infof("Stock refresh job %s completed: %d success, %d failed, %d rate limited", jobId, successCount,
                    failureCount, rateLimitCount);

            incrementCounter("stock.refresh.jobs.completed");

        } catch (Exception e) {
            jobSpan.recordException(e);
            LOG.errorf(e, "Stock refresh job %s failed", jobId);
            incrementCounter("stock.refresh.jobs.failed");
            throw new RuntimeException("Stock refresh job failed", e);

        } finally {
            jobSpan.end();
            LoggingConfig.clearMDC();
        }
    }

    /**
     * Collect unique stock symbols from all users' watchlists.
     *
     * @return set of unique ticker symbols
     */
    @SuppressWarnings("unchecked")
    private Set<String> collectUniqueSymbols() {
        Set<String> symbols = new HashSet<>();

        // Query all users with non-empty watchlists
        List<User> users = User.listAll();

        for (User user : users) {
            if (user.preferences != null && user.preferences.containsKey("watchlist")) {
                Object watchlistObj = user.preferences.get("watchlist");
                if (watchlistObj instanceof List<?>) {
                    List<String> watchlist = (List<String>) watchlistObj;
                    symbols.addAll(watchlist);
                }
            }
        }

        return symbols;
    }

    /**
     * Increment a counter metric.
     */
    private void incrementCounter(String name, String... tags) {
        Counter.Builder builder = Counter.builder(name);
        for (int i = 0; i < tags.length; i += 2) {
            builder.tag(tags[i], tags[i + 1]);
        }
        builder.register(meterRegistry).increment();
    }
}
