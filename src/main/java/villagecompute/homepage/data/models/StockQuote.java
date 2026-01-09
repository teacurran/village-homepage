package villagecompute.homepage.data.models;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import villagecompute.homepage.api.types.StockQuoteType;

/**
 * Cache entity for stock market quotes from Alpha Vantage API.
 *
 * <p>
 * Stores stock quote data with dynamic expiration based on market hours:
 * <ul>
 * <li>Market open (9:30 AM - 4:00 PM ET, Mon-Fri): 5 minutes</li>
 * <li>After hours (weekdays): 1 hour</li>
 * <li>Weekends: 6 hours</li>
 * </ul>
 *
 * <p>
 * Uses Panache ActiveRecord pattern with static finder methods for database access.
 */
@Entity
@Table(
        name = "stock_quotes")
public class StockQuote extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    /**
     * Stock ticker symbol (uppercase, e.g., "AAPL", "^GSPC").
     */
    @Column(
            nullable = false,
            unique = true)
    public String symbol;

    /**
     * Company or index name.
     */
    @Column(
            name = "company_name",
            nullable = false)
    public String companyName;

    /**
     * Serialized StockQuoteType with price, change, sparkline data.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "quote_data",
            nullable = false,
            columnDefinition = "jsonb")
    public StockQuoteType quoteData;

    /**
     * When the quote was last fetched from Alpha Vantage API.
     */
    @Column(
            name = "fetched_at",
            nullable = false)
    public Instant fetchedAt;

    /**
     * When the cached quote expires (dynamic based on market hours).
     */
    @Column(
            name = "expires_at",
            nullable = false)
    public Instant expiresAt;

    @CreationTimestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false)
    public Instant createdAt;

    @UpdateTimestamp
    @Column(
            name = "updated_at",
            nullable = false)
    public Instant updatedAt;

    /**
     * Find stock quote by symbol.
     *
     * @param symbol
     *            ticker symbol (case-insensitive, will be converted to uppercase)
     * @return StockQuote if found
     */
    public static Optional<StockQuote> findBySymbol(String symbol) {
        return find("symbol", symbol.toUpperCase()).firstResultOptional();
    }

    /**
     * Find valid (non-expired) stock quote by symbol.
     *
     * @param symbol
     *            ticker symbol (case-insensitive, will be converted to uppercase)
     * @return StockQuote if found and not expired
     */
    public static Optional<StockQuote> findValidCache(String symbol) {
        return find("symbol = ?1 AND expiresAt > ?2", symbol.toUpperCase(), Instant.now()).firstResultOptional();
    }

    /**
     * Delete all expired stock quotes.
     *
     * @return number of deleted records
     */
    public static long deleteExpired() {
        return delete("expiresAt < ?1", Instant.now());
    }

    /**
     * Check if this quote is stale (older than 2 hours).
     *
     * <p>
     * Stale quotes are still valid for serving to users during rate limit conditions, but should be flagged in the
     * response so users know the data may be outdated.
     *
     * @return true if fetched more than 2 hours ago
     */
    public boolean isStale() {
        return fetchedAt.isBefore(Instant.now().minus(2, ChronoUnit.HOURS));
    }
}
