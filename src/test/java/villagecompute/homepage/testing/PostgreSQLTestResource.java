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

    @Override
    public Map<String, String> start() {
        // Start PostgreSQL 17 + pgvector container
        // NOTE: Using ankane/pgvector image which includes both PostgreSQL 17 and pgvector
        // PostGIS will be installed via init script
        // Use asCompatibleSubstituteFor to allow pgvector image (based on postgres)
        DockerImageName pgvectorImage = DockerImageName.parse("pgvector/pgvector:pg17")
                .asCompatibleSubstituteFor("postgres");

        postgresContainer = new PostgreSQLContainer<>(pgvectorImage).withDatabaseName("homepage_test")
                .withUsername("test").withPassword("test").withReuse(true)
                // Enable PostGIS and pgvector extensions
                .withInitScript("db/init-test-pgvector.sql");

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
        if (postgresContainer != null) {
            // Container will be reused across test classes (withReuse(true))
            // Testcontainers Ryuk will clean up on JVM exit
            postgresContainer.stop();
        }
    }
}
