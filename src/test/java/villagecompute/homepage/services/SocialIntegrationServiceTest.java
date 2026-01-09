/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.services;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.api.types.SocialWidgetStateType;
import villagecompute.homepage.data.models.SocialPost;
import villagecompute.homepage.data.models.SocialToken;
import villagecompute.homepage.data.models.User;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SocialIntegrationService} covering cache-first logic, staleness tiers, and graceful degradation.
 *
 * <p>
 * NOTE: These tests focus on testing the service logic with database entities. Actual API integration is tested
 * manually or skipped in test environment since Meta credentials are not configured.
 */
@QuarkusTest
class SocialIntegrationServiceTest {

    @Inject
    SocialIntegrationService service;

    private User testUser;

    @BeforeEach
    @Transactional
    void setUp() {
        // Create test user
        testUser = User.createAnonymous();
    }

    @AfterEach
    @Transactional
    void tearDown() {
        // Clean up test data
        SocialPost.deleteAll();
        SocialToken.deleteAll();
        if (testUser != null) {
            testUser.delete();
        }
    }

    @Test
    @Transactional
    void testGetSocialFeed_Disconnected() {
        // Test disconnected state (no token exists)
        SocialWidgetStateType state = service.getSocialFeed(testUser.id, SocialToken.PLATFORM_INSTAGRAM);

        assertNotNull(state);
        assertEquals(SocialToken.PLATFORM_INSTAGRAM, state.platform());
        assertEquals("disconnected", state.connectionStatus());
        assertEquals("VERY_STALE", state.staleness());
        assertTrue(state.posts().isEmpty());
        assertNotNull(state.reconnectUrl());
        assertNotNull(state.message());
        assertTrue(state.message().contains("Connect your Instagram"));
    }

    @Test
    @Transactional
    void testGetSocialFeed_WithCachedPosts() {
        // Create token with recent refresh
        Instant now = Instant.now();
        SocialToken token = SocialToken.create(testUser.id, SocialToken.PLATFORM_INSTAGRAM, "test_access_token", null,
                now.plus(30, ChronoUnit.DAYS), "instagram_basic");
        token.lastRefreshAttempt = now.minus(1, ChronoUnit.HOURS);
        token.persist();

        // Create cached post
        SocialPost.create(token.id, SocialToken.PLATFORM_INSTAGRAM, "post123", "image", "Test caption",
                List.of(Map.of("url", "https://example.com/image.jpg", "type", "image")),
                now.minus(2, ChronoUnit.HOURS), Map.of("likes", 10, "comments", 2));

        // Service will serve cached posts (API call will fail due to missing credentials)
        SocialWidgetStateType state = service.getSocialFeed(testUser.id, SocialToken.PLATFORM_INSTAGRAM);

        assertNotNull(state);
        assertEquals(SocialToken.PLATFORM_INSTAGRAM, state.platform());
        assertFalse(state.posts().isEmpty());
        assertEquals(1, state.posts().size());
        assertEquals("post123", state.posts().get(0).platformPostId());
    }

    @Test
    @Transactional
    void testGetSocialFeed_SlightlyStale() {
        // Create token with 2-day-old refresh
        Instant now = Instant.now();
        SocialToken token = SocialToken.create(testUser.id, SocialToken.PLATFORM_FACEBOOK, "test_access_token", null,
                now.plus(30, ChronoUnit.DAYS), "user_posts");
        token.lastRefreshAttempt = now.minus(2, ChronoUnit.DAYS);
        token.persist();

        // Create cached posts
        SocialPost.create(token.id, SocialToken.PLATFORM_FACEBOOK, "fb123", "status", "Status update", List.of(),
                now.minus(2, ChronoUnit.DAYS), Map.of("likes", 5, "comments", 1, "shares", 0));

        // Test slightly stale state
        SocialWidgetStateType state = service.getSocialFeed(testUser.id, SocialToken.PLATFORM_FACEBOOK);

        assertNotNull(state);
        assertEquals(SocialToken.PLATFORM_FACEBOOK, state.platform());
        assertEquals("stale", state.connectionStatus());
        assertEquals("SLIGHTLY_STALE", state.staleness());
        assertEquals(2, state.stalenessDays());
        assertFalse(state.posts().isEmpty());
        assertNotNull(state.message());
        assertTrue(state.message().contains("2 days ago"));
    }

    @Test
    @Transactional
    void testGetSocialFeed_Stale() {
        // Create token with 5-day-old refresh
        Instant now = Instant.now();
        SocialToken token = SocialToken.create(testUser.id, SocialToken.PLATFORM_INSTAGRAM, "test_access_token", null,
                now.plus(30, ChronoUnit.DAYS), "instagram_basic");
        token.lastRefreshAttempt = now.minus(5, ChronoUnit.DAYS);
        token.persist();

        // Create cached posts
        SocialPost.create(token.id, SocialToken.PLATFORM_INSTAGRAM, "ig456", "image", "Old post",
                List.of(Map.of("url", "https://example.com/old.jpg", "type", "image")), now.minus(5, ChronoUnit.DAYS),
                Map.of("likes", 20, "comments", 3));

        // Test stale state
        SocialWidgetStateType state = service.getSocialFeed(testUser.id, SocialToken.PLATFORM_INSTAGRAM);

        assertNotNull(state);
        assertEquals(SocialToken.PLATFORM_INSTAGRAM, state.platform());
        assertEquals("stale", state.connectionStatus());
        assertEquals("STALE", state.staleness());
        assertEquals(5, state.stalenessDays());
        assertFalse(state.posts().isEmpty());
        assertNotNull(state.reconnectUrl());
        assertNotNull(state.message());
        assertTrue(state.message().contains("5 days ago"));
        assertTrue(state.message().contains("Reconnect"));
    }

    @Test
    @Transactional
    void testGetSocialFeed_VeryStale() {
        // Create token with 10-day-old refresh (very stale)
        Instant now = Instant.now();
        SocialToken token = SocialToken.create(testUser.id, SocialToken.PLATFORM_FACEBOOK, "test_access_token", null,
                now.plus(30, ChronoUnit.DAYS), "user_posts");
        token.lastRefreshAttempt = now.minus(10, ChronoUnit.DAYS);
        token.persist();

        // Create cached posts
        SocialPost.create(token.id, SocialToken.PLATFORM_FACEBOOK, "fb789", "photo", "Very old post",
                List.of(Map.of("url", "https://example.com/very-old.jpg", "type", "image")),
                now.minus(10, ChronoUnit.DAYS), Map.of("likes", 30, "comments", 5, "shares", 2));

        // Test very stale state
        SocialWidgetStateType state = service.getSocialFeed(testUser.id, SocialToken.PLATFORM_FACEBOOK);

        assertNotNull(state);
        assertEquals(SocialToken.PLATFORM_FACEBOOK, state.platform());
        assertEquals("expired", state.connectionStatus());
        assertEquals("VERY_STALE", state.staleness());
        assertTrue(state.stalenessDays() >= 10);
        assertFalse(state.posts().isEmpty());
        assertNotNull(state.reconnectUrl());
        assertNotNull(state.message());
        assertTrue(state.message().contains("expired"));
    }

    @Test
    @Transactional
    void testSocialTokenHelper_Methods() {
        // Test SocialToken helper methods
        Instant now = Instant.now();
        SocialToken token = SocialToken.create(testUser.id, SocialToken.PLATFORM_INSTAGRAM, "access123", "refresh456",
                now.plus(7, ChronoUnit.DAYS), "instagram_basic,instagram_content_publish");
        token.persist();

        // Test expiration checks
        assertFalse(token.isExpired());
        assertTrue(token.expiresWithin(8)); // expires within 8 days
        assertFalse(token.expiresWithin(6)); // does NOT expire within 6 days

        // Test staleness checks
        assertFalse(token.isStale()); // fresh token
        assertEquals(0, token.getDaysSinceRefresh()); // never refreshed, so uses grantedAt

        // Update token
        token.updateToken("new_access_token", now.plus(60, ChronoUnit.DAYS));
        assertEquals("new_access_token", token.accessToken);
        assertNotNull(token.lastRefreshAttempt);

        // Revoke token
        token.revoke();
        assertNotNull(token.revokedAt);
    }

    @Test
    @Transactional
    void testSocialPostHelper_Methods() {
        // Create token and posts
        Instant now = Instant.now();
        SocialToken token = SocialToken.create(testUser.id, SocialToken.PLATFORM_INSTAGRAM, "access123", null,
                now.plus(30, ChronoUnit.DAYS), "instagram_basic");
        token.persist();

        SocialPost post1 = SocialPost.create(token.id, SocialToken.PLATFORM_INSTAGRAM, "post1", "image", "Caption 1",
                List.of(Map.of("url", "https://example.com/1.jpg", "type", "image")), now.minus(1, ChronoUnit.HOURS),
                Map.of("likes", 10, "comments", 2));

        SocialPost post2 = SocialPost.create(token.id, SocialToken.PLATFORM_INSTAGRAM, "post2", "video", "Caption 2",
                List.of(Map.of("url", "https://example.com/2.mp4", "type", "video")), now.minus(2, ChronoUnit.HOURS),
                Map.of("likes", 20, "comments", 5));

        // Test find methods
        List<SocialPost> recentPosts = SocialPost.findRecentByToken(token.id, 5);
        assertEquals(2, recentPosts.size());

        List<SocialPost> allPosts = SocialPost.findAllByToken(token.id);
        assertEquals(2, allPosts.size());

        // Test archive
        post1.archive();
        assertTrue(post1.isArchived);

        // Archive all
        int archived = SocialPost.archiveAllByToken(token.id);
        assertEquals(1, archived); // Only post2 was not archived yet

        // Test staleness checks
        assertFalse(post2.isStale(1)); // Not stale after 1 day threshold
        assertEquals(0, post2.getDaysSinceFetch()); // Fetched today
    }
}
