package villagecompute.homepage.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Quarkus test profile for PostgreSQL 17 + PostGIS integration tests.
 *
 * <p>
 * This profile configures tests to use Testcontainers PostgreSQL instead of H2. It loads configuration from
 * application-test.properties.
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
    public Map<String, String> getConfigOverrides() {
        // Override datasource configuration to use PostgreSQL 17 + PostGIS with dev services
        return Map.of(
                "quarkus.datasource.db-kind", "postgresql",
                "quarkus.datasource.username", "test",
                "quarkus.datasource.password", "test",
                "quarkus.datasource.devservices.enabled", "true",
                "quarkus.datasource.devservices.image-name", "postgis/postgis:17-3.5-alpine",
                "quarkus.datasource.devservices.init-script-path", "db/init-test-postgis.sql",
                "quarkus.hibernate-orm.database.generation", "drop-and-create",
                "quarkus.hibernate-orm.log.sql", "false",
                "quarkus.hibernate-orm.sql-load-script", "no-file"
        );
    }

    @Override
    public String getConfigProfile() {
        return "test";
    }
}
