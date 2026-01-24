package villagecompute.homepage.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Collections;
import java.util.List;

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
        // Use PostgreSQLTestResource to manage testcontainer lifecycle
        return Collections.singletonList(new TestResourceEntry(PostgreSQLTestResource.class));
    }

    @Override
    public boolean disableGlobalTestResources() {
        // CRITICAL: Disable H2TestResource and other global test resources
        // This ensures PostgreSQL Testcontainers is used instead of H2
        return true;
    }

    @Override
    public String getConfigProfile() {
        return "test";
    }
}
