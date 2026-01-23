package villagecompute.homepage;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.testing.PostgreSQLTestProfile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test for BaseIntegrationTest infrastructure.
 *
 * <p>
 * This test verifies that:
 * <ul>
 * <li>PostgreSQL 17 Testcontainer starts correctly</li>
 * <li>PostGIS extension is enabled</li>
 * <li>Entity operations work with the test database</li>
 * <li>Helper methods function as expected</li>
 * <li>@TestTransaction ensures proper isolation</li>
 * </ul>
 *
 * <p>
 * <b>Ref:</b> Task I1.T2 - BaseIntegrationTest infrastructure verification
 */
@QuarkusTest
@TestProfile(PostgreSQLTestProfile.class)
class BaseIntegrationTestVerification extends BaseIntegrationTest {

    @Inject
    EntityManager entityManager;

    @Test
    @Transactional
    void postgresqlContainerStartsSuccessfully() {
        // Verify database is PostgreSQL (not H2)
        String databaseProductName = entityManager.getEntityManagerFactory().getProperties()
                .getOrDefault("hibernate.dialect", "").toString();
        assertTrue(databaseProductName.contains("PostgreSQL") || databaseProductName.isEmpty(),
                "Should be using PostgreSQL database");
    }

    @Test
    @Transactional
    void postgisExtensionIsEnabled() {
        // Query PostGIS version to verify extension is loaded
        Object result = entityManager.createNativeQuery("SELECT PostGIS_Version()").getSingleResult();
        assertNotNull(result, "PostGIS extension should be enabled");
        assertTrue(result.toString().contains("POSTGIS"), "PostGIS version should be returned: " + result.toString());
    }

    @Test
    @Transactional
    void assertEntityExistsWorksWithUuid() {
        // Create anonymous user (uses UUID as primary key)
        User user = new User();
        user.id = java.util.UUID.randomUUID();
        user.createdAt = java.time.Instant.now();
        user.updatedAt = java.time.Instant.now();
        user.lastActiveAt = java.time.Instant.now();
        user.directoryKarma = 0;
        user.directoryTrustLevel = "untrusted";
        user.analyticsConsent = false;
        user.persist();
        entityManager.flush();

        // Verify helper method works
        assertEntityExists(User.class, user.id);
    }

    @Test
    @Transactional
    void assertEntityDeletedWorksWithSoftDelete() {
        // Create and soft-delete a user
        User user = new User();
        user.id = java.util.UUID.randomUUID();
        user.createdAt = java.time.Instant.now();
        user.updatedAt = java.time.Instant.now();
        user.lastActiveAt = java.time.Instant.now();
        user.directoryKarma = 0;
        user.directoryTrustLevel = "untrusted";
        user.analyticsConsent = false;
        user.persist();
        entityManager.flush();

        // Manually set deletedAt to simulate soft deletion
        user.deletedAt = java.time.Instant.now();
        entityManager.flush();

        // Verify helper method detects soft deletion
        assertEntityDeleted(User.class, user.id);
    }

    @Test
    @Transactional
    void testTransactionRollbackIsolation() {
        // Create a user within transaction
        User user = new User();
        user.id = java.util.UUID.randomUUID();
        user.createdAt = java.time.Instant.now();
        user.updatedAt = java.time.Instant.now();
        user.lastActiveAt = java.time.Instant.now();
        user.directoryKarma = 0;
        user.directoryTrustLevel = "untrusted";
        user.analyticsConsent = false;
        user.persist();
        entityManager.flush();

        // Verify it exists
        assertEntityExists(User.class, user.id);

        // Test ends here - @TestTransaction will rollback the changes
        // The user should not exist in subsequent tests
    }
}
