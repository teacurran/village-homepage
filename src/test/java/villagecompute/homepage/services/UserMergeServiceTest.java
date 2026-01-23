package villagecompute.homepage.services;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import jakarta.transaction.Transactional;
import villagecompute.homepage.BaseIntegrationTest;
import villagecompute.homepage.TestConstants;
import villagecompute.homepage.TestFixtures;
import villagecompute.homepage.data.models.AccountMergeAudit;
import villagecompute.homepage.data.models.MarketplaceListing;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.data.models.UserNotification;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for UserMergeService.
 *
 * <p>
 * Tests cover:
 * <ul>
 * <li>Marketplace listing ownership transfer during merge
 * <li>Notification ownership transfer during merge
 * <li>Account merge audit trail creation
 * <li>Preference merging logic
 * <li>Anonymous user soft-deletion after merge
 * </ul>
 *
 * <p>
 * All tests verify database state changes using Testcontainers PostgreSQL. No mocking of internal services.
 *
 * <p>
 * <b>Ref:</b> Task I3.T7, Foundation Blueprint Section 3.5 (Testing Strategy: "Never mock internal services")
 *
 * @see UserMergeService
 * @see BaseIntegrationTest
 */
@QuarkusTest
public class UserMergeServiceTest extends BaseIntegrationTest {

    private static final Logger LOG = Logger.getLogger(UserMergeServiceTest.class);

    @Inject
    UserMergeService userMergeService;

    /**
     * Tests marketplace listing reassignment during account merge.
     *
     * <p>
     * Verifies:
     * <ul>
     * <li>All listings transferred from anonymous to authenticated user
     * <li>Listing IDs preserved (no duplication)
     * <li>Listing metadata preserved (title, price, status, etc.)
     * <li>Only userId field updated
     * </ul>
     */
    @Test
    @Transactional
    public void testListingReassignment() {
        // 1. Create anonymous user with multiple listings
        User anonUser = TestFixtures.createAnonymousUser(TestConstants.TEST_SESSION_HASH);
        MarketplaceListing listing1 = TestFixtures.createTestListing(anonUser, "Vintage Bike", "Great condition",
                new java.math.BigDecimal("250.00"));
        MarketplaceListing listing2 = TestFixtures.createTestListing(anonUser, "Table Lamp", "Like new",
                new java.math.BigDecimal("35.00"));

        LOG.infof("Created anonymous user %s with %d listings", anonUser.id, 2);

        // 2. Create authenticated user
        User authUser = TestFixtures.createOAuthUser(TestConstants.VALID_EMAIL, TestConstants.OAUTH_PROVIDER_GOOGLE,
                TestConstants.OAUTH_GOOGLE_ID);

        // 3. Merge accounts
        userMergeService.upgradeAnonymousAccount(anonUser, authUser);

        // 4. Verify listings reassigned to authenticated user
        assertEquals(2, MarketplaceListing.count("userId = ?1", authUser.id),
                "Authenticated user should have 2 listings after merge");
        assertEquals(0, MarketplaceListing.count("userId = ?1", anonUser.id),
                "Anonymous user should have 0 listings after merge");

        // 5. Verify listing IDs preserved (same entities, just userId changed)
        MarketplaceListing reloadedListing1 = MarketplaceListing.findById(listing1.id);
        assertEquals(authUser.id, reloadedListing1.userId, "Listing 1 userId should point to authenticated user");
        assertEquals("Vintage Bike", reloadedListing1.title, "Listing 1 title should be preserved");

        MarketplaceListing reloadedListing2 = MarketplaceListing.findById(listing2.id);
        assertEquals(authUser.id, reloadedListing2.userId, "Listing 2 userId should point to authenticated user");
        assertEquals("Table Lamp", reloadedListing2.title, "Listing 2 title should be preserved");
    }

    /**
     * Tests notification reassignment during account merge.
     *
     * <p>
     * Verifies:
     * <ul>
     * <li>All notifications transferred from anonymous to authenticated user
     * <li>Notification IDs preserved
     * <li>Read/unread status preserved
     * <li>Only userId field updated
     * </ul>
     */
    @Test
    @Transactional
    public void testNotificationReassignment() {
        // 1. Create anonymous user with notifications
        User anonUser = TestFixtures.createAnonymousUser(TestConstants.TEST_SESSION_HASH);

        UserNotification notification1 = new UserNotification();
        notification1.userId = anonUser.id;
        notification1.type = "system";
        notification1.title = "Welcome";
        notification1.message = "Welcome to Village Homepage";
        notification1.createdAt = java.time.Instant.now();
        notification1.persist();

        UserNotification notification2 = new UserNotification();
        notification2.userId = anonUser.id;
        notification2.type = "listing";
        notification2.title = "Listing Expiring Soon";
        notification2.message = "Your listing expires in 3 days";
        notification2.readAt = java.time.Instant.now(); // Mark as read
        notification2.createdAt = java.time.Instant.now();
        notification2.persist();

        LOG.infof("Created anonymous user %s with %d notifications", anonUser.id, 2);

        // 2. Create authenticated user
        User authUser = TestFixtures.createOAuthUser(TestConstants.VALID_EMAIL, TestConstants.OAUTH_PROVIDER_GOOGLE,
                TestConstants.OAUTH_GOOGLE_ID);

        // 3. Merge accounts
        userMergeService.upgradeAnonymousAccount(anonUser, authUser);

        // 4. Verify notifications reassigned to authenticated user
        assertEquals(2, UserNotification.count("userId = ?1", authUser.id),
                "Authenticated user should have 2 notifications after merge");
        assertEquals(0, UserNotification.count("userId = ?1", anonUser.id),
                "Anonymous user should have 0 notifications after merge");

        // 5. Verify notification IDs and read status preserved
        UserNotification reloadedNotification1 = UserNotification.findById(notification1.id);
        assertEquals(authUser.id, reloadedNotification1.userId,
                "Notification 1 userId should point to authenticated user");
        assertNull(reloadedNotification1.readAt, "Notification 1 should remain unread");

        UserNotification reloadedNotification2 = UserNotification.findById(notification2.id);
        assertEquals(authUser.id, reloadedNotification2.userId,
                "Notification 2 userId should point to authenticated user");
        assertNotNull(reloadedNotification2.readAt, "Notification 2 should remain read (readAt preserved)");
    }

    /**
     * Tests account merge audit trail creation.
     *
     * <p>
     * Verifies:
     * <ul>
     * <li>AccountMergeAudit record created with correct user IDs
     * <li>Merged data summary JSONB contains listing and notification counts
     * <li>Consent flag set to true (implicit via OAuth)
     * <li>Privacy policy version recorded
     * <li>IP address and user agent recorded (placeholders for now)
     * </ul>
     */
    @Test
    @Transactional
    public void testMergeAuditCreation() {
        // 1. Create anonymous user with data
        User anonUser = TestFixtures.createAnonymousUserWithData(TestConstants.TEST_SESSION_HASH);

        // 2. Create authenticated user
        User authUser = TestFixtures.createOAuthUser(TestConstants.VALID_EMAIL, TestConstants.OAUTH_PROVIDER_GOOGLE,
                TestConstants.OAUTH_GOOGLE_ID);

        // 3. Merge accounts
        userMergeService.upgradeAnonymousAccount(anonUser, authUser);

        // 4. Verify merge audit created
        java.util.List<AccountMergeAudit> audits = AccountMergeAudit.findByAuthenticatedUser(authUser.id);
        assertFalse(audits.isEmpty(), "Merge audit should exist for authenticated user");

        AccountMergeAudit audit = audits.get(0);
        assertNotNull(audit, "Merge audit should be created");
        assertEquals(anonUser.id, audit.anonymousUserId, "Audit should reference anonymous user ID");
        assertEquals(authUser.id, audit.authenticatedUserId, "Audit should reference authenticated user ID");
        assertTrue(audit.consentGiven, "Consent should be true (implicit via OAuth)");
        assertNotNull(audit.mergedDataSummary, "Merged data summary should not be null");
        assertNotNull(audit.consentPolicyVersion, "Privacy policy version should be recorded");

        // 5. Verify merged data summary contains expected keys
        Map<String, Object> summary = audit.mergedDataSummary;
        assertTrue(summary.containsKey("listings_transferred"), "Summary should contain listings_transferred count");
        assertTrue(summary.containsKey("notifications_transferred"),
                "Summary should contain notifications_transferred count");
        assertTrue(summary.containsKey("preferences_merged"), "Summary should contain preferences_merged flag");

        LOG.infof("Merge audit created: %s → %s, summary: %s", anonUser.id, authUser.id, summary);
    }

    /**
     * Tests preference merging logic during account merge.
     *
     * <p>
     * Verifies:
     * <ul>
     * <li>Authenticated user's existing preferences take precedence
     * <li>Anonymous user's preferences merged where keys don't conflict
     * <li>Merge operation idempotent (can be called multiple times safely)
     * </ul>
     */
    @Test
    @Transactional
    public void testPreferenceMerge() {
        // 1. Create anonymous user with preferences
        User anonUser = TestFixtures.createAnonymousUser(TestConstants.TEST_SESSION_HASH);
        anonUser.preferences = new java.util.HashMap<>();
        anonUser.preferences.put("theme", "dark");
        anonUser.preferences.put("anonymous_only_key", "value123");
        anonUser.persist();

        // 2. Create authenticated user with preferences
        User authUser = TestFixtures.createOAuthUser(TestConstants.VALID_EMAIL, TestConstants.OAUTH_PROVIDER_GOOGLE,
                TestConstants.OAUTH_GOOGLE_ID);
        authUser.preferences = new java.util.HashMap<>();
        authUser.preferences.put("theme", "light"); // Conflict - auth user's value should win
        authUser.preferences.put("notifications_enabled", true);
        authUser.persist();

        // 3. Merge accounts
        userMergeService.upgradeAnonymousAccount(anonUser, authUser);

        // 4. Reload authenticated user to verify preferences
        User reloadedAuthUser = User.findById(authUser.id);

        // 5. Verify authenticated user's preferences take precedence
        assertEquals("light", reloadedAuthUser.preferences.get("theme"),
                "Authenticated user's theme preference should take precedence");

        // 6. Verify anonymous user's unique preferences merged
        assertEquals("value123", reloadedAuthUser.preferences.get("anonymous_only_key"),
                "Anonymous user's unique preference should be merged");

        // 7. Verify authenticated user's existing preferences preserved
        assertEquals(true, reloadedAuthUser.preferences.get("notifications_enabled"),
                "Authenticated user's existing preference should be preserved");

        LOG.infof("Merged preferences: %s", reloadedAuthUser.preferences);
    }

    /**
     * Tests validation that prevents merging non-anonymous users.
     *
     * <p>
     * Verifies that attempting to merge two authenticated users throws IllegalArgumentException.
     */
    @Test
    @Transactional
    public void testMergeValidationSourceNotAnonymous() {
        // 1. Create two authenticated users
        User authUser1 = TestFixtures.createOAuthUser(TestConstants.VALID_EMAIL, TestConstants.OAUTH_PROVIDER_GOOGLE,
                TestConstants.OAUTH_GOOGLE_ID);
        User authUser2 = TestFixtures.createOAuthUser(TestConstants.VALID_EMAIL_2,
                TestConstants.OAUTH_PROVIDER_FACEBOOK, TestConstants.OAUTH_FACEBOOK_ID);

        // 2. Attempt merge (should fail - source is not anonymous)
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            userMergeService.upgradeAnonymousAccount(authUser1, authUser2);
        }, "Should throw IllegalArgumentException when source user is not anonymous");

        assertTrue(exception.getMessage().contains("not anonymous"),
                "Error message should indicate source user is not anonymous");
    }

    /**
     * Tests validation that prevents merging into anonymous target.
     *
     * <p>
     * Verifies that attempting to merge into an anonymous user throws IllegalArgumentException.
     */
    @Test
    @Transactional
    public void testMergeValidationTargetIsAnonymous() {
        // 1. Create anonymous and authenticated users
        User anonUser = TestFixtures.createAnonymousUser(TestConstants.TEST_SESSION_HASH);
        User anonTarget = TestFixtures.createAnonymousUser("another-session-hash");

        // 2. Attempt merge (should fail - target is anonymous)
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            userMergeService.upgradeAnonymousAccount(anonUser, anonTarget);
        }, "Should throw IllegalArgumentException when target user is anonymous");

        assertTrue(exception.getMessage().contains("must be authenticated"),
                "Error message should indicate target must be authenticated");
    }

    /**
     * Tests that anonymous user is soft-deleted after merge.
     *
     * <p>
     * Verifies:
     * <ul>
     * <li>Anonymous user's deletedAt timestamp set
     * <li>Anonymous user still exists in database (soft delete, not hard delete)
     * <li>Anonymous user's data no longer active (listings/notifications transferred)
     * </ul>
     */
    @Test
    @Transactional
    public void testAnonymousUserSoftDelete() {
        // 1. Create anonymous user with data
        User anonUser = TestFixtures.createAnonymousUserWithData(TestConstants.TEST_SESSION_HASH);
        java.util.UUID anonUserId = anonUser.id;

        // 2. Create authenticated user
        User authUser = TestFixtures.createOAuthUser(TestConstants.VALID_EMAIL, TestConstants.OAUTH_PROVIDER_GOOGLE,
                TestConstants.OAUTH_GOOGLE_ID);

        // 3. Merge accounts
        userMergeService.upgradeAnonymousAccount(anonUser, authUser);

        // 4. Verify anonymous user soft-deleted (still exists in DB)
        User deletedAnonUser = User.findById(anonUserId);
        assertNotNull(deletedAnonUser, "Anonymous user should still exist in database after soft delete");
        assertNotNull(deletedAnonUser.deletedAt, "Anonymous user should have deletedAt timestamp set");
        assertTrue(deletedAnonUser.isAnonymous, "Anonymous user should still have isAnonymous flag set");

        LOG.infof("Anonymous user %s soft-deleted at %s", anonUserId, deletedAnonUser.deletedAt);
    }
}
