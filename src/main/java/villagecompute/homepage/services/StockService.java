package villagecompute.homepage.services;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jboss.logging.Logger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import villagecompute.homepage.api.types.StockQuoteType;
import villagecompute.homepage.api.types.StockWidgetType;
import villagecompute.homepage.data.models.StockQuote;
import villagecompute.homepage.exceptions.RateLimitException;
import villagecompute.homepage.integration.stocks.AlphaVantageClient;

/**
 * Application-scoped service for stock market data with caching and market hours awareness.
 *
 * <p>
 * This service follows the same pattern as WeatherService:
 * <ul>
 * <li>Cache-first strategy: check cache before calling API</li>
 * <li>Dynamic expiration based on market hours (5min open, 1hr closed, 6hr weekend)</li>
 * <li>Rate limit handling: serve stale cache on Alpha Vantage limit exhaustion</li>
 * <li>OpenTelemetry tracing and Micrometer metrics</li>
 * </ul>
 */
@ApplicationScoped
public class StockService {

    private static final Logger LOG = Logger.getLogger(StockService.class);

    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 30);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(16, 0);

    @Inject
    AlphaVantageClient alphaVantageClient;

    @Inject
    Tracer tracer;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Get stock quotes for multiple symbols (cache-first).
     *
     * @param symbols
     *            list of ticker symbols
     * @return StockWidgetType with quotes and metadata
     */
    public StockWidgetType getQuotes(List<String> symbols) {
        LOG.debugf("Getting quotes for %d symbols", symbols.size());

        List<StockQuoteType> quotes = new ArrayList<>();
        boolean anyStale = false;
        boolean rateLimited = false;
        Instant oldestCache = Instant.now();

        for (String symbol : symbols) {
            Span span = tracer.spanBuilder("stock.get_quote").setAttribute("symbol", symbol).startSpan();

            try (Scope scope = span.makeCurrent()) {
                Optional<StockQuote> cached = StockQuote.findValidCache(symbol);

                if (cached.isPresent()) {
                    // Cache hit
                    incrementCounter("stock.cache.hits");
                    span.setAttribute("cache_hit", true);

                    StockQuote quote = cached.get();
                    quotes.add(quote.quoteData);

                    if (quote.isStale()) {
                        anyStale = true;
                    }

                    if (quote.fetchedAt.isBefore(oldestCache)) {
                        oldestCache = quote.fetchedAt;
                    }

                    LOG.debugf("Cache hit for symbol %s (fetched at %s)", symbol, quote.fetchedAt);
                } else {
                    // Cache miss - fetch from API
                    incrementCounter("stock.cache.misses");
                    span.setAttribute("cache_hit", false);

                    LOG.debugf("Cache miss for symbol %s, fetching from API", symbol);

                    try {
                        refreshCache(symbol);
                        Optional<StockQuote> refreshed = StockQuote.findBySymbol(symbol);
                        if (refreshed.isPresent()) {
                            quotes.add(refreshed.get().quoteData);
                            if (refreshed.get().fetchedAt.isBefore(oldestCache)) {
                                oldestCache = refreshed.get().fetchedAt;
                            }
                        }
                    } catch (RateLimitException e) {
                        // Rate limit exceeded - try to serve stale cache
                        rateLimited = true;
                        incrementCounter("stock.rate_limit.exceeded");
                        span.setAttribute("rate_limited", true);

                        LOG.warnf("Rate limit exceeded for %s, attempting to serve stale cache", symbol);

                        Optional<StockQuote> stale = StockQuote.findBySymbol(symbol);
                        if (stale.isPresent()) {
                            quotes.add(stale.get().quoteData);
                            anyStale = true;
                            if (stale.get().fetchedAt.isBefore(oldestCache)) {
                                oldestCache = stale.get().fetchedAt;
                            }
                            LOG.infof("Serving stale cache for %s (fetched at %s)", symbol, stale.get().fetchedAt);
                        } else {
                            LOG.errorf("No cached data available for %s and rate limit exceeded", symbol);
                        }
                    }
                }
            } finally {
                span.end();
            }
        }

        String marketStatus = getMarketStatus();

        return new StockWidgetType(quotes, marketStatus, oldestCache.toString(), anyStale, rateLimited);
    }

    /**
     * Refresh cache for a symbol (bypass cache, fetch from API).
     *
     * <p>
     * This method is called by the job handler to refresh cache in the background.
     *
     * @param symbol
     *            ticker symbol
     * @throws RateLimitException
     *             if Alpha Vantage rate limit is exceeded
     */
    @Transactional
    public void refreshCache(String symbol) {
        LOG.debugf("Refreshing cache for symbol: %s", symbol);

        Span span = tracer.spanBuilder("stock.refresh_cache").setAttribute("symbol", symbol)
                .setAttribute("market_status", getMarketStatus()).startSpan();

        Timer.Sample sample = Timer.start(meterRegistry);

        try (Scope scope = span.makeCurrent()) {
            // Fetch quote from API
            StockQuoteType quoteData = alphaVantageClient.fetchQuote(symbol);

            span.setAttribute("price", quoteData.price());
            span.setAttribute("change_percent", quoteData.changePercent());

            // Calculate expiration based on market hours
            Instant expiresAt = calculateExpiration();

            // Upsert cache entry
            Optional<StockQuote> existing = StockQuote.findBySymbol(symbol);

            if (existing.isPresent()) {
                StockQuote quote = existing.get();
                quote.companyName = quoteData.companyName();
                quote.quoteData = quoteData;
                quote.fetchedAt = Instant.now();
                quote.expiresAt = expiresAt;
                quote.persist();
                LOG.debugf("Updated cache for %s (expires at %s)", symbol, expiresAt);
            } else {
                StockQuote quote = new StockQuote();
                quote.symbol = symbol.toUpperCase();
                quote.companyName = quoteData.companyName();
                quote.quoteData = quoteData;
                quote.fetchedAt = Instant.now();
                quote.expiresAt = expiresAt;
                quote.persist();
                LOG.debugf("Created cache for %s (expires at %s)", symbol, expiresAt);
            }

            incrementCounter("stock.fetch.total", "symbol", symbol, "status", "success");

        } catch (RateLimitException e) {
            incrementCounter("stock.fetch.total", "symbol", symbol, "status", "rate_limited");
            span.setAttribute("rate_limited", true);
            span.recordException(e);
            throw e;
        } catch (Exception e) {
            incrementCounter("stock.fetch.total", "symbol", symbol, "status", "error");
            span.recordException(e);
            LOG.errorf(e, "Failed to refresh cache for symbol: %s", symbol);
            throw new RuntimeException("Failed to refresh cache for " + symbol, e);
        } finally {
            sample.stop(Timer.builder("stock.api.duration").tag("endpoint", "global_quote").register(meterRegistry));
            span.end();
        }
    }

    /**
     * Check if US stock market is currently open.
     *
     * @return true if market is open (9:30 AM - 4:00 PM ET, Monday-Friday)
     */
    public boolean isMarketOpen() {
        ZonedDateTime now = ZonedDateTime.now(ET_ZONE);
        DayOfWeek day = now.getDayOfWeek();
        LocalTime time = now.toLocalTime();

        // Closed on weekends
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }

        // Market hours: 9:30 AM - 4:00 PM ET
        return time.isAfter(MARKET_OPEN) && time.isBefore(MARKET_CLOSE);
    }

    /**
     * Get current market status.
     *
     * @return "OPEN", "CLOSED", or "WEEKEND"
     */
    public String getMarketStatus() {
        ZonedDateTime now = ZonedDateTime.now(ET_ZONE);
        DayOfWeek day = now.getDayOfWeek();

        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return "WEEKEND";
        }

        return isMarketOpen() ? "OPEN" : "CLOSED";
    }

    /**
     * Calculate cache expiration based on market hours.
     *
     * @return expiration timestamp
     */
    private Instant calculateExpiration() {
        ZonedDateTime now = ZonedDateTime.now(ET_ZONE);
        DayOfWeek day = now.getDayOfWeek();

        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            // Weekend: 6 hours
            return Instant.now().plus(6, ChronoUnit.HOURS);
        } else if (isMarketOpen()) {
            // Market open: 5 minutes
            return Instant.now().plus(5, ChronoUnit.MINUTES);
        } else {
            // After hours: 1 hour
            return Instant.now().plus(1, ChronoUnit.HOURS);
        }
    }

    /**
     * Get refresh interval based on market status.
     *
     * @return duration between refreshes
     */
    public Duration getRefreshInterval() {
        if (isMarketOpen()) {
            return Duration.ofMinutes(5);
        } else if (ZonedDateTime.now(ET_ZONE).getDayOfWeek() == DayOfWeek.SATURDAY
                || ZonedDateTime.now(ET_ZONE).getDayOfWeek() == DayOfWeek.SUNDAY) {
            return Duration.ofHours(6);
        } else {
            return Duration.ofHours(1);
        }
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
