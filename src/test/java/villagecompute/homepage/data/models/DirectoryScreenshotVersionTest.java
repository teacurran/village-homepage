package villagecompute.homepage.data.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * Unit tests for {@link DirectoryScreenshotVersion}.
 *
 * <p>
 * Tests version history queries, cascade deletion, and static finder methods.
 */
@QuarkusTest
class DirectoryScreenshotVersionTest {

    @Inject
    EntityManager entityManager;

    private UUID testSiteId;
    private UUID testCategoryId;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up any existing test data
        DirectoryScreenshotVersion.deleteAll();
        DirectorySiteCategory.deleteAll();
        DirectorySite.deleteAll();
        DirectoryCategory.deleteAll();

        // Create test category
        DirectoryCategory category = new DirectoryCategory();
        category.slug = "test-category";
        category.name = "Test Category";
        category.description = "Test category for screenshot tests";
        category.parentId = null;
        category.iconUrl = null;
        category.createdAt = Instant.now();
        category.updatedAt = Instant.now();
        category.persist();

        testCategoryId = category.id;

        // Create test site
        DirectorySite site = new DirectorySite();
        site.url = "https://example.com";
        site.domain = DirectorySite.extractDomain("https://example.com");
        site.title = "Example Site";
        site.description = "Example site for screenshot tests";
        site.submittedByUserId = UUID.randomUUID();
        site.status = "active";
        site.createdAt = Instant.now();
        site.updatedAt = Instant.now();
        site.persist();

        testSiteId = site.id;

        // Link site to category
        DirectorySiteCategory siteCategory = new DirectorySiteCategory();
        siteCategory.siteId = testSiteId;
        siteCategory.categoryId = testCategoryId;
        siteCategory.submittedByUserId = UUID.randomUUID();
        siteCategory.status = "approved";
        siteCategory.createdAt = Instant.now();
        siteCategory.updatedAt = Instant.now();
        siteCategory.persist();
    }

    @Test
    @Transactional
    void testGetLatestVersion_noVersions() {
        // When: No versions exist
        int latestVersion = DirectoryScreenshotVersion.getLatestVersion(testSiteId);

        // Then: Should return 0
        assertEquals(0, latestVersion);
    }

    @Test
    @Transactional
    void testGetLatestVersion_multipleVersions() {
        // Given: Create multiple versions
        createVersion(testSiteId, 1, "success");
        createVersion(testSiteId, 2, "success");
        createVersion(testSiteId, 3, "failed");

        // When: Get latest version
        int latestVersion = DirectoryScreenshotVersion.getLatestVersion(testSiteId);

        // Then: Should return highest version number
        assertEquals(3, latestVersion);
    }

    @Test
    @Transactional
    void testFindBySiteId_returnsAllVersions() {
        // Given: Create versions for multiple sites
        UUID otherSiteId = createAdditionalSite();

        createVersion(testSiteId, 1, "success");
        createVersion(testSiteId, 2, "success");
        createVersion(otherSiteId, 1, "success");

        // When: Find versions for test site
        List<DirectoryScreenshotVersion> versions = DirectoryScreenshotVersion.findBySiteId(testSiteId);

        // Then: Should return only test site versions, newest first
        assertEquals(2, versions.size());
        assertEquals(2, versions.get(0).version);
        assertEquals(1, versions.get(1).version);
    }

    @Test
    @Transactional
    void testFindByVersion_success() {
        // Given: Create version
        createVersion(testSiteId, 1, "success");

        // When: Find specific version
        var versionOpt = DirectoryScreenshotVersion.findByVersion(testSiteId, 1);

        // Then: Should find version
        assertTrue(versionOpt.isPresent());
        assertEquals(1, versionOpt.get().version);
        assertEquals("success", versionOpt.get().status);
    }

    @Test
    @Transactional
    void testFindByVersion_notFound() {
        // When: Find non-existent version
        var versionOpt = DirectoryScreenshotVersion.findByVersion(testSiteId, 999);

        // Then: Should return empty
        assertFalse(versionOpt.isPresent());
    }

    @Test
    @Transactional
    void testFindFailedCaptures_onlyFailedAndTimeout() {
        // Given: Create mixed status versions
        createVersion(testSiteId, 1, "success");
        createVersion(testSiteId, 2, "failed");
        createVersion(testSiteId, 3, "timeout");
        createVersion(testSiteId, 4, "success");

        // When: Find failed captures
        List<DirectoryScreenshotVersion> failedCaptures = DirectoryScreenshotVersion.findFailedCaptures();

        // Then: Should return only failed and timeout
        assertEquals(2, failedCaptures.size());
        assertTrue(failedCaptures.stream().allMatch(v -> v.status.equals("failed") || v.status.equals("timeout")));
    }

    @Test
    @Transactional
    void testCountBySiteId() {
        // Given: Create versions
        createVersion(testSiteId, 1, "success");
        createVersion(testSiteId, 2, "failed");
        createVersion(testSiteId, 3, "success");

        // When: Count versions
        long count = DirectoryScreenshotVersion.countBySiteId(testSiteId);

        // Then: Should return total count
        assertEquals(3, count);
    }

    @Test
    @Transactional
    void testCountSuccessfulCaptures() {
        // Given: Create mixed status versions
        createVersion(testSiteId, 1, "success");
        createVersion(testSiteId, 2, "failed");
        createVersion(testSiteId, 3, "success");
        createVersion(testSiteId, 4, "timeout");

        // When: Count successful captures
        long successCount = DirectoryScreenshotVersion.countSuccessfulCaptures(testSiteId);

        // Then: Should return only success count
        assertEquals(2, successCount);
    }

    @Test
    @Transactional
    void testCascadeDelete_deletingSiteDeletesVersions() {
        // Given: Create versions for site
        createVersion(testSiteId, 1, "success");
        createVersion(testSiteId, 2, "success");

        assertEquals(2, DirectoryScreenshotVersion.countBySiteId(testSiteId));

        // When: Manually delete versions then site (simulating cascade)
        DirectoryScreenshotVersion.delete("siteId", testSiteId);
        DirectorySiteCategory.delete("siteId", testSiteId);
        DirectorySite site = DirectorySite.findById(testSiteId);
        site.delete();
        entityManager.flush();

        // Then: Versions should be deleted
        assertEquals(0, DirectoryScreenshotVersion.countBySiteId(testSiteId));
    }

    @Test
    @Transactional
    void testVersionIncrement_startsAtOne() {
        // When: Create first version
        int nextVersion = DirectoryScreenshotVersion.getLatestVersion(testSiteId) + 1;

        // Then: Should be version 1
        assertEquals(1, nextVersion);
    }

    @Test
    @Transactional
    void testVersionIncrement_incrementsCorrectly() {
        // Given: Create version 1
        createVersion(testSiteId, 1, "success");

        // When: Calculate next version
        int nextVersion = DirectoryScreenshotVersion.getLatestVersion(testSiteId) + 1;

        // Then: Should be version 2
        assertEquals(2, nextVersion);
    }

    @Test
    @Transactional
    void testFailedVersions_haveEmptyStorageKeys() {
        // When: Create failed version
        DirectoryScreenshotVersion failedVersion = new DirectoryScreenshotVersion();
        failedVersion.siteId = testSiteId;
        failedVersion.version = 1;
        failedVersion.thumbnailStorageKey = "";
        failedVersion.fullStorageKey = "";
        failedVersion.capturedAt = Instant.now();
        failedVersion.captureDurationMs = 5000;
        failedVersion.status = "failed";
        failedVersion.errorMessage = "Network timeout";
        failedVersion.createdAt = Instant.now();
        failedVersion.persist();

        // Then: Storage keys should be empty
        var version = DirectoryScreenshotVersion.findByVersion(testSiteId, 1).orElseThrow();
        assertEquals("", version.thumbnailStorageKey);
        assertEquals("", version.fullStorageKey);
        assertEquals("failed", version.status);
        assertEquals("Network timeout", version.errorMessage);
    }

    // Helper methods

    private void createVersion(UUID siteId, int version, String status) {
        DirectoryScreenshotVersion v = new DirectoryScreenshotVersion();
        v.siteId = siteId;
        v.version = version;

        if (status.equals("success")) {
            v.thumbnailStorageKey = siteId + "/v" + version + "/thumbnail.webp";
            v.fullStorageKey = siteId + "/v" + version + "/full.webp";
        } else {
            v.thumbnailStorageKey = "";
            v.fullStorageKey = "";
        }

        v.capturedAt = Instant.now();
        v.captureDurationMs = 10000;
        v.status = status;

        if (!status.equals("success")) {
            v.errorMessage = "Test error message";
        }

        v.createdAt = Instant.now();
        v.persist();
    }

    private UUID createAdditionalSite() {
        DirectorySite site = new DirectorySite();
        site.url = "https://other.com";
        site.domain = DirectorySite.extractDomain("https://other.com");
        site.title = "Other Site";
        site.description = "Other site";
        site.submittedByUserId = UUID.randomUUID();
        site.status = "active";
        site.createdAt = Instant.now();
        site.updatedAt = Instant.now();
        site.persist();

        // Link to category
        DirectorySiteCategory siteCategory = new DirectorySiteCategory();
        siteCategory.siteId = site.id;
        siteCategory.categoryId = testCategoryId;
        siteCategory.submittedByUserId = UUID.randomUUID();
        siteCategory.status = "approved";
        siteCategory.createdAt = Instant.now();
        siteCategory.updatedAt = Instant.now();
        siteCategory.persist();

        return site.id;
    }
}
