package villagecompute.homepage.api.types;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Type representing the complete stock widget response with quotes and metadata.
 *
 * <p>
 * All JSON-marshalled types in this project use the Type suffix and record classes.
 *
 * @param quotes
 *            list of stock quotes for user's watchlist
 * @param marketStatus
 *            current market status: "OPEN", "CLOSED", or "WEEKEND"
 * @param cachedAt
 *            ISO 8601 timestamp when data was cached
 * @param stale
 *            true if cached data is older than 2 hours (still valid, but potentially outdated)
 * @param rateLimited
 *            true if Alpha Vantage API rate limit was exceeded and stale cache was served
 */
public record StockWidgetType(@NotNull @Valid List<StockQuoteType> quotes,
        @JsonProperty("market_status") @NotBlank String marketStatus,
        @JsonProperty("cached_at") @NotBlank String cachedAt, boolean stale,
        @JsonProperty("rate_limited") boolean rateLimited) {
}
