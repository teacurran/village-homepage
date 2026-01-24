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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * GeoCity entity representing cities in the geographic reference dataset with PostGIS spatial support.
 *
 * <p>
 * Part of the marketplace location filtering system per Policy P6 (US + Canada only). Loaded from dr5hn/countries-
 * states-cities-database via {@code ./scripts/import-geo-data-to-app-schema.sh}.
 *
 * <p>
 * <b>Spatial Queries:</b> Uses PostGIS {@code geography(Point, 4326)} column for accurate distance calculations. Radius
 * queries (5-250 miles per Policy P6) are optimized via GIST spatial index. Performance target: p95 < 100ms for ≤100mi
 * radius per Policy P11.
 *
 * <p>
 * <b>Schema Mapping:</b>
 * <ul>
 * <li>{@code id} (BIGSERIAL, PK) - Primary identifier</li>
 * <li>{@code state_id} (BIGINT, FK) - Foreign key to geo_states</li>
 * <li>{@code country_id} (BIGINT, FK) - Foreign key to geo_countries</li>
 * <li>{@code name} (TEXT) - City name (e.g., "Groton", "Burlington")</li>
 * <li>{@code latitude} (NUMERIC(10,7)) - City latitude (WGS84)</li>
 * <li>{@code longitude} (NUMERIC(10,7)) - City longitude (WGS84)</li>
 * <li>{@code timezone} (TEXT) - IANA timezone (e.g., "America/New_York")</li>
 * <li>{@code location} (geography(Point, 4326)) - PostGIS spatial column for radius queries</li>
 * <li>{@code created_at} (TIMESTAMPTZ) - Record creation timestamp</li>
 * </ul>
 *
 * <p>
 * <b>Indexes:</b>
 * <ul>
 * <li>idx_geo_cities_location (GIST) - Spatial index for ST_DWithin queries (CRITICAL for performance)</li>
 * <li>idx_geo_cities_state_id on state_id</li>
 * <li>idx_geo_cities_country_id on country_id</li>
 * <li>idx_geo_cities_name on name</li>
 * <li>idx_geo_cities_state_name on (state_id, name)</li>
 * </ul>
 *
 * <p>
 * <b>Usage Example:</b>
 *
 * <pre>
 * // Find cities within 25 miles of Groton, VT (44.7172°N, 72.2095°W)
 * double radiusMiles = 25.0;
 * List&lt;GeoCity&gt; nearbyCities = GeoCity.findNearby(44.7172, -72.2095, radiusMiles);
 *
 * // Autocomplete search
 * List&lt;GeoCity&gt; matches = GeoCity.findByNamePrefix("Burl"); // Returns Burlington, etc.
 * </pre>
 *
 * @see GeoCountry for parent country
 * @see GeoState for parent state/province
 */
@Entity
@Table(
        name = "geo_cities")
@NamedQuery(
        name = GeoCity.QUERY_FIND_BY_NAME_PREFIX,
        query = "SELECT c FROM GeoCity c WHERE LOWER(c.name) LIKE LOWER(:namePrefix) ORDER BY c.name")
@NamedQuery(
        name = GeoCity.QUERY_FIND_BY_STATE,
        query = "SELECT c FROM GeoCity c WHERE c.state.id = :stateId ORDER BY c.name")
@NamedQuery(
        name = GeoCity.QUERY_FIND_BY_COUNTRY,
        query = "SELECT c FROM GeoCity c WHERE c.country.id = :countryId ORDER BY c.name")
public class GeoCity extends PanacheEntityBase {

    public static final String QUERY_FIND_BY_NAME_PREFIX = "GeoCity.findByNamePrefix";
    public static final String QUERY_FIND_BY_STATE = "GeoCity.findByState";
    public static final String QUERY_FIND_BY_COUNTRY = "GeoCity.findByCountry";

    /**
     * Conversion factor: 1 mile = 1,609.34 meters
     */
    public static final double METERS_PER_MILE = 1609.34;

    @Id
    @GeneratedValue(
            strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(
            fetch = FetchType.LAZY)
    @JoinColumn(
            name = "state_id",
            nullable = false)
    public GeoState state;

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
            nullable = false,
            precision = 10,
            scale = 7)
    public BigDecimal latitude;

    @Column(
            nullable = false,
            precision = 10,
            scale = 7)
    public BigDecimal longitude;

    @Column
    public String timezone;

    /**
     * PostGIS spatial column for radius queries.
     *
     * <p>
     * Maps to {@code geometry(Point, 4326)} in PostgreSQL. Uses geometry type instead of geography because
     * Hibernate Spatial 7.x has better support for geometry columns. For distance calculations, we use
     * ST_DWithin with geography casting in native queries.
     *
     * <p>
     * Populated via:
     *
     * <pre>
     * ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)
     * </pre>
     *
     * <p>
     * Requires Hibernate Spatial and JTS Topology Suite dependencies.
     *
     * <p>
     * NOTE: Using {@code @JdbcTypeCode(SqlTypes.GEOMETRY)} to explicitly map to PostGIS geometry type.
     */
    @Column(
            columnDefinition = "geometry(Point, 4326)")
    @JdbcTypeCode(SqlTypes.GEOMETRY)
    public Point location;

    @CreationTimestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false)
    public Instant createdAt;

    /**
     * Auto-calculates PostGIS location from latitude/longitude before persist/update.
     *
     * <p>
     * PostGIS uses (longitude, latitude) order for ST_MakePoint, not (lat, lon). This is because PostGIS follows the
     * (X, Y) convention where X is longitude and Y is latitude.
     *
     * <p>
     * SRID 4326 is WGS84 coordinate system (GPS coordinates).
     *
     * <p>
     * This method is called automatically by JPA before INSERT or UPDATE operations. It ensures the spatial location
     * column is always in sync with the latitude/longitude values.
     */
    @PrePersist
    @PreUpdate
    public void calculateLocation() {
        if (latitude != null && longitude != null && location == null) {
            // CRITICAL: JTS Point constructor uses (x, y) which maps to (longitude, latitude)
            // This matches PostGIS ST_MakePoint(longitude, latitude) convention
            GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
            location = geometryFactory.createPoint(new Coordinate(longitude.doubleValue(), latitude.doubleValue()));
        }
    }

    /**
     * Finds cities by name prefix (autocomplete search).
     *
     * <p>
     * Case-insensitive LIKE query ordered by name. For high-volume autocomplete, consider adding LIMIT:
     *
     * <pre>
     * GeoCity.find("#" + QUERY_FIND_BY_NAME_PREFIX, params).page(0, 10).list()
     * </pre>
     *
     * @param namePrefix
     *            the name prefix to search for (case-insensitive)
     * @return list of matching cities ordered by name
     */
    public static List<GeoCity> findByNamePrefix(String namePrefix) {
        if (namePrefix == null || namePrefix.isBlank()) {
            return List.of();
        }
        return find("#" + QUERY_FIND_BY_NAME_PREFIX,
                io.quarkus.panache.common.Parameters.with("namePrefix", namePrefix + "%")).list();
    }

    /**
     * Finds all cities in a given state.
     *
     * @param stateId
     *            the state ID
     * @return list of cities ordered by name
     */
    public static List<GeoCity> findByState(Long stateId) {
        if (stateId == null) {
            return List.of();
        }
        return find("#" + QUERY_FIND_BY_STATE, io.quarkus.panache.common.Parameters.with("stateId", stateId)).list();
    }

    /**
     * Finds all cities in a given country.
     *
     * @param countryId
     *            the country ID
     * @return list of cities ordered by name
     */
    public static List<GeoCity> findByCountry(Long countryId) {
        if (countryId == null) {
            return List.of();
        }
        return find("#" + QUERY_FIND_BY_COUNTRY, io.quarkus.panache.common.Parameters.with("countryId", countryId))
                .list();
    }

    /**
     * Finds cities within a specified radius of a coordinate.
     *
     * <p>
     * Uses PostGIS {@code ST_DWithin(geography, geography, distance_meters)} for accurate distance calculations. The
     * GIST spatial index {@code idx_geo_cities_location} enables fast queries (p95 < 100ms for ≤100mi radius per Policy
     * P11).
     *
     * <p>
     * <b>IMPORTANT:</b> {@code ST_MakePoint(longitude, latitude)} takes (X, Y) coordinates, which is (lon, lat), NOT
     * (lat, lon). This is a common source of errors.
     *
     * <p>
     * <b>Distance Calculation:</b> PostGIS {@code ST_DWithin} on {@code geography} type uses meters. The method
     * converts miles to meters via {@link #METERS_PER_MILE}.
     *
     * <p>
     * <b>Example:</b>
     *
     * <pre>
     * // Find cities within 50 miles of Seattle (47.6062°N, 122.3321°W)
     * List&lt;GeoCity&gt; cities = GeoCity.findNearby(47.6062, -122.3321, 50.0);
     * </pre>
     *
     * <p>
     * <b>Performance Note:</b> For very large radii (>250 miles), consider adding pagination or limiting results to
     * avoid memory issues.
     *
     * @param latitude
     *            the center latitude in decimal degrees (WGS84)
     * @param longitude
     *            the center longitude in decimal degrees (WGS84)
     * @param radiusMiles
     *            the search radius in miles (typically 5-250 per Policy P6)
     * @return list of cities within the radius, unordered (order by distance separately if needed)
     */
    @SuppressWarnings("unchecked")
    public static List<GeoCity> findNearby(double latitude, double longitude, double radiusMiles) {
        double radiusMeters = radiusMiles * METERS_PER_MILE;

        // ST_MakePoint takes (longitude, latitude) - note the order!
        // Cast both sides to geography for accurate distance calculations
        return getEntityManager()
                .createNativeQuery(
                        "SELECT * FROM geo_cities WHERE ST_DWithin(location::geography, ST_MakePoint(?1, ?2)::geography, ?3)",
                        GeoCity.class)
                .setParameter(1, longitude) // X coordinate = longitude
                .setParameter(2, latitude) // Y coordinate = latitude
                .setParameter(3, radiusMeters).getResultList();
    }

    /**
     * Finds cities within a specified radius of a coordinate, ordered by distance.
     *
     * <p>
     * Similar to {@link #findNearby(double, double, double)}, but includes distance calculation and ordering. Returns
     * results as a list of {@link DistancedGeoCity} records containing both the city and its distance from the center
     * point.
     *
     * <p>
     * <b>Distance Calculation:</b> Uses PostGIS {@code ST_Distance(geography, geography)} which returns meters. The
     * result is converted to miles via {@link #METERS_PER_MILE}.
     *
     * <p>
     * <b>Example:</b>
     *
     * <pre>
     * // Find cities within 25 miles of Groton, VT, ordered by distance
     * List&lt;DistancedGeoCity&gt; results = GeoCity.findNearbyWithDistance(44.7172, -72.2095, 25.0);
     * results.forEach(r -&gt; System.out.println(r.city().name + ": " + r.distanceMiles() + " miles"));
     * </pre>
     *
     * @param latitude
     *            the center latitude in decimal degrees (WGS84)
     * @param longitude
     *            the center longitude in decimal degrees (WGS84)
     * @param radiusMiles
     *            the search radius in miles (typically 5-250 per Policy P6)
     * @return list of cities with distances, ordered from nearest to farthest
     */
    @SuppressWarnings("unchecked")
    public static List<DistancedGeoCity> findNearbyWithDistance(double latitude, double longitude, double radiusMiles) {
        double radiusMeters = radiusMiles * METERS_PER_MILE;

        // Native query that returns Object[] with [column1, column2, ..., distance_miles]
        // We cannot use GeoCity.class result type when adding extra columns
        // Cast geometry to geography for accurate distance calculations
        List<Object[]> results = getEntityManager()
                .createNativeQuery(
                        "SELECT c.id, c.state_id, c.country_id, c.name, c.latitude, c.longitude, c.timezone, c.location, c.created_at, "
                                + "ST_Distance(c.location::geography, ST_MakePoint(?1, ?2)::geography) / ?4 as distance_miles "
                                + "FROM geo_cities c "
                                + "WHERE ST_DWithin(c.location::geography, ST_MakePoint(?1, ?2)::geography, ?3) "
                                + "ORDER BY distance_miles")
                .setParameter(1, longitude) // X coordinate = longitude
                .setParameter(2, latitude) // Y coordinate = latitude
                .setParameter(3, radiusMeters).setParameter(4, METERS_PER_MILE).getResultList();

        // Transform Object[] results into DistancedGeoCity records
        return results.stream().map(row -> {
            // Manually reconstruct GeoCity from columns
            Long id = ((Number) row[0]).longValue();
            GeoCity city = GeoCity.findById(id);
            // Distance is the last column
            double distanceMiles = ((Number) row[9]).doubleValue();
            return new DistancedGeoCity(city, distanceMiles);
        }).toList();
    }

    /**
     * Calculates the great-circle distance between two points using the Haversine formula.
     *
     * <p>
     * This is a fallback method for distance calculation when not using PostGIS ST_Distance. Accuracy is within 0.5%
     * for short distances (<500 miles).
     *
     * @param lat1
     *            latitude of first point
     * @param lon1
     *            longitude of first point
     * @param lat2
     *            latitude of second point
     * @param lon2
     *            longitude of second point
     * @return distance in miles
     */
    private static double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final double R = 3958.8; // Earth radius in miles

        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);

        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2) + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2)) * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return R * c;
    }

    /**
     * Record containing a GeoCity and its distance from a query point.
     *
     * @param city
     *            the GeoCity entity
     * @param distanceMiles
     *            the distance from the query point in miles
     */
    public record DistancedGeoCity(GeoCity city, double distanceMiles) {
    }
}
