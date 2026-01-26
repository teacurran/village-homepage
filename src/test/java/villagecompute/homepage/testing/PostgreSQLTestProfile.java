package villagecompute.homepage.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Quarkus test profile for PostgreSQL 17 + PostGIS integration tests.
 *
 * <p>
 * This profile configures tests to use Testcontainers PostgreSQL instead of H2. It delegates container management to
 * {@link PostgreSQLTestResource} to avoid conflicts with .env file configuration.
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
 * <b>Note:</b> This profile is automatically used by BaseIntegrationTest subclasses when no explicit
 * &#64;QuarkusTestResource(H2TestResource.class) annotation is present.
 *
 * <p>
 * <b>Ref:</b> Task I1.T2 - BaseIntegrationTest infrastructure
 */
public class PostgreSQLTestProfile implements QuarkusTestProfile {

    @Override
    public List<TestResourceEntry> testResources() {
        // Don't use PostgreSQLTestResource - rely on Quarkus DevServices which is already configured
        // in application.yaml %test profile with postgis/postgis:17-3.5-alpine image
        return Collections.emptyList();
    }

    @Override
    public boolean disableGlobalTestResources() {
        // Don't disable global test resources - DevServices needs to work
        return false;
    }

    @Override
    public String getConfigProfile() {
        return "test";
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        // Override any env vars that might interfere with DevServices
        // Note: We deliberately do NOT set jdbc.url - DevServices needs it to be unset to auto-generate the URL
        // Note: We don't set username/password - DevServices uses its own credentials by default
        return Map.of("quarkus.datasource.db-kind", "postgresql", "quarkus.datasource.devservices.enabled", "true",
                "quarkus.datasource.devservices.image-name", "joshuasundance/postgis_pgvector:latest",
                "quarkus.datasource.devservices.init-script-path", "db/init-test-postgis.sql");
    }
}
