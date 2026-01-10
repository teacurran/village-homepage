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
        assertEquals(categoryId.toString(), click.metadata.getString("category_id"));
        assertEquals(false, click.metadata.getBoolean("is_bubbled"));
        assertEquals(2, click.metadata.getInteger("rank_in_category"));
        assertEquals(15, click.metadata.getInteger("score"));
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
        assertTrue(click.metadata.getBoolean("is_bubbled"));
        assertEquals(sourceCategoryId.toString(), click.metadata.getString("source_category_id"));
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
        assertEquals("programming", click.metadata.getString("category_slug"));
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
        assertEquals("electronics", click.metadata.getString("category"));
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
}
