package villagecompute.homepage.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.AppleIdTokenClaims;
import villagecompute.homepage.api.types.AppleTokenResponseType;
import villagecompute.homepage.api.types.FacebookTokenResponseType;
import villagecompute.homepage.api.types.FacebookUserInfoType;
import villagecompute.homepage.api.types.GoogleTokenResponseType;
import villagecompute.homepage.api.types.GoogleUserInfoType;
import villagecompute.homepage.api.types.OAuthUrlResponseType;
import villagecompute.homepage.data.models.OAuthState;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.integration.oauth.AppleOAuthClient;
import villagecompute.homepage.integration.oauth.FacebookOAuthClient;
import villagecompute.homepage.integration.oauth.GoogleOAuthClient;

/**
 * Service for OAuth 2.0 authentication flows.
 *
 * <p>
 * Orchestrates the authorization code flow with CSRF protection:
 *
 * <ol>
 * <li>Generate authorization URL with state token
 * <li>Store state token in database (5-minute TTL)
 * <li>Validate state token on callback
 * <li>Exchange authorization code for access token
 * <li>Retrieve user profile
 * <li>Create or link user account
 * </ol>
 *
 * <p>
 * Security features:
 *
 * <ul>
 * <li>CSRF protection via state parameter (Policy P9)
 * <li>State token single-use (deleted after validation)
 * <li>State token expiration (5 minutes)
 * <li>Email uniqueness enforcement
 * </ul>
 */
@ApplicationScoped
public class OAuthService {

    private static final Logger LOG = Logger.getLogger(OAuthService.class);
    private static final int STATE_TTL_MINUTES = 5;

    @Inject
    GoogleOAuthClient googleClient;

    @Inject
    FacebookOAuthClient facebookClient;

    @Inject
    AppleOAuthClient appleClient;

    @Inject
    UserMergeService userMergeService;

    /**
     * Initiate Google OAuth login flow.
     *
     * <p>
     * Generates a cryptographically random state token (UUID v4), stores it in the database, and returns the Google
     * authorization URL.
     *
     * @param sessionId
     *            the anonymous session ID or authenticated user ID
     * @param redirectUri
     *            the callback URL for this application
     * @return OAuth URL response with authorization URL and state token
     */
    @Transactional
    public OAuthUrlResponseType initiateGoogleLogin(String sessionId, String redirectUri) {
        String state = generateState();

        // Store state token in database with 5-minute expiration
        OAuthState oauthState = new OAuthState();
        oauthState.state = state;
        oauthState.sessionId = sessionId;
        oauthState.provider = "google";
        oauthState.createdAt = Instant.now();
        oauthState.expiresAt = Instant.now().plus(STATE_TTL_MINUTES, ChronoUnit.MINUTES);
        oauthState.persist();

        String authUrl = googleClient.getAuthorizationUrl(redirectUri, state);
        LOG.infof("Initiated Google OAuth login: sessionId=%s, state=%s", sessionId, state);

        return new OAuthUrlResponseType(authUrl, state);
    }

    /**
     * Handle Google OAuth callback.
     *
     * <p>
     * Validates the state token, exchanges the authorization code for an access token, retrieves the user profile, and
     * creates or links the user account.
     *
     * <p>
     * Account creation logic:
     *
     * <ul>
     * <li>If OAuth account exists (provider + providerId) → return existing user
     * <li>If email exists with different provider → throw exception (merge in I3.T4)
     * <li>If new user → create authenticated account
     * </ul>
     *
     * @param code
     *            the authorization code from OAuth callback
     * @param state
     *            the CSRF state token
     * @param redirectUri
     *            the callback URL (must match authorization request)
     * @return the authenticated user (existing or newly created)
     * @throws SecurityException
     *             if state token is invalid or expired
     * @throws IllegalStateException
     *             if email exists with different provider
     */
    @Transactional
    public User handleGoogleCallback(String code, String state, String redirectUri) {
        // 1. Validate state token
        Optional<OAuthState> stateRecord = OAuthState.findByStateAndProvider(state, "google");
        if (stateRecord.isEmpty()) {
            LOG.warnf("Invalid or expired OAuth state: state=%s", state);
            throw new SecurityException("Invalid or expired state token");
        }

        String sessionId = stateRecord.get().sessionId;

        // 2. Delete state (single-use token)
        stateRecord.get().delete();
        LOG.infof("Validated and deleted OAuth state: state=%s, sessionId=%s", state, sessionId);

        // 3. Exchange code for token
        GoogleTokenResponseType tokenResponse = googleClient.exchangeCodeForToken(code, redirectUri);
        LOG.infof("Exchanged authorization code for access token: sessionId=%s", sessionId);

        // 4. Get user profile
        GoogleUserInfoType userInfo = googleClient.getUserProfile(tokenResponse.accessToken());
        LOG.infof("Retrieved Google user profile: email=%s, sub=%s, name=%s", userInfo.email(), userInfo.sub(),
                userInfo.name());

        // 5. Find or create user
        // Check if OAuth account already exists
        Optional<User> existingOAuthUser = User.findByOAuth("google", userInfo.sub());
        if (existingOAuthUser.isPresent()) {
            LOG.infof("Existing OAuth user found: userId=%s, email=%s", existingOAuthUser.get().id, userInfo.email());
            return existingOAuthUser.get();
        }

        // Check if email already exists with different provider
        Optional<User> emailMatch = User.findByEmail(userInfo.email());
        User finalUser;

        if (emailMatch.isPresent()) {
            // Email exists - merge anonymous account into existing user (I3.T4)
            User existingUser = emailMatch.get();
            LOG.infof("Email %s already exists (provider: %s), will merge anonymous account into existing user %s",
                    userInfo.email(), existingUser.oauthProvider, existingUser.id);
            finalUser = existingUser;
        } else {
            // Create new authenticated user
            User newUser = new User();
            newUser.email = userInfo.email();
            newUser.oauthProvider = "google";
            newUser.oauthId = userInfo.sub();
            newUser.displayName = userInfo.name();
            newUser.avatarUrl = userInfo.picture();
            newUser.isAnonymous = false;
            newUser.preferences = Map.of(); // Initialize JSONB column (non-null)
            newUser.directoryKarma = 0;
            newUser.directoryTrustLevel = User.TRUST_LEVEL_UNTRUSTED;
            newUser.createdAt = Instant.now();
            newUser.updatedAt = Instant.now();
            newUser.persist();

            LOG.infof("Created new user via Google OAuth: userId=%s, email=%s", newUser.id, newUser.email);
            finalUser = newUser;
        }

        // 6. Handle anonymous account upgrade (I3.T4)
        if (sessionId != null && !sessionId.equals(finalUser.id.toString())) {
            try {
                UUID anonymousUserId = UUID.fromString(sessionId);
                User anonymousUser = User.findById(anonymousUserId);

                if (anonymousUser != null && anonymousUser.isAnonymous) {
                    userMergeService.upgradeAnonymousAccount(anonymousUser, finalUser);
                    LOG.infof("Merged anonymous user %s into authenticated user %s", anonymousUserId, finalUser.id);
                }
            } catch (IllegalArgumentException e) {
                LOG.warnf("Invalid sessionId format: %s", sessionId);
            }
        }

        return finalUser;
    }

    /**
     * Initiate Facebook OAuth login flow.
     *
     * <p>
     * Generates a cryptographically random state token (UUID v4), stores it in the database, and returns the Facebook
     * authorization URL.
     *
     * @param sessionId
     *            the anonymous session ID or authenticated user ID
     * @param redirectUri
     *            the callback URL for this application
     * @return OAuth URL response with authorization URL and state token
     */
    @Transactional
    public OAuthUrlResponseType initiateFacebookLogin(String sessionId, String redirectUri) {
        String state = generateState();

        // Store state token in database with 5-minute expiration
        OAuthState oauthState = new OAuthState();
        oauthState.state = state;
        oauthState.sessionId = sessionId;
        oauthState.provider = "facebook";
        oauthState.createdAt = Instant.now();
        oauthState.expiresAt = Instant.now().plus(STATE_TTL_MINUTES, ChronoUnit.MINUTES);
        oauthState.persist();

        String authUrl = facebookClient.getAuthorizationUrl(redirectUri, state);
        LOG.infof("Initiated Facebook OAuth login: sessionId=%s, state=%s", sessionId, state);

        return new OAuthUrlResponseType(authUrl, state);
    }

    /**
     * Handle Facebook OAuth callback.
     *
     * <p>
     * Validates the state token, exchanges the authorization code for an access token, retrieves the user profile, and
     * creates or links the user account.
     *
     * <p>
     * Account creation logic (same as Google):
     *
     * <ul>
     * <li>If OAuth account exists (provider + providerId) → return existing user
     * <li>If email exists with different provider → throw exception (merge in I3.T4)
     * <li>If new user → create authenticated account
     * </ul>
     *
     * <p>
     * Facebook-specific handling:
     *
     * <ul>
     * <li>Validates email permission (throws if denied)
     * <li>Extracts picture URL from nested JSON: picture.data.url
     * <li>Calculates token expiration from expires_in (for I3.T5 token refresh)
     * </ul>
     *
     * @param code
     *            the authorization code from OAuth callback
     * @param state
     *            the CSRF state token
     * @param redirectUri
     *            the callback URL (must match authorization request)
     * @return the authenticated user (existing or newly created)
     * @throws SecurityException
     *             if state token is invalid or expired
     * @throws IllegalStateException
     *             if email exists with different provider or email permission denied
     */
    @Transactional
    public User handleFacebookCallback(String code, String state, String redirectUri) {
        // 1. Validate state token
        Optional<OAuthState> stateRecord = OAuthState.findByStateAndProvider(state, "facebook");
        if (stateRecord.isEmpty()) {
            LOG.warnf("Invalid or expired OAuth state: state=%s", state);
            throw new SecurityException("Invalid or expired state token");
        }

        String sessionId = stateRecord.get().sessionId;

        // 2. Delete state (single-use token)
        stateRecord.get().delete();
        LOG.infof("Validated and deleted OAuth state: state=%s, sessionId=%s", state, sessionId);

        // 3. Exchange code for token
        FacebookTokenResponseType tokenResponse = facebookClient.exchangeCodeForToken(code, redirectUri);
        LOG.infof("Exchanged authorization code for access token: sessionId=%s, expiresIn=%d", sessionId,
                tokenResponse.expiresIn());

        // 4. Get user profile
        FacebookUserInfoType userInfo = facebookClient.getUserProfile(tokenResponse.accessToken());

        // Validate email permission (Facebook users can deny email access)
        if (userInfo.email() == null || userInfo.email().isBlank()) {
            LOG.warnf("Facebook user denied email permission: facebookId=%s", userInfo.id());
            throw new IllegalStateException("Email permission required. Please authorize email access.");
        }

        LOG.infof("Retrieved Facebook user profile: email=%s, id=%s, name=%s", userInfo.email(), userInfo.id(),
                userInfo.name());

        // 5. Find or create user
        // Check if OAuth account already exists
        Optional<User> existingOAuthUser = User.findByOAuth("facebook", userInfo.id());
        if (existingOAuthUser.isPresent()) {
            LOG.infof("Existing OAuth user found: userId=%s, email=%s", existingOAuthUser.get().id, userInfo.email());
            return existingOAuthUser.get();
        }

        // Check if email already exists with different provider
        Optional<User> emailMatch = User.findByEmail(userInfo.email());
        User finalUser;

        if (emailMatch.isPresent()) {
            // Email exists - merge anonymous account into existing user (I3.T4)
            User existingUser = emailMatch.get();
            LOG.infof("Email %s already exists (provider: %s), will merge anonymous account into existing user %s",
                    userInfo.email(), existingUser.oauthProvider, existingUser.id);
            finalUser = existingUser;
        } else {
            // Extract avatar URL from nested picture structure
            String avatarUrl = null;
            if (userInfo.picture() != null && userInfo.picture().data() != null) {
                avatarUrl = userInfo.picture().data().url();
            }

            // Create new authenticated user
            User newUser = new User();
            newUser.email = userInfo.email();
            newUser.oauthProvider = "facebook";
            newUser.oauthId = userInfo.id();
            newUser.displayName = userInfo.name();
            newUser.avatarUrl = avatarUrl;
            newUser.isAnonymous = false;
            newUser.preferences = Map.of(); // Initialize JSONB column (non-null)
            newUser.directoryKarma = 0;
            newUser.directoryTrustLevel = User.TRUST_LEVEL_UNTRUSTED;
            newUser.createdAt = Instant.now();
            newUser.updatedAt = Instant.now();
            newUser.persist();

            LOG.infof("Created new user via Facebook OAuth: userId=%s, email=%s", newUser.id, newUser.email);
            finalUser = newUser;
        }

        // 6. Handle anonymous account upgrade (I3.T4)
        if (sessionId != null && !sessionId.equals(finalUser.id.toString())) {
            try {
                UUID anonymousUserId = UUID.fromString(sessionId);
                User anonymousUser = User.findById(anonymousUserId);

                if (anonymousUser != null && anonymousUser.isAnonymous) {
                    userMergeService.upgradeAnonymousAccount(anonymousUser, finalUser);
                    LOG.infof("Merged anonymous user %s into authenticated user %s", anonymousUserId, finalUser.id);
                }
            } catch (IllegalArgumentException e) {
                LOG.warnf("Invalid sessionId format: %s", sessionId);
            }
        }

        // Note: Token expiration (tokenResponse.expiresIn()) will be stored in I3.T5 for token refresh

        return finalUser;
    }

    /**
     * Initiate Apple OAuth login flow.
     *
     * <p>
     * Generates a cryptographically random state token (UUID v4), stores it in the database, and returns the Apple
     * authorization URL.
     *
     * @param sessionId
     *            the anonymous session ID or authenticated user ID
     * @param redirectUri
     *            the callback URL for this application
     * @return OAuth URL response with authorization URL and state token
     */
    @Transactional
    public OAuthUrlResponseType initiateAppleLogin(String sessionId, String redirectUri) {
        String state = generateState();

        // Store state token in database with 5-minute expiration
        OAuthState oauthState = new OAuthState();
        oauthState.state = state;
        oauthState.sessionId = sessionId;
        oauthState.provider = "apple";
        oauthState.createdAt = Instant.now();
        oauthState.expiresAt = Instant.now().plus(STATE_TTL_MINUTES, ChronoUnit.MINUTES);
        oauthState.persist();

        String authUrl = appleClient.getAuthorizationUrl(redirectUri, state);
        LOG.infof("Initiated Apple OAuth login: sessionId=%s, state=%s", sessionId, state);

        return new OAuthUrlResponseType(authUrl, state);
    }

    /**
     * Handle Apple OAuth callback.
     *
     * <p>
     * Validates the state token, exchanges the authorization code for an access token, parses the ID token to extract
     * user profile, and creates or links the user account.
     *
     * <p>
     * Account creation logic (same as Google/Facebook):
     *
     * <ul>
     * <li>If OAuth account exists (provider + providerId) → return existing user
     * <li>If email exists with different provider → throw exception (merge in I3.T4)
     * <li>If new user → create authenticated account
     * </ul>
     *
     * <p>
     * Apple-specific handling:
     *
     * <ul>
     * <li>User info extracted from ID token JWT (not separate API call)</li>
     * <li>Name claim only present on first sign-in - fallback to email prefix if missing</li>
     * <li>Email may be Apple private relay address (@privaterelay.appleid.com)</li>
     * </ul>
     *
     * @param code
     *            the authorization code from OAuth callback
     * @param state
     *            the CSRF state token
     * @param redirectUri
     *            the callback URL (must match authorization request)
     * @return the authenticated user (existing or newly created)
     * @throws SecurityException
     *             if state token is invalid or expired
     * @throws IllegalStateException
     *             if email exists with different provider
     */
    @Transactional
    public User handleAppleCallback(String code, String state, String redirectUri) {
        // 1. Validate state token
        Optional<OAuthState> stateRecord = OAuthState.findByStateAndProvider(state, "apple");
        if (stateRecord.isEmpty()) {
            LOG.warnf("Invalid or expired OAuth state: state=%s", state);
            throw new SecurityException("Invalid or expired state token");
        }

        String sessionId = stateRecord.get().sessionId;

        // 2. Delete state (single-use token)
        stateRecord.get().delete();
        LOG.infof("Validated and deleted OAuth state: state=%s, sessionId=%s", state, sessionId);

        // 3. Exchange code for token
        AppleTokenResponseType tokenResponse = appleClient.exchangeCodeForToken(code, redirectUri);
        LOG.infof("Exchanged authorization code for access token: sessionId=%s", sessionId);

        // 4. Parse ID token to get user info (Apple returns user info in ID token, not separate API call)
        AppleIdTokenClaims idTokenClaims = appleClient.parseIdToken(tokenResponse.idToken());

        // Handle missing name (only present on first sign-in)
        String displayName = idTokenClaims.displayName();
        if (displayName == null || displayName.isBlank()) {
            // Fallback to email prefix
            displayName = idTokenClaims.email().split("@")[0];
            LOG.infof("Apple name claim missing (subsequent login), using email prefix: %s", displayName);
        }

        LOG.infof("Retrieved Apple user profile: email=%s, sub=%s, name=%s", idTokenClaims.email(), idTokenClaims.sub(),
                displayName);

        // 5. Find or create user
        // Check if OAuth account already exists
        Optional<User> existingOAuthUser = User.findByOAuth("apple", idTokenClaims.sub());
        if (existingOAuthUser.isPresent()) {
            LOG.infof("Existing OAuth user found: userId=%s, email=%s", existingOAuthUser.get().id,
                    idTokenClaims.email());
            return existingOAuthUser.get();
        }

        // Check if email already exists with different provider
        Optional<User> emailMatch = User.findByEmail(idTokenClaims.email());
        User finalUser;

        if (emailMatch.isPresent()) {
            // Email exists - merge anonymous account into existing user (I3.T4)
            User existingUser = emailMatch.get();
            LOG.infof("Email %s already exists (provider: %s), will merge anonymous account into existing user %s",
                    idTokenClaims.email(), existingUser.oauthProvider, existingUser.id);
            finalUser = existingUser;
        } else {
            // Create new authenticated user
            User newUser = new User();
            newUser.email = idTokenClaims.email();
            newUser.oauthProvider = "apple";
            newUser.oauthId = idTokenClaims.sub();
            newUser.displayName = displayName;
            newUser.avatarUrl = null; // Apple doesn't provide avatar URL
            newUser.isAnonymous = false;
            newUser.preferences = Map.of(); // Initialize JSONB column (non-null)
            newUser.directoryKarma = 0;
            newUser.directoryTrustLevel = User.TRUST_LEVEL_UNTRUSTED;
            newUser.createdAt = Instant.now();
            newUser.updatedAt = Instant.now();
            newUser.persist();

            LOG.infof("Created new user via Apple OAuth: userId=%s, email=%s", newUser.id, newUser.email);
            finalUser = newUser;
        }

        // 6. Handle anonymous account upgrade (I3.T4)
        if (sessionId != null && !sessionId.equals(finalUser.id.toString())) {
            try {
                UUID anonymousUserId = UUID.fromString(sessionId);
                User anonymousUser = User.findById(anonymousUserId);

                if (anonymousUser != null && anonymousUser.isAnonymous) {
                    userMergeService.upgradeAnonymousAccount(anonymousUser, finalUser);
                    LOG.infof("Merged anonymous user %s into authenticated user %s", anonymousUserId, finalUser.id);
                }
            } catch (IllegalArgumentException e) {
                LOG.warnf("Invalid sessionId format: %s", sessionId);
            }
        }

        // Note: Token expiration (tokenResponse.expiresIn()) will be stored in I3.T5 for token refresh

        return finalUser;
    }

    /**
     * Generate cryptographically random state token.
     *
     * @return UUID v4 as string (122 bits of entropy)
     */
    private String generateState() {
        return UUID.randomUUID().toString();
    }
}
