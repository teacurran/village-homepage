package villagecompute.homepage.api.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.data.models.LinkClick;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ClickTrackingResource (Task I5.T8).
 *
 * <p>
 * Verifies click tracking endpoint functionality including:
 * <ul>
 * <li>Valid click events are persisted with correct metadata</li>
 * <li>Invalid payloads are rejected with appropriate errors</li>
 * <li>Anonymous and authenticated tracking works correctly</li>
 * <li>Metadata JSONB fields are stored properly</li>
 * </ul>
 */
@QuarkusTest
public class ClickTrackingResourceTest {

    /**
     * Test tracking a directory site click with full metadata.
     */
    @Test
    public void testTrackDirectorySiteClick() {
        UUID siteId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();

        Map<String, Object> payload = Map.of("clickType", "directory_site_click", "targetId", siteId.toString(),
                "targetUrl", "https://example.com", "metadata",
                Map.of("category_id", categoryId.toString(), "is_bubbled", false, "rank_in_category", 2, "score", 15));

        given().contentType(ContentType.JSON).body(payload).when().post("/track/click").then().statusCode(200)
                .body("status", equalTo("tracked")).body("clickId", notNullValue());

        // Verify click was persisted
        LinkClick click = LinkClick.find("targetId", siteId).firstResult();
        assertNotNull(click, "Click should be persisted in database");
        assertEquals("directory_site_click", click.clickType);
        assertEquals(siteId, click.targetId);
        assertEquals("https://example.com", click.targetUrl);
        assertNotNull(click.metadata);
        assertEquals(categoryId.toString(), click.getMetadataAsJson().getString("category_id"));
        assertEquals(false, click.getMetadataAsJson().getBoolean("is_bubbled"));
        assertEquals(2, click.getMetadataAsJson().getInteger("rank_in_category"));
        assertEquals(15, click.getMetadataAsJson().getInteger("score"));
    }

    /**
     * Test tracking a bubbled site click with source category metadata.
     */
    @Test
    public void testTrackBubbledSiteClick() {
        UUID siteId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID sourceCategoryId = UUID.randomUUID();

        Map<String, Object> payload = Map.of("clickType", "directory_bubbled_click", "targetId", siteId.toString(),
                "targetUrl", "https://bubbled-site.com", "metadata",
                Map.of("category_id", categoryId.toString(), "is_bubbled", true, "source_category_id",
                        sourceCategoryId.toString(), "rank_in_category", 1, "score", 25));

        given().contentType(ContentType.JSON).body(payload).when().post("/track/click").then().statusCode(200)
                .body("status", equalTo("tracked"));

        // Verify bubbled click metadata
        LinkClick click = LinkClick.find("targetId", siteId).firstResult();
        assertNotNull(click);
        assertEquals("directory_bubbled_click", click.clickType);
        assertTrue(click.getMetadataAsJson().getBoolean("is_bubbled"));
        assertEquals(sourceCategoryId.toString(), click.getMetadataAsJson().getString("source_category_id"));
    }

    /**
     * Test tracking a category view event.
     */
    @Test
    public void testTrackCategoryView() {
        UUID categoryId = UUID.randomUUID();

        Map<String, Object> payload = Map.of("clickType", "directory_category_view", "targetId", categoryId.toString(),
                "targetUrl", "/good-sites/programming", "metadata",
                Map.of("category_id", categoryId.toString(), "category_slug", "programming"));

        given().contentType(ContentType.JSON).body(payload).when().post("/track/click").then().statusCode(200);

        LinkClick click = LinkClick.find("clickType", "directory_category_view").firstResult();
        assertNotNull(click);
        assertEquals(categoryId, click.categoryId);
        assertEquals("programming", click.getMetadataAsJson().getString("category_slug"));
    }

    /**
     * Test invalid click type is rejected.
     */
    @Test
    public void testInvalidClickType() {
        Map<String, Object> payload = Map.of("clickType", "invalid_click_type", "targetId",
                UUID.randomUUID().toString(), "targetUrl", "https://example.com");

        given().contentType(ContentType.JSON).body(payload).when().post("/track/click").then().statusCode(400);
    }

    /**
     * Test missing required field is rejected.
     */
    @Test
    public void testMissingRequiredField() {
        Map<String, Object> payload = Map.of("clickType", "directory_site_click");

        given().contentType(ContentType.JSON).body(payload).when().post("/track/click").then().statusCode(400);
    }

    /**
     * Test tracking marketplace listing click.
     */
    @Test
    public void testTrackMarketplaceClick() {
        UUID listingId = UUID.randomUUID();

        Map<String, Object> payload = Map.of("clickType", "marketplace_listing", "targetId", listingId.toString(),
                "targetUrl", "/marketplace/listings/" + listingId, "metadata", Map.of("category", "electronics"));

        given().contentType(ContentType.JSON).body(payload).when().post("/track/click").then().statusCode(200);

        LinkClick click = LinkClick.find("targetId", listingId).firstResult();
        assertNotNull(click);
        assertEquals("marketplace_listing", click.clickType);
        assertEquals("electronics", click.getMetadataAsJson().getString("category"));
    }

    /**
     * Test tracking with empty metadata.
     */
    @Test
    public void testTrackWithEmptyMetadata() {
        UUID targetId = UUID.randomUUID();

        Map<String, Object> payload = Map.of("clickType", "directory_site_view", "targetId", targetId.toString(),
                "targetUrl", "https://example.com", "metadata", Map.of());

        given().contentType(ContentType.JSON).body(payload).when().post("/track/click").then().statusCode(200);

        LinkClick click = LinkClick.find("targetId", targetId).firstResult();
        assertNotNull(click);
        assertEquals("directory_site_view", click.clickType);
    }

    /**
     * Test tracking without metadata field.
     */
    @Test
    public void testTrackWithoutMetadata() {
        UUID targetId = UUID.randomUUID();

        Map<String, Object> payload = Map.of("clickType", "profile_view", "targetId", targetId.toString(), "targetUrl",
                "/users/" + targetId);

        given().contentType(ContentType.JSON).body(payload).when().post("/track/click").then().statusCode(200);

        LinkClick click = LinkClick.find("targetId", targetId).firstResult();
        assertNotNull(click);
        assertEquals("profile_view", click.clickType);
    }

    /**
     * Test tracking profile_view event with profile metadata (Task I6.T4).
     */
    @Test
    public void testTrackProfileView() {
        UUID profileId = UUID.randomUUID();

        Map<String, Object> payload = Map.of("clickType", "profile_view", "targetId", profileId.toString(), "targetUrl",
                "/users/johndoe", "metadata",
                Map.of("profile_id", profileId.toString(), "profile_username", "johndoe", "template", "minimal"));

        given().contentType(ContentType.JSON).body(payload).when().post("/track/click").then().statusCode(200)
                .body("status", equalTo("tracked")).body("clickId", notNullValue());

        // Verify profile_view was persisted with metadata
        LinkClick click = LinkClick.find("targetId", profileId).firstResult();
        assertNotNull(click, "Profile view click should be persisted");
        assertEquals("profile_view", click.clickType);
        assertEquals(profileId, click.targetId);
        assertNull(click.categoryId, "Profile events should have NULL category_id");
        assertNotNull(click.metadata);
        assertEquals(profileId.toString(), click.getMetadataAsJson().getString("profile_id"));
        assertEquals("johndoe", click.getMetadataAsJson().getString("profile_username"));
        assertEquals("minimal", click.getMetadataAsJson().getString("template"));
    }

    /**
     * Test tracking profile_curated event with article metadata (Task I6.T4).
     */
    @Test
    public void testTrackProfileCuratedClick() {
        UUID profileId = UUID.randomUUID();
        UUID articleId = UUID.randomUUID();

        Map<String, Object> payload = Map.of("clickType", "profile_curated", "targetId", articleId.toString(),
                "targetUrl", "https://example.com/article", "metadata",
                Map.of("profile_id", profileId.toString(), "profile_username", "johndoe", "article_id",
                        articleId.toString(), "article_slot", "top_pick", "template", "minimal"));

        given().contentType(ContentType.JSON).body(payload).when().post("/track/click").then().statusCode(200)
                .body("status", equalTo("tracked"));

        // Verify profile_curated was persisted with full metadata
        LinkClick click = LinkClick.find("targetId", articleId).firstResult();
        assertNotNull(click, "Profile curated click should be persisted");
        assertEquals("profile_curated", click.clickType);
        assertEquals(articleId, click.targetId);
        assertEquals("https://example.com/article", click.targetUrl);
        assertNull(click.categoryId, "Profile events should have NULL category_id");
        assertNotNull(click.metadata);
        assertEquals(profileId.toString(), click.getMetadataAsJson().getString("profile_id"));
        assertEquals("johndoe", click.getMetadataAsJson().getString("profile_username"));
        assertEquals(articleId.toString(), click.getMetadataAsJson().getString("article_id"));
        assertEquals("top_pick", click.getMetadataAsJson().getString("article_slot"));
        assertEquals("minimal", click.getMetadataAsJson().getString("template"));
    }

    /**
     * Test that profile_curated is accepted by validation regex (Task I6.T4).
     */
    @Test
    public void testProfileCuratedValidation() {
        UUID articleId = UUID.randomUUID();

        Map<String, Object> payload = Map.of("clickType", "profile_curated", "targetId", articleId.toString(),
                "targetUrl", "https://example.com/article");

        // Should not return 400 (validation error)
        given().contentType(ContentType.JSON).body(payload).when().post("/track/click").then().statusCode(200);
    }

    /**
     * Test IP address sanitization for privacy (Policy F14.9).
     */
    @Test
    public void testIpAddressSanitization() {
        UUID targetId = UUID.randomUUID();

        Map<String, Object> payload = Map.of("clickType", "directory_site_click", "targetId", targetId.toString(),
                "targetUrl", "https://example.com");

        // Track click with X-Forwarded-For header
        given().contentType(ContentType.JSON).header("X-Forwarded-For", "192.168.1.100").body(payload).when()
                .post("/track/click").then().statusCode(200);

        // Verify IP was sanitized (last octet zeroed)
        LinkClick click = LinkClick.find("targetId", targetId).firstResult();
        assertNotNull(click);
        assertEquals("192.168.1.0", click.ipAddress, "IP address should have last octet zeroed");
    }

    /**
     * Test User-Agent truncation for privacy (Policy F14.9).
     */
    @Test
    public void testUserAgentTruncation() {
        UUID targetId = UUID.randomUUID();

        // Create a very long User-Agent string (>512 chars)
        String longUserAgent = "Mozilla/5.0 ".repeat(60); // ~660 chars

        Map<String, Object> payload = Map.of("clickType", "directory_site_click", "targetId", targetId.toString(),
                "targetUrl", "https://example.com");

        given().contentType(ContentType.JSON).header("User-Agent", longUserAgent).body(payload).when()
                .post("/track/click").then().statusCode(200);

        // Verify User-Agent was truncated to 512 chars
        LinkClick click = LinkClick.find("targetId", targetId).firstResult();
        assertNotNull(click);
        assertNotNull(click.userAgent);
        assertTrue(click.userAgent.length() <= 512, "User-Agent should be truncated to 512 characters");
    }

    /**
     * Test IPv6 address sanitization for privacy.
     */
    @Test
    public void testIpv6AddressSanitization() {
        UUID targetId = UUID.randomUUID();

        Map<String, Object> payload = Map.of("clickType", "directory_site_click", "targetId", targetId.toString(),
                "targetUrl", "https://example.com");

        // Track click with IPv6 address
        given().contentType(ContentType.JSON).header("X-Forwarded-For", "2001:0db8:85a3:0000:0000:8a2e:0370:7334")
                .body(payload).when().post("/track/click").then().statusCode(200);

        // Verify IPv6 was sanitized (last segment zeroed)
        LinkClick click = LinkClick.find("targetId", targetId).firstResult();
        assertNotNull(click);
        assertNotNull(click.ipAddress);
        assertTrue(click.ipAddress.endsWith(":0"), "IPv6 address should have last segment zeroed");
    }

    /**
     * Test profile click with invalid profile_id in metadata logs warning but succeeds.
     */
    @Test
    public void testProfileClickWithInvalidMetadata() {
        UUID articleId = UUID.randomUUID();

        Map<String, Object> payload = Map.of("clickType", "profile_curated", "targetId", articleId.toString(),
                "targetUrl", "https://example.com/article", "metadata", Map.of("profile_id", "not-a-uuid"));

        // Should still succeed (invalid metadata logged as warning, not error)
        given().contentType(ContentType.JSON).body(payload).when().post("/track/click").then().statusCode(200);

        LinkClick click = LinkClick.find("targetId", articleId).firstResult();
        assertNotNull(click);
        assertEquals("profile_curated", click.clickType);
        // Metadata still stored as-is in JSONB
        assertEquals("not-a-uuid", click.getMetadataAsJson().getString("profile_id"));
    }
}
