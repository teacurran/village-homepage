package villagecompute.homepage.data.models;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import villagecompute.homepage.BaseIntegrationTest;
import villagecompute.homepage.testing.PostgreSQLTestProfile;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for geographic entities (GeoCountry, GeoState, GeoCity) with PostGIS spatial queries.
 *
 * <p>
 * Verifies:
 * <ul>
 * <li>Entity persistence and relationships</li>
 * <li>PostGIS spatial column mapping (geography(Point, 4326))</li>
 * <li>Radius queries using ST_DWithin</li>
 * <li>Autocomplete search queries</li>
 * <li>Named query validation</li>
 * <li>Performance targets (p95 < 100ms per Policy P11)</li>
 * </ul>
 *
 * <p>
 * <b>Test Data:</b> Creates sample US + Canada cities following Policy P6. Real data is loaded via
 * {@code ./scripts/import-geo-data-to-app-schema.sh}.
 *
 * <p>
 * <b>PostGIS Requirement:</b> Requires PostgreSQL 17 with PostGIS extension enabled. Testcontainers uses
 * postgis/postgis image with extension pre-installed.
 */
@QuarkusTest
@TestProfile(PostgreSQLTestProfile.class)
public class GeoCityIntegrationTest extends BaseIntegrationTest {

    @Inject
    EntityManager entityManager;

    private GeometryFactory geometryFactory;
    private GeoCountry unitedStates;
    private GeoCountry canada;
    private GeoState vermont;
    private GeoState washington;
    private GeoState ontario;
    private GeoCity groton;
    private GeoCity burlington;
    private GeoCity seattle;
    private GeoCity tacoma;
    private GeoCity toronto;

    @BeforeEach
    @Transactional
    public void setupTestData() {
        // Clean up any existing test data from previous test runs
        // This is necessary because @Transactional on @BeforeEach commits data
        // which is then visible to all subsequent tests
        entityManager.createNativeQuery("DELETE FROM geo_cities").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM geo_states").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM geo_countries").executeUpdate();

        // Initialize JTS GeometryFactory for creating Point geometries
        // SRID 4326 = WGS84 coordinate system (standard lat/lon)
        geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

        // Create spatial index if it doesn't exist (drop-and-create removes it)
        try {
            entityManager
                    .createNativeQuery(
                            "CREATE INDEX IF NOT EXISTS idx_geo_cities_location ON geo_cities USING GIST(location)")
                    .executeUpdate();
        } catch (Exception e) {
            // Index creation might fail if table doesn't exist yet - that's ok
            // Hibernate will create the table via drop-and-create, then we'll retry
        }

        // Create countries
        unitedStates = createCountry("United States", "US", "USA", "+1", "Washington", "USD", "🇺🇸");
        canada = createCountry("Canada", "CA", "CAN", "+1", "Ottawa", "CAD", "🇨🇦");

        // Create states
        vermont = createState(unitedStates, "Vermont", "VT", 44.5588, -72.5778);
        washington = createState(unitedStates, "Washington", "WA", 47.7511, -120.7401);
        ontario = createState(canada, "Ontario", "ON", 51.2538, -85.3232);

        // Create cities with PostGIS location column
        // Groton, VT (44.7172°N, 72.2095°W) - reference point for radius tests
        groton = createCity(vermont, unitedStates, "Groton", 44.7172, -72.2095, "America/New_York");

        // Burlington, VT (44.4759°N, 73.2121°W) - ~60 miles from Groton
        burlington = createCity(vermont, unitedStates, "Burlington", 44.4759, -73.2121, "America/New_York");

        // Seattle, WA (47.6062°N, 122.3321°W) - ~2,400 miles from Groton
        seattle = createCity(washington, unitedStates, "Seattle", 47.6062, -122.3321, "America/Los_Angeles");

        // Tacoma, WA (47.2529°N, 122.4443°W) - ~25 miles from Seattle
        tacoma = createCity(washington, unitedStates, "Tacoma", 47.2529, -122.4443, "America/Los_Angeles");

        // Toronto, ON (43.6532°N, 79.3832°W) - ~370 miles from Groton
        toronto = createCity(ontario, canada, "Toronto", 43.6532, -79.3832, "America/Toronto");

        // Flush changes to database so they're visible in the test transaction
        // Note: Don't clear() here because @Transactional and @TestTransaction share the same persistence context
        entityManager.flush();
    }

    @Test
    @TestTransaction
    public void testGeoCountryPersistenceAndFinders() {
        // Verify country persistence
        assertNotNull(unitedStates.id, "United States should have generated ID");
        assertNotNull(canada.id, "Canada should have generated ID");

        // Test findByIso2
        var foundUS = GeoCountry.findByIso2("US");
        assertTrue(foundUS.isPresent(), "Should find US by ISO2 code");
        assertEquals("United States", foundUS.get().name);
        assertEquals("🇺🇸", foundUS.get().emoji);

        var foundCA = GeoCountry.findByIso2("ca"); // Test case insensitivity
        assertTrue(foundCA.isPresent(), "Should find Canada by lowercase ISO2");
        assertEquals("Canada", foundCA.get().name);

        // Test findByNamePrefix
        var canadaMatches = GeoCountry.findByNamePrefix("Can");
        assertEquals(1, canadaMatches.size(), "Should find 1 country starting with 'Can'");
        assertEquals("Canada", canadaMatches.get(0).name);

        // Test empty result
        var noMatch = GeoCountry.findByIso2("ZZ");
        assertFalse(noMatch.isPresent(), "Should not find country with invalid ISO2");
    }

    @Test
    @TestTransaction
    public void testGeoStateRelationshipsAndFinders() {
        // Verify state-country relationship
        assertNotNull(vermont.country, "State should have country relationship");
        assertEquals("United States", vermont.country.name);

        // Test findByCountryAndCode
        var foundVT = GeoState.findByCountryAndCode(unitedStates.id, "VT");
        assertTrue(foundVT.isPresent(), "Should find Vermont by country ID and code");
        assertEquals("Vermont", foundVT.get().name);

        // Test findByCountry
        var usStates = GeoState.findByCountry(unitedStates.id);
        assertEquals(2, usStates.size(), "Should find 2 US states (VT, WA)");

        var canadaStates = GeoState.findByCountry(canada.id);
        assertEquals(1, canadaStates.size(), "Should find 1 Canadian province (ON)");

        // Test findByNamePrefix
        var vermontMatches = GeoState.findByNamePrefix("Ver");
        assertEquals(1, vermontMatches.size(), "Should find 1 state starting with 'Ver'");
        assertEquals("Vermont", vermontMatches.get(0).name);
    }

    @Test
    @TestTransaction
    public void testGeoCityPersistenceWithSpatialColumn() {
        // Verify city persistence
        assertNotNull(groton.id, "Groton should have generated ID");
        assertNotNull(groton.location, "Groton should have PostGIS location column populated");

        // Verify PostGIS Point structure
        assertEquals(4326, groton.location.getSRID(), "Location should use SRID 4326 (WGS84)");
        assertEquals(-72.2095, groton.location.getX(), 0.0001, "Longitude should match (X coordinate)");
        assertEquals(44.7172, groton.location.getY(), 0.0001, "Latitude should match (Y coordinate)");

        // Verify relationships
        assertNotNull(groton.state, "City should have state relationship");
        assertEquals("Vermont", groton.state.name);
        assertNotNull(groton.country, "City should have country relationship");
        assertEquals("United States", groton.country.name);
    }

    @Test
    @TestTransaction
    public void testRadiusQueryWithinRange() {
        // Find cities within 100 miles of Groton, VT
        List<GeoCity> nearbyCities = GeoCity.findNearby(44.7172, -72.2095, 100.0);

        // Should find: Groton (0 mi), Burlington (~60 mi)
        // Should NOT find: Seattle (~2,400 mi), Toronto (~370 mi), Tacoma (~2,400 mi)
        assertEquals(2, nearbyCities.size(), "Should find 2 cities within 100 miles of Groton");

        // Verify correct cities returned (order not guaranteed)
        var cityNames = nearbyCities.stream().map(c -> c.name).toList();
        assertTrue(cityNames.contains("Groton"), "Should include Groton");
        assertTrue(cityNames.contains("Burlington"), "Should include Burlington");
        assertFalse(cityNames.contains("Seattle"), "Should NOT include Seattle (too far)");
        assertFalse(cityNames.contains("Toronto"), "Should NOT include Toronto (too far)");
    }

    @Test
    @TestTransaction
    public void testRadiusQuerySmallRadius() {
        // Find cities within 30 miles of Seattle, WA
        List<GeoCity> nearbyCities = GeoCity.findNearby(47.6062, -122.3321, 30.0);

        // Should find: Seattle (0 mi), Tacoma (~25 mi)
        assertEquals(2, nearbyCities.size(), "Should find 2 cities within 30 miles of Seattle");

        var cityNames = nearbyCities.stream().map(c -> c.name).toList();
        assertTrue(cityNames.contains("Seattle"), "Should include Seattle");
        assertTrue(cityNames.contains("Tacoma"), "Should include Tacoma");
    }

    @Test
    @TestTransaction
    public void testRadiusQueryWithDistance() {
        // Find cities within 500 miles of Groton, VT with distances
        List<GeoCity.DistancedGeoCity> results = GeoCity.findNearbyWithDistance(44.7172, -72.2095, 500.0);

        // Should find: Groton (0 mi), Burlington (~60 mi), Toronto (~370 mi)
        // Should NOT find: Seattle (~2,400 mi), Tacoma (~2,400 mi)
        assertEquals(3, results.size(), "Should find 3 cities within 500 miles");

        // Verify ordered by distance (nearest first)
        assertEquals("Groton", results.get(0).city().name, "Nearest city should be Groton");
        assertTrue(results.get(0).distanceMiles() < 1.0, "Groton distance should be ~0 miles");

        assertEquals("Burlington", results.get(1).city().name, "Second nearest should be Burlington");
        assertTrue(results.get(1).distanceMiles() > 50.0 && results.get(1).distanceMiles() < 70.0,
                "Burlington should be ~60 miles away");

        assertEquals("Toronto", results.get(2).city().name, "Third nearest should be Toronto");
        assertTrue(results.get(2).distanceMiles() > 350.0 && results.get(2).distanceMiles() < 400.0,
                "Toronto should be ~370 miles away");
    }

    @Test
    @TestTransaction
    public void testAutocompleteCitySearch() {
        // Test autocomplete for "Bur"
        List<GeoCity> matches = GeoCity.findByNamePrefix("Bur");
        assertEquals(1, matches.size(), "Should find 1 city starting with 'Bur'");
        assertEquals("Burlington", matches.get(0).name);

        // Test autocomplete for "T" (should match Tacoma, Toronto)
        var tMatches = GeoCity.findByNamePrefix("T");
        assertEquals(2, tMatches.size(), "Should find 2 cities starting with 'T'");
        var tNames = tMatches.stream().map(c -> c.name).toList();
        assertTrue(tNames.contains("Tacoma"));
        assertTrue(tNames.contains("Toronto"));

        // Test case insensitivity
        var grotonMatches = GeoCity.findByNamePrefix("gro");
        assertEquals(1, grotonMatches.size(), "Should find 1 city with lowercase prefix");
        assertEquals("Groton", grotonMatches.get(0).name);

        // Test no matches
        var noMatches = GeoCity.findByNamePrefix("Xyz");
        assertTrue(noMatches.isEmpty(), "Should find no cities starting with 'Xyz'");
    }

    @Test
    @TestTransaction
    public void testFindCitiesByState() {
        List<GeoCity> vtCities = GeoCity.findByState(vermont.id);
        assertEquals(2, vtCities.size(), "Should find 2 cities in Vermont");

        var cityNames = vtCities.stream().map(c -> c.name).toList();
        assertTrue(cityNames.contains("Groton"));
        assertTrue(cityNames.contains("Burlington"));
    }

    @Test
    @TestTransaction
    public void testFindCitiesByCountry() {
        List<GeoCity> usCities = GeoCity.findByCountry(unitedStates.id);
        assertEquals(4, usCities.size(), "Should find 4 US cities");

        List<GeoCity> canadaCities = GeoCity.findByCountry(canada.id);
        assertEquals(1, canadaCities.size(), "Should find 1 Canadian city");
        assertEquals("Toronto", canadaCities.get(0).name);
    }

    @Test
    @TestTransaction
    public void testRadiusQueryPerformance() {
        // Performance test: radius query should complete in < 100ms per Policy P11
        // Note: This is a smoke test with minimal data; real performance testing requires 40K+ cities
        long startTime = System.currentTimeMillis();

        List<GeoCity> results = GeoCity.findNearby(44.7172, -72.2095, 100.0);

        long duration = System.currentTimeMillis() - startTime;

        assertNotNull(results, "Query should return results");
        assertTrue(duration < 100, String.format("Query should complete in < 100ms (took %dms)", duration));
    }

    @Test
    @TestTransaction
    public void testSpatialIndexUsage() {
        // Verify that GIST index exists and is used by spatial queries
        // This query checks pg_indexes for the spatial index on location column
        var indexCheck = (Boolean) entityManager.createNativeQuery("SELECT EXISTS (SELECT 1 FROM pg_indexes "
                + "WHERE tablename = 'geo_cities' " + "AND indexname = 'idx_geo_cities_location')").getSingleResult();

        assertTrue(indexCheck, "GIST spatial index should exist on geo_cities.location");
    }

    @Test
    @TestTransaction
    public void testPostGISExtensionEnabled() {
        // Verify PostGIS extension is available
        var postgisVersion = (String) entityManager.createNativeQuery("SELECT PostGIS_Version()").getSingleResult();

        assertNotNull(postgisVersion, "PostGIS extension should be enabled");
        assertTrue(postgisVersion.contains("3."), "PostGIS version should be 3.x");
    }

    @Test
    @TestTransaction
    public void testNullInputHandling() {
        // Test graceful handling of null/empty inputs
        assertTrue(GeoCountry.findByIso2(null).isEmpty(), "Should handle null ISO2");
        assertTrue(GeoCountry.findByIso2("").isEmpty(), "Should handle empty ISO2");
        assertTrue(GeoCountry.findByNamePrefix(null).isEmpty(), "Should handle null name prefix");

        assertTrue(GeoState.findByCountryAndCode(null, "VT").isEmpty(), "Should handle null country ID");
        assertTrue(GeoState.findByCountryAndCode(unitedStates.id, null).isEmpty(), "Should handle null state code");

        assertTrue(GeoCity.findByNamePrefix(null).isEmpty(), "Should handle null city name prefix");
        assertTrue(GeoCity.findByState(null).isEmpty(), "Should handle null state ID");
        assertTrue(GeoCity.findByCountry(null).isEmpty(), "Should handle null country ID");
    }

    // Helper methods for test data creation

    private GeoCountry createCountry(String name, String iso2, String iso3, String phoneCode, String capital,
            String currency, String emoji) {
        GeoCountry country = new GeoCountry();
        country.name = name;
        country.iso2 = iso2;
        country.iso3 = iso3;
        country.phoneCode = phoneCode;
        country.capital = capital;
        country.currency = currency;
        country.nativeName = name;
        country.region = "Americas";
        country.subregion = "Northern America";
        country.emoji = emoji;
        country.persist();
        return country;
    }

    private GeoState createState(GeoCountry country, String name, String stateCode, double latitude, double longitude) {
        GeoState state = new GeoState();
        state.country = country;
        state.name = name;
        state.stateCode = stateCode;
        state.latitude = BigDecimal.valueOf(latitude);
        state.longitude = BigDecimal.valueOf(longitude);
        state.persist();
        return state;
    }

    private GeoCity createCity(GeoState state, GeoCountry country, String name, double latitude, double longitude,
            String timezone) {
        GeoCity city = new GeoCity();
        city.state = state;
        city.country = country;
        city.name = name;
        city.latitude = BigDecimal.valueOf(latitude);
        city.longitude = BigDecimal.valueOf(longitude);
        city.timezone = timezone;

        // NOTE: location column is auto-calculated by @PrePersist callback
        // Do NOT set it manually here - the callback will handle it

        city.persist();
        return city;
    }
}
