package villagecompute.homepage.data.models;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * GeoCountry entity representing countries in the geographic reference dataset.
 *
 * <p>
 * Part of the marketplace location filtering system per Policy P6 (US + Canada only). Loaded from dr5hn/countries-
 * states-cities-database via {@code ./scripts/import-geo-data-to-app-schema.sh}.
 *
 * <p>
 * <b>Schema Mapping:</b>
 * <ul>
 * <li>{@code id} (BIGSERIAL, PK) - Primary identifier</li>
 * <li>{@code name} (TEXT) - Country name (e.g., "United States", "Canada")</li>
 * <li>{@code iso2} (TEXT) - Two-letter ISO code (e.g., "US", "CA")</li>
 * <li>{@code iso3} (TEXT) - Three-letter ISO code (e.g., "USA", "CAN")</li>
 * <li>{@code phone_code} (TEXT) - International dialing code (e.g., "+1")</li>
 * <li>{@code capital} (TEXT) - Capital city name (e.g., "Washington", "Ottawa")</li>
 * <li>{@code currency} (TEXT) - Currency code (e.g., "USD", "CAD")</li>
 * <li>{@code native_name} (TEXT) - Country name in native language</li>
 * <li>{@code region} (TEXT) - Geographic region (e.g., "Americas")</li>
 * <li>{@code subregion} (TEXT) - Subregion (e.g., "Northern America")</li>
 * <li>{@code latitude} (NUMERIC(10,7)) - Country center latitude</li>
 * <li>{@code longitude} (NUMERIC(10,7)) - Country center longitude</li>
 * <li>{@code emoji} (TEXT) - Country flag emoji (e.g., "🇺🇸", "🇨🇦")</li>
 * <li>{@code created_at} (TIMESTAMPTZ) - Record creation timestamp</li>
 * </ul>
 *
 * <p>
 * <b>Indexes:</b>
 * <ul>
 * <li>UNIQUE idx_geo_countries_iso2 on iso2</li>
 * <li>UNIQUE idx_geo_countries_iso3 on iso3</li>
 * <li>idx_geo_countries_name on name</li>
 * </ul>
 *
 * @see GeoState for states/provinces
 * @see GeoCity for cities with PostGIS spatial columns
 */
@Entity
@Table(
        name = "geo_countries")
@NamedQuery(
        name = GeoCountry.QUERY_FIND_BY_ISO2,
        query = "SELECT c FROM GeoCountry c WHERE c.iso2 = :iso2")
@NamedQuery(
        name = GeoCountry.QUERY_FIND_BY_NAME_PREFIX,
        query = "SELECT c FROM GeoCountry c WHERE LOWER(c.name) LIKE LOWER(:namePrefix) ORDER BY c.name")
public class GeoCountry extends PanacheEntityBase {

    public static final String QUERY_FIND_BY_ISO2 = "GeoCountry.findByIso2";
    public static final String QUERY_FIND_BY_NAME_PREFIX = "GeoCountry.findByNamePrefix";

    @Id
    @GeneratedValue(
            strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(
            nullable = false)
    public String name;

    @Column(
            nullable = false)
    public String iso2;

    @Column(
            nullable = false)
    public String iso3;

    @Column(
            name = "phone_code")
    public String phoneCode;

    @Column
    public String capital;

    @Column
    public String currency;

    @Column(
            name = "native_name")
    public String nativeName;

    @Column
    public String region;

    @Column
    public String subregion;

    @Column(
            precision = 10,
            scale = 7)
    public BigDecimal latitude;

    @Column(
            precision = 10,
            scale = 7)
    public BigDecimal longitude;

    @Column
    public String emoji;

    @CreationTimestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false)
    public Instant createdAt;

    /**
     * Finds a country by its two-letter ISO code.
     *
     * @param iso2
     *            the ISO 3166-1 alpha-2 code (e.g., "US", "CA")
     * @return Optional containing the country if found
     */
    public static Optional<GeoCountry> findByIso2(String iso2) {
        if (iso2 == null || iso2.isBlank()) {
            return Optional.empty();
        }
        return find("#" + QUERY_FIND_BY_ISO2, io.quarkus.panache.common.Parameters.with("iso2", iso2.toUpperCase()))
                .firstResultOptional();
    }

    /**
     * Finds countries by name prefix (autocomplete search).
     *
     * @param namePrefix
     *            the name prefix to search for (case-insensitive)
     * @return list of matching countries ordered by name
     */
    public static List<GeoCountry> findByNamePrefix(String namePrefix) {
        if (namePrefix == null || namePrefix.isBlank()) {
            return List.of();
        }
        return find("#" + QUERY_FIND_BY_NAME_PREFIX,
                io.quarkus.panache.common.Parameters.with("namePrefix", namePrefix + "%")).list();
    }
}
