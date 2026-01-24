package villagecompute.homepage.api.types;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
@Schema(
        description = "Stock quote with price, change, and sparkline data")
public record StockQuoteType(@Schema(
        description = "Stock ticker symbol (uppercase)",
        example = "AAPL",
        required = true) @NotBlank String symbol,

        @Schema(
                description = "Company or index name",
                example = "Apple Inc.",
                required = true) @JsonProperty("company_name") @NotBlank String companyName,

        @Schema(
                description = "Current price",
                example = "175.50",
                required = true) @NotNull Double price,

        @Schema(
                description = "Absolute price change from previous close",
                example = "2.30",
                required = true) @NotNull Double change,

        @Schema(
                description = "Percentage change from previous close",
                example = "1.33",
                required = true) @JsonProperty("change_percent") @NotNull Double changePercent,

        @Schema(
                description = "Closing prices for last 5 trading days (oldest to newest)",
                example = "[170.20, 172.50, 173.10, 174.80, 175.50]",
                required = true) @NotNull List<Double> sparkline,

        @Schema(
                description = "ISO 8601 timestamp when quote was last fetched",
                example = "2026-01-24T16:00:00Z",
                required = true) @JsonProperty("last_updated") @NotBlank String lastUpdated) {
}
