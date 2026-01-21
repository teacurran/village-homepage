package villagecompute.homepage.api.rest.admin;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.data.models.ClickStatsDailyItems;
import villagecompute.homepage.data.models.LinkClick;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Tests for {@link AnalyticsResource} profile analytics endpoints (Task I6.T4).
 *
 * <p>
 * Verifies admin analytics queries for profile engagement, curated article click-through rates, and top viewed
 * profiles.
 */
@QuarkusTest
class AnalyticsResourceTest {

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
        entityManager.createNativeQuery("DELETE FROM click_stats_daily_items WHERE stat_date = :testDate")
                .setParameter("testDate", testDate).executeUpdate();
        LinkClick.delete("clickDate = ?1", testDate);
    }

    /**
     * Test GET /admin/api/analytics/profiles/top-viewed endpoint.
     */
    @Test
    @TestSecurity(
            user = "admin",
            roles = {"super_admin"})
    @Transactional
    void testGetTopViewedProfiles() {
        // Create rollup data for profile views
        createProfileViewRollup(profileId1, 150, 120, 140);
        createProfileViewRollup(profileId2, 75, 60, 70);

        given().contentType(ContentType.JSON).queryParam("start_date", testDate.toString())
                .queryParam("end_date", testDate.toString()).queryParam("limit", 10).when()
                .get("/admin/api/analytics/profiles/top-viewed").then().statusCode(200).body("profiles", hasSize(2))
                .body("profiles[0].profile_id", equalTo(profileId1.toString()))
                .body("profiles[0].total_views", equalTo(150)).body("profiles[0].unique_users", equalTo(120))
                .body("profiles[0].unique_sessions", equalTo(140))
                .body("profiles[1].profile_id", equalTo(profileId2.toString()))
                .body("profiles[1].total_views", equalTo(75)).body("count", equalTo(2));
    }

    /**
     * Test GET /admin/api/analytics/profiles/{id}/curated-clicks endpoint.
     */
    @Test
    @TestSecurity(
            user = "admin",
            roles = {"ops"})
    @Transactional
    void testGetCuratedArticleClicks() {
        // Create raw click data for profile_curated events
        createProfileCuratedClick(profileId1, articleId1, "https://example.com/article1", "top_pick", "user1");
        createProfileCuratedClick(profileId1, articleId1, "https://example.com/article1", "top_pick", "user2");
        createProfileCuratedClick(profileId1, articleId2, "https://example.com/article2", "featured", "user1");

        given().contentType(ContentType.JSON).pathParam("id", profileId1.toString())
                .queryParam("start_date", testDate.toString()).queryParam("end_date", testDate.toString()).when()
                .get("/admin/api/analytics/profiles/{id}/curated-clicks").then().statusCode(200)
                .body("profile_id", equalTo(profileId1.toString())).body("articles", hasSize(2))
                // Article 1 has 2 clicks
                .body("articles[0].article_id", equalTo(articleId1.toString()))
                .body("articles[0].article_url", equalTo("https://example.com/article1"))
                .body("articles[0].article_slot", equalTo("top_pick")).body("articles[0].total_clicks", equalTo(2))
                .body("articles[0].unique_users", equalTo(2))
                // Article 2 has 1 click
                .body("articles[1].article_id", equalTo(articleId2.toString()))
                .body("articles[1].article_slot", equalTo("featured")).body("articles[1].total_clicks", equalTo(1));
    }

    /**
     * Test GET /admin/api/analytics/profiles/engagement endpoint.
     */
    @Test
    @TestSecurity(
            user = "admin",
            roles = {"read_only"})
    @Transactional
    void testGetProfileEngagement() {
        // Create rollup data for profile views
        createProfileViewRollup(profileId1, 100, 80, 90);
        createProfileViewRollup(profileId2, 50, 40, 45);

        // Create rollup data for profile curated clicks
        createProfileCuratedRollup(articleId1, 30, 25, 28);
        createProfileCuratedRollup(articleId2, 20, 18, 19);

        given().contentType(ContentType.JSON).queryParam("start_date", testDate.toString())
                .queryParam("end_date", testDate.toString()).when().get("/admin/api/analytics/profiles/engagement")
                .then().statusCode(200).body("total_profile_views", equalTo(150)) // 100 + 50
                .body("total_curated_clicks", equalTo(50)) // 30 + 20
                .body("unique_users", equalTo(120)) // 80 + 40 (sum from view rollups)
                .body("profile_count", equalTo(2)).body("article_count", equalTo(2))
                .body("engagement_rate", closeTo(0.333, 0.01)) // 50/150
                .body("avg_views_per_profile", closeTo(75.0, 0.1)) // 150/2
                .body("avg_clicks_per_profile", closeTo(25.0, 0.1)); // 50/2
    }

    /**
     * Test analytics endpoints require authentication.
     */
    @Test
    void testAnalyticsRequiresAuth() {
        given().when().get("/admin/api/analytics/profiles/top-viewed").then().statusCode(401);

        given().pathParam("id", UUID.randomUUID().toString()).when()
                .get("/admin/api/analytics/profiles/{id}/curated-clicks").then().statusCode(401);

        given().when().get("/admin/api/analytics/profiles/engagement").then().statusCode(401);
    }

    /**
     * Test top-viewed endpoint with limit parameter.
     */
    @Test
    @TestSecurity(
            user = "admin",
            roles = {"super_admin"})
    @Transactional
    void testTopViewedWithLimit() {
        // Create 5 profile views with different counts
        for (int i = 0; i < 5; i++) {
            UUID profileId = UUID.randomUUID();
            createProfileViewRollup(profileId, 100 - (i * 10), 80 - (i * 8), 90 - (i * 9));
        }

        // Request only top 3
        given().queryParam("limit", 3).when().get("/admin/api/analytics/profiles/top-viewed").then().statusCode(200)
                .body("profiles", hasSize(3)).body("profiles[0].total_views", equalTo(100)) // Highest
                .body("profiles[2].total_views", equalTo(80)); // Third highest
    }

    /**
     * Test curated clicks endpoint with no data returns empty array.
     */
    @Test
    @TestSecurity(
            user = "admin",
            roles = {"ops"})
    void testCuratedClicksNoData() {
        UUID profileId = UUID.randomUUID();

        given().pathParam("id", profileId.toString()).when().get("/admin/api/analytics/profiles/{id}/curated-clicks")
                .then().statusCode(200).body("profile_id", equalTo(profileId.toString())).body("articles", hasSize(0))
                .body("count", equalTo(0));
    }

    /**
     * Test engagement endpoint with no data returns zeros.
     */
    @Test
    @TestSecurity(
            user = "admin",
            roles = {"read_only"})
    void testEngagementNoData() {
        given().queryParam("start_date", testDate.toString()).queryParam("end_date", testDate.toString()).when()
                .get("/admin/api/analytics/profiles/engagement").then().statusCode(200)
                .body("total_profile_views", equalTo(0)).body("total_curated_clicks", equalTo(0))
                .body("engagement_rate", equalTo(0.0f));
    }

    // Helper methods

    private void createProfileViewRollup(UUID profileId, long totalClicks, long uniqueUsers, long uniqueSessions) {
        ClickStatsDailyItems item = new ClickStatsDailyItems();

        item.statDate = testDate;
        item.clickType = "profile_view";
        item.targetId = profileId;
        item.totalClicks = totalClicks;
        item.uniqueUsers = uniqueUsers;
        item.uniqueSessions = uniqueSessions;
        item.avgRank = null;
        item.avgScore = null;
        item.bubbledClicks = 0L;
        item.createdAt = Instant.now();
        item.updatedAt = Instant.now();
        item.persist();
    }

    private void createProfileCuratedRollup(UUID articleId, long totalClicks, long uniqueUsers, long uniqueSessions) {
        ClickStatsDailyItems item = new ClickStatsDailyItems();

        item.statDate = testDate;
        item.clickType = "profile_curated";
        item.targetId = articleId;
        item.totalClicks = totalClicks;
        item.uniqueUsers = uniqueUsers;
        item.uniqueSessions = uniqueSessions;
        item.avgRank = null;
        item.avgScore = null;
        item.bubbledClicks = 0L;
        item.createdAt = Instant.now();
        item.updatedAt = Instant.now();
        item.persist();
    }

    private void createProfileCuratedClick(UUID profileId, UUID articleId, String articleUrl, String slot,
            String userId) {
        LinkClick click = new LinkClick();

        click.clickDate = testDate;
        click.clickTimestamp = testDate.atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
        click.clickType = "profile_curated";
        click.targetId = articleId;
        click.targetUrl = articleUrl;
        click.userId = UUID.nameUUIDFromBytes(userId.getBytes());
        click.sessionId = null;
        click.ipAddress = "192.168.1.0";
        click.userAgent = "Mozilla/5.0";
        click.categoryId = null;
        click.metadata = new JsonObject().put("profile_id", profileId.toString()).put("profile_username", "johndoe")
                .put("article_id", articleId.toString()).put("article_slot", slot).put("template", "minimal").encode();
        click.createdAt = Instant.now();
        click.persist();
    }
}
