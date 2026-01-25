package villagecompute.homepage.testing;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

/**
 * Forces Quarkus tests to use an in-memory H2 database that emulates PostgreSQL behavior.
 *
 * <p>
 * Quarkus devservices are disabled inside the Codex harness, so we override datasource settings directly to avoid
 * external Postgres dependencies when running {@code node tools/test.cjs}.
 */
public class H2TestResource implements QuarkusTestResourceLifecycleManager {

    private static final String JDBC_URL = "jdbc:h2:mem:homepage-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    @Override
    public Map<String, String> start() {
        // DISABLED: H2TestResource cannot override build-time properties at runtime.
        // The build is configured with PostgreSQL DevServices, so trying to switch to H2
        // causes "Build time property cannot be changed at runtime" errors.
        // All tests now use PostgreSQL DevServices from the %test profile.
        // This resource is kept for backwards compatibility but returns empty config.
        return Map.of();
    }

    @Override
    public void stop() {
        // Nothing to clean up - in-memory database is discarded when JVM exits
    }
}
