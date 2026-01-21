package villagecompute.homepage.api.rest.profile;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.data.models.ReservedUsername;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.data.models.UserProfile;
import villagecompute.homepage.testing.H2TestResource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for ProfileResource (Feature F11).
 *
 * <p>
 * Tests cover:
 * <ul>
 * <li>Profile creation (authenticated, validation)</li>
 * <li>Profile retrieval (public/draft access control)</li>
 * <li>Profile updates (owner only)</li>
 * <li>Publish/unpublish workflow</li>
 * <li>Soft delete</li>
 * <li>Curated article management</li>
 * <li>Public profile page access</li>
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(H2TestResource.class)
public class ProfileResourceTest {

    private UUID testUserId;
    private String testUserIdString;

    @BeforeEach
    @Transactional
    public void setUp() {
        // Clean up test data
        UserProfile.delete("username LIKE 'testprofile%'");
        User.delete("email LIKE 'testprofile%'");

        // Create test user
        User testUser = new User();
        testUser.email = "testprofile" + System.currentTimeMillis() + "@example.com";
        testUser.isAnonymous = false;
        testUser.preferences = Map.of();
        testUser.createdAt = Instant.now();
        testUser.updatedAt = Instant.now();
        testUser.persist();
        testUserId = testUser.id;
        testUserIdString = testUserId.toString();

        // Ensure reserved names are seeded
        if (ReservedUsername.countAll() == 0) {
            ReservedUsername.seedDefaults();
        }
    }

    @AfterEach
    @Transactional
    public void tearDown() {
        UserProfile.delete("username LIKE 'testprofile%'");
        User.delete("id = ?1", testUserId);
    }

    // ========== Profile Creation Tests ==========

    @Test
    @TestSecurity(
            user = "testuser",
            roles = {"USER"})
    public void testCreateProfile_Success() {
        given().contentType(ContentType.JSON).body(Map.of("username", "testprofile123")).when().post("/api/profiles")
                .then().statusCode(201)
                .body("username", equalTo("testprofile123"), "is_published", equalTo(false), "view_count", equalTo(0));
    }

    @Test
    @TestSecurity(
            user = "testuser",
            roles = {"USER"})
    public void testCreateProfile_InvalidUsername_TooShort() {
        given().contentType(ContentType.JSON).body(Map.of("username", "ab")).when().post("/api/profiles").then()
                .statusCode(400).body("error", containsString("at least 3 characters"));
    }

    @Test
    @TestSecurity(
            user = "testuser",
            roles = {"USER"})
    public void testCreateProfile_InvalidUsername_InvalidCharacters() {
        given().contentType(ContentType.JSON).body(Map.of("username", "test user")).when().post("/api/profiles").then()
                .statusCode(400).body("error", containsString("letters, numbers, underscore, and dash"));
    }

    @Test
    @TestSecurity(
            user = "testuser",
            roles = {"USER"})
    public void testCreateProfile_ReservedUsername() {
        given().contentType(ContentType.JSON).body(Map.of("username", "admin")).when().post("/api/profiles").then()
                .statusCode(400).body("error", containsString("reserved"));
    }

    @Test
    @TestSecurity(
            user = "testuser",
            roles = {"USER"})
    @Transactional
    public void testCreateProfile_DuplicateUsername() {
        // Create first profile
        UserProfile.createProfile(testUserId, "testprofile123");

        // Try to create another with same username (different user)
        User user2 = new User();
        user2.email = "testprofile2" + System.currentTimeMillis() + "@example.com";
        user2.isAnonymous = false;
        user2.preferences = Map.of();
        user2.createdAt = Instant.now();
        user2.updatedAt = Instant.now();
        user2.persist();

        given().contentType(ContentType.JSON).body(Map.of("username", "testprofile123")).when().post("/api/profiles")
                .then().statusCode(409).body("error", containsString("already taken"));

        // Clean up
        User.delete("id = ?1", user2.id);
    }

    @Test
    @TestSecurity(
            user = "testuser",
            roles = {"USER"})
    @Transactional
    public void testCreateProfile_OnePerUser() {
        // Create first profile
        UserProfile.createProfile(testUserId, "testprofile123");

        // Try to create second profile for same user
        given().contentType(ContentType.JSON).body(Map.of("username", "testprofile456")).when().post("/api/profiles")
                .then().statusCode(409).body("error", containsString("already has a profile"));
    }

    @Test
    public void testCreateProfile_Unauthenticated() {
        given().contentType(ContentType.JSON).body(Map.of("username", "testprofile123")).when().post("/api/profiles")
                .then().statusCode(401);
    }

    // ========== Profile Retrieval Tests ==========

    @Test
    @TestSecurity(
            user = "testuser",
            roles = {"USER"})
    @Transactional
    public void testGetProfile_Owner_CanSeeDraft() {
        UserProfile profile = UserProfile.createProfile(testUserId, "testprofile123");

        given().when().get("/api/profiles/" + profile.id).then().statusCode(200).body("username",
                equalTo("testprofile123"), "is_published", equalTo(false));
    }

    @Test
    @TestSecurity(
            user = "otheruser",
            roles = {"USER"})
    @Transactional
    public void testGetProfile_NonOwner_CannotSeeDraft() {
        UserProfile profile = UserProfile.createProfile(testUserId, "testprofile123");

        given().when().get("/api/profiles/" + profile.id).then().statusCode(403);
    }

    @Test
    @Transactional
    public void testGetProfile_Public_CanSeePublished() {
        UserProfile profile = UserProfile.createProfile(testUserId, "testprofile123");
        profile.publish();

        given().when().get("/api/profiles/" + profile.id).then().statusCode(200).body("username",
                equalTo("testprofile123"), "is_published", equalTo(true));
    }

    @Test
    @Transactional
    public void testGetProfile_NotFound() {
        given().when().get("/api/profiles/" + UUID.randomUUID()).then().statusCode(404);
    }

    // ========== Profile Update Tests ==========

    @Test
    @TestSecurity(
            user = "testuser",
            roles = {"USER"})
    @Transactional
    public void testUpdateProfile_Owner() {
        UserProfile profile = UserProfile.createProfile(testUserId, "testprofile123");

        Map<String, Object> updates = Map.of("display_name", "Test Display Name", "bio", "Test bio", "location_text",
                "Test Location", "website_url", "https://example.com", "avatar_url", "https://avatar.example.com");

        given().contentType(ContentType.JSON).body(updates).when().put("/api/profiles/" + profile.id).then()
                .statusCode(200).body("display_name", equalTo("Test Display Name"), "bio", equalTo("Test bio"),
                        "location_text", equalTo("Test Location"), "website_url", equalTo("https://example.com"),
                        "avatar_url", equalTo("https://avatar.example.com"));
    }

    @Test
    @TestSecurity(
            user = "otheruser",
            roles = {"USER"})
    @Transactional
    public void testUpdateProfile_NonOwner() {
        UserProfile profile = UserProfile.createProfile(testUserId, "testprofile123");

        Map<String, Object> updates = Map.of("display_name", "Unauthorized Update");

        given().contentType(ContentType.JSON).body(updates).when().put("/api/profiles/" + profile.id).then()
                .statusCode(403);
    }

    @Test
    @TestSecurity(
            user = "testuser",
            roles = {"USER"})
    @Transactional
    public void testUpdateProfile_SocialLinks() {
        UserProfile profile = UserProfile.createProfile(testUserId, "testprofile123");

        Map<String, Object> socialLinks = Map.of("twitter", "https://twitter.com/test", "github",
                "https://github.com/test");
        Map<String, Object> updates = Map.of("social_links", socialLinks);

        given().contentType(ContentType.JSON).body(updates).when().put("/api/profiles/" + profile.id).then()
                .statusCode(200);
    }

    // ========== Publish/Unpublish Tests ==========

    @Test
    @TestSecurity(
            user = "testuser",
            roles = {"USER"})
    @Transactional
    public void testPublishProfile_Owner() {
        UserProfile profile = UserProfile.createProfile(testUserId, "testprofile123");
        assertFalse(profile.isPublished);

        given().when().put("/api/profiles/" + profile.id + "/publish").then().statusCode(200).body("is_published",
                equalTo(true));
    }

    @Test
    @TestSecurity(
            user = "otheruser",
            roles = {"USER"})
    @Transactional
    public void testPublishProfile_NonOwner() {
        UserProfile profile = UserProfile.createProfile(testUserId, "testprofile123");

        given().when().put("/api/profiles/" + profile.id + "/publish").then().statusCode(403);
    }

    @Test
    @TestSecurity(
            user = "testuser",
            roles = {"USER"})
    @Transactional
    public void testUnpublishProfile_Owner() {
        UserProfile profile = UserProfile.createProfile(testUserId, "testprofile123");
        profile.publish();

        given().when().put("/api/profiles/" + profile.id + "/unpublish").then().statusCode(200).body("is_published",
                equalTo(false));
    }

    // ========== Soft Delete Tests ==========

    @Test
    @TestSecurity(
            user = "testuser",
            roles = {"USER"})
    @Transactional
    public void testDeleteProfile_Owner() {
        UserProfile profile = UserProfile.createProfile(testUserId, "testprofile123");

        given().when().delete("/api/profiles/" + profile.id).then().statusCode(204);

        // Verify soft deleted
        UserProfile reloaded = UserProfile.findById(profile.id);
        assertNotNull(reloaded.deletedAt);
    }

    @Test
    @TestSecurity(
            user = "otheruser",
            roles = {"USER"})
    @Transactional
    public void testDeleteProfile_NonOwner() {
        UserProfile profile = UserProfile.createProfile(testUserId, "testprofile123");

        given().when().delete("/api/profiles/" + profile.id).then().statusCode(403);
    }

    // ========== Public Profile Page Tests ==========

    @Test
    @Transactional
    public void testPublicProfilePage_Published() {
        UserProfile profile = UserProfile.createProfile(testUserId, "testprofile123");
        profile.publish();

        given().when().get("/u/testprofile123").then().statusCode(200).contentType(ContentType.HTML);
    }

    @Test
    @Transactional
    public void testPublicProfilePage_Draft() {
        UserProfile.createProfile(testUserId, "testprofile123");

        // Unpublished profile should return 404
        given().when().get("/u/testprofile123").then().statusCode(404);
    }

    @Test
    public void testPublicProfilePage_NotFound() {
        given().when().get("/u/nonexistent").then().statusCode(404);
    }

    @Test
    @Transactional
    public void testPublicProfilePage_CaseInsensitive() {
        UserProfile profile = UserProfile.createProfile(testUserId, "testprofile123");
        profile.publish();

        // Should work with uppercase
        given().when().get("/u/TESTPROFILE123").then().statusCode(200);
    }

    // ========== Curated Article Tests ==========

    @Test
    @TestSecurity(
            user = "testuser",
            roles = {"USER"})
    @Transactional
    public void testAddArticle_Manual() {
        UserProfile profile = UserProfile.createProfile(testUserId, "testprofile123");

        Map<String, Object> article = Map.of("original_url", "https://example.com/article", "original_title",
                "Test Article", "original_description", "Test description", "original_image_url",
                "https://example.com/image.jpg");

        given().contentType(ContentType.JSON).body(article).when().post("/api/profiles/" + profile.id + "/articles")
                .then().statusCode(201).body("original_url", equalTo("https://example.com/article"), "original_title",
                        equalTo("Test Article"), "is_active", equalTo(true));
    }

    @Test
    @TestSecurity(
            user = "testuser",
            roles = {"USER"})
    @Transactional
    public void testAddArticle_BlankUrl() {
        UserProfile profile = UserProfile.createProfile(testUserId, "testprofile123");

        Map<String, Object> article = Map.of("original_url", "", "original_title", "Test Article");

        given().contentType(ContentType.JSON).body(article).when().post("/api/profiles/" + profile.id + "/articles")
                .then().statusCode(400);
    }

    @Test
    @TestSecurity(
            user = "otheruser",
            roles = {"USER"})
    @Transactional
    public void testAddArticle_NonOwner() {
        UserProfile profile = UserProfile.createProfile(testUserId, "testprofile123");

        Map<String, Object> article = Map.of("original_url", "https://example.com/article", "original_title",
                "Test Article");

        given().contentType(ContentType.JSON).body(article).when().post("/api/profiles/" + profile.id + "/articles")
                .then().statusCode(403);
    }

    // Helper method (using static import from JUnit)
    private static void assertFalse(boolean condition) {
        org.junit.jupiter.api.Assertions.assertFalse(condition);
    }

    private static void assertNotNull(Object object) {
        org.junit.jupiter.api.Assertions.assertNotNull(object);
    }
}
