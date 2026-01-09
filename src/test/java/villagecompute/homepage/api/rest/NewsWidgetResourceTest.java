/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.api.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import villagecompute.homepage.api.types.AiTagsType;
import villagecompute.homepage.api.types.NewsWidgetType;
import villagecompute.homepage.api.types.ThemeType;
import villagecompute.homepage.api.types.UserPreferencesType;
import villagecompute.homepage.data.models.FeedItem;
import villagecompute.homepage.data.models.UserFeedSubscription;
import villagecompute.homepage.services.FeedAggregationService;
import villagecompute.homepage.services.RateLimitService;
import villagecompute.homepage.services.UserPreferenceService;

/**
 * Unit tests for {@link NewsWidgetResource}.
 *
 * <p>
 * Tests cover:
 * <ul>
 * <li>Successful retrieval with pagination</li>
 * <li>Topic filtering with AI tags</li>
 * <li>Empty feed scenarios</li>
 * <li>Rate limiting</li>
 * <li>Authentication requirements</li>
 * <li>Caching headers</li>
 * <li>Metadata structure</li>
 * </ul>
 */
class NewsWidgetResourceTest {

    @Mock
    FeedAggregationService feedAggregationService;

    @Mock
    UserPreferenceService userPreferenceService;

    @Mock
    RateLimitService rateLimitService;

    @Mock
    SecurityContext securityContext;

    @Mock
    Principal principal;

    @InjectMocks
    NewsWidgetResource resource;

    private UUID testUserId;
    private UUID testSourceId;
    private UserPreferencesType mockPreferences;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        testUserId = UUID.randomUUID();
        testSourceId = UUID.randomUUID();

        mockPreferences = new UserPreferencesType(1, List.of(), List.of(), List.of(), List.of(),
                new ThemeType("system", null, "standard"), Map.of());
    }

    @Test
    void testGetNews_Success() {
        // Setup mocks
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(testUserId.toString());

        when(userPreferenceService.getPreferences(any())).thenReturn(mockPreferences);

        List<UserFeedSubscription> subscriptions = createSubscriptions();
        when(feedAggregationService.getActiveSubscriptions(any())).thenReturn(subscriptions);

        List<FeedItem> feedItems = createFeedItems();
        when(feedAggregationService.getRecentFeedItems(anyInt())).thenReturn(feedItems);

        when(rateLimitService.checkLimit(any(), any(), eq("news_read"), any(), anyString()))
                .thenReturn(new RateLimitService.RateLimitResult(true, 100, 99, 3600));

        // Execute request
        Response response = resource.getNews(securityContext, 20, 0, null, null);

        // Verify response
        assertEquals(200, response.getStatus());
        assertNotNull(response.getEntity());
        assertTrue(response.getEntity() instanceof NewsWidgetType);

        NewsWidgetType newsWidget = (NewsWidgetType) response.getEntity();
        assertNotNull(newsWidget.items());
        assertEquals(20, newsWidget.limit());
        assertEquals(0, newsWidget.offset());
        assertNotNull(newsWidget.metadata());

        // Verify headers
        assertEquals("max-age=300, must-revalidate", response.getHeaderString("Cache-Control"));
        assertEquals("Authorization", response.getHeaderString("Vary"));
        assertEquals("100", response.getHeaderString("X-RateLimit-Limit"));
        assertEquals("99", response.getHeaderString("X-RateLimit-Remaining"));
    }

    @Test
    void testGetNews_WithPagination() {
        // Setup mocks
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(testUserId.toString());

        when(userPreferenceService.getPreferences(any())).thenReturn(mockPreferences);

        List<UserFeedSubscription> subscriptions = createSubscriptions();
        when(feedAggregationService.getActiveSubscriptions(any())).thenReturn(subscriptions);

        List<FeedItem> feedItems = createManyFeedItems(50);
        when(feedAggregationService.getRecentFeedItems(anyInt())).thenReturn(feedItems);

        when(rateLimitService.checkLimit(any(), any(), eq("news_read"), any(), anyString()))
                .thenReturn(new RateLimitService.RateLimitResult(true, 100, 99, 3600));

        // Execute request with pagination
        Response response = resource.getNews(securityContext, 10, 5, null, null);

        // Verify response
        assertEquals(200, response.getStatus());
        NewsWidgetType newsWidget = (NewsWidgetType) response.getEntity();
        assertEquals(10, newsWidget.limit());
        assertEquals(5, newsWidget.offset());
        assertTrue(newsWidget.items().size() <= 10);
    }

    @Test
    void testGetNews_WithTopicFiltering() {
        // Setup mocks with topic preferences
        UserPreferencesType preferencesWithTopics = new UserPreferencesType(1, List.of(),
                Arrays.asList("technology", "ai"), List.of(), List.of(), new ThemeType("system", null, "standard"),
                Map.of());

        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(testUserId.toString());
        when(userPreferenceService.getPreferences(any())).thenReturn(preferencesWithTopics);

        List<UserFeedSubscription> subscriptions = createSubscriptions();
        when(feedAggregationService.getActiveSubscriptions(any())).thenReturn(subscriptions);

        List<FeedItem> feedItems = createFeedItemsWithTags();
        when(feedAggregationService.getRecentFeedItems(anyInt())).thenReturn(feedItems);

        when(rateLimitService.checkLimit(any(), any(), eq("news_read"), any(), anyString()))
                .thenReturn(new RateLimitService.RateLimitResult(true, 100, 99, 3600));

        // Execute request
        Response response = resource.getNews(securityContext, 20, 0, null, null);

        // Verify response
        assertEquals(200, response.getStatus());
        NewsWidgetType newsWidget = (NewsWidgetType) response.getEntity();

        @SuppressWarnings("unchecked")
        Map<String, Object> featureFlags = (Map<String, Object>) newsWidget.metadata().get("feature_flags");
        assertEquals(true, featureFlags.get("personalization_enabled"));
    }

    @Test
    void testGetNews_EmptySubscriptions() {
        // Setup mocks with no subscriptions
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(testUserId.toString());
        when(userPreferenceService.getPreferences(any())).thenReturn(mockPreferences);
        when(feedAggregationService.getActiveSubscriptions(any())).thenReturn(List.of());

        when(rateLimitService.checkLimit(any(), any(), eq("news_read"), any(), anyString()))
                .thenReturn(new RateLimitService.RateLimitResult(true, 100, 99, 3600));

        // Execute request
        Response response = resource.getNews(securityContext, 20, 0, null, null);

        // Verify response
        assertEquals(200, response.getStatus());
        NewsWidgetType newsWidget = (NewsWidgetType) response.getEntity();
        assertEquals(0, newsWidget.items().size());
        assertEquals(0, newsWidget.totalCount());
        assertNotNull(newsWidget.metadata());
    }

    @Test
    void testGetNews_InvalidPagination() {
        // Setup mocks
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(testUserId.toString());

        // Test with invalid limit (too high)
        Response response1 = resource.getNews(securityContext, 200, 0, null, null);
        assertEquals(400, response1.getStatus());

        // Test with negative offset
        Response response2 = resource.getNews(securityContext, 20, -1, null, null);
        assertEquals(400, response2.getStatus());
    }

    @Test
    void testGetNews_RateLimited() {
        // Setup mocks
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(testUserId.toString());

        // Setup rate limit exceeded
        when(rateLimitService.checkLimit(any(), any(), eq("news_read"), any(), anyString()))
                .thenReturn(new RateLimitService.RateLimitResult(false, 100, 0, 3600));

        // Execute request
        Response response = resource.getNews(securityContext, 20, 0, null, null);

        // Verify response
        assertEquals(429, response.getStatus());
        assertEquals("100", response.getHeaderString("X-RateLimit-Limit"));
        assertEquals("0", response.getHeaderString("X-RateLimit-Remaining"));
    }

    @Test
    void testGetNews_Unauthenticated() {
        // Setup mocks with null principal
        when(securityContext.getUserPrincipal()).thenReturn(null);

        // Execute request
        Response response = resource.getNews(securityContext, 20, 0, null, null);

        // Verify response
        assertEquals(401, response.getStatus());
    }

    @Test
    void testGetNews_VerifyAiTags() {
        // Setup mocks
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(testUserId.toString());
        when(userPreferenceService.getPreferences(any())).thenReturn(mockPreferences);

        List<UserFeedSubscription> subscriptions = createSubscriptions();
        when(feedAggregationService.getActiveSubscriptions(any())).thenReturn(subscriptions);

        List<FeedItem> feedItems = createFeedItemsWithTags();
        when(feedAggregationService.getRecentFeedItems(anyInt())).thenReturn(feedItems);

        when(rateLimitService.checkLimit(any(), any(), eq("news_read"), any(), anyString()))
                .thenReturn(new RateLimitService.RateLimitResult(true, 100, 99, 3600));

        // Execute request
        Response response = resource.getNews(securityContext, 20, 0, null, null);

        // Verify response
        assertEquals(200, response.getStatus());
        NewsWidgetType newsWidget = (NewsWidgetType) response.getEntity();

        // Verify AI tags are included
        assertTrue(newsWidget.items().size() > 0);
        assertEquals(true, newsWidget.items().get(0).aiTagged());
        assertNotNull(newsWidget.items().get(0).aiTags());
    }

    // Helper methods

    private List<UserFeedSubscription> createSubscriptions() {
        UserFeedSubscription sub = new UserFeedSubscription();
        sub.id = UUID.randomUUID();
        sub.userId = testUserId;
        sub.sourceId = testSourceId;
        sub.subscribedAt = Instant.now();
        return List.of(sub);
    }

    private List<FeedItem> createFeedItems() {
        List<FeedItem> items = new ArrayList<>();

        FeedItem item = new FeedItem();
        item.id = UUID.randomUUID();
        item.sourceId = testSourceId;
        item.title = "Test Article 1";
        item.url = "https://example.com/article1";
        item.description = "Test description";
        item.author = "Test Author";
        item.publishedAt = Instant.now().minusSeconds(3600);
        item.aiTagged = false;
        item.aiTags = null;
        item.fetchedAt = Instant.now();
        items.add(item);

        return items;
    }

    private List<FeedItem> createFeedItemsWithTags() {
        List<FeedItem> items = new ArrayList<>();

        FeedItem item = new FeedItem();
        item.id = UUID.randomUUID();
        item.sourceId = testSourceId;
        item.title = "AI Breakthrough in Machine Learning";
        item.url = "https://example.com/ai-article";
        item.description = "Latest AI research";
        item.author = "AI Researcher";
        item.publishedAt = Instant.now().minusSeconds(3600);
        item.aiTagged = true;
        item.aiTags = new AiTagsType(Arrays.asList("ai", "machine-learning", "technology"), "positive",
                Arrays.asList("Technology", "Science"), 0.95);
        item.fetchedAt = Instant.now();
        items.add(item);

        return items;
    }

    private List<FeedItem> createManyFeedItems(int count) {
        List<FeedItem> items = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            FeedItem item = new FeedItem();
            item.id = UUID.randomUUID();
            item.sourceId = testSourceId;
            item.title = "Test Article " + i;
            item.url = "https://example.com/article" + i;
            item.description = "Test description " + i;
            item.author = "Test Author";
            item.publishedAt = Instant.now().minusSeconds(3600 * i);
            item.aiTagged = false;
            item.aiTags = null;
            item.fetchedAt = Instant.now();
            items.add(item);
        }

        return items;
    }
}
