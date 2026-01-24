package villagecompute.homepage.data.models;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * GeoState entity representing states/provinces in the geographic reference dataset.
 *
 * <p>
 * Part of the marketplace location filtering system per Policy P6 (US + Canada only). Loaded from dr5hn/countries-
 * states-cities-database via {@code ./scripts/import-geo-data-to-app-schema.sh}.
 *
 * <p>
 * <b>Schema Mapping:</b>
 * <ul>
 * <li>{@code id} (BIGSERIAL, PK) - Primary identifier</li>
 * <li>{@code country_id} (BIGINT, FK) - Foreign key to geo_countries</li>
 * <li>{@code name} (TEXT) - State/province name (e.g., "Vermont", "Ontario")</li>
 * <li>{@code state_code} (TEXT) - State/province code (e.g., "VT", "ON")</li>
 * <li>{@code latitude} (NUMERIC(10,7)) - State center latitude</li>
 * <li>{@code longitude} (NUMERIC(10,7)) - State center longitude</li>
 * <li>{@code created_at} (TIMESTAMPTZ) - Record creation timestamp</li>
 * </ul>
 *
 * <p>
 * <b>Indexes:</b>
 * <ul>
 * <li>idx_geo_states_country_id on country_id</li>
 * <li>idx_geo_states_state_code on state_code</li>
 * <li>idx_geo_states_name on name</li>
 * <li>UNIQUE idx_geo_states_country_code on (country_id, state_code)</li>
 * </ul>
 *
 * @see GeoCountry for parent country
 * @see GeoCity for cities with PostGIS spatial columns
 */
@Entity
@Table(
        name = "geo_states")
@NamedQuery(
        name = GeoState.QUERY_FIND_BY_COUNTRY_AND_CODE,
        query = "SELECT s FROM GeoState s WHERE s.country.id = :countryId AND s.stateCode = :stateCode")
@NamedQuery(
        name = GeoState.QUERY_FIND_BY_COUNTRY,
        query = "SELECT s FROM GeoState s WHERE s.country.id = :countryId ORDER BY s.name")
@NamedQuery(
        name = GeoState.QUERY_FIND_BY_NAME_PREFIX,
        query = "SELECT s FROM GeoState s WHERE LOWER(s.name) LIKE LOWER(:namePrefix) ORDER BY s.name")
public class GeoState extends PanacheEntityBase {

    public static final String QUERY_FIND_BY_COUNTRY_AND_CODE = "GeoState.findByCountryAndCode";
    public static final String QUERY_FIND_BY_COUNTRY = "GeoState.findByCountry";
    public static final String QUERY_FIND_BY_NAME_PREFIX = "GeoState.findByNamePrefix";

    @Id
    @GeneratedValue(
            strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(
            fetch = FetchType.LAZY)
    @JoinColumn(
            name = "country_id",
            nullable = false)
    public GeoCountry country;

    @Column(
            nullable = false)
    public String name;

    @Column(
            name = "state_code",
            nullable = false)
    public String stateCode;

    @Column(
            precision = 10,
            scale = 7)
    public BigDecimal latitude;

    @Column(
            precision = 10,
            scale = 7)
    public BigDecimal longitude;

    @CreationTimestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false)
    public Instant createdAt;

    /**
     * Finds a state by country and state code.
     *
     * @param countryId
     *            the country ID
     * @param stateCode
     *            the state/province code (e.g., "VT", "ON")
     * @return Optional containing the state if found
     */
    public static Optional<GeoState> findByCountryAndCode(Long countryId, String stateCode) {
        if (countryId == null || stateCode == null || stateCode.isBlank()) {
            return Optional.empty();
        }
        return find("#" + QUERY_FIND_BY_COUNTRY_AND_CODE, io.quarkus.panache.common.Parameters
                .with("countryId", countryId).and("stateCode", stateCode.toUpperCase())).firstResultOptional();
    }

    /**
     * Finds all states for a given country.
     *
     * @param countryId
     *            the country ID
     * @return list of states ordered by name
     */
    public static List<GeoState> findByCountry(Long countryId) {
        if (countryId == null) {
            return List.of();
        }
        return find("#" + QUERY_FIND_BY_COUNTRY, io.quarkus.panache.common.Parameters.with("countryId", countryId))
                .list();
    }

    /**
     * Finds states by name prefix (autocomplete search).
     *
     * @param namePrefix
     *            the name prefix to search for (case-insensitive)
     * @return list of matching states ordered by name
     */
    public static List<GeoState> findByNamePrefix(String namePrefix) {
        if (namePrefix == null || namePrefix.isBlank()) {
            return List.of();
        }
        return find("#" + QUERY_FIND_BY_NAME_PREFIX,
                io.quarkus.panache.common.Parameters.with("namePrefix", namePrefix + "%")).list();
    }
}
