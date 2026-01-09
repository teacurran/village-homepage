package villagecompute.homepage.api.types;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Type representing a single stock quote with price, change, and sparkline data.
 *
 * <p>
 * All JSON-marshalled types in this project use the Type suffix and record classes.
 *
 * @param symbol
 *            stock ticker symbol (uppercase, e.g., "AAPL", "^GSPC")
 * @param companyName
 *            company or index name
 * @param price
 *            current price
 * @param change
 *            absolute price change from previous close
 * @param changePercent
 *            percentage change from previous close
 * @param sparkline
 *            closing prices for last 5 trading days (chronological order, oldest to newest)
 * @param lastUpdated
 *            ISO 8601 timestamp when quote was last fetched
 */
public record StockQuoteType(@NotBlank String symbol, @JsonProperty("company_name") @NotBlank String companyName,
        @NotNull Double price, @NotNull Double change, @JsonProperty("change_percent") @NotNull Double changePercent,
        @NotNull List<Double> sparkline, @JsonProperty("last_updated") @NotBlank String lastUpdated) {
}
