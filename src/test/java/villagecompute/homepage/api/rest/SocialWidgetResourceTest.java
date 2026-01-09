/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.api.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.data.models.SocialPost;
import villagecompute.homepage.data.models.SocialToken;
import villagecompute.homepage.data.models.User;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Tests for {@link SocialWidgetResource} REST endpoints.
 */
@QuarkusTest
class SocialWidgetResourceTest {

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
    void testGetSocialFeed_Disconnected() {
        // Test GET /api/widgets/social with no token
        given().queryParam("user_id", testUser.id.toString()).queryParam("platform", "instagram").when()
                .get("/api/widgets/social").then().statusCode(200).body("platform", equalTo("instagram"))
                .body("connection_status", equalTo("disconnected")).body("staleness", equalTo("VERY_STALE"))
                .body("posts", hasSize(0)).body("reconnect_url", notNullValue()).body("message", containsString("Connect"));
    }

    @Test
    @Transactional
    void testGetSocialFeed_WithCachedPosts() {
        // Create token and cached post
        Instant now = Instant.now();
        SocialToken token = SocialToken.create(testUser.id, SocialToken.PLATFORM_FACEBOOK, "test_token", null,
                now.plus(30, ChronoUnit.DAYS), "user_posts");
        token.lastRefreshAttempt = now.minus(1, ChronoUnit.HOURS);
        token.persist();

        SocialPost.create(token.id, SocialToken.PLATFORM_FACEBOOK, "fb123", "status", "Test post", List.of(),
                now.minus(2, ChronoUnit.HOURS), Map.of("likes", 10, "comments", 2));

        // Test GET /api/widgets/social with cached posts
        given().queryParam("user_id", testUser.id.toString()).queryParam("platform", "facebook").when()
                .get("/api/widgets/social").then().statusCode(200).body("platform", equalTo("facebook"))
                .body("posts", hasSize(1)).body("posts[0].platform_post_id", equalTo("fb123"));
    }

    @Test
    void testGetSocialFeed_InvalidUserId() {
        // Test with invalid user ID format
        given().queryParam("user_id", "invalid-uuid").queryParam("platform", "instagram").when()
                .get("/api/widgets/social").then().statusCode(400).body("error", equalTo("Invalid user_id format"));
    }

    @Test
    void testGetSocialFeed_InvalidPlatform() {
        // Test with invalid platform
        given().queryParam("user_id", testUser.id.toString()).queryParam("platform", "invalid").when()
                .get("/api/widgets/social").then().statusCode(400)
                .body("error", equalTo("Platform must be 'instagram' or 'facebook'"));
    }

    @Test
    void testGetSocialFeed_MissingUserIdParameter() {
        // Test with missing user_id parameter
        given().queryParam("platform", "instagram").when().get("/api/widgets/social").then().statusCode(400);
    }

    @Test
    void testGetSocialFeed_MissingPlatformParameter() {
        // Test with missing platform parameter
        given().queryParam("user_id", testUser.id.toString()).when().get("/api/widgets/social").then()
                .statusCode(400);
    }

    @Test
    @Transactional
    void testGetSocialFeed_StaleState() {
        // Create stale token (refreshed 5 days ago)
        Instant now = Instant.now();
        SocialToken token = SocialToken.create(testUser.id, SocialToken.PLATFORM_INSTAGRAM, "stale_token", null,
                now.plus(30, ChronoUnit.DAYS), "instagram_basic");
        token.lastRefreshAttempt = now.minus(5, ChronoUnit.DAYS);
        token.persist();

        SocialPost.create(token.id, SocialToken.PLATFORM_INSTAGRAM, "ig123", "image", "Old post",
                List.of(Map.of("url", "https://example.com/old.jpg", "type", "image")),
                now.minus(5, ChronoUnit.DAYS), Map.of("likes", 15, "comments", 3));

        // Test GET /api/widgets/social with stale token
        given().queryParam("user_id", testUser.id.toString()).queryParam("platform", "instagram").when()
                .get("/api/widgets/social").then().statusCode(200).body("platform", equalTo("instagram"))
                .body("connection_status", equalTo("stale")).body("staleness", equalTo("STALE"))
                .body("staleness_days", greaterThanOrEqualTo(5)).body("reconnect_url", notNullValue())
                .body("message", containsString("Reconnect"));
    }
}
