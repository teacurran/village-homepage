package villagecompute.homepage.services;

import java.util.List;
import java.util.concurrent.Semaphore;

import org.jboss.logging.Logger;

import com.ruiyun.jvppeteer.api.core.Browser;
import com.ruiyun.jvppeteer.api.core.Page;
import com.ruiyun.jvppeteer.cdp.core.Puppeteer;
import com.ruiyun.jvppeteer.cdp.entities.ImageType;
import com.ruiyun.jvppeteer.cdp.entities.LaunchOptions;
import com.ruiyun.jvppeteer.cdp.entities.ScreenshotOptions;
import com.ruiyun.jvppeteer.cdp.entities.Viewport;
import com.ruiyun.jvppeteer.cdp.entities.WaitForOptions;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Screenshot capture service with browser pool management.
 *
 * <p>
 * Implements Policy P12: Screenshot Queue Concurrency Limits. Uses a semaphore to limit concurrent browser instances to
 * 3 maximum per pod, preventing memory exhaustion.
 * </p>
 *
 * <p>
 * <b>Browser Pool Configuration:</b>
 * <ul>
 * <li>Max concurrent browsers: 3 (enforced via semaphore)</li>
 * <li>Browser launch timeout: 30 seconds</li>
 * <li>Page load timeout: 30 seconds</li>
 * <li>Network idle timeout: 10 seconds (best-effort)</li>
 * <li>Memory per browser: ~200-300 MB</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>Performance Characteristics:</b>
 * <ul>
 * <li>Browser launch: ~2-3 seconds</li>
 * <li>Page load: 5-15 seconds (depends on site complexity)</li>
 * <li>Screenshot capture: <1 second</li>
 * <li>Total duration: 10-20 seconds per capture</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>Error Scenarios:</b>
 * <ul>
 * <li>TimeoutException: Page load exceeds 30 seconds</li>
 * <li>NetworkException: DNS failure, connection refused</li>
 * <li>SSLException: Invalid certificate</li>
 * <li>RuntimeException: Browser launch failure, JavaScript errors</li>
 * </ul>
 * </p>
 */
@ApplicationScoped
public class ScreenshotService {

    private static final Logger LOG = Logger.getLogger(ScreenshotService.class);

    /**
     * Policy P12: Max 3 concurrent screenshot captures per pod.
     *
     * <p>
     * This semaphore prevents memory exhaustion by limiting browser instances. Each browser consumes ~200-300 MB.
     * </p>
     */
    private final Semaphore browserSemaphore = new Semaphore(3);

    /**
     * Captures screenshot of URL with browser pooling and semaphore-limited concurrency.
     *
     * @param url
     *            Target URL to capture
     * @param viewportWidth
     *            Viewport width in pixels
     * @param viewportHeight
     *            Viewport height in pixels
     * @return Screenshot bytes (PNG format)
     * @throws ScreenshotCaptureException
     *             on timeout, network error, or other capture failure
     * @throws InterruptedException
     *             if thread is interrupted while waiting for semaphore
     */
    public byte[] captureScreenshot(String url, int viewportWidth, int viewportHeight) throws InterruptedException {

        // Acquire semaphore slot (blocks if 3 browsers already active)
        boolean acquired = browserSemaphore.tryAcquire();
        if (!acquired) {
            LOG.warnf("Browser pool exhausted, waiting for slot (url=%s)", url);
            browserSemaphore.acquire(); // Block until slot available
        }

        Browser browser = null;
        try {
            LOG.infof("Launching browser for screenshot (url=%s, viewport=%dx%d)", url, viewportWidth, viewportHeight);

            // Launch headless Chromium with memory-optimized flags
            LaunchOptions launchOptions = LaunchOptions.builder().headless(true).timeout(30000)
                    .args(List.of("--no-sandbox", // Required for containerized environments
                            "--disable-setuid-sandbox", // Required for non-root users
                            "--disable-dev-shm-usage", // Reduce memory usage in Docker
                            "--disable-gpu", // Not needed for headless
                            "--no-first-run", // Skip first-run wizard
                            "--no-default-browser-check" // Skip default browser check
                    )).build();

            browser = Puppeteer.launch(launchOptions);
            Page page = browser.newPage();

            // Set viewport size for consistent screenshots
            Viewport viewport = new Viewport();
            viewport.setWidth(viewportWidth);
            viewport.setHeight(viewportHeight);
            page.setViewport(viewport);

            // Navigate to URL with 30-second timeout
            page.goTo(url);

            // Wait for network idle (or timeout after 10 seconds)
            // This ensures dynamic content (JS-rendered) is loaded
            try {
                WaitForOptions waitOptions = new WaitForOptions();
                waitOptions.setTimeout(10000);
                page.waitForNavigation(waitOptions);
            } catch (Exception e) {
                // Network idle timeout is best-effort, capture anyway
                LOG.warnf("Network idle timeout for %s, capturing current state", url);
            }

            // Capture screenshot (PNG format)
            ScreenshotOptions screenshotOptions = new ScreenshotOptions();
            screenshotOptions.setFullPage(false); // Capture viewport only (not entire scrollable page)
            screenshotOptions.setType(ImageType.PNG); // PNG format (will be converted to WebP by StorageGateway)

            String screenshotBase64 = page.screenshot(screenshotOptions);
            byte[] screenshotBytes = java.util.Base64.getDecoder().decode(screenshotBase64);

            LOG.infof("Screenshot captured successfully: %d bytes (url=%s)", screenshotBytes.length, url);

            page.close();
            return screenshotBytes;

        } catch (Exception e) {
            // Wrap all exceptions in ScreenshotCaptureException for consistent error handling
            String errorType = e.getClass().getSimpleName();
            if (e.getMessage() != null && e.getMessage().contains("timeout")) {
                throw new ScreenshotCaptureException("Page load timeout: " + url, e);
            } else if (errorType.contains("Network") || errorType.contains("Connection")) {
                throw new ScreenshotCaptureException("Network error: " + url, e);
            } else if (errorType.contains("SSL") || errorType.contains("Certificate")) {
                throw new ScreenshotCaptureException("SSL error: " + url, e);
            } else {
                throw new ScreenshotCaptureException("Screenshot capture failed: " + url, e);
            }
        } finally {
            // Critical: Always close browser and release semaphore, even on exception
            if (browser != null) {
                try {
                    browser.close();
                } catch (Exception e) {
                    LOG.errorf(e, "Failed to close browser (url=%s)", url);
                }
            }
            browserSemaphore.release();
            LOG.debugf("Browser semaphore released (available=%d)", browserSemaphore.availablePermits());
        }
    }

    /**
     * Gets the number of available browser slots.
     *
     * <p>
     * Used for monitoring and debugging. Should return 3 when idle.
     * </p>
     *
     * @return Number of available semaphore permits
     */
    public int getAvailableBrowserSlots() {
        return browserSemaphore.availablePermits();
    }

    /**
     * Custom exception for screenshot capture failures.
     *
     * <p>
     * Wraps underlying exceptions (timeout, network, SSL, etc.) for consistent error handling in job handlers.
     * </p>
     */
    public static class ScreenshotCaptureException extends RuntimeException {

        public ScreenshotCaptureException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
