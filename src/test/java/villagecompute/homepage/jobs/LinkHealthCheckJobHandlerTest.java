package villagecompute.homepage.jobs;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.data.models.DirectorySite;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LinkHealthCheckJobHandler}.
 *
 * <p>
 * Tests link health monitoring, failure tracking, dead link detection, and recovery detection.
 *
 * <p>
 * <b>Note:</b> These tests verify the database state changes and failure counter logic. They do NOT test actual HTTP
 * requests (handler uses real HttpClient for simplicity). In production, use integration tests with WireMock for HTTP
 * mocking.
 */
@QuarkusTest
class LinkHealthCheckJobHandlerTest {

    @Inject
    LinkHealthCheckJobHandler handler;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    EntityManager entityManager;

    private UUID testSiteId1;
    private UUID testSiteId2;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up test data
        DirectorySite.deleteAll();

        // Create test site 1 (healthy site)
        DirectorySite site1 = new DirectorySite();
        site1.url = "https://httpbin.org/status/200"; // Real endpoint that returns 200
        site1.domain = DirectorySite.extractDomain(site1.url);
        site1.title = "Healthy Site";
        site1.description = "Test site that should pass health checks";
        site1.submittedByUserId = UUID.randomUUID();
        site1.status = "approved";
        site1.isDead = false;
        site1.healthCheckFailures = 0;
        site1.lastCheckedAt = null;
        site1.createdAt = Instant.now();
        site1.updatedAt = Instant.now();
        site1.persist();
        testSiteId1 = site1.id;

        // Create test site 2 (site with existing failures)
        DirectorySite site2 = new DirectorySite();
        site2.url = "https://httpbin.org/status/404"; // Real endpoint that returns 404
        site2.domain = DirectorySite.extractDomain(site2.url);
        site2.title = "Failing Site";
        site2.description = "Test site with existing failures";
        site2.submittedByUserId = UUID.randomUUID();
        site2.status = "approved";
        site2.isDead = false;
        site2.healthCheckFailures = 2; // 2 previous failures
        site2.lastCheckedAt = Instant.now().minusSeconds(86400); // Last checked 1 day ago
        site2.createdAt = Instant.now();
        site2.updatedAt = Instant.now();
        site2.persist();
        testSiteId2 = site2.id;
    }

    @Test
    void testHandlesType() {
        // When: Check handler type
        JobType type = handler.handlesType();

        // Then: Should handle LINK_HEALTH_CHECK
        assertEquals(JobType.LINK_HEALTH_CHECK, type);
    }

    @Test
    @Transactional
    void testExecute_healthySite_resetsFailureCounter() throws Exception {
        // Given: Site with healthy URL (httpbin.org/status/200)
        // When: Execute health check job
        Map<String, Object> payload = Map.of();
        handler.execute(1L, payload);

        // Then: Site should have zero failures and updated timestamp
        DirectorySite site = DirectorySite.findById(testSiteId1);
        assertNotNull(site);
        assertEquals(0, site.healthCheckFailures);
        assertNotNull(site.lastCheckedAt);
        assertFalse(site.isDead);
        assertEquals("approved", site.status);
    }

    @Test
    @Transactional
    void testExecute_failingSite_incrementsCounter() throws Exception {
        // Given: Site with failing URL (httpbin.org/status/404) and 2 existing failures
        // When: Execute health check job
        Map<String, Object> payload = Map.of();
        handler.execute(1L, payload);

        // Then: Site should be marked dead (3rd consecutive failure)
        DirectorySite site = DirectorySite.findById(testSiteId2);
        assertNotNull(site);
        assertEquals(3, site.healthCheckFailures);
        assertTrue(site.isDead);
        assertEquals("dead", site.status);
        assertNotNull(site.lastCheckedAt);
    }

    @Test
    @Transactional
    void testFailureThreshold_markDeadAfterThirdFailure() {
        // Given: Site with 2 failures
        DirectorySite site = DirectorySite.findById(testSiteId2);
        assertEquals(2, site.healthCheckFailures);
        assertFalse(site.isDead);

        // When: Simulate 3rd failure manually
        site.healthCheckFailures = 3;
        site.markDead();

        // Then: Site should be marked dead
        DirectorySite updated = DirectorySite.findById(testSiteId2);
        assertTrue(updated.isDead);
        assertEquals("dead", updated.status);
        assertEquals(3, updated.healthCheckFailures);
    }

    @Test
    @Transactional
    void testDeadSiteRecovery() {
        // Given: Site marked as dead
        DirectorySite site = DirectorySite.findById(testSiteId1);
        site.isDead = true;
        site.status = "dead";
        site.healthCheckFailures = 3;
        site.persist();

        // When: Site URL becomes accessible (httpbin.org/status/200 is healthy)
        // Execute health check job
        Map<String, Object> payload = Map.of();
        try {
            handler.execute(1L, payload);
        } catch (Exception e) {
            fail("Job should not throw exception: " + e.getMessage());
        }

        // Then: Failure counter should reset
        DirectorySite recovered = DirectorySite.findById(testSiteId1);
        assertEquals(0, recovered.healthCheckFailures);
        assertNotNull(recovered.lastCheckedAt);

        // Note: Site status remains "dead" - manual moderator action required to restore
        assertTrue(recovered.isDead);
        assertEquals("dead", recovered.status);
    }

    @Test
    @Transactional
    void testBatchProcessing_handlesMultipleSites() throws Exception {
        // Given: Multiple test sites (already created in setUp)
        long beforeCount = DirectorySite.count("status = 'approved'");
        assertTrue(beforeCount >= 2);

        // When: Execute health check job
        Map<String, Object> payload = Map.of();
        handler.execute(1L, payload);

        // Then: All approved sites should have updated lastCheckedAt
        long checkedCount = DirectorySite.count("status = 'approved' AND lastCheckedAt IS NOT NULL");
        assertTrue(checkedCount >= 2);
    }

    @Test
    @Transactional
    void testHealthCheckUpdatesTimestamp() throws Exception {
        // Given: Site with null lastCheckedAt
        DirectorySite site = DirectorySite.findById(testSiteId1);
        assertNull(site.lastCheckedAt);

        // When: Execute health check
        Map<String, Object> payload = Map.of();
        handler.execute(1L, payload);

        // Then: lastCheckedAt should be populated
        DirectorySite updated = DirectorySite.findById(testSiteId1);
        assertNotNull(updated.lastCheckedAt);
        assertTrue(updated.lastCheckedAt.isBefore(Instant.now().plusSeconds(1)));
        assertTrue(updated.lastCheckedAt.isAfter(Instant.now().minusSeconds(60)));
    }

    @Test
    @Transactional
    void testOnlyApprovedSitesChecked() {
        // Given: Create pending site
        DirectorySite pendingSite = new DirectorySite();
        pendingSite.url = "https://httpbin.org/status/200";
        pendingSite.domain = DirectorySite.extractDomain(pendingSite.url);
        pendingSite.title = "Pending Site";
        pendingSite.description = "Should not be checked";
        pendingSite.submittedByUserId = UUID.randomUUID();
        pendingSite.status = "pending"; // Not approved
        pendingSite.isDead = false;
        pendingSite.healthCheckFailures = 0;
        pendingSite.lastCheckedAt = null;
        pendingSite.createdAt = Instant.now();
        pendingSite.updatedAt = Instant.now();
        pendingSite.persist();

        // When: Execute health check
        Map<String, Object> payload = Map.of();
        try {
            handler.execute(1L, payload);
        } catch (Exception e) {
            fail("Job should not throw exception: " + e.getMessage());
        }

        // Then: Pending site should NOT have been checked
        DirectorySite unchecked = DirectorySite.findById(pendingSite.id);
        assertNull(unchecked.lastCheckedAt);
    }

    @Test
    @Transactional
    void testMarkDead_setsAllRequiredFields() {
        // Given: Approved site
        DirectorySite site = DirectorySite.findById(testSiteId1);
        assertFalse(site.isDead);
        assertEquals("approved", site.status);

        // When: Mark dead
        Instant before = Instant.now();
        site.markDead();
        Instant after = Instant.now();

        // Then: All fields updated correctly
        DirectorySite dead = DirectorySite.findById(testSiteId1);
        assertTrue(dead.isDead);
        assertEquals("dead", dead.status);
        assertNotNull(dead.lastCheckedAt);
        assertTrue(dead.lastCheckedAt.isAfter(before.minusSeconds(1)));
        assertTrue(dead.lastCheckedAt.isBefore(after.plusSeconds(1)));
        assertNotNull(dead.updatedAt);
        assertTrue(dead.updatedAt.isAfter(before.minusSeconds(1)));
        assertTrue(dead.updatedAt.isBefore(after.plusSeconds(1)));
    }
}
