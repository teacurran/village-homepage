/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.jobs;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.data.models.SocialPost;
import villagecompute.homepage.data.models.SocialToken;
import villagecompute.homepage.data.models.User;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SocialFeedRefreshJobHandler}.
 *
 * <p>
 * NOTE: These tests verify the job handler logic without actually calling Meta Graph API (since credentials are not
 * configured in test environment). The handler will skip very stale tokens and process others.
 */
@QuarkusTest
class SocialFeedRefreshJobHandlerTest {

    @Inject
    SocialFeedRefreshJobHandler handler;

    private User testUser1;
    private User testUser2;

    @BeforeEach
    @Transactional
    void setUp() {
        // Create test users
        testUser1 = User.createAnonymous();
        testUser2 = User.createAnonymous();
    }

    @AfterEach
    @Transactional
    void tearDown() {
        // Clean up test data
        SocialPost.deleteAll();
        SocialToken.deleteAll();
        if (testUser1 != null) {
            testUser1.delete();
        }
        if (testUser2 != null) {
            testUser2.delete();
        }
    }

    @Test
    void testHandlesType() {
        // Verify handler returns correct job type
        assertEquals(JobType.SOCIAL_REFRESH, handler.handlesType());
    }

    @Test
    @Transactional
    void testExecute_NoActiveTokens() throws Exception {
        // Test job execution with no active tokens
        Map<String, Object> payload = new HashMap<>();

        // Should complete without errors
        assertDoesNotThrow(() -> handler.execute(1L, payload));
    }

    @Test
    @Transactional
    void testExecute_WithActiveTokens() throws Exception {
        // Create fresh token (refreshed 1 hour ago)
        Instant now = Instant.now();
        SocialToken token1 = SocialToken.create(testUser1.id, SocialToken.PLATFORM_INSTAGRAM, "token1", null,
                now.plus(30, ChronoUnit.DAYS), "instagram_basic");
        token1.lastRefreshAttempt = now.minus(1, ChronoUnit.HOURS);
        token1.persist();

        // Create slightly stale token (refreshed 2 days ago)
        SocialToken token2 = SocialToken.create(testUser2.id, SocialToken.PLATFORM_FACEBOOK, "token2", null,
                now.plus(30, ChronoUnit.DAYS), "user_posts");
        token2.lastRefreshAttempt = now.minus(2, ChronoUnit.DAYS);
        token2.persist();

        Map<String, Object> payload = new HashMap<>();

        // Handler will attempt to refresh both tokens
        // API calls will fail (no credentials), but handler should continue processing
        assertDoesNotThrow(() -> handler.execute(2L, payload));

        // Verify tokens still exist
        assertEquals(2, SocialToken.findAllActive().size());
    }

    @Test
    @Transactional
    void testExecute_WithVeryStaleToken() throws Exception {
        // Create very stale token (refreshed 10 days ago)
        Instant now = Instant.now();
        SocialToken token = SocialToken.create(testUser1.id, SocialToken.PLATFORM_INSTAGRAM, "token_stale", null,
                now.plus(30, ChronoUnit.DAYS), "instagram_basic");
        token.lastRefreshAttempt = now.minus(10, ChronoUnit.DAYS);
        token.persist();

        // Create some cached posts
        SocialPost.create(token.id, SocialToken.PLATFORM_INSTAGRAM, "post1", "image", "Old caption",
                List.of(Map.of("url", "https://example.com/old.jpg", "type", "image")), now.minus(10, ChronoUnit.DAYS),
                Map.of("likes", 5, "comments", 1));

        Map<String, Object> payload = new HashMap<>();
        payload.put("archive_expired", true);

        // Handler should skip very stale token and archive posts
        assertDoesNotThrow(() -> handler.execute(3L, payload));

        // Verify post was archived
        List<SocialPost> archivedPosts = SocialPost.findAllByToken(token.id);
        assertFalse(archivedPosts.isEmpty());
        assertTrue(archivedPosts.get(0).isArchived);
    }

    @Test
    @Transactional
    void testExecute_WithSpecificTokenId() throws Exception {
        // Create two tokens
        Instant now = Instant.now();
        SocialToken token1 = SocialToken.create(testUser1.id, SocialToken.PLATFORM_INSTAGRAM, "token1", null,
                now.plus(30, ChronoUnit.DAYS), "instagram_basic");
        token1.persist();

        SocialToken token2 = SocialToken.create(testUser2.id, SocialToken.PLATFORM_FACEBOOK, "token2", null,
                now.plus(30, ChronoUnit.DAYS), "user_posts");
        token2.persist();

        // Execute job targeting only token1
        Map<String, Object> payload = new HashMap<>();
        payload.put("token_id", token1.id);

        // Should only process token1
        assertDoesNotThrow(() -> handler.execute(4L, payload));
    }

    @Test
    @Transactional
    void testExecute_WithInvalidTokenId() {
        // Test with non-existent token ID
        Map<String, Object> payload = new HashMap<>();
        payload.put("token_id", 99999L);

        // Should throw exception for invalid token ID
        assertThrows(Exception.class, () -> handler.execute(5L, payload));
    }
}
