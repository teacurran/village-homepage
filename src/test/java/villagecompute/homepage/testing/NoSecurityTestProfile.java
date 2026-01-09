package villagecompute.homepage.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Test profile that disables security for integration tests requiring admin endpoint access without authentication.
 *
 * <p>
 * This profile is used by tests that need to bypass @RolesAllowed restrictions. It disables OIDC authentication and
 * JPA-based security, allowing tests to call secured endpoints directly.
 *
 * <p>
 * Usage:
 *
 * <pre>
 * {@code @QuarkusTest}
 * {@code @TestProfile(NoSecurityTestProfile.class)}
 * class MyAdminResourceTest {
 *     // Tests can now access admin endpoints without authentication
 * }
 * </pre>
 */
public class NoSecurityTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("quarkus.oidc.enabled", "false", "quarkus.security.jpa.enabled", "false",
                "quarkus.security.auth.enabled-in-dev-mode", "false");
    }
}
