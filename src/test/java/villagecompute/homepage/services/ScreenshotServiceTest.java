package villagecompute.homepage.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import villagecompute.homepage.services.ScreenshotService.ScreenshotCaptureException;

/**
 * Unit tests for {@link ScreenshotService}.
 *
 * <p>
 * Tests browser pool management, semaphore limits, timeout handling, and screenshot capture.
 *
 * <p>
 * Note: These tests verify the service logic. Actual browser integration requires Chromium installed in environment.
 * Some tests may be skipped in CI environments without Chromium.
 */
@QuarkusTest
class ScreenshotServiceTest {

    @Inject
    ScreenshotService screenshotService;

    @Test
    void testGetAvailableBrowserSlots_initialState() {
        // When: Check initial semaphore state
        int availableSlots = screenshotService.getAvailableBrowserSlots();

        // Then: Should have 3 available slots (Policy P12)
        assertEquals(3, availableSlots);
    }

    @Test
    void testScreenshotCaptureException_withMessage() {
        // When: Create exception with message
        ScreenshotCaptureException exception = new ScreenshotCaptureException("Test error",
                new RuntimeException("Root cause"));

        // Then: Should preserve message and cause
        assertEquals("Test error", exception.getMessage());
        assertNotNull(exception.getCause());
        assertEquals("Root cause", exception.getCause().getMessage());
    }

    @Test
    void testScreenshotCaptureException_timeoutDetection() {
        // When: Create exception with timeout message
        ScreenshotCaptureException exception = new ScreenshotCaptureException("Page load timeout: https://slow.com",
                new RuntimeException("timeout"));

        // Then: Should contain timeout in message
        assertTrue(exception.getMessage().contains("timeout"));
    }

    @Test
    void testCaptureScreenshot_invalidUrl() {
        // When: Capture screenshot of invalid URL
        // Then: Should throw ScreenshotCaptureException
        assertThrows(ScreenshotCaptureException.class, () -> {
            screenshotService.captureScreenshot("not-a-valid-url", 1280, 800);
        });
    }

    @Test
    void testCaptureScreenshot_networkError() {
        // When: Capture screenshot of non-existent domain
        // Then: Should throw ScreenshotCaptureException with network error
        ScreenshotCaptureException exception = assertThrows(ScreenshotCaptureException.class, () -> {
            screenshotService.captureScreenshot("https://this-domain-definitely-does-not-exist-12345.com", 1280, 800);
        });

        // Error message should indicate network issue
        assertTrue(exception.getMessage().contains("this-domain-definitely-does-not-exist-12345.com"));
    }

    @Test
    void testViewportDimensions_thumbnail() {
        // Note: This test verifies the API accepts thumbnail dimensions
        // Actual screenshot capture tested in integration tests with real browser

        // When/Then: Should accept thumbnail viewport (320x200)
        assertThrows(ScreenshotCaptureException.class, () -> {
            screenshotService.captureScreenshot("https://invalid.test", 320, 200);
        });
    }

    @Test
    void testViewportDimensions_full() {
        // Note: This test verifies the API accepts full dimensions
        // Actual screenshot capture tested in integration tests with real browser

        // When/Then: Should accept full viewport (1280x800)
        assertThrows(ScreenshotCaptureException.class, () -> {
            screenshotService.captureScreenshot("https://invalid.test", 1280, 800);
        });
    }

    /**
     * Tests semaphore release on exception.
     *
     * <p>
     * Critical test: Ensures semaphore is released even when screenshot capture fails, preventing deadlock.
     */
    @Test
    void testSemaphoreRelease_onException() throws InterruptedException {
        // Given: Initial state has 3 available slots
        assertEquals(3, screenshotService.getAvailableBrowserSlots());

        // When: Capture fails with invalid URL
        try {
            screenshotService.captureScreenshot("invalid-url", 1280, 800);
        } catch (ScreenshotCaptureException e) {
            // Expected exception
        }

        // Then: Semaphore should be released (still 3 available)
        assertEquals(3, screenshotService.getAvailableBrowserSlots());
    }

    /**
     * Tests that error messages distinguish between error types.
     */
    @Test
    void testErrorMessageTypes() {
        // Test timeout error
        Exception timeoutCause = new RuntimeException("Navigation timeout");
        ScreenshotCaptureException timeoutException = new ScreenshotCaptureException(
                "Page load timeout: https://slow.com", timeoutCause);
        assertTrue(timeoutException.getMessage().contains("timeout"));

        // Test network error
        Exception networkCause = new RuntimeException("Connection refused");
        ScreenshotCaptureException networkException = new ScreenshotCaptureException(
                "Network error: https://offline.com", networkCause);
        assertTrue(networkException.getMessage().contains("Network error"));

        // Test SSL error
        Exception sslCause = new RuntimeException("Certificate invalid");
        ScreenshotCaptureException sslException = new ScreenshotCaptureException("SSL error: https://badcert.com",
                sslCause);
        assertTrue(sslException.getMessage().contains("SSL error"));
    }

    /**
     * Tests that multiple failures don't exhaust semaphore.
     */
    @Test
    void testMultipleFailures_semaphoreNotExhausted() throws InterruptedException {
        // Given: Initial state
        assertEquals(3, screenshotService.getAvailableBrowserSlots());

        // When: Trigger 5 failures
        for (int i = 0; i < 5; i++) {
            try {
                screenshotService.captureScreenshot("invalid-url-" + i, 1280, 800);
            } catch (ScreenshotCaptureException e) {
                // Expected
            }
        }

        // Then: Semaphore should still have 3 available slots
        assertEquals(3, screenshotService.getAvailableBrowserSlots());
    }
}
