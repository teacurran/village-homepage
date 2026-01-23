package villagecompute.homepage.services;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.data.models.OAuthState;
import villagecompute.homepage.testing.H2TestResource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for OAuth state management (CSRF protection).
 *
 * <p>
 * Tests cover state generation, validation, expiration, and single-use enforcement. Verifies Policy P9 (OAuth CSRF
 * Protection) requirements.
 *
 * <p>
 * Acceptance Criteria (I3.T6):
 * <ul>
 * <li>State parameter is cryptographically random (UUID v4)</li>
 * <li>State expires after 5 minutes</li>
 * <li>Callback rejects invalid or expired state with 403 Forbidden (SecurityException)</li>
 * <li>State deleted after successful validation (single-use)</li>
 * <li>Integration test verifies state validation logic</li>
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(H2TestResource.class)
class OAuthStateManagementTest {

    @Inject
    OAuthService oauthService;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up any existing OAuth states from previous tests
        OAuthState.deleteAll();
    }

    @Test
    @Transactional
    void testStateGenerationIsCryptographicallyRandom() {
        // Generate multiple state tokens
        String state1 = oauthService.generateState("session-1", "google");
        String state2 = oauthService.generateState("session-2", "google");
        String state3 = oauthService.generateState("session-3", "google");

        // Verify all tokens are unique (UUID v4 provides 122 bits of entropy)
        assertNotEquals(state1, state2);
        assertNotEquals(state2, state3);
        assertNotEquals(state1, state3);

        // Verify tokens are valid UUIDs
        assertDoesNotThrow(() -> UUID.fromString(state1));
        assertDoesNotThrow(() -> UUID.fromString(state2));
        assertDoesNotThrow(() -> UUID.fromString(state3));
    }

    @Test
    @Transactional
    void testStateExpiresAfterFiveMinutes() {
        String sessionId = "test-session";
        String state = oauthService.generateState(sessionId, "google");

        // Verify state was persisted with correct expiration
        OAuthState stateRecord = OAuthState.findByStateAndProvider(state, "google")
                .orElseThrow(() -> new AssertionError("State should be persisted"));

        // Calculate expected expiration (5 minutes from now, with 1-second tolerance)
        Instant expectedExpiration = Instant.now().plus(5, ChronoUnit.MINUTES);
        long diffSeconds = Math.abs(ChronoUnit.SECONDS.between(stateRecord.expiresAt, expectedExpiration));

        assertTrue(diffSeconds <= 1, "State should expire in 5 minutes (difference: " + diffSeconds + " seconds)");

        // Verify state fields
        assertEquals(state, stateRecord.state);
        assertEquals(sessionId, stateRecord.sessionId);
        assertEquals("google", stateRecord.provider);
        assertNotNull(stateRecord.createdAt);
    }

    @Test
    @Transactional
    void testValidationSucceedsForValidState() {
        String sessionId = "test-session";
        String state = oauthService.generateState(sessionId, "google");

        // Validate state
        String validatedSessionId = oauthService.validateState(state, "google");

        // Verify session ID matches
        assertEquals(sessionId, validatedSessionId);

        // Verify state was deleted (single-use)
        assertTrue(OAuthState.findByStateAndProvider(state, "google").isEmpty(),
                "State should be deleted after validation");
    }

    @Test
    @Transactional
    void testValidationFailsForExpiredState() {
        // Manually create expired state
        OAuthState expiredState = new OAuthState();
        expiredState.state = UUID.randomUUID().toString();
        expiredState.sessionId = "test-session";
        expiredState.provider = "google";
        expiredState.createdAt = Instant.now().minus(10, ChronoUnit.MINUTES);
        expiredState.expiresAt = Instant.now().minus(5, ChronoUnit.MINUTES); // Expired 5 minutes ago
        expiredState.persist();

        // Attempt to validate expired state
        SecurityException exception = assertThrows(SecurityException.class, () -> {
            oauthService.validateState(expiredState.state, "google");
        });

        assertEquals("Invalid or expired state token", exception.getMessage());

        // Verify expired state was NOT deleted (query filters by expiresAt > CURRENT_TIMESTAMP)
        // The named query won't find it because it checks expiresAt > CURRENT_TIMESTAMP
        assertTrue(OAuthState.findByStateAndProvider(expiredState.state, "google").isEmpty(),
                "Named query should filter out expired state");

        // But state still exists in database (cleanup job will delete it)
        long count = OAuthState.count("state = ?1", expiredState.state);
        assertEquals(1, count, "Expired state should still exist in database for cleanup job");
    }

    @Test
    @Transactional
    void testValidationFailsForInvalidState() {
        String nonExistentState = UUID.randomUUID().toString();

        // Attempt to validate non-existent state
        SecurityException exception = assertThrows(SecurityException.class, () -> {
            oauthService.validateState(nonExistentState, "google");
        });

        assertEquals("Invalid or expired state token", exception.getMessage());
    }

    @Test
    @Transactional
    void testValidationFailsForWrongProvider() {
        String sessionId = "test-session";
        String state = oauthService.generateState(sessionId, "google");

        // Attempt to validate with wrong provider
        SecurityException exception = assertThrows(SecurityException.class, () -> {
            oauthService.validateState(state, "facebook");
        });

        assertEquals("Invalid or expired state token", exception.getMessage());

        // Verify state was NOT deleted (validation failed)
        assertTrue(OAuthState.findByStateAndProvider(state, "google").isPresent(),
                "State should NOT be deleted when validation fails");
    }

    @Test
    @Transactional
    void testStateIsSingleUse() {
        String sessionId = "test-session";
        String state = oauthService.generateState(sessionId, "google");

        // First validation succeeds
        String validatedSessionId = oauthService.validateState(state, "google");
        assertEquals(sessionId, validatedSessionId);

        // Second validation fails (state was deleted)
        SecurityException exception = assertThrows(SecurityException.class, () -> {
            oauthService.validateState(state, "google");
        });

        assertEquals("Invalid or expired state token", exception.getMessage());
    }

    @Test
    @Transactional
    void testMultipleStatesForDifferentProviders() {
        String sessionId = "test-session";

        // Generate states for different providers
        String googleState = oauthService.generateState(sessionId, "google");
        String facebookState = oauthService.generateState(sessionId, "facebook");
        String appleState = oauthService.generateState(sessionId, "apple");

        // Verify all states are unique
        assertNotEquals(googleState, facebookState);
        assertNotEquals(facebookState, appleState);
        assertNotEquals(googleState, appleState);

        // Validate each state with correct provider
        assertEquals(sessionId, oauthService.validateState(googleState, "google"));
        assertEquals(sessionId, oauthService.validateState(facebookState, "facebook"));
        assertEquals(sessionId, oauthService.validateState(appleState, "apple"));

        // Verify all states were deleted
        assertTrue(OAuthState.findByStateAndProvider(googleState, "google").isEmpty());
        assertTrue(OAuthState.findByStateAndProvider(facebookState, "facebook").isEmpty());
        assertTrue(OAuthState.findByStateAndProvider(appleState, "apple").isEmpty());
    }

    @Test
    @Transactional
    void testCleanupJobDeletesExpiredStates() {
        // Create multiple expired states
        for (int i = 0; i < 5; i++) {
            OAuthState expiredState = new OAuthState();
            expiredState.state = UUID.randomUUID().toString();
            expiredState.sessionId = "session-" + i;
            expiredState.provider = "google";
            expiredState.createdAt = Instant.now().minus(10, ChronoUnit.MINUTES);
            expiredState.expiresAt = Instant.now().minus(5, ChronoUnit.MINUTES);
            expiredState.persist();
        }

        // Create one valid state (not expired)
        OAuthState validState = new OAuthState();
        validState.state = UUID.randomUUID().toString();
        validState.sessionId = "valid-session";
        validState.provider = "google";
        validState.createdAt = Instant.now();
        validState.expiresAt = Instant.now().plus(5, ChronoUnit.MINUTES);
        validState.persist();

        // Verify we have 6 states total
        assertEquals(6, OAuthState.count());

        // Simulate cleanup job
        long deleted = OAuthState.delete("expiresAt < ?1", Instant.now());

        // Verify 5 expired states were deleted
        assertEquals(5, deleted);

        // Verify valid state still exists
        assertEquals(1, OAuthState.count());
        assertTrue(OAuthState.findByStateAndProvider(validState.state, "google").isPresent());
    }
}
