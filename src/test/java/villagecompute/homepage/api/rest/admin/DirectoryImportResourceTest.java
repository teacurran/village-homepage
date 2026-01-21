package villagecompute.homepage.api.rest.admin;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.data.models.DirectoryAiSuggestion;
import villagecompute.homepage.data.models.DirectoryCategory;
import villagecompute.homepage.data.models.User;

import java.math.BigDecimal;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for DirectoryImportResource REST endpoints.
 */
@QuarkusTest
public class DirectoryImportResourceTest {

    private UUID testUserId;
    private UUID testCategoryId;

    @BeforeEach
    @Transactional
    public void setup() {
        // Create test user
        User testUser = User.findByEmail("test@example.com").orElseGet(() -> {
            User u = new User();
            u.email = "test@example.com";
            u.adminRole = User.ROLE_SUPER_ADMIN;
            u.persist();
            return u;
        });
        testUserId = testUser.id;

        // Create test category
        DirectoryCategory category = DirectoryCategory.findBySlug("computers-internet").orElseGet(() -> {
            DirectoryCategory c = new DirectoryCategory();
            c.name = "Computers & Internet";
            c.slug = "computers-internet";
            c.sortOrder = 1;
            c.isActive = true;
            c.linkCount = 0;
            c.persist();
            return c;
        });
        testCategoryId = category.id;

        // Clean up test suggestions
        DirectoryAiSuggestion.delete("uploadedByUserId = ?1", testUserId);
    }

    @Test
    @TestSecurity(
            user = "test@example.com",
            roles = "super_admin")
    public void testListSuggestions_empty() {
        given().when().get("/admin/api/directory/import/suggestions").then().statusCode(200)
                .contentType(ContentType.JSON).body("$", hasSize(0));
    }

    @Test
    @TestSecurity(
            user = "test@example.com",
            roles = "super_admin")
    @Transactional
    public void testListSuggestions_withData() {
        // Create test suggestion
        DirectoryAiSuggestion suggestion = new DirectoryAiSuggestion();
        suggestion.url = "https://github.com/";
        suggestion.domain = "github.com";
        suggestion.title = "GitHub";
        suggestion.description = "Code hosting platform";
        suggestion.status = "pending";
        suggestion.uploadedByUserId = testUserId;
        suggestion.suggestedCategoryIds = new UUID[]{testCategoryId};
        suggestion.confidence = BigDecimal.valueOf(0.85);
        suggestion.reasoning = "Programming-related content";
        suggestion.persist();

        given().when().get("/admin/api/directory/import/suggestions?status=pending").then().statusCode(200)
                .contentType(ContentType.JSON).body("$", hasSize(greaterThanOrEqualTo(1)))
                .body("[0].url", equalTo("https://github.com/")).body("[0].title", equalTo("GitHub"))
                .body("[0].status", equalTo("pending")).body("[0].confidence", notNullValue())
                .body("[0].aiSuggested", notNullValue());
    }

    @Test
    @TestSecurity(
            user = "test@example.com",
            roles = "super_admin")
    @Transactional
    public void testGetSuggestion_found() {
        // Create test suggestion
        DirectoryAiSuggestion suggestion = new DirectoryAiSuggestion();
        suggestion.url = "https://example.com/";
        suggestion.domain = "example.com";
        suggestion.title = "Example";
        suggestion.status = "pending";
        suggestion.uploadedByUserId = testUserId;
        suggestion.suggestedCategoryIds = new UUID[]{testCategoryId};
        suggestion.confidence = BigDecimal.valueOf(0.75);
        suggestion.persist();

        given().when().get("/admin/api/directory/import/suggestions/" + suggestion.id).then().statusCode(200)
                .contentType(ContentType.JSON).body("id", equalTo(suggestion.id.toString()))
                .body("url", equalTo("https://example.com/")).body("title", equalTo("Example"))
                .body("aiSuggested", hasSize(1));
    }

    @Test
    @TestSecurity(
            user = "test@example.com",
            roles = "super_admin")
    public void testGetSuggestion_notFound() {
        UUID randomId = UUID.randomUUID();

        given().when().get("/admin/api/directory/import/suggestions/" + randomId).then().statusCode(404).body("error",
                equalTo("Suggestion not found"));
    }

    @Test
    @TestSecurity(
            user = "test@example.com",
            roles = "super_admin")
    @Transactional
    public void testRejectSuggestion_success() {
        // Create test suggestion
        DirectoryAiSuggestion suggestion = new DirectoryAiSuggestion();
        suggestion.url = "https://reject-test.com/";
        suggestion.domain = "reject-test.com";
        suggestion.title = "Reject Test";
        suggestion.status = "pending";
        suggestion.uploadedByUserId = testUserId;
        suggestion.persist();

        given().when().post("/admin/api/directory/import/suggestions/" + suggestion.id + "/reject").then()
                .statusCode(200).body("status", equalTo("rejected")).body("id", equalTo(suggestion.id.toString()));

        // Verify status updated
        DirectoryAiSuggestion updated = DirectoryAiSuggestion.findById(suggestion.id);
        assertEquals("rejected", updated.status);
    }

    @Test
    @TestSecurity(
            user = "test@example.com",
            roles = "super_admin")
    @Transactional
    public void testRejectSuggestion_alreadyProcessed() {
        // Create already-rejected suggestion
        DirectoryAiSuggestion suggestion = new DirectoryAiSuggestion();
        suggestion.url = "https://already-rejected.com/";
        suggestion.domain = "already-rejected.com";
        suggestion.title = "Already Rejected";
        suggestion.status = "rejected";
        suggestion.uploadedByUserId = testUserId;
        suggestion.reviewedByUserId = testUserId;
        suggestion.persist();

        given().when().post("/admin/api/directory/import/suggestions/" + suggestion.id + "/reject").then()
                .statusCode(400).body("error", equalTo("Suggestion already processed"));
    }

    @Test
    @TestSecurity(
            user = "unauthorized@example.com",
            roles = "user")
    public void testListSuggestions_unauthorized() {
        // Regular user should not have access
        given().when().get("/admin/api/directory/import/suggestions").then().statusCode(403);
    }

    private void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected: " + expected + ", but got: " + actual);
        }
    }
}
