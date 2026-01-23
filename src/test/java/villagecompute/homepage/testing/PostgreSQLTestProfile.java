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
    public boolean disableGlobalTestResources() {
        // CRITICAL: Disable H2TestResource and other global test resources
        // This ensures PostgreSQL Testcontainers is used instead of H2
        return true;
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        // Override datasource configuration to use PostgreSQL 17 + PostGIS with dev services
        // CRITICAL: These settings override H2TestResource AND environment variables to force PostgreSQL Testcontainers
        // NOTE: We DON'T set jdbc.url, username, or password - Dev Services will auto-configure Testcontainers
        // NOTE: No init script needed - PostGIS extensions are pre-installed in postgis/postgis image
        return new java.util.HashMap<>(Map.ofEntries(Map.entry("quarkus.datasource.db-kind", "postgresql"),
                Map.entry("quarkus.datasource.jdbc.driver", "org.postgresql.Driver"),
                Map.entry("quarkus.datasource.devservices.enabled", "true"),
                Map.entry("quarkus.datasource.devservices.image-name", "postgis/postgis:17-3.5-alpine"),
                Map.entry("quarkus.hibernate-orm.database.generation", "drop-and-create"),
                Map.entry("quarkus.hibernate-orm.dialect", "org.hibernate.dialect.PostgreSQLDialect"),
                Map.entry("quarkus.hibernate-orm.log.sql", "true"),
                Map.entry("quarkus.hibernate-orm.sql-load-script", "no-file"),
                // CRITICAL: Enable Jackson JSON-B handling for JSONB columns (fixes Map serialization)
                Map.entry("quarkus.hibernate-orm.mapping.jackson.convert-to-json-mode", "enabled"),
                // LangChain4j test configuration (Task I4.T1, I4.T5)
                // Use Anthropic for chat, OpenAI for embeddings (Anthropic doesn't provide embedding models)
                Map.entry("quarkus.langchain4j.chat-model.provider", "anthropic"),
                Map.entry("quarkus.langchain4j.embedding-model.provider", "openai"),
                Map.entry("quarkus.langchain4j.anthropic.api-key", "sk-ant-test-fake-key-for-testing-12345678"),
                Map.entry("quarkus.langchain4j.anthropic.chat-model.model-name", "claude-3-5-sonnet-20241022"),
                Map.entry("quarkus.langchain4j.openai.api-key", "sk-proj-test-fake-key-00000000000000000000"),
                Map.entry("quarkus.langchain4j.openai.embedding-model.model-name", "text-embedding-3-small")));
    }

    @Override
    public String getConfigProfile() {
        return "test";
    }
}
