package villagecompute.homepage.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.testing.PostgreSQLTestProfile;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Integration test for OpenAPI 3.0 specification generation.
 *
 * <p>
 * Validates that the OpenAPI spec is correctly generated and contains all expected endpoints, tags, and security
 * schemes.
 *
 * <p>
 * Task I6.T9: Generate comprehensive OpenAPI 3.0 documentation for all REST endpoints.
 */
@QuarkusTest
@TestProfile(PostgreSQLTestProfile.class)
public class OpenApiSpecTest {

    /**
     * Test that the OpenAPI spec endpoint is accessible and returns valid JSON.
     */
    @Test
    public void testOpenApiSpecEndpointReturnsJson() {
        Response response = given().accept("application/json").when().get("/q/openapi").then().statusCode(200)
                .contentType("application/json").extract().response();

        // Verify the response is valid JSON by parsing it
        Map<String, Object> spec = response.jsonPath().getMap("$");
        assertThat("OpenAPI spec should not be empty", spec, is(not(anEmptyMap())));
    }

    /**
     * Test that the OpenAPI spec has the correct version and structure.
     */
    @Test
    public void testOpenApiSpecVersionAndStructure() {
        given().accept("application/json").when().get("/q/openapi").then().statusCode(200)
                .body("openapi", equalTo("3.1.0")).body("info.title", equalTo("Village Homepage API"))
                .body("info.version", equalTo("1.0.0"))
                .body("info.contact.email", equalTo("tcurran@villagecompute.com"))
                .body("info.contact.name", equalTo("Village Compute"));
    }

    /**
     * Test that the OpenAPI spec contains all expected tags.
     */
    @Test
    public void testOpenApiSpecContainsExpectedTags() {
        Response response = given().accept("application/json").when().get("/q/openapi").then().statusCode(200).extract()
                .response();

        List<Map<String, String>> tags = response.jsonPath().getList("tags");
        List<String> tagNames = tags.stream().map(tag -> tag.get("name")).toList();

        // Verify all expected tags are present
        assertThat(tagNames,
                hasItems("Authentication", "Marketplace", "Directory", "Widgets", "Notifications", "Social", "Profile",
                        "GDPR", "Admin - Feature Flags", "Admin - Rate Limits", "Admin - Moderation", "Admin - Users",
                        "Admin - Categories", "Admin - Analytics", "Admin - Payments", "Admin - System", "Health"));
    }

    /**
     * Test that the OpenAPI spec contains security schemes.
     */
    @Test
    public void testOpenApiSpecContainsSecuritySchemes() {
        given().accept("application/json").when().get("/q/openapi").then().statusCode(200)
                .body("components.securitySchemes.bearerAuth.type", equalTo("http"))
                .body("components.securitySchemes.bearerAuth.scheme", equalTo("bearer"))
                .body("components.securitySchemes.bearerAuth.bearerFormat", equalTo("JWT"))
                .body("components.securitySchemes.anonymousCookie.type", equalTo("apiKey"))
                .body("components.securitySchemes.anonymousCookie.name", equalTo("vu_anon_id"))
                .body("components.securitySchemes.anonymousCookie.in", equalTo("cookie"));
    }

    /**
     * Test that the OpenAPI spec contains authentication endpoints.
     */
    @Test
    public void testOpenApiSpecContainsAuthenticationEndpoints() {
        Response response = given().accept("application/json").when().get("/q/openapi").then().statusCode(200).extract()
                .response();

        Map<String, Object> paths = response.jsonPath().getMap("paths");

        // Verify key authentication endpoints are documented
        assertThat(paths, hasKey("/api/auth/anonymous"));
        assertThat(paths, hasKey("/api/auth/login/{provider}"));
        assertThat(paths, hasKey("/api/auth/bootstrap"));
        assertThat(paths, hasKey("/api/auth/logout"));
        assertThat(paths, hasKey("/api/auth/google/login"));
        assertThat(paths, hasKey("/api/auth/google/callback"));
        assertThat(paths, hasKey("/api/auth/facebook/login"));
        assertThat(paths, hasKey("/api/auth/facebook/callback"));
        assertThat(paths, hasKey("/api/auth/apple/login"));
        assertThat(paths, hasKey("/api/auth/apple/callback"));
    }

    /**
     * Test that the OpenAPI spec contains marketplace endpoints.
     */
    @Test
    public void testOpenApiSpecContainsMarketplaceEndpoints() {
        Response response = given().accept("application/json").when().get("/q/openapi").then().statusCode(200).extract()
                .response();

        Map<String, Object> paths = response.jsonPath().getMap("paths");

        // Verify key marketplace endpoints are documented
        assertThat(paths, hasKey("/api/marketplace/listings"));
        assertThat(paths, hasKey("/api/marketplace/listings/{id}"));
        assertThat(paths, hasKey("/api/marketplace/listings/{id}/publish"));
    }

    /**
     * Test that the OpenAPI spec contains directory endpoints.
     */
    @Test
    public void testOpenApiSpecContainsDirectoryEndpoints() {
        Response response = given().accept("application/json").when().get("/q/openapi").then().statusCode(200).extract()
                .response();

        Map<String, Object> paths = response.jsonPath().getMap("paths");

        // Verify key directory endpoints are documented
        assertThat(paths, hasKey("/good-sites"));
        assertThat(paths, hasKey("/good-sites/{slug}"));
        assertThat(paths, hasKey("/good-sites/site/{id}"));
        assertThat(paths, hasKey("/good-sites/search"));
        assertThat(paths, hasKey("/good-sites/api/vote"));
    }

    /**
     * Test that the OpenAPI spec contains admin endpoints.
     */
    @Test
    public void testOpenApiSpecContainsAdminEndpoints() {
        Response response = given().accept("application/json").when().get("/q/openapi").then().statusCode(200).extract()
                .response();

        Map<String, Object> paths = response.jsonPath().getMap("paths");

        // Verify key admin endpoints are documented
        assertThat(paths, hasKey("/admin/api/feature-flags"));
        assertThat(paths, hasKey("/admin/api/feature-flags/{key}"));
        assertThat(paths, hasKey("/admin/api/rate-limits"));
    }

    /**
     * Test that the OpenAPI spec documents servers.
     */
    @Test
    public void testOpenApiSpecContainsServers() {
        Response response = given().accept("application/json").when().get("/q/openapi").then().statusCode(200).extract()
                .response();

        List<Map<String, String>> servers = response.jsonPath().getList("servers");
        List<String> serverUrls = servers.stream().map(server -> server.get("url")).toList();

        // Verify all expected servers are present
        assertThat(serverUrls, hasItems("https://homepage.villagecompute.com",
                "https://homepage-beta.villagecompute.com", "http://localhost:8080"));
    }

    /**
     * Test that authenticated endpoints have security requirements.
     */
    @Test
    public void testAuthenticatedEndpointsHaveSecurityRequirements() {
        Response response = given().accept("application/json").when().get("/q/openapi").then().statusCode(200).extract()
                .response();

        // Check that the POST /api/marketplace/listings endpoint requires bearerAuth
        Map<String, Object> createListingEndpoint = response.jsonPath()
                .getMap("paths.'/api/marketplace/listings'.post");

        assertThat("Create listing endpoint should exist", createListingEndpoint, is(not(nullValue())));

        // Verify security requirement is present
        List<Map<String, List<String>>> security = (List<Map<String, List<String>>>) createListingEndpoint
                .get("security");
        assertThat("Create listing should have security requirements", security, is(not(nullValue())));

        // Verify bearerAuth is required
        boolean hasBearerAuth = security.stream().anyMatch(req -> req.containsKey("bearerAuth"));
        assertThat("Create listing should require bearerAuth", hasBearerAuth, is(true));
    }

    /**
     * Test that the OpenAPI spec includes response schemas.
     */
    @Test
    public void testOpenApiSpecIncludesResponseSchemas() {
        Response response = given().accept("application/json").when().get("/q/openapi").then().statusCode(200).extract()
                .response();

        Map<String, Object> components = response.jsonPath().getMap("components");

        // Verify components section exists
        assertThat("Components should exist", components, is(not(nullValue())));

        // Verify schemas exist
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        assertThat("Schemas should exist", schemas, is(not(nullValue())));
        assertThat("Schemas should not be empty", schemas, is(not(anEmptyMap())));
    }

    /**
     * Test that error responses are documented.
     */
    @Test
    public void testErrorResponsesAreDocumented() {
        Response response = given().accept("application/json").when().get("/q/openapi").then().statusCode(200).extract()
                .response();

        // Check POST /api/auth/anonymous endpoint for error responses
        Map<String, Object> anonymousEndpoint = response.jsonPath().getMap("paths.'/api/auth/anonymous'.post");

        assertThat("Anonymous endpoint should exist", anonymousEndpoint, is(not(nullValue())));

        Map<String, Object> responses = (Map<String, Object>) anonymousEndpoint.get("responses");
        assertThat("Responses should exist", responses, is(not(nullValue())));

        // Verify both success and error responses are documented
        assertThat("Should document 200 response", responses, hasKey("200"));
        assertThat("Should document 500 response", responses, hasKey("500"));
    }
}
