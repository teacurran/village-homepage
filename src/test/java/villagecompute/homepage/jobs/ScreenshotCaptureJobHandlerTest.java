package villagecompute.homepage.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import villagecompute.homepage.api.types.StorageUploadResultType;
import villagecompute.homepage.data.models.DirectoryCategory;
import villagecompute.homepage.data.models.DirectoryScreenshotVersion;
import villagecompute.homepage.data.models.DirectorySite;
import villagecompute.homepage.data.models.DirectorySiteCategory;
import villagecompute.homepage.services.ScreenshotService;
import villagecompute.homepage.services.ScreenshotService.ScreenshotCaptureException;
import villagecompute.homepage.services.StorageGateway;

/**
 * Unit tests for {@link ScreenshotCaptureJobHandler}.
 *
 * <p>
 * Tests job execution, version history creation, failure handling, and metrics emission.
 */
@QuarkusTest
class ScreenshotCaptureJobHandlerTest {

    @Inject
    ScreenshotCaptureJobHandler handler;

    @InjectMock
    ScreenshotService screenshotService;

    @InjectMock
    StorageGateway storageGateway;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    EntityManager entityManager;

    private UUID testSiteId;
    private UUID testCategoryId;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up test data
        DirectoryScreenshotVersion.deleteAll();
        DirectorySiteCategory.deleteAll();
        DirectorySite.deleteAll();
        DirectoryCategory.deleteAll();

        // Create test category
        DirectoryCategory category = new DirectoryCategory();
        category.slug = "test-category";
        category.name = "Test Category";
        category.description = "Test category";
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
        site.description = "Example site";
        site.submittedByUserId = UUID.randomUUID();
        site.status = "active";
        site.ogImageUrl = "https://example.com/og-image.jpg";
        site.screenshotUrl = null;
        site.screenshotCapturedAt = null;
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
    void testHandlesType() {
        // When: Check handler type
        JobType type = handler.handlesType();

        // Then: Should handle SCREENSHOT_CAPTURE
        assertEquals(JobType.SCREENSHOT_CAPTURE, type);
    }

    @Test
    @Transactional
    void testExecute_success() throws Exception {
        // Given: Mock screenshot service to return fake screenshot bytes
        byte[] fakeFullScreenshot = "full-screenshot-bytes".getBytes();
        byte[] fakeThumbnailScreenshot = "thumbnail-screenshot-bytes".getBytes();

        when(screenshotService.captureScreenshot(eq("https://example.com"), eq(1280), eq(800)))
                .thenReturn(fakeFullScreenshot);
        when(screenshotService.captureScreenshot(eq("https://example.com"), eq(320), eq(200)))
                .thenReturn(fakeThumbnailScreenshot);

        // Mock storage uploads
        when(storageGateway.upload(eq(StorageGateway.BucketType.SCREENSHOTS), anyString(), eq("full"),
                eq(fakeFullScreenshot), anyString()))
                .thenReturn(new StorageUploadResultType(testSiteId + "/v1/full.webp", "screenshots",
                        (long) fakeFullScreenshot.length, "image/png", "full", "1", Instant.now().toString()));

        when(storageGateway.upload(eq(StorageGateway.BucketType.SCREENSHOTS), anyString(), eq("thumbnail"),
                eq(fakeThumbnailScreenshot), anyString()))
                .thenReturn(new StorageUploadResultType(testSiteId + "/v1/thumbnail.webp", "screenshots",
                        (long) fakeThumbnailScreenshot.length, "image/png", "thumbnail", "1",
                        Instant.now().toString()));

        // When: Execute job
        Map<String, Object> payload = Map.of("siteId", testSiteId.toString(), "url", "https://example.com",
                "isRecapture", false);

        handler.execute(1L, payload);

        // Then: Verify screenshots were captured
        verify(screenshotService, times(1)).captureScreenshot("https://example.com", 1280, 800);
        verify(screenshotService, times(1)).captureScreenshot("https://example.com", 320, 200);

        // Verify uploads
        verify(storageGateway, times(1)).upload(eq(StorageGateway.BucketType.SCREENSHOTS), anyString(), eq("full"),
                eq(fakeFullScreenshot), anyString());
        verify(storageGateway, times(1)).upload(eq(StorageGateway.BucketType.SCREENSHOTS), anyString(), eq("thumbnail"),
                eq(fakeThumbnailScreenshot), anyString());

        // Verify version record created
        List<DirectoryScreenshotVersion> versions = DirectoryScreenshotVersion.findBySiteId(testSiteId);
        assertEquals(1, versions.size());

        DirectoryScreenshotVersion version = versions.get(0);
        assertEquals(1, version.version);
        assertEquals("success", version.status);
        assertEquals(testSiteId + "/v1/full.webp", version.fullStorageKey);
        assertEquals(testSiteId + "/v1/thumbnail.webp", version.thumbnailStorageKey);
        assertNotNull(version.captureDurationMs);
        assertNull(version.errorMessage);

        // Verify site updated
        DirectorySite site = DirectorySite.findById(testSiteId);
        assertEquals(testSiteId + "/v1/full.webp", site.screenshotUrl);
        assertNotNull(site.screenshotCapturedAt);
    }

    @Test
    @Transactional
    void testExecute_timeout() throws Exception {
        // Given: Mock screenshot service to throw timeout exception
        when(screenshotService.captureScreenshot(anyString(), anyInt(), anyInt()))
                .thenThrow(new ScreenshotCaptureException("Page load timeout: https://slow.com",
                        new RuntimeException("timeout")));

        // When: Execute job (expect exception)
        Map<String, Object> payload = Map.of("siteId", testSiteId.toString(), "url", "https://slow.com", "isRecapture",
                false);

        assertThrows(ScreenshotCaptureException.class, () -> {
            handler.execute(1L, payload);
        });

        // Then: Verify failed version record created
        List<DirectoryScreenshotVersion> versions = DirectoryScreenshotVersion.findBySiteId(testSiteId);
        assertEquals(1, versions.size());

        DirectoryScreenshotVersion version = versions.get(0);
        assertEquals(1, version.version);
        assertEquals("timeout", version.status);
        assertEquals("", version.fullStorageKey);
        assertEquals("", version.thumbnailStorageKey);
        assertNotNull(version.errorMessage);
        assertTrue(version.errorMessage.contains("timeout"));

        // Verify site NOT updated (keeps og_image_url as fallback)
        DirectorySite site = DirectorySite.findById(testSiteId);
        assertNull(site.screenshotUrl);
        assertNull(site.screenshotCapturedAt);
        assertEquals("https://example.com/og-image.jpg", site.ogImageUrl);
    }

    @Test
    @Transactional
    void testExecute_networkError() throws Exception {
        // Given: Mock screenshot service to throw network exception
        when(screenshotService.captureScreenshot(anyString(), anyInt(), anyInt())).thenThrow(
                new ScreenshotCaptureException("Network error: https://offline.com", new RuntimeException("DNS fail")));

        // When: Execute job (expect exception)
        Map<String, Object> payload = Map.of("siteId", testSiteId.toString(), "url", "https://offline.com",
                "isRecapture", false);

        assertThrows(ScreenshotCaptureException.class, () -> {
            handler.execute(1L, payload);
        });

        // Then: Verify failed version record created
        DirectoryScreenshotVersion version = DirectoryScreenshotVersion.findBySiteId(testSiteId).get(0);
        assertEquals("failed", version.status);
        assertTrue(version.errorMessage.contains("Network error"));
    }

    @Test
    @Transactional
    void testExecute_recapture_versionIncrements() throws Exception {
        // Given: Create initial version
        DirectoryScreenshotVersion v1 = new DirectoryScreenshotVersion();
        v1.siteId = testSiteId;
        v1.version = 1;
        v1.thumbnailStorageKey = testSiteId + "/v1/thumbnail.webp";
        v1.fullStorageKey = testSiteId + "/v1/full.webp";
        v1.capturedAt = Instant.now();
        v1.captureDurationMs = 10000;
        v1.status = "success";
        v1.createdAt = Instant.now();
        v1.persist();

        // Mock recapture
        byte[] fakeScreenshot = "recapture-bytes".getBytes();
        when(screenshotService.captureScreenshot(anyString(), anyInt(), anyInt())).thenReturn(fakeScreenshot);

        when(storageGateway.upload(any(), anyString(), anyString(), any(), anyString()))
                .thenReturn(new StorageUploadResultType(testSiteId + "/v2/full.webp", "screenshots",
                        (long) fakeScreenshot.length, "image/png", "full", "2", Instant.now().toString()));

        // When: Execute recapture job
        Map<String, Object> payload = Map.of("siteId", testSiteId.toString(), "url", "https://example.com",
                "isRecapture", true);

        handler.execute(2L, payload);

        // Then: Version should increment to 2
        List<DirectoryScreenshotVersion> versions = DirectoryScreenshotVersion.findBySiteId(testSiteId);
        assertEquals(2, versions.size());

        DirectoryScreenshotVersion v2 = versions.get(0); // Newest first
        assertEquals(2, v2.version);
        assertEquals("success", v2.status);

        // Old version should still exist
        DirectoryScreenshotVersion oldVersion = versions.get(1);
        assertEquals(1, oldVersion.version);
    }

    @Test
    @Transactional
    void testExecute_updatesSiteFields() throws Exception {
        // Given: Mock successful capture
        byte[] fakeScreenshot = "screenshot-bytes".getBytes();
        when(screenshotService.captureScreenshot(anyString(), anyInt(), anyInt())).thenReturn(fakeScreenshot);

        when(storageGateway.upload(any(), anyString(), anyString(), any(), anyString()))
                .thenReturn(new StorageUploadResultType(testSiteId + "/v1/full.webp", "screenshots",
                        (long) fakeScreenshot.length, "image/png", "full", "1", Instant.now().toString()));

        // When: Execute job
        Map<String, Object> payload = Map.of("siteId", testSiteId.toString(), "url", "https://example.com",
                "isRecapture", false);

        handler.execute(1L, payload);

        // Then: Site fields should be updated
        DirectorySite site = DirectorySite.findById(testSiteId);
        assertNotNull(site.screenshotUrl);
        assertTrue(site.screenshotUrl.contains("/v1/full.webp"));
        assertNotNull(site.screenshotCapturedAt);
        assertNotNull(site.updatedAt);
    }

    @Test
    @Transactional
    void testExecute_truncatesLongErrorMessage() throws Exception {
        // Given: Mock failure with very long error message
        String longMessage = "Error: " + "x".repeat(600); // 606 characters
        when(screenshotService.captureScreenshot(anyString(), anyInt(), anyInt()))
                .thenThrow(new ScreenshotCaptureException(longMessage, new RuntimeException()));

        // When: Execute job
        Map<String, Object> payload = Map.of("siteId", testSiteId.toString(), "url", "https://error.com", "isRecapture",
                false);

        assertThrows(ScreenshotCaptureException.class, () -> {
            handler.execute(1L, payload);
        });

        // Then: Error message should be truncated to 500 characters
        DirectoryScreenshotVersion version = DirectoryScreenshotVersion.findBySiteId(testSiteId).get(0);
        assertNotNull(version.errorMessage);
        assertTrue(version.errorMessage.length() <= 500);
        assertTrue(version.errorMessage.endsWith("..."));
    }
}
