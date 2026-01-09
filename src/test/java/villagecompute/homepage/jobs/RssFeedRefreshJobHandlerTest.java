package villagecompute.homepage.jobs;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.data.models.FeedItem;
import villagecompute.homepage.data.models.RssSource;
import villagecompute.homepage.testing.H2TestResource;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RssFeedRefreshJobHandler.
 *
 * <p>
 * Basic smoke tests to verify handler compiles and basic functionality works. Full integration tests would require
 * WireMock or similar mocking for HTTP feeds.
 *
 * <p>
 * Acceptance criteria for I3.T2: "Job schedules per feed config, dedupe works, metrics exported, tests cover error
 * cases, documentation updated."
 *
 * <p>
 * Coverage target: ≥80% line and branch coverage.
 */
@QuarkusTest
@QuarkusTestResource(H2TestResource.class)
class RssFeedRefreshJobHandlerTest {

    @Inject
    RssFeedRefreshJobHandler handler;

    private RssSource testSource;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up any existing test data
        FeedItem.deleteAll();
        RssSource.deleteAll();

        // Create test RSS source
        testSource = new RssSource();
        testSource.name = "Test Feed";
        testSource.url = "https://example.com/feed.xml";
        testSource.category = "Technology";
        testSource.isSystem = true;
        testSource.userId = null;
        testSource.refreshIntervalMinutes = 15;
        testSource.isActive = true;
        testSource.errorCount = 0;
        testSource.lastErrorMessage = null;
        testSource.lastFetchedAt = null;
        testSource = RssSource.create(testSource);
    }

    @Test
    void testHandlesType_ReturnsRssFeedRefresh() {
        // Verify handler returns correct job type
        assertEquals(JobType.RSS_FEED_REFRESH, handler.handlesType());
    }

    @Test
    @Transactional
    void testRssSource_Creation() {
        // Verify RSS source was created correctly
        assertNotNull(testSource.id);
        assertEquals("Test Feed", testSource.name);
        assertEquals(15, testSource.refreshIntervalMinutes);
        assertTrue(testSource.isActive);
        assertEquals(0, testSource.errorCount);
    }

    @Test
    @Transactional
    void testFeedItem_Creation() {
        // Create feed item
        FeedItem item = new FeedItem();
        item.sourceId = testSource.id;
        item.title = "Test Article";
        item.url = "https://example.com/article";
        item.itemGuid = "test-guid";
        item.publishedAt = Instant.now();
        item.aiTagged = false;
        FeedItem.create(item);

        // Verify item was persisted correctly
        FeedItem reloaded = FeedItem.findById(item.id);
        assertNotNull(reloaded);
        assertEquals("Test Article", reloaded.title);
        assertFalse(reloaded.aiTagged, "New items should have ai_tagged = false for I3.T3 pipeline");
    }

    @Test
    @Transactional
    void testExecute_WithInvalidSourceId_HandlesGracefully() throws Exception {
        // Test with non-existent source ID
        Map<String, Object> payload = new HashMap<>();
        payload.put("source_id", UUID.randomUUID().toString());

        // Should not throw exception, just log warning and return
        assertDoesNotThrow(() -> handler.execute(1L, payload));
    }
}
