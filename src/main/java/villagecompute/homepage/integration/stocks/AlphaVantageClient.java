package villagecompute.homepage.integration.stocks;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import villagecompute.homepage.api.types.StockQuoteType;
import villagecompute.homepage.exceptions.RateLimitException;

/**
 * HTTP client for Alpha Vantage stock market data API.
 *
 * <p>
 * Alpha Vantage provides real-time and historical stock market data. Free tier includes:
 * <ul>
 * <li>25 requests per day</li>
 * <li>5 requests per minute</li>
 * </ul>
 *
 * <p>
 * Rate limit responses are HTTP 200 with JSON containing a "Note" field explaining the limit.
 */
@ApplicationScoped
public class AlphaVantageClient {

    private static final Logger LOG = Logger.getLogger(AlphaVantageClient.class);

    private static final String BASE_URL = "https://www.alphavantage.co/query";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    @ConfigProperty(
            name = "alphavantage.api-key")
    String apiKey;

    @Inject
    ObjectMapper objectMapper;

    private final HttpClient httpClient;

    public AlphaVantageClient() {
        this.httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    }

    /**
     * Fetch current stock quote from Alpha Vantage GLOBAL_QUOTE endpoint.
     *
     * @param symbol
     *            ticker symbol (e.g., "AAPL", "^GSPC")
     * @return StockQuoteType with price, change, and sparkline data
     * @throws RateLimitException
     *             if API rate limit is exceeded
     */
    public StockQuoteType fetchQuote(String symbol) {
        LOG.debugf("Fetching quote for symbol: %s", symbol);

        try {
            // Fetch current quote
            JsonNode quoteResponse = fetchGlobalQuote(symbol);

            // Fetch sparkline data (last 5 days)
            List<Double> sparkline = fetchSparkline(symbol);

            // Parse quote data
            JsonNode globalQuote = quoteResponse.get("Global Quote");
            if (globalQuote == null || globalQuote.isEmpty()) {
                throw new RuntimeException("Invalid response from Alpha Vantage: missing Global Quote data");
            }

            String quotedSymbol = globalQuote.get("01. symbol").asText();
            double price = globalQuote.get("05. price").asDouble();
            double change = globalQuote.get("09. change").asDouble();
            String changePercentStr = globalQuote.get("10. change percent").asText();
            // Remove trailing '%' character
            double changePercent = Double.parseDouble(changePercentStr.replace("%", ""));
            String lastUpdated = Instant.now().toString();

            // Use symbol as company name for now (could be enhanced with a company lookup)
            String companyName = quotedSymbol;

            LOG.debugf("Successfully fetched quote for %s: price=%.2f, change=%.2f (%.2f%%)", quotedSymbol, price,
                    change, changePercent);

            return new StockQuoteType(quotedSymbol, companyName, price, change, changePercent, sparkline, lastUpdated);

        } catch (RateLimitException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to fetch quote for symbol: %s", symbol);
            throw new RuntimeException("Failed to fetch stock quote for " + symbol, e);
        }
    }

    /**
     * Fetch sparkline data (closing prices for last 5 trading days).
     *
     * @param symbol
     *            ticker symbol
     * @return list of closing prices in chronological order (oldest to newest)
     * @throws RateLimitException
     *             if API rate limit is exceeded
     */
    public List<Double> fetchSparkline(String symbol) {
        LOG.debugf("Fetching sparkline for symbol: %s", symbol);

        try {
            String url = String.format("%s?function=TIME_SERIES_DAILY&symbol=%s&outputsize=compact&apikey=%s", BASE_URL,
                    symbol, apiKey);

            URI uri = URI.create(url);
            HttpRequest request = HttpRequest.newBuilder().uri(uri).timeout(HTTP_TIMEOUT).GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException(
                        "Alpha Vantage API returned status " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());

            // Check for rate limit response
            if (root.has("Note")) {
                String message = root.get("Note").asText();
                LOG.warnf("Alpha Vantage rate limit exceeded: %s", message);
                throw new RateLimitException("Alpha Vantage rate limit exceeded: " + message);
            }

            // Check for error response
            if (root.has("Error Message")) {
                String errorMessage = root.get("Error Message").asText();
                throw new RuntimeException("Alpha Vantage API error: " + errorMessage);
            }

            JsonNode timeSeries = root.get("Time Series (Daily)");
            if (timeSeries == null || timeSeries.isEmpty()) {
                throw new RuntimeException("Invalid response from Alpha Vantage: missing Time Series data");
            }

            // Extract last 5 closing prices
            List<Double> prices = new ArrayList<>();
            Iterator<String> dates = timeSeries.fieldNames();
            int count = 0;
            while (dates.hasNext() && count < 5) {
                String date = dates.next();
                JsonNode day = timeSeries.get(date);
                double close = day.get("4. close").asDouble();
                prices.add(close);
                count++;
            }

            // Reverse to get chronological order (oldest to newest)
            Collections.reverse(prices);

            LOG.debugf("Successfully fetched sparkline for %s: %d data points", symbol, prices.size());

            return prices;

        } catch (RateLimitException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to fetch sparkline for symbol: %s", symbol);
            throw new RuntimeException("Failed to fetch sparkline for " + symbol, e);
        }
    }

    /**
     * Fetch global quote from Alpha Vantage API.
     *
     * @param symbol
     *            ticker symbol
     * @return JsonNode with quote data
     * @throws RateLimitException
     *             if API rate limit is exceeded
     */
    private JsonNode fetchGlobalQuote(String symbol) throws Exception {
        String url = String.format("%s?function=GLOBAL_QUOTE&symbol=%s&apikey=%s", BASE_URL, symbol, apiKey);

        URI uri = URI.create(url);
        HttpRequest request = HttpRequest.newBuilder().uri(uri).timeout(HTTP_TIMEOUT).GET().build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException(
                    "Alpha Vantage API returned status " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());

        // Check for rate limit response
        if (root.has("Note")) {
            String message = root.get("Note").asText();
            LOG.warnf("Alpha Vantage rate limit exceeded: %s", message);
            throw new RateLimitException("Alpha Vantage rate limit exceeded: " + message);
        }

        // Check for error response
        if (root.has("Error Message")) {
            String errorMessage = root.get("Error Message").asText();
            throw new RuntimeException("Alpha Vantage API error: " + errorMessage);
        }

        return root;
    }
}
