package villagecompute.homepage.testing;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.HashMap;
import java.util.Map;

/**
 * Quarkus test resource that starts a PostgreSQL 17 + PostGIS container for integration tests.
 *
 * <p>
 * This resource explicitly manages the Testcontainers PostgreSQL instance and provides JDBC connection details via
 * configuration overrides. This approach bypasses Quarkus Dev Services to avoid conflicts with .env file configuration.
 *
 * <p>
 * <b>CI Mode:</b> When QUARKUS_DATASOURCE_JDBC_URL environment variable is set (e.g., in GitHub Actions), this resource
 * skips container startup and returns an empty configuration map, allowing the tests to use the CI-provided database.
 *
 * <p>
 * <b>Usage:</b>
 *
 * <pre>
 * &#64;QuarkusTest
 * &#64;QuarkusTestResource(PostgreSQLTestResource.class)
 * public class MyIntegrationTest {
 *     // test methods
 * }
 * </pre>
 *
 * <p>
 * <b>Rationale:</b> Dev Services cannot activate when .env file sets QUARKUS_DATASOURCE_JDBC_URL. This resource
 * provides explicit JDBC URL configuration that overrides .env values.
 */
public class PostgreSQLTestResource implements QuarkusTestResourceLifecycleManager {

    private PostgreSQLContainer<?> postgresContainer;
    private boolean usingCiDatabase = false;

    @Override
    public Map<String, String> start() {
        // Check if running in CI with pre-configured database
        String ciJdbcUrl = System.getenv("QUARKUS_DATASOURCE_JDBC_URL");
        if (ciJdbcUrl != null && !ciJdbcUrl.isEmpty()) {
            // In CI: database is already available via service container
            // Don't start Testcontainers, just use the CI database
            usingCiDatabase = true;
            return Map.of();
        }

        // Local development: Start PostgreSQL with PostGIS + pgvector container
        // Using joshuasundance/postgis_pgvector which has both extensions pre-installed
        DockerImageName postgisImage = DockerImageName.parse("joshuasundance/postgis_pgvector:latest")
                .asCompatibleSubstituteFor("postgres");

        postgresContainer = new PostgreSQLContainer<>(postgisImage).withDatabaseName("homepage_test")
                .withUsername("test").withPassword("test").withReuse(true)
                // Enable both extensions
                .withInitScript("db/init-test-postgis.sql");

        postgresContainer.start();

        // Return configuration overrides
        // NOTE: Only override runtime properties here. Build-time properties like devservices.enabled,
        // db-kind, and dialect cannot be changed at runtime (they cause "Build time property cannot be changed" errors)
        Map<String, String> config = new HashMap<>();
        config.put("quarkus.datasource.username", postgresContainer.getUsername());
        config.put("quarkus.datasource.password", postgresContainer.getPassword());
        config.put("quarkus.datasource.jdbc.url", postgresContainer.getJdbcUrl());

        return config;
    }

    @Override
    public void stop() {
        if (postgresContainer != null && !usingCiDatabase) {
            // Container will be reused across test classes (withReuse(true))
            // Testcontainers Ryuk will clean up on JVM exit
            postgresContainer.stop();
        }
    }
}
