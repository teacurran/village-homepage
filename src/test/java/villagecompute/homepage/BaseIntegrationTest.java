package villagecompute.homepage;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Abstract base class for all integration tests requiring PostgreSQL 17 + PostGIS database.
 *
 * <p>
 * This class provides:
 * <ul>
 * <li>Testcontainers PostgreSQL 17 with PostGIS extension (via application-test.properties)</li>
 * <li>@Transactional annotation ensures database rollback after each test</li>
 * <li>Helper methods for common entity assertions (assertEntityExists, assertEntityDeleted)</li>
 * <li>Lifecycle hooks for setup/teardown if needed by subclasses</li>
 * </ul>
 *
 * <p>
 * <b>Usage Example:</b>
 *
 * <pre>
 * &#64;QuarkusTest
 * public class UserServiceTest extends BaseIntegrationTest {
 *     &#64;Test
 *     &#64;TestTransaction
 *     public void testCreateUser() {
 *         User user = User.createAnonymous();
 *         assertEntityExists(User.class, user.id);
 *     }
 * }
 * </pre>
 *
 * <p>
 * <b>Database Configuration:</b> Tests use PostgreSQL 17 Alpine image via Testcontainers JDBC driver. PostGIS extension
 * is enabled via initialization script at db/init-test-postgis.sql. See application-test.properties for configuration
 * details.
 *
 * <p>
 * <b>Test Isolation:</b> Use &#64;TestTransaction annotation on test methods to ensure automatic rollback. This
 * prevents test pollution and eliminates need for manual cleanup code.
 *
 * <p>
 * <b>Ref:</b> Foundation Blueprint Section 3.5 (Testing Strategy), Task I1.T2
 *
 * @see io.quarkus.test.junit.QuarkusTest
 * @see jakarta.transaction.Transactional
 */
public abstract class BaseIntegrationTest {

    /**
     * Hook executed before each test method. Override in subclasses if custom setup is needed. Default implementation
     * does nothing.
     */
    @BeforeEach
    protected void setUp() {
        // Subclasses can override for custom setup
        // Database is automatically created by Testcontainers before this hook runs
    }

    /**
     * Hook executed after each test method. Override in subclasses if custom teardown is needed. Default implementation
     * does nothing.
     *
     * <p>
     * Note: If using @Transactional, database changes are automatically rolled back. Manual cleanup is usually not
     * required.
     */
    @AfterEach
    protected void tearDown() {
        // Subclasses can override for custom teardown
        // Database is automatically rolled back by @Transactional (if used)
    }

    /**
     * Asserts that an entity with the given ID exists in the database.
     *
     * <p>
     * Uses reflection to call the entity class's findById() static method. Fails the test if entity is not found.
     *
     * @param entityClass
     *            the entity class (must extend PanacheEntityBase)
     * @param id
     *            the entity ID (Long)
     * @param <T>
     *            entity type parameter
     * @throws AssertionError
     *             if entity does not exist
     */
    protected <T extends PanacheEntityBase> void assertEntityExists(Class<T> entityClass, Long id) {
        assertNotNull(id, "Entity ID must not be null");
        T entity = findEntityById(entityClass, id);
        assertNotNull(entity, "Entity of type " + entityClass.getSimpleName() + " with id " + id + " should exist");
    }

    /**
     * Asserts that an entity with the given UUID exists in the database.
     *
     * <p>
     * Uses reflection to call the entity class's findById() static method. Fails the test if entity is not found.
     *
     * @param entityClass
     *            the entity class (must extend PanacheEntityBase)
     * @param id
     *            the entity ID (UUID)
     * @param <T>
     *            entity type parameter
     * @throws AssertionError
     *             if entity does not exist
     */
    protected <T extends PanacheEntityBase> void assertEntityExists(Class<T> entityClass, UUID id) {
        assertNotNull(id, "Entity ID must not be null");
        T entity = findEntityById(entityClass, id);
        assertNotNull(entity, "Entity of type " + entityClass.getSimpleName() + " with id " + id + " should exist");
    }

    /**
     * Asserts that an entity with the given ID has been soft-deleted (deletedAt IS NOT NULL).
     *
     * <p>
     * This method uses reflection to access the deletedAt field on the entity. The entity must have a field named
     * "deletedAt" of type Instant for soft deletion tracking.
     *
     * @param entityClass
     *            the entity class (must extend PanacheEntityBase)
     * @param id
     *            the entity ID (Long)
     * @param <T>
     *            entity type parameter
     * @throws AssertionError
     *             if entity does not exist or deletedAt is null
     */
    protected <T extends PanacheEntityBase> void assertEntityDeleted(Class<T> entityClass, Long id) {
        assertNotNull(id, "Entity ID must not be null");
        T entity = findEntityById(entityClass, id);
        assertNotNull(entity, "Entity of type " + entityClass.getSimpleName() + " with id " + id + " should exist");

        Instant deletedAt = getDeletedAtField(entity);
        assertNotNull(deletedAt, "Entity of type " + entityClass.getSimpleName() + " with id " + id
                + " should be soft-deleted (deletedAt should not be null)");
    }

    /**
     * Asserts that an entity with the given UUID has been soft-deleted (deletedAt IS NOT NULL).
     *
     * <p>
     * This method uses reflection to access the deletedAt field on the entity. The entity must have a field named
     * "deletedAt" of type Instant for soft deletion tracking.
     *
     * @param entityClass
     *            the entity class (must extend PanacheEntityBase)
     * @param id
     *            the entity ID (UUID)
     * @param <T>
     *            entity type parameter
     * @throws AssertionError
     *             if entity does not exist or deletedAt is null
     */
    protected <T extends PanacheEntityBase> void assertEntityDeleted(Class<T> entityClass, UUID id) {
        assertNotNull(id, "Entity ID must not be null");
        T entity = findEntityById(entityClass, id);
        assertNotNull(entity, "Entity of type " + entityClass.getSimpleName() + " with id " + id + " should exist");

        Instant deletedAt = getDeletedAtField(entity);
        assertNotNull(deletedAt, "Entity of type " + entityClass.getSimpleName() + " with id " + id
                + " should be soft-deleted (deletedAt should not be null)");
    }

    // ==================== HTTP Response Assertion Methods ====================

    /**
     * Asserts that the HTTP response indicates an unauthorized request (401 Unauthorized).
     *
     * <p>
     * Verifies:
     * <ul>
     * <li>HTTP status code is 401 Unauthorized</li>
     * </ul>
     *
     * <p>
     * <b>Usage Example:</b>
     *
     * <pre>
     * Response response = given().when().get("/api/admin/users").then().extract().response();
     *
     * assertUnauthorized(response);
     * </pre>
     *
     * @param response
     *            the HTTP response to validate (from RestAssured)
     * @throws AssertionError
     *             if response status is not 401
     */
    protected void assertUnauthorized(Response response) {
        assertEquals(401, response.statusCode(),
                String.format("Expected 401 Unauthorized but got %d %s", response.statusCode(), response.statusLine()));
    }

    /**
     * Asserts that the HTTP response indicates a forbidden request (403 Forbidden).
     *
     * <p>
     * Verifies:
     * <ul>
     * <li>HTTP status code is 403 Forbidden</li>
     * </ul>
     *
     * <p>
     * <b>Usage Example:</b>
     *
     * <pre>
     * Response response = given().when().delete("/api/admin/users/123").then().extract().response();
     *
     * assertForbidden(response);
     * </pre>
     *
     * @param response
     *            the HTTP response to validate (from RestAssured)
     * @throws AssertionError
     *             if response status is not 403
     */
    protected void assertForbidden(Response response) {
        assertEquals(403, response.statusCode(),
                String.format("Expected 403 Forbidden but got %d %s", response.statusCode(), response.statusLine()));
    }

    /**
     * Asserts that the HTTP response indicates a server error (5xx status code).
     *
     * <p>
     * Verifies:
     * <ul>
     * <li>HTTP status code is in the 5xx range (500-599)</li>
     * </ul>
     *
     * <p>
     * <b>Usage Example:</b>
     *
     * <pre>
     * Response response = given().when().get("/api/external-service").then().extract().response();
     *
     * assertServerError(response);
     * </pre>
     *
     * @param response
     *            the HTTP response to validate (from RestAssured)
     * @throws AssertionError
     *             if response status is not in 5xx range
     */
    protected void assertServerError(Response response) {
        int statusCode = response.statusCode();
        assertTrue(statusCode >= 500 && statusCode <= 599,
                String.format("Expected 5xx Server Error but got %d %s", response.statusCode(), response.statusLine()));
    }

    /**
     * Asserts that the HTTP response indicates resource not found (404 Not Found).
     *
     * <p>
     * Verifies:
     * <ul>
     * <li>HTTP status code is 404 Not Found</li>
     * <li>Error message contains the expected text (case-insensitive partial match)</li>
     * </ul>
     *
     * <p>
     * <b>Usage Example:</b>
     *
     * <pre>
     * Response response = given().when().get("/api/users/999").then().extract().response();
     *
     * assertNotFound(response, "User not found");
     * </pre>
     *
     * @param response
     *            the HTTP response to validate (from RestAssured)
     * @param expectedMessage
     *            the expected error message (case-insensitive partial match)
     * @throws AssertionError
     *             if response status is not 404 or message doesn't match
     */
    protected void assertNotFound(Response response, String expectedMessage) {
        assertEquals(404, response.statusCode(),
                String.format("Expected 404 Not Found but got %d %s", response.statusCode(), response.statusLine()));

        String actualMessage = extractErrorMessage(response);
        assertTrue(actualMessage.toLowerCase().contains(expectedMessage.toLowerCase()), String
                .format("Expected 404 error message containing '%s' but got '%s'", expectedMessage, actualMessage));
    }

    /**
     * Asserts that the HTTP response indicates a validation error (400 Bad Request).
     *
     * <p>
     * Verifies:
     * <ul>
     * <li>HTTP status code is 400 Bad Request</li>
     * <li>Error message contains the expected text (case-insensitive partial match)</li>
     * </ul>
     *
     * <p>
     * This is the general validation error assertion method for cases where the error is not tied to a specific field.
     * For field-specific validation errors, use the overloaded method with field parameter.
     *
     * <p>
     * <b>Usage Example:</b>
     *
     * <pre>
     * Response response = given().contentType("application/json").body("{\"invalid\": \"json\"}").when()
     *         .post("/api/listings").then().extract().response();
     *
     * assertValidationError(response, "validation failed");
     * </pre>
     *
     * @param response
     *            the HTTP response to validate (from RestAssured)
     * @param expectedMessage
     *            the expected error message (case-insensitive partial match)
     * @throws AssertionError
     *             if response status is not 400 or message doesn't match
     */
    protected void assertValidationError(Response response, String expectedMessage) {
        assertEquals(400, response.statusCode(),
                String.format("Expected 400 Bad Request but got %d %s", response.statusCode(), response.statusLine()));

        String actualMessage = extractErrorMessage(response);
        assertTrue(actualMessage.toLowerCase().contains(expectedMessage.toLowerCase()), String.format(
                "Expected validation error message containing '%s' but got '%s'", expectedMessage, actualMessage));
    }

    /**
     * Asserts that the HTTP response indicates a validation error for a specific field (400 Bad Request).
     *
     * <p>
     * Verifies:
     * <ul>
     * <li>HTTP status code is 400 Bad Request</li>
     * <li>Error message contains the expected text (case-insensitive partial match)</li>
     * <li>If fieldErrors array is present, verifies the specific field has the expected error message</li>
     * </ul>
     *
     * <p>
     * This method handles both simple error responses and complex validation error responses with field-level errors.
     * For general validation errors not tied to a specific field, use the single-parameter overload.
     *
     * <p>
     * <b>Usage Example:</b>
     *
     * <pre>
     * Response response = given().contentType("application/json").body("{\"title\": \"\", \"categoryId\": null}").when()
     *         .post("/api/marketplace/listings").then().extract().response();
     *
     * assertValidationError(response, "title", "must not be blank");
     * assertValidationError(response, "categoryId", "must not be null");
     * </pre>
     *
     * @param response
     *            the HTTP response to validate (from RestAssured)
     * @param field
     *            the field name that should have a validation error
     * @param expectedMessage
     *            the expected error message for the field (case-insensitive partial match)
     * @throws AssertionError
     *             if response status is not 400, field is not found, or message doesn't match
     */
    protected void assertValidationError(Response response, String field, String expectedMessage) {
        assertEquals(400, response.statusCode(),
                String.format("Expected 400 Bad Request but got %d %s", response.statusCode(), response.statusLine()));

        // Try to extract field-specific error from fieldErrors array
        try {
            String fieldErrorMessage = response.jsonPath()
                    .getString("fieldErrors.find { it.field == '" + field + "' }.message");

            if (fieldErrorMessage != null) {
                assertTrue(fieldErrorMessage.toLowerCase().contains(expectedMessage.toLowerCase()),
                        String.format("Expected validation error for field '%s' containing '%s' but got '%s'", field,
                                expectedMessage, fieldErrorMessage));
                return;
            }
        } catch (Exception e) {
            // fieldErrors array not present or JSON parsing failed, fall back to general error message check
        }

        // Fallback: check general error message
        String actualMessage = extractErrorMessage(response);
        assertTrue(actualMessage.toLowerCase().contains(expectedMessage.toLowerCase()),
                String.format("Expected validation error for field '%s' containing '%s' but got '%s'", field,
                        expectedMessage, actualMessage));
    }

    /**
     * Asserts that the HTTP response indicates rate limit exceeded (429 Too Many Requests).
     *
     * <p>
     * Verifies:
     * <ul>
     * <li>HTTP status code is 429 Too Many Requests</li>
     * <li>Retry-After header is present</li>
     * <li>If expectedRetryAfterSeconds is greater than 0, verifies header value matches</li>
     * </ul>
     *
     * <p>
     * <b>Usage Example:</b>
     *
     * <pre>
     * Response response = given().when().get("/api/stocks/quote").then().extract().response();
     *
     * assertRateLimitExceeded(response, 60); // Expect Retry-After: 60
     * </pre>
     *
     * @param response
     *            the HTTP response to validate (from RestAssured)
     * @param expectedRetryAfterSeconds
     *            the expected Retry-After header value in seconds (0 = don't check value, just verify header exists)
     * @throws AssertionError
     *             if response status is not 429, Retry-After header is missing, or value doesn't match
     */
    protected void assertRateLimitExceeded(Response response, int expectedRetryAfterSeconds) {
        assertEquals(429, response.statusCode(), String.format("Expected 429 Too Many Requests but got %d %s",
                response.statusCode(), response.statusLine()));

        String retryAfterHeader = response.getHeader("Retry-After");
        assertNotNull(retryAfterHeader, "Expected Retry-After header to be present in 429 response");

        if (expectedRetryAfterSeconds > 0) {
            assertEquals(String.valueOf(expectedRetryAfterSeconds), retryAfterHeader,
                    String.format("Expected Retry-After header value '%d' but got '%s'", expectedRetryAfterSeconds,
                            retryAfterHeader));
        }
    }

    /**
     * Asserts that the HTTP response indicates success (2xx status code) and deserializes the response body.
     *
     * <p>
     * Verifies:
     * <ul>
     * <li>HTTP status code is in the 2xx range (200-299)</li>
     * <li>Response body can be deserialized to the expected type</li>
     * </ul>
     *
     * <p>
     * This method is useful for verifying successful API responses and extracting the response payload for further
     * assertions.
     *
     * <p>
     * <b>Usage Example:</b>
     *
     * <pre>
     * Response response = given().when().get("/api/users/123").then().extract().response();
     *
     * UserResponseType user = assertSuccessResponse(response, UserResponseType.class);
     * assertThat(user.email()).isEqualTo("test@example.com");
     * </pre>
     *
     * @param response
     *            the HTTP response to validate (from RestAssured)
     * @param expectedType
     *            the expected response body type
     * @param <T>
     *            the type parameter for deserialization
     * @return the deserialized response body
     * @throws AssertionError
     *             if response status is not 2xx or deserialization fails
     */
    protected <T> T assertSuccessResponse(Response response, Class<T> expectedType) {
        int statusCode = response.statusCode();
        assertTrue(statusCode >= 200 && statusCode <= 299, String.format("Expected 2xx success but got %d: %s",
                response.statusCode(), response.body().asString()));

        try {
            return response.as(expectedType);
        } catch (Exception e) {
            fail("Failed to deserialize response body to " + expectedType.getSimpleName() + ": " + e.getMessage()
                    + "\nResponse body: " + response.body().asString());
            return null; // Unreachable, fail() throws AssertionError
        }
    }

    /**
     * Asserts that the HTTP response indicates successful resource creation (201 Created).
     *
     * <p>
     * Verifies:
     * <ul>
     * <li>HTTP status code is 201 Created</li>
     * <li>If expectedLocationPattern is not null, verifies Location header matches the pattern</li>
     * </ul>
     *
     * <p>
     * <b>Usage Example:</b>
     *
     * <pre>
     * Response response = given().contentType("application/json").body(newUser).when().post("/api/users").then().extract()
     *         .response();
     *
     * assertCreated(response, "/api/users/");
     * </pre>
     *
     * @param response
     *            the HTTP response to validate (from RestAssured)
     * @param expectedLocationPattern
     *            the expected Location header pattern (null = don't check Location header)
     * @throws AssertionError
     *             if response status is not 201 or Location header doesn't match
     */
    protected void assertCreated(Response response, String expectedLocationPattern) {
        assertEquals(201, response.statusCode(),
                String.format("Expected 201 Created but got %d %s", response.statusCode(), response.statusLine()));

        if (expectedLocationPattern != null) {
            String locationHeader = response.getHeader("Location");
            assertNotNull(locationHeader, "Expected Location header to be present in 201 response");
            assertTrue(locationHeader.contains(expectedLocationPattern), String.format(
                    "Expected Location header to contain '%s' but got '%s'", expectedLocationPattern, locationHeader));
        }
    }

    /**
     * Asserts that the HTTP response indicates successful deletion or no content (204 No Content).
     *
     * <p>
     * Verifies:
     * <ul>
     * <li>HTTP status code is 204 No Content</li>
     * </ul>
     *
     * <p>
     * <b>Usage Example:</b>
     *
     * <pre>
     * Response response = given().when().delete("/api/users/123").then().extract().response();
     *
     * assertNoContent(response);
     * </pre>
     *
     * @param response
     *            the HTTP response to validate (from RestAssured)
     * @throws AssertionError
     *             if response status is not 204
     */
    protected void assertNoContent(Response response) {
        assertEquals(204, response.statusCode(),
                String.format("Expected 204 No Content but got %d %s", response.statusCode(), response.statusLine()));
    }

    // ==================== Private Helper Methods ====================

    /**
     * Extracts error message from HTTP response JSON.
     *
     * <p>
     * Handles multiple response formats:
     * <ul>
     * <li>Simple format: {"error": "message"}</li>
     * <li>Alternative format: {"message": "description"}</li>
     * <li>Standard format: {"error": "CODE", "message": "description"}</li>
     * </ul>
     *
     * <p>
     * If JSON parsing fails or no error/message field is found, returns the raw response body.
     *
     * @param response
     *            the HTTP response
     * @return extracted error message, or raw response body if extraction fails
     */
    private String extractErrorMessage(Response response) {
        try {
            // Try "error" field first (most common format)
            String error = response.jsonPath().getString("error");
            if (error != null && !error.isBlank()) {
                return error;
            }

            // Try "message" field (alternative format)
            String message = response.jsonPath().getString("message");
            if (message != null && !message.isBlank()) {
                return message;
            }

            // Fallback: return full body
            return response.body().asString();
        } catch (Exception e) {
            // JSON parsing failed, return raw body
            return response.body().asString();
        }
    }

    /**
     * Finds an entity by ID using reflection to call the entity class's static findById() method.
     *
     * <p>
     * This helper method works around Panache's static method enhancement by calling the entity class's findById()
     * method directly via reflection.
     *
     * @param entityClass
     *            the entity class
     * @param id
     *            the entity ID (Long or UUID)
     * @param <T>
     *            entity type parameter
     * @return the entity instance, or null if not found
     * @throws IllegalStateException
     *             if the entity class does not have a findById() method
     */
    @SuppressWarnings("unchecked")
    private <T extends PanacheEntityBase> T findEntityById(Class<T> entityClass, Object id) {
        try {
            Method findByIdMethod = entityClass.getMethod("findById", Object.class);
            return (T) findByIdMethod.invoke(null, id);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Cannot invoke findById() on entity " + entityClass.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Retrieves the deletedAt field value from an entity using reflection.
     *
     * <p>
     * This is a helper method for assertEntityDeleted(). Throws IllegalStateException if the entity does not have a
     * deletedAt field.
     *
     * @param entity
     *            the entity instance
     * @param <T>
     *            entity type parameter
     * @return the deletedAt timestamp, or null if not deleted
     * @throws IllegalStateException
     *             if entity class does not have a deletedAt field
     */
    private <T extends PanacheEntityBase> Instant getDeletedAtField(T entity) {
        try {
            Field deletedAtField = entity.getClass().getField("deletedAt");
            deletedAtField.setAccessible(true);
            return (Instant) deletedAtField.get(entity);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("Entity " + entity.getClass().getSimpleName()
                    + " does not have a public 'deletedAt' field for soft-delete tracking", e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Cannot access deletedAt field on entity " + entity.getClass().getSimpleName(), e);
        } catch (ClassCastException e) {
            throw new IllegalStateException(
                    "Entity " + entity.getClass().getSimpleName() + " deletedAt field is not of type Instant", e);
        }
    }
}
