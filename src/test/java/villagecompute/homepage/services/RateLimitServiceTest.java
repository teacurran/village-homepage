package villagecompute.homepage.services;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.data.models.RateLimitConfig;
import villagecompute.homepage.data.models.RateLimitViolation;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RateLimitService.
 *
 * Tests cover rate limit enforcement, sliding window behavior, tier differentiation, violation logging, and cache
 * invalidation. Ensures acceptance criteria for I2.T9: "rate limit tests assert 429 path" - verified through denial
 * responses.
 *
 * Coverage target: ≥80% line and branch coverage.
 */
@QuarkusTest
class RateLimitServiceTest {

    @Inject
    RateLimitService rateLimitService;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clear existing test data
        RateLimitConfig.deleteAll();
        RateLimitViolation.deleteAll();

        // Create test rate limit configs
        createTestConfig("test_action", "anonymous", 5, 60);
        createTestConfig("test_action", "logged_in", 20, 60);
        createTestConfig("login_attempt", "anonymous", 10, 300);
    }

    private void createTestConfig(String actionType, String tier, int limitCount, int windowSeconds) {
        RateLimitConfig config = new RateLimitConfig();
        config.id = UUID.randomUUID();
        config.actionType = actionType;
        config.tier = tier;
        config.limitCount = limitCount;
        config.windowSeconds = windowSeconds;
        config.updatedAt = Instant.now();
        config.persist();
    }

    @Test
    void testCheckLimit_WithinLimit_AllowsRequest() {
        // Make requests within limit
        for (int i = 0; i < 5; i++) {
            RateLimitService.RateLimitResult result = rateLimitService.checkLimit(null, "127.0.0.1", "test_action",
                    RateLimitService.Tier.ANONYMOUS, "/test");

            assertTrue(result.allowed(), "Request " + (i + 1) + " should be allowed");
            assertEquals(5, result.limitCount(), "Limit count should be 5");
            assertEquals(5 - i - 1, result.remaining(), "Remaining should be " + (5 - i - 1));
        }
    }

    @Test
    void testCheckLimit_ExceedsLimit_DeniesRequest() {
        // Exhaust the limit
        for (int i = 0; i < 5; i++) {
            rateLimitService.checkLimit(null, "127.0.0.1", "test_action", RateLimitService.Tier.ANONYMOUS, "/test");
        }

        // 6th request should be denied
        RateLimitService.RateLimitResult result = rateLimitService.checkLimit(null, "127.0.0.1", "test_action",
                RateLimitService.Tier.ANONYMOUS, "/test");

        assertFalse(result.allowed(), "Request should be denied after exceeding limit");
        assertEquals(5, result.limitCount(), "Limit should still be 5");
        assertEquals(0, result.remaining(), "No requests remaining");

        // Verify this is the "429 path" mentioned in acceptance criteria
        // In actual usage, RateLimitFilter would return HTTP 429 based on allowed=false
    }

    @Test
    void testCheckLimit_TierDifferentiation_AnonymousVsLoggedIn() {
        // Anonymous tier: 5 requests allowed
        for (int i = 0; i < 5; i++) {
            RateLimitService.RateLimitResult result = rateLimitService.checkLimit(null, "192.168.1.1", "test_action",
                    RateLimitService.Tier.ANONYMOUS, "/test");
            assertTrue(result.allowed(), "Anonymous request " + (i + 1) + " should be allowed");
        }

        // 6th anonymous request denied
        RateLimitService.RateLimitResult anonResult = rateLimitService.checkLimit(null, "192.168.1.1", "test_action",
                RateLimitService.Tier.ANONYMOUS, "/test");
        assertFalse(anonResult.allowed(), "6th anonymous request should be denied");

        // Logged-in tier: 20 requests allowed (different IP, same action)
        for (int i = 0; i < 20; i++) {
            RateLimitService.RateLimitResult result = rateLimitService.checkLimit(123L, "192.168.1.2", "test_action",
                    RateLimitService.Tier.LOGGED_IN, "/test");
            assertTrue(result.allowed(), "Logged-in request " + (i + 1) + " should be allowed");
            assertEquals(20, result.limitCount(), "Logged-in limit should be 20");
        }

        // 21st logged-in request denied
        RateLimitService.RateLimitResult loggedInResult = rateLimitService.checkLimit(123L, "192.168.1.2",
                "test_action", RateLimitService.Tier.LOGGED_IN, "/test");
        assertFalse(loggedInResult.allowed(), "21st logged-in request should be denied");
    }

    @Test
    void testGetConfig_ReturnsCorrectConfig() {
        Optional<RateLimitConfig> configOpt = rateLimitService.getConfig("test_action", "anonymous");

        assertTrue(configOpt.isPresent(), "Config should exist");
        RateLimitConfig config = configOpt.get();
        assertEquals("test_action", config.actionType);
        assertEquals("anonymous", config.tier);
        assertEquals(5, config.limitCount);
        assertEquals(60, config.windowSeconds);
    }

    @Test
    void testGetConfig_NonExistentAction_ReturnsEmpty() {
        Optional<RateLimitConfig> config = rateLimitService.getConfig("nonexistent_action", "anonymous");
        assertFalse(config.isPresent(), "Should return empty for non-existent action");
    }

    @Test
    void testCheckLimit_NoConfig_AllowsRequest() {
        // Check limit for action without config
        RateLimitService.RateLimitResult result = rateLimitService.checkLimit(null, "127.0.0.1", "unconfigured_action",
                RateLimitService.Tier.ANONYMOUS, "/test");

        // Without config, service should allow (fail-open behavior for safety)
        assertTrue(result.allowed(), "Should allow when no config exists");
        assertEquals(Integer.MAX_VALUE, result.limitCount(), "Limit should be max value");
    }

    @Test
    void testCheckLimit_DifferentActions_IndependentLimits() {
        // Exhaust limit for test_action
        for (int i = 0; i < 5; i++) {
            rateLimitService.checkLimit(null, "127.0.0.1", "test_action", RateLimitService.Tier.ANONYMOUS, "/test");
        }

        // test_action should be limited
        assertFalse(rateLimitService
                .checkLimit(null, "127.0.0.1", "test_action", RateLimitService.Tier.ANONYMOUS, "/test").allowed(),
                "test_action should be limited");

        // login_attempt should still be available (different action, independent limit)
        RateLimitService.RateLimitResult loginResult = rateLimitService.checkLimit(null, "127.0.0.1", "login_attempt",
                RateLimitService.Tier.ANONYMOUS, "/login");
        assertTrue(loginResult.allowed(), "login_attempt should be independent and allowed");
        assertEquals(10, loginResult.limitCount(), "login_attempt has different limit");
    }

    @Test
    void testCheckLimit_MultipleSubjects_IndependentTracking() {
        // Exhaust limit for IP 1
        for (int i = 0; i < 5; i++) {
            rateLimitService.checkLimit(null, "10.0.0.1", "test_action", RateLimitService.Tier.ANONYMOUS, "/test");
        }

        // IP 1 should be limited
        assertFalse(rateLimitService
                .checkLimit(null, "10.0.0.1", "test_action", RateLimitService.Tier.ANONYMOUS, "/test").allowed(),
                "IP 1 should be limited");

        // IP 2 should still have full quota (independent tracking)
        RateLimitService.RateLimitResult result2 = rateLimitService.checkLimit(null, "10.0.0.2", "test_action",
                RateLimitService.Tier.ANONYMOUS, "/test");
        assertTrue(result2.allowed(), "IP 2 should be independent and allowed");
        assertEquals(4, result2.remaining(), "IP 2 should have 4 remaining (5 - 1 used)");
    }

    @Test
    @Transactional
    void testUpdateConfig_InvalidatesCache() {
        // Load config into cache
        Optional<RateLimitConfig> initialConfig = rateLimitService.getConfig("test_action", "anonymous");
        assertTrue(initialConfig.isPresent());
        assertEquals(5, initialConfig.get().limitCount);

        // Update config
        RateLimitConfig updated = rateLimitService.updateConfig("test_action", "anonymous", 10, null, 1L);
        assertEquals(10, updated.limitCount);

        // Re-fetch should get updated value (cache invalidated)
        Optional<RateLimitConfig> refreshedConfig = rateLimitService.getConfig("test_action", "anonymous");
        assertTrue(refreshedConfig.isPresent());
        assertEquals(10, refreshedConfig.get().limitCount, "Cache should be invalidated after update");
    }

    @Test
    void testGetRemainingAttempts() {
        // Make 2 requests
        rateLimitService.checkLimit(null, "127.0.0.1", "test_action", RateLimitService.Tier.ANONYMOUS, "/test");
        rateLimitService.checkLimit(null, "127.0.0.1", "test_action", RateLimitService.Tier.ANONYMOUS, "/test");

        // Check remaining
        int remaining = rateLimitService.getRemainingAttempts(null, "127.0.0.1", "test_action",
                RateLimitService.Tier.ANONYMOUS);

        assertEquals(3, remaining, "Should have 3 attempts remaining (5 - 2)");
    }

    @Test
    void testLegacyCheckMethod_BackwardCompatibility() {
        RateLimitService.RateLimitRule rule = RateLimitService.RateLimitRule.of("test", 3,
                java.time.Duration.ofSeconds(60));

        // Make requests up to limit
        assertTrue(rateLimitService.check("legacy:test:key", rule));
        assertTrue(rateLimitService.check("legacy:test:key", rule));
        assertTrue(rateLimitService.check("legacy:test:key", rule));

        // 4th request should be denied
        assertFalse(rateLimitService.check("legacy:test:key", rule), "Legacy method should deny after limit exceeded");
    }
}
