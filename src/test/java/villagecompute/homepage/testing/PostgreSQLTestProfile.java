package villagecompute.homepage.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.List;
import java.util.Map;

/**
 * Quarkus test profile for PostgreSQL 17 + pgvector integration tests.
 *
 * <p>
 * This profile configures tests to use Testcontainers PostgreSQL via {@link PostgreSQLTestResource}. This approach
 * bypasses DevServices to avoid conflicts with .env file configuration (which sets QUARKUS_DATASOURCE_JDBC_URL).
 *
 * <p>
 * <b>Usage:</b>
 *
 * <pre>
 * &#64;QuarkusTest
 * &#64;TestProfile(PostgreSQLTestProfile.class)
 * public class MyIntegrationTest extends BaseIntegrationTest {
 *     // test methods
 * }
 * </pre>
 *
 * <p>
 * <b>Ref:</b> Task I1.T2 - BaseIntegrationTest infrastructure
 */
public class PostgreSQLTestProfile implements QuarkusTestProfile {

    @Override
    public List<TestResourceEntry> testResources() {
        // Use PostgreSQLTestResource to explicitly manage Testcontainers PostgreSQL
        // This bypasses DevServices and overrides .env file JDBC_URL
        return List.of(new TestResourceEntry(PostgreSQLTestResource.class));
    }

    @Override
    public boolean disableGlobalTestResources() {
        // Don't disable global test resources
        return false;
    }

    @Override
    public String getConfigProfile() {
        return "test";
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        // Disable DevServices since we're using PostgreSQLTestResource
        // PostgreSQLTestResource provides jdbc.url, username, and password
        return Map.of(
                "quarkus.datasource.db-kind", "postgresql",
                "quarkus.datasource.devservices.enabled", "false");
    }
}
