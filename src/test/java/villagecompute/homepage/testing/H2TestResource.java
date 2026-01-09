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
        return Map.of("quarkus.datasource.db-kind", "h2", "quarkus.datasource.username", "sa",
                "quarkus.datasource.password", "sa", "quarkus.datasource.jdbc.url", JDBC_URL,
                "quarkus.datasource.jdbc.driver", "org.h2.Driver", "quarkus.datasource.devservices.enabled", "false");
    }

    @Override
    public void stop() {
        // Nothing to clean up - in-memory database is discarded when JVM exits
    }
}
