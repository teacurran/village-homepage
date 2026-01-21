package villagecompute.homepage.jobs;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.data.models.ClickStatsDaily;
import villagecompute.homepage.data.models.ClickStatsDailyItems;
import villagecompute.homepage.data.models.LinkClick;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ClickRollupJobHandler}.
 *
 * <p>
 * Tests click event aggregation into rollup tables, including profile event handling (Task I6.T4).
 */
@QuarkusTest
class ClickRollupJobHandlerTest {

    @Inject
    ClickRollupJobHandler handler;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    EntityManager entityManager;

    private LocalDate testDate;
    private UUID profileId1;
    private UUID profileId2;
    private UUID articleId1;
    private UUID articleId2;

    @BeforeEach
    @Transactional
    void setUp() {
        testDate = LocalDate.now().minusDays(1);
        profileId1 = UUID.randomUUID();
        profileId2 = UUID.randomUUID();
        articleId1 = UUID.randomUUID();
        articleId2 = UUID.randomUUID();

        // Clean up test data
        entityManager.createNativeQuery("DELETE FROM click_stats_daily WHERE stat_date = :testDate")
                .setParameter("testDate", testDate).executeUpdate();
        entityManager.createNativeQuery("DELETE FROM click_stats_daily_items WHERE stat_date = :testDate")
                .setParameter("testDate", testDate).executeUpdate();
        LinkClick.delete("clickDate = ?1", testDate);
    }

    /**
     * Test rollup job aggregates profile_view events correctly (Task I6.T4).
     */
    @Test
    @Transactional
    void testProfileViewAggregation() throws Exception {
        // Create profile_view clicks
        createProfileViewClick(profileId1, "user1", null);
        createProfileViewClick(profileId1, "user2", null);
        createProfileViewClick(profileId1, null, "session1");
        createProfileViewClick(profileId2, "user1", null);

        // Run rollup job
        handler.execute(1L, Map.of("rollup_date", testDate.toString()));

        // Verify category rollup (NULL category_id for profile events)
        ClickStatsDaily categoryStats = ClickStatsDaily
                .find("stat_date = ?1 AND click_type = ?2 AND category_id IS NULL", testDate, "profile_view")
                .firstResult();
        assertNotNull(categoryStats, "Profile view category stats should be created");
        assertEquals(4L, categoryStats.totalClicks);
        assertEquals(3L, categoryStats.uniqueUsers); // user1, user2, session1
        assertEquals(1L, categoryStats.uniqueSessions); // Only session1 counted

        // Verify item rollup for profile 1
        ClickStatsDailyItems profile1Stats = ClickStatsDailyItems
                .find("stat_date = ?1 AND click_type = ?2 AND target_id = ?3", testDate, "profile_view", profileId1)
                .firstResult();
        assertNotNull(profile1Stats, "Profile 1 item stats should be created");
        assertEquals(3L, profile1Stats.totalClicks);
        assertEquals(3L, profile1Stats.uniqueUsers); // user1, user2, session1
        assertNull(profile1Stats.avgRank, "Profile events should not have rank");
        assertNull(profile1Stats.avgScore, "Profile events should not have score");
        assertEquals(0L, profile1Stats.bubbledClicks, "Profile events should not have bubbled clicks");

        // Verify item rollup for profile 2
        ClickStatsDailyItems profile2Stats = ClickStatsDailyItems
                .find("stat_date = ?1 AND click_type = ?2 AND target_id = ?3", testDate, "profile_view", profileId2)
                .firstResult();
        assertNotNull(profile2Stats, "Profile 2 item stats should be created");
        assertEquals(1L, profile2Stats.totalClicks);
        assertEquals(1L, profile2Stats.uniqueUsers);
    }

    /**
     * Test rollup job aggregates profile_curated events correctly (Task I6.T4).
     */
    @Test
    @Transactional
    void testProfileCuratedAggregation() throws Exception {
        // Create profile_curated clicks
        createProfileCuratedClick(profileId1, articleId1, "top_pick", "user1", null);
        createProfileCuratedClick(profileId1, articleId1, "top_pick", "user2", null);
        createProfileCuratedClick(profileId1, articleId2, "featured", "user1", null);
        createProfileCuratedClick(profileId2, articleId1, "top_pick", null, "session1");

        // Run rollup job
        handler.execute(1L, Map.of("rollup_date", testDate.toString()));

        // Verify category rollup (NULL category_id for profile events)
        ClickStatsDaily categoryStats = ClickStatsDaily
                .find("stat_date = ?1 AND click_type = ?2 AND category_id IS NULL", testDate, "profile_curated")
                .firstResult();
        assertNotNull(categoryStats, "Profile curated category stats should be created");
        assertEquals(4L, categoryStats.totalClicks);
        assertEquals(3L, categoryStats.uniqueUsers); // user1, user2, session1

        // Verify item rollup for article 1 (clicked from profile1 twice + profile2 once)
        ClickStatsDailyItems article1Stats = ClickStatsDailyItems
                .find("stat_date = ?1 AND click_type = ?2 AND target_id = ?3", testDate, "profile_curated", articleId1)
                .firstResult();
        assertNotNull(article1Stats, "Article 1 item stats should be created");
        assertEquals(3L, article1Stats.totalClicks);
        assertEquals(3L, article1Stats.uniqueUsers); // user1, user2, session1

        // Verify item rollup for article 2
        ClickStatsDailyItems article2Stats = ClickStatsDailyItems
                .find("stat_date = ?1 AND click_type = ?2 AND target_id = ?3", testDate, "profile_curated", articleId2)
                .firstResult();
        assertNotNull(article2Stats, "Article 2 item stats should be created");
        assertEquals(1L, article2Stats.totalClicks);
        assertEquals(1L, article2Stats.uniqueUsers);
    }

    /**
     * Test rollup job handles mixed event types correctly (directory + profile).
     */
    @Test
    @Transactional
    void testMixedEventTypeAggregation() throws Exception {
        UUID siteId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();

        // Create directory site click with category
        createDirectorySiteClick(siteId, categoryId, 2, 15, false, "user1", null);

        // Create profile view (NULL category)
        createProfileViewClick(profileId1, "user2", null);

        // Run rollup job
        handler.execute(1L, Map.of("rollup_date", testDate.toString()));

        // Verify directory category rollup
        ClickStatsDaily directoryStats = ClickStatsDaily.find("stat_date = ?1 AND click_type = ?2 AND category_id = ?3",
                testDate, "directory_site_click", categoryId).firstResult();
        assertNotNull(directoryStats, "Directory category stats should be created");
        assertEquals(1L, directoryStats.totalClicks);

        // Verify profile category rollup (NULL category_id)
        ClickStatsDaily profileStats = ClickStatsDaily
                .find("stat_date = ?1 AND click_type = ?2 AND category_id IS NULL", testDate, "profile_view")
                .firstResult();
        assertNotNull(profileStats, "Profile category stats should be created");
        assertEquals(1L, profileStats.totalClicks);

        // Verify both item rollups exist
        assertEquals(1L, ClickStatsDailyItems.count("stat_date = ?1 AND click_type = ?2 AND target_id = ?3", testDate,
                "directory_site_click", siteId), "Directory site item stats should exist");
        assertEquals(1L, ClickStatsDailyItems.count("stat_date = ?1 AND click_type = ?2 AND target_id = ?3", testDate,
                "profile_view", profileId1), "Profile item stats should exist");
    }

    /**
     * Test rollup job is idempotent (can be re-run safely).
     */
    @Test
    @Transactional
    void testRollupIdempotency() throws Exception {
        // Create profile_view click
        createProfileViewClick(profileId1, "user1", null);

        // Run rollup job first time
        handler.execute(1L, Map.of("rollup_date", testDate.toString()));

        ClickStatsDailyItems firstStats = ClickStatsDailyItems
                .find("stat_date = ?1 AND click_type = ?2 AND target_id = ?3", testDate, "profile_view", profileId1)
                .firstResult();
        assertNotNull(firstStats);
        assertEquals(1L, firstStats.totalClicks);

        // Run rollup job again (should update, not duplicate)
        handler.execute(2L, Map.of("rollup_date", testDate.toString()));

        long count = ClickStatsDailyItems.count("stat_date = ?1 AND click_type = ?2 AND target_id = ?3", testDate,
                "profile_view", profileId1);
        assertEquals(1L, count, "Should still have only one rollup row (idempotent)");

        ClickStatsDailyItems secondStats = ClickStatsDailyItems
                .find("stat_date = ?1 AND click_type = ?2 AND target_id = ?3", testDate, "profile_view", profileId1)
                .firstResult();
        assertEquals(1L, secondStats.totalClicks, "Click count should remain the same");
    }

    /**
     * Test rollup job handles NULL category_id gracefully (doesn't break SQL).
     */
    @Test
    @Transactional
    void testNullCategoryHandling() throws Exception {
        // Create profile_view with NULL category_id
        LinkClick click = new LinkClick();

        click.clickDate = testDate;
        click.clickTimestamp = testDate.atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
        click.clickType = "profile_view";
        click.targetId = profileId1;
        click.targetUrl = "/users/johndoe";
        click.userId = UUID.randomUUID();
        click.categoryId = null; // Explicitly NULL
        click.metadata = new JsonObject().put("profile_id", profileId1.toString()).encode();
        click.createdAt = Instant.now();
        click.persist();

        // Should not throw SQL exception
        assertDoesNotThrow(() -> handler.execute(1L, Map.of("rollup_date", testDate.toString())),
                "Rollup job should handle NULL category_id without SQL errors");

        // Verify rollup was created with NULL category_id
        ClickStatsDaily categoryStats = ClickStatsDaily
                .find("stat_date = ?1 AND click_type = ?2 AND category_id IS NULL", testDate, "profile_view")
                .firstResult();
        assertNotNull(categoryStats);
        assertNull(categoryStats.categoryId);
        assertNull(categoryStats.categoryName);
    }

    // Helper methods

    private void createProfileViewClick(UUID profileId, String userId, String sessionId) {
        LinkClick click = new LinkClick();

        click.clickDate = testDate;
        click.clickTimestamp = testDate.atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
        click.clickType = "profile_view";
        click.targetId = profileId;
        click.targetUrl = "/users/johndoe";
        click.userId = userId != null ? UUID.nameUUIDFromBytes(userId.getBytes()) : null;
        click.sessionId = sessionId;
        click.ipAddress = "192.168.1.0";
        click.userAgent = "Mozilla/5.0";
        click.categoryId = null; // Profile events have no category
        click.metadata = new JsonObject().put("profile_id", profileId.toString()).put("profile_username", "johndoe")
                .put("template", "minimal").encode();
        click.createdAt = Instant.now();
        click.persist();
    }

    private void createProfileCuratedClick(UUID profileId, UUID articleId, String slot, String userId,
            String sessionId) {
        LinkClick click = new LinkClick();

        click.clickDate = testDate;
        click.clickTimestamp = testDate.atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
        click.clickType = "profile_curated";
        click.targetId = articleId;
        click.targetUrl = "https://example.com/article";
        click.userId = userId != null ? UUID.nameUUIDFromBytes(userId.getBytes()) : null;
        click.sessionId = sessionId;
        click.ipAddress = "192.168.1.0";
        click.userAgent = "Mozilla/5.0";
        click.categoryId = null; // Profile events have no category
        click.metadata = new JsonObject().put("profile_id", profileId.toString()).put("profile_username", "johndoe")
                .put("article_id", articleId.toString()).put("article_slot", slot).put("template", "minimal").encode();
        click.createdAt = Instant.now();
        click.persist();
    }

    private void createDirectorySiteClick(UUID siteId, UUID categoryId, int rank, int score, boolean isBubbled,
            String userId, String sessionId) {
        LinkClick click = new LinkClick();

        click.clickDate = testDate;
        click.clickTimestamp = testDate.atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
        click.clickType = "directory_site_click";
        click.targetId = siteId;
        click.targetUrl = "https://example.com";
        click.userId = userId != null ? UUID.nameUUIDFromBytes(userId.getBytes()) : null;
        click.sessionId = sessionId;
        click.ipAddress = "192.168.1.0";
        click.userAgent = "Mozilla/5.0";
        click.categoryId = categoryId;
        click.metadata = new JsonObject().put("category_id", categoryId.toString()).put("rank_in_category", rank)
                .put("score", score).put("is_bubbled", isBubbled).encode();
        click.createdAt = Instant.now();
        click.persist();
    }
}
