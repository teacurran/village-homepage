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
        // Start PostgreSQL 17 + PostGIS container
        // NOTE: Using postgis/postgis image which includes PostGIS for geographic queries (I6.T2)
        // pgvector is NOT available in PostGIS images but is not required for GeoCity tests
        // Use asCompatibleSubstituteFor to allow postgis image (based on postgres)
        DockerImageName postgisImage = DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                .asCompatibleSubstituteFor("postgres");

        postgresContainer = new PostgreSQLContainer<>(postgisImage).withDatabaseName("homepage_test")
                .withUsername("test").withPassword("test").withReuse(true)
                // Enable PostGIS extension for geographic queries (I6.T2)
                .withInitScript("db/init-test-postgis.sql");

        postgresContainer.start();

        // Return configuration overrides
        Map<String, String> config = new HashMap<>();
        config.put("quarkus.datasource.db-kind", "postgresql");
        config.put("quarkus.datasource.username", postgresContainer.getUsername());
        config.put("quarkus.datasource.password", postgresContainer.getPassword());
        config.put("quarkus.datasource.jdbc.url", postgresContainer.getJdbcUrl());
        config.put("quarkus.datasource.jdbc.driver", "org.postgresql.Driver");
        config.put("quarkus.datasource.devservices.enabled", "false"); // Disable Dev Services (we're managing
                                                                       // container)
        config.put("quarkus.hibernate-orm.database.generation", "drop-and-create");
        config.put("quarkus.hibernate-orm.log.sql", "false");
        config.put("quarkus.hibernate-orm.sql-load-script", "no-file");
        // Enable Jackson for JSONB type handling
        config.put("quarkus.hibernate-orm.mapping.jackson.convert-to-json-mode", "enabled");

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
