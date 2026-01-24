package villagecompute.homepage.util;

import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Utility for loading geographic data from dr5hn/countries-states-cities-database.
 *
 * <p>
 * <b>Data Source:</b> https://github.com/dr5hn/countries-states-cities-database
 *
 * <p>
 * <b>Loading Strategy:</b> Geographic data is loaded via shell scripts (not Java code) during database initialization:
 * <ol>
 * <li>{@code ./scripts/load-geo-data.sh} - Downloads dr5hn dataset and loads into native schema</li>
 * <li>{@code ./scripts/import-geo-data-to-app-schema.sh} - Transforms into application schema with US+CA filtering</li>
 * </ol>
 *
 * <p>
 * <b>Policy P6 Compliance:</b> Only US and Canada data is loaded (~40K cities instead of 153K+ globally). This reduces
 * database size and query complexity while meeting marketplace requirements.
 *
 * <p>
 * <b>Schema Initialization:</b>
 * <ol>
 * <li>Run migration {@code V017__create_geo_tables.sql} to create tables</li>
 * <li>Run {@code ./scripts/load-geo-data.sh} to download and load raw data</li>
 * <li>Run {@code ./scripts/import-geo-data-to-app-schema.sh} to transform into application schema</li>
 * </ol>
 *
 * <p>
 * <b>Data Validation:</b> After loading, verify with:
 *
 * <pre>
 * -- Count cities by country
 * SELECT c.name, COUNT(ci.id) as city_count
 * FROM geo_countries c
 * JOIN geo_cities ci ON ci.country_id = c.id
 * GROUP BY c.name
 * ORDER BY city_count DESC;
 *
 * -- Test radius query (50 miles from Seattle)
 * SELECT name,
 *        ST_Distance(location, ST_MakePoint(-122.3321, 47.6062)::geography) / 1609.34 as distance_miles
 * FROM geo_cities
 * WHERE ST_DWithin(location, ST_MakePoint(-122.3321, 47.6062)::geography, 80467)
 * ORDER BY distance_miles
 * LIMIT 10;
 * </pre>
 *
 * <p>
 * <b>Performance Expectations:</b>
 * <ul>
 * <li>Data load: < 5 minutes for US+CA dataset</li>
 * <li>Radius queries: p95 < 100ms for ≤100 mile radius (per Policy P11)</li>
 * <li>Autocomplete: < 50ms for name prefix queries</li>
 * </ul>
 *
 * @see villagecompute.homepage.data.models.GeoCountry for country entity
 * @see villagecompute.homepage.data.models.GeoState for state entity
 * @see villagecompute.homepage.data.models.GeoCity for city entity with spatial queries
 */
public class GeoDataLoader {

    private static final Logger LOG = Logger.getLogger(GeoDataLoader.class);

    private GeoDataLoader() {
        // Utility class - prevent instantiation
    }

    /**
     * Verifies that geographic data has been loaded successfully.
     *
     * <p>
     * Checks:
     * <ol>
     * <li>PostGIS extension is enabled</li>
     * <li>Tables exist and are populated</li>
     * <li>Spatial index exists on geo_cities.location</li>
     * </ol>
     *
     * <p>
     * This method can be called during application startup to ensure data availability.
     *
     * @return true if data is loaded and valid, false otherwise
     */
    public static boolean verifyDataLoaded() {
        try {
            // Execute verification script
            ProcessBuilder pb = new ProcessBuilder("./scripts/verify-geo-data.sh");
            Process process = pb.start();

            // Read script output
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                StringBuilder output = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
                LOG.infof("Geo data verification output:\n%s", output);
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                LOG.info("Geographic data verification succeeded");
                return true;
            } else {
                LOG.warnf("Geographic data verification failed with exit code %d", exitCode);
                return false;
            }
        } catch (IOException | InterruptedException e) {
            LOG.errorf(e, "Failed to verify geographic data");
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Loads geographic data by executing the import scripts.
     *
     * <p>
     * <b>WARNING:</b> This method should ONLY be called during initial database setup or after a full data reset. It
     * will download ~100MB of data and populate ~40K city records.
     *
     * <p>
     * <b>Execution Steps:</b>
     * <ol>
     * <li>Downloads dr5hn dataset (requires internet connection)</li>
     * <li>Loads into native schema (countries, states, cities tables)</li>
     * <li>Transforms into application schema (geo_countries, geo_states, geo_cities)</li>
     * <li>Populates PostGIS location column</li>
     * <li>Creates spatial indexes</li>
     * </ol>
     *
     * <p>
     * <b>Prerequisites:</b>
     * <ul>
     * <li>PostgreSQL 17 with PostGIS extension enabled</li>
     * <li>Migration V017 applied (creates geo_* tables)</li>
     * <li>Database credentials configured in .env or environment variables</li>
     * </ul>
     *
     * <p>
     * <b>Typical Usage:</b>
     *
     * <pre>
     * // During development setup
     * if (!GeoDataLoader.verifyDataLoaded()) {
     *     GeoDataLoader.loadData();
     * }
     * </pre>
     *
     * @throws RuntimeException
     *             if data loading fails
     */
    public static void loadData() {
        LOG.info("Starting geographic data load (this may take several minutes)...");

        try {
            // Step 1: Download and load raw data
            LOG.info("Step 1/2: Downloading and loading raw dr5hn dataset...");
            executeScript("./scripts/load-geo-data.sh");

            // Step 2: Transform into application schema
            LOG.info("Step 2/2: Transforming into application schema with US+CA filtering...");
            executeScript("./scripts/import-geo-data-to-app-schema.sh");

            LOG.info("Geographic data load completed successfully");

            // Verify the load
            if (!verifyDataLoaded()) {
                throw new RuntimeException("Data verification failed after load");
            }

        } catch (Exception e) {
            LOG.errorf(e, "Failed to load geographic data");
            throw new RuntimeException("Geographic data load failed", e);
        }
    }

    /**
     * Executes a shell script and logs output.
     *
     * @param scriptPath
     *            path to the script to execute
     * @throws IOException
     *             if script execution fails
     * @throws InterruptedException
     *             if script is interrupted
     */
    private static void executeScript(String scriptPath) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(scriptPath);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // Stream script output to log
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                LOG.info(line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(String.format("Script %s failed with exit code %d", scriptPath, exitCode));
        }
    }

    /**
     * Returns the expected record counts for the US+CA dataset per Policy P6.
     *
     * @return record containing expected counts
     */
    public static ExpectedCounts getExpectedCounts() {
        return new ExpectedCounts(2, // countries: US + CA
                63, // states: 50 US states + 13 Canadian provinces/territories
                40000 // cities: approximate US+CA total
        );
    }

    /**
     * Expected record counts for validation.
     *
     * @param countries
     *            expected number of countries (2 for US+CA)
     * @param states
     *            expected number of states/provinces (63 for US+CA)
     * @param cities
     *            expected number of cities (~40K for US+CA)
     */
    public record ExpectedCounts(int countries, int states, int cities) {
    }
}
