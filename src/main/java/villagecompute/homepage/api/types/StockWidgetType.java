package villagecompute.homepage.api.types;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
@Schema(
        description = "Stock widget response with quotes, market status, and cache metadata")
public record StockWidgetType(@Schema(
        description = "List of stock quotes for user's watchlist",
        required = true) @NotNull @Valid List<StockQuoteType> quotes,

        @Schema(
                description = "Current market status",
                example = "OPEN",
                enumeration = {
                        "OPEN", "CLOSED", "WEEKEND"},
                required = true) @JsonProperty("market_status") @NotBlank String marketStatus,

        @Schema(
                description = "ISO 8601 timestamp when data was cached",
                example = "2026-01-24T16:00:00Z",
                required = true) @JsonProperty("cached_at") @NotBlank String cachedAt,

        @Schema(
                description = "True if cached data is older than 2 hours",
                example = "false",
                required = true) boolean stale,

        @Schema(
                description = "True if API rate limit exceeded and stale cache served",
                example = "false",
                required = true) @JsonProperty("rate_limited") boolean rateLimited){
}
