package villagecompute.homepage.integration.oauth;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import jakarta.transaction.Transactional;
import villagecompute.homepage.TestConstants;
import villagecompute.homepage.TestFixtures;
import villagecompute.homepage.WireMockTestBase;
import villagecompute.homepage.api.types.OAuthUrlResponseType;
import villagecompute.homepage.data.models.AccountMergeAudit;
import villagecompute.homepage.data.models.OAuthState;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.services.OAuthService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Apple Sign-In OAuth 2.0 authentication flow.
 *
 * <p>
 * Tests cover:
 * <ul>
 * <li>Successful user authentication (new and existing users)
 * <li>CSRF protection via state validation
 * <li>Error handling (invalid state, token exchange failures)
 * <li>Anonymous account upgrade and data merge
 * <li>Apple-specific JWT ID token parsing
 * </ul>
 *
 * <p>
 * Uses WireMock to stub external Apple OAuth API calls (token exchange only - user info comes from ID token JWT). All
 * tests run with Testcontainers PostgreSQL for realistic database integration.
 *
 * <p>
 * <b>Apple-Specific Behavior:</b>
 * <ul>
 * <li>User info extracted from ID token (JWT) - no separate user profile API call</li>
 * <li>Name claim only present on first sign-in</li>
 * <li>Email may be Apple private relay address (@privaterelay.appleid.com)</li>
 * </ul>
 *
 * <p>
 * <b>Ref:</b> Task I3.T7, Foundation Blueprint Section 3.5 (OAuth Flow Testing)
 *
 * @see OAuthService
 * @see WireMockTestBase
 */
@QuarkusTest
public class AppleOAuthFlowTest extends WireMockTestBase {

    private static final Logger LOG = Logger.getLogger(AppleOAuthFlowTest.class);

    @Inject
    OAuthService oauthService;

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        // Configure OAuth client to use WireMock server
        System.setProperty("quarkus.rest-client.apple-oauth.url", "http://localhost:" + wireMockServer.port());
    }

    /**
     * Tests successful Apple OAuth login flow for a new user.
     *
     * <p>
     * Verifies:
     * <ul>
     * <li>Authorization URL generation with state token
     * <li>State token stored in database
     * <li>Token exchange with Apple
     * <li>ID token JWT parsing for user info
     * <li>New user account creation
     * <li>State token deleted after use (single-use)
     * </ul>
     */
    @Test
    @Transactional
    public void testSuccessfulLogin() {
        // 1. Initiate login flow
        OAuthUrlResponseType initResponse = oauthService.initiateAppleLogin(TestConstants.TEST_SESSION_HASH,
                TestConstants.TEST_OAUTH_REDIRECT_URI);

        assertNotNull(initResponse.authorizationUrl(), "Authorization URL should not be null");
        assertNotNull(initResponse.state(), "State token should not be null");
        assertTrue(initResponse.authorizationUrl().contains("appleid.apple.com"),
                "Auth URL should point to Apple OAuth endpoint");

        // Verify state stored in database
        assertEntityExists(OAuthState.class, OAuthState.findByStateAndProvider(initResponse.state(), "apple").get().id);

        // 2. Stub Apple OAuth API response (returns ID token JWT)
        stubAppleTokenExchange();

        // 3. Handle OAuth callback
        User user = oauthService.handleAppleCallback(TestConstants.TEST_OAUTH_CODE, initResponse.state(),
                TestConstants.TEST_OAUTH_REDIRECT_URI);

        // 4. Verify user created successfully
        assertNotNull(user, "User should be created");
        assertNotNull(user.id, "User ID should be assigned");
        assertEquals(TestConstants.TEST_APPLE_EMAIL, user.email, "Email should match Apple ID token email claim");
        assertEquals(TestConstants.OAUTH_PROVIDER_APPLE, user.oauthProvider, "OAuth provider should be 'apple'");
        assertEquals(TestConstants.TEST_APPLE_USER_ID, user.oauthId, "OAuth ID should match Apple ID token sub claim");
        assertFalse(user.isAnonymous, "User should not be anonymous after OAuth login");

        // Verify user persisted in database
        assertEntityExists(User.class, user.id);

        // 5. Verify state token deleted (single-use CSRF protection)
        assertTrue(OAuthState.findByStateAndProvider(initResponse.state(), "apple").isEmpty(),
                "State token should be deleted after use to prevent replay attacks");
    }

    /**
     * Tests successful login for an existing Apple OAuth user.
     *
     * <p>
     * Verifies that existing users are recognized by their OAuth provider ID and no duplicate accounts are created.
     */
    @Test
    @Transactional
    public void testExistingUserLogin() {
        // 1. Create existing user with same Apple OAuth ID
        User existingUser = TestFixtures.createOAuthUser(TestConstants.TEST_APPLE_EMAIL,
                TestConstants.OAUTH_PROVIDER_APPLE, TestConstants.TEST_APPLE_USER_ID);

        // 2. Initiate login flow
        OAuthUrlResponseType initResponse = oauthService.initiateAppleLogin(TestConstants.TEST_SESSION_HASH,
                TestConstants.TEST_OAUTH_REDIRECT_URI);

        // 3. Stub Apple OAuth API response
        stubAppleTokenExchange();

        // 4. Handle OAuth callback
        User returnedUser = oauthService.handleAppleCallback(TestConstants.TEST_OAUTH_CODE, initResponse.state(),
                TestConstants.TEST_OAUTH_REDIRECT_URI);

        // 5. Verify same user returned (no duplicate created)
        assertEquals(existingUser.id, returnedUser.id, "Should return existing user, not create new one");

        // 6. Verify no duplicate users created
        assertEquals(1, User.count("oauthProvider = ?1 AND oauthId = ?2", TestConstants.OAUTH_PROVIDER_APPLE,
                TestConstants.TEST_APPLE_USER_ID), "Should have exactly one user with this Apple OAuth ID");
    }

    /**
     * Tests CSRF protection by rejecting invalid state tokens.
     *
     * <p>
     * Verifies that OAuth callbacks with wrong state tokens are rejected with SecurityException.
     */
    @Test
    @Transactional
    public void testInvalidState() {
        // 1. Initiate login flow (generates valid state)
        OAuthUrlResponseType initResponse = oauthService.initiateAppleLogin(TestConstants.TEST_SESSION_HASH,
                TestConstants.TEST_OAUTH_REDIRECT_URI);

        // 2. Stub Apple OAuth API response
        stubAppleTokenExchange();

        // 3. Attempt callback with WRONG state token
        SecurityException exception = assertThrows(SecurityException.class, () -> {
            oauthService.handleAppleCallback(TestConstants.TEST_OAUTH_CODE, "invalid-state-token",
                    TestConstants.TEST_OAUTH_REDIRECT_URI);
        }, "Should throw SecurityException for invalid state");

        // 4. Verify error message
        assertTrue(exception.getMessage().contains("Invalid or expired state"),
                "Error message should indicate invalid state");

        // 5. Verify no user created
        assertTrue(User.findByEmail(TestConstants.TEST_APPLE_EMAIL).isEmpty(),
                "No user should be created when state validation fails");

        // 6. Verify original state not deleted (still in database)
        assertEntityExists(OAuthState.class, OAuthState.findByStateAndProvider(initResponse.state(), "apple").get().id);
    }

    /**
     * Tests error handling when Apple token exchange fails.
     *
     * <p>
     * Verifies that token exchange failures (invalid code, expired code) are handled gracefully without creating user
     * accounts.
     */
    @Test
    @Transactional
    public void testTokenExchangeFailure() {
        // 1. Initiate login flow
        OAuthUrlResponseType initResponse = oauthService.initiateAppleLogin(TestConstants.TEST_SESSION_HASH,
                TestConstants.TEST_OAUTH_REDIRECT_URI);

        // 2. Stub token endpoint to return error
        stubAppleTokenExchangeError();

        // 3. Attempt callback (should fail during token exchange)
        assertThrows(Exception.class, () -> {
            oauthService.handleAppleCallback(TestConstants.TEST_OAUTH_CODE, initResponse.state(),
                    TestConstants.TEST_OAUTH_REDIRECT_URI);
        }, "Should throw exception when token exchange fails");

        // 4. Verify no user created
        assertTrue(User.findByEmail(TestConstants.TEST_APPLE_EMAIL).isEmpty(),
                "No user should be created when token exchange fails");

        // 5. Verify state deleted even on failure (cleanup)
        assertTrue(OAuthState.findByStateAndProvider(initResponse.state(), "apple").isEmpty(),
                "State should be deleted even on failure to prevent accumulation");
    }

    /**
     * Tests anonymous account upgrade during OAuth login.
     *
     * <p>
     * Verifies:
     * <ul>
     * <li>Anonymous user upgraded to authenticated (isAnonymous flag set to false)
     * <li>User data preserved (marketplace listings, notifications, preferences)
     * <li>Audit trail created for GDPR compliance
     * <li>Anonymous user soft-deleted
     * </ul>
     */
    @Test
    @Transactional
    public void testAnonymousUpgrade() {
        // 1. Create anonymous user with data
        User anonUser = TestFixtures.createAnonymousUserWithData(TestConstants.TEST_SESSION_HASH);
        int listingCount = (int) villagecompute.homepage.data.models.MarketplaceListing.count("userId = ?1",
                anonUser.id);
        int notificationCount = (int) villagecompute.homepage.data.models.UserNotification.count("userId = ?1",
                anonUser.id);

        LOG.infof("Created anonymous user %s with %d listings and %d notifications", anonUser.id, listingCount,
                notificationCount);

        // 2. Initiate OAuth login with same session
        OAuthUrlResponseType initResponse = oauthService.initiateAppleLogin(anonUser.id.toString(),
                TestConstants.TEST_OAUTH_REDIRECT_URI);

        // 3. Stub Apple OAuth API response
        stubAppleTokenExchange();

        // 4. Complete OAuth callback
        User authenticatedUser = oauthService.handleAppleCallback(TestConstants.TEST_OAUTH_CODE, initResponse.state(),
                TestConstants.TEST_OAUTH_REDIRECT_URI);

        // 5. Verify user created (not upgraded in place, but merged)
        assertNotNull(authenticatedUser);
        assertFalse(authenticatedUser.isAnonymous, "User should be authenticated after OAuth");
        assertEquals(TestConstants.OAUTH_PROVIDER_APPLE, authenticatedUser.oauthProvider);
        assertEquals(TestConstants.TEST_APPLE_EMAIL, authenticatedUser.email);

        // 6. Verify anonymous user's data transferred
        int authListingCount = (int) villagecompute.homepage.data.models.MarketplaceListing.count("userId = ?1",
                authenticatedUser.id);
        int authNotificationCount = (int) villagecompute.homepage.data.models.UserNotification.count("userId = ?1",
                authenticatedUser.id);

        assertEquals(listingCount, authListingCount, "Listings should be transferred to authenticated user");
        assertEquals(notificationCount, authNotificationCount,
                "Notifications should be transferred to authenticated user");

        // 7. Verify merge audit created
        assertEntityExists(AccountMergeAudit.class,
                AccountMergeAudit.findByAuthenticatedUser(authenticatedUser.id).get(0).id);

        // 8. Verify anonymous user soft-deleted
        User deletedAnonUser = User.findById(anonUser.id);
        assertNotNull(deletedAnonUser.deletedAt, "Anonymous user should be soft-deleted after merge");
    }

    /**
     * Tests Apple ID token JWT parsing for user info extraction.
     *
     * <p>
     * Verifies that the ID token (JWT) is correctly parsed to extract user claims (sub, email, name).
     */
    @Test
    @Transactional
    public void testIdTokenParsing() {
        // 1. Initiate login flow
        OAuthUrlResponseType initResponse = oauthService.initiateAppleLogin(TestConstants.TEST_SESSION_HASH,
                TestConstants.TEST_OAUTH_REDIRECT_URI);

        // 2. Stub Apple OAuth API response with ID token
        stubAppleTokenExchange();

        // 3. Complete OAuth flow
        User user = oauthService.handleAppleCallback(TestConstants.TEST_OAUTH_CODE, initResponse.state(),
                TestConstants.TEST_OAUTH_REDIRECT_URI);

        // 4. Verify user info extracted from ID token JWT
        assertEquals(TestConstants.TEST_APPLE_EMAIL, user.email, "Email should be extracted from ID token email claim");
        assertEquals(TestConstants.TEST_APPLE_USER_ID, user.oauthId,
                "User ID should be extracted from ID token sub claim");
        assertNotNull(user.displayName, "Display name should be present");
    }

    /**
     * Tests handling of Apple ID token with missing name claim (subsequent logins).
     *
     * <p>
     * Apple only provides the name claim on the first sign-in. Subsequent logins have no name claim, so display name
     * should fallback to email prefix.
     */
    @Test
    @Transactional
    public void testIdTokenMissingName() {
        // 1. Create custom ID token response without name claim
        String customTokenResponse = """
                {
                  "access_token": "apple-access-token-test",
                  "token_type": "Bearer",
                  "expires_in": 3600,
                  "refresh_token": "apple-refresh-token-test",
                  "id_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6InRlc3Qta2V5In0.eyJpc3MiOiJodHRwczovL2FwcGxlaWQuYXBwbGUuY29tIiwiYXVkIjoiY29tLnZpbGxhZ2Vjb21wdXRlLmhvbWVwYWdlIiwiZXhwIjoxNzM4MzcwNDAwLCJpYXQiOjE3MzgzNjY4MDAsInN1YiI6ImFwcGxlLXVzZXItbm8tbmFtZSIsImVtYWlsIjoidGVzdEBhcHBsZWlkLmNvbSIsImVtYWlsX3ZlcmlmaWVkIjp0cnVlfQ.test-signature"
                }
                """;

        // 2. Initiate login flow
        OAuthUrlResponseType initResponse = oauthService.initiateAppleLogin(TestConstants.TEST_SESSION_HASH,
                TestConstants.TEST_OAUTH_REDIRECT_URI);

        // 3. Stub Apple OAuth API response with ID token missing name
        stubAppleTokenExchange(customTokenResponse);

        // 4. Complete OAuth flow
        User user = oauthService.handleAppleCallback(TestConstants.TEST_OAUTH_CODE, initResponse.state(),
                TestConstants.TEST_OAUTH_REDIRECT_URI);

        // 5. Verify display name falls back to email prefix
        assertEquals("test", user.displayName, "Display name should fallback to email prefix when name claim missing");
    }

    /**
     * Tests handling of malformed Apple ID token JWT.
     *
     * <p>
     * Verifies that invalid JWT tokens are rejected with appropriate error handling.
     */
    @Test
    @Transactional
    public void testIdTokenInvalid() {
        // 1. Create custom token response with invalid JWT
        String invalidTokenResponse = """
                {
                  "access_token": "apple-access-token-test",
                  "token_type": "Bearer",
                  "expires_in": 3600,
                  "id_token": "invalid.jwt.token"
                }
                """;

        // 2. Initiate login flow
        OAuthUrlResponseType initResponse = oauthService.initiateAppleLogin(TestConstants.TEST_SESSION_HASH,
                TestConstants.TEST_OAUTH_REDIRECT_URI);

        // 3. Stub Apple OAuth API response with invalid JWT
        stubAppleTokenExchange(invalidTokenResponse);

        // 4. Attempt callback (should fail during JWT parsing)
        assertThrows(Exception.class, () -> {
            oauthService.handleAppleCallback(TestConstants.TEST_OAUTH_CODE, initResponse.state(),
                    TestConstants.TEST_OAUTH_REDIRECT_URI);
        }, "Should throw exception when ID token JWT parsing fails");

        // 5. Verify no user created
        assertTrue(User.count("oauthProvider = ?1", TestConstants.OAUTH_PROVIDER_APPLE) == 0,
                "No Apple user should be created when JWT parsing fails");
    }
}
