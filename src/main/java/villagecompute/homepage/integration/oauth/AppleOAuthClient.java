package villagecompute.homepage.integration.oauth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.UriBuilder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.AppleIdTokenClaims;
import villagecompute.homepage.api.types.AppleTokenResponseType;

/**
 * Client for Apple Sign-In OAuth 2.0 authorization code flow.
 *
 * <p>
 * Provides methods for:
 *
 * <ul>
 * <li>Generating authorization URLs for user consent
 * <li>Exchanging authorization codes for access tokens
 * <li>Parsing ID token JWTs to extract user information
 * </ul>
 *
 * <p>
 * Apple Sign-In differs significantly from Google/Facebook:
 *
 * <ul>
 * <li><strong>Client Secret:</strong> JWT signed with ES256 (not static string), regenerated for each token
 * exchange</li>
 * <li><strong>User Info:</strong> Extracted from ID token JWT claims (no separate API call)</li>
 * <li><strong>Privacy:</strong> Name only provided on first sign-in; email may be relay address</li>
 * </ul>
 *
 * <p>
 * Configuration properties:
 *
 * <ul>
 * <li>quarkus.oidc.apple.client-id (Service ID)</li>
 * <li>villagecompute.apple.team-id (10-character Team ID)</li>
 * <li>villagecompute.apple.key-id (10-character Key ID)</li>
 * <li>villagecompute.apple.private-key-path (Path to P8 file)</li>
 * </ul>
 *
 * <p>
 * See: https://developer.apple.com/documentation/sign_in_with_apple/sign_in_with_apple_rest_api
 */
@ApplicationScoped
public class AppleOAuthClient {

    private static final Logger LOG = Logger.getLogger(AppleOAuthClient.class);

    @ConfigProperty(
            name = "quarkus.oidc.apple.client-id")
    String clientId;

    @ConfigProperty(
            name = "villagecompute.apple.team-id")
    String teamId;

    @ConfigProperty(
            name = "villagecompute.apple.key-id")
    String keyId;

    @ConfigProperty(
            name = "villagecompute.apple.private-key-path")
    String privateKeyPath;

    @Inject
    @RestClient
    AppleOAuthRestClient restClient;

    /**
     * Generate Apple Sign-In authorization URL.
     *
     * <p>
     * Builds the authorization URL with the following parameters:
     *
     * <ul>
     * <li>client_id: Apple Service ID</li>
     * <li>redirect_uri: Callback URL for this application</li>
     * <li>response_type: "code" (authorization code flow)</li>
     * <li>response_mode: "form_post" (POST callback for security)</li>
     * <li>scope: "name email" (user identity - openid is implicit)</li>
     * <li>state: CSRF token (caller-provided)</li>
     * </ul>
     *
     * @param redirectUri
     *            the callback URL (must match Apple Developer configuration)
     * @param state
     *            CSRF state token (UUID v4)
     * @return full authorization URL to redirect user to
     */
    public String getAuthorizationUrl(String redirectUri, String state) {
        return UriBuilder.fromUri("https://appleid.apple.com/auth/authorize").queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri).queryParam("response_type", "code")
                .queryParam("response_mode", "form_post").queryParam("scope", "name email").queryParam("state", state)
                .build().toString();
    }

    /**
     * Exchange authorization code for access token and ID token.
     *
     * <p>
     * Generates a fresh client secret JWT (expires in 10 minutes), then calls POST https://appleid.apple.com/auth/token
     * with form-encoded parameters.
     *
     * <p>
     * Critical: The client secret JWT is regenerated on every token exchange to ensure it's never expired. Apple
     * rejects expired client secrets.
     *
     * @param code
     *            the authorization code from OAuth callback
     * @param redirectUri
     *            the redirect URI used in authorization request (must match exactly)
     * @return token response with access_token, refresh_token, id_token (user info in ID token)
     */
    public AppleTokenResponseType exchangeCodeForToken(String code, String redirectUri) {
        // Generate fresh client secret JWT (10-minute expiration)
        String clientSecret = generateClientSecret();

        return restClient.exchangeToken("authorization_code", code, redirectUri, clientId, clientSecret);
    }

    /**
     * Parse Apple ID token JWT to extract user information.
     *
     * <p>
     * Decodes the ID token JWT and extracts claims:
     *
     * <ul>
     * <li>sub: Apple user ID (unique, stable identifier)</li>
     * <li>email: User email (may be relay email like @privaterelay.appleid.com)</li>
     * <li>email_verified: Always true for Apple</li>
     * <li>name: User's full name (ONLY present on first sign-in)</li>
     * </ul>
     *
     * <p>
     * Note: For v1, this method skips signature verification (Policy P10 allows this for initial release). Production
     * should verify the signature using Apple's public keys from https://appleid.apple.com/auth/keys.
     *
     * @param idToken
     *            the ID token JWT from token response
     * @return parsed ID token claims (displayName may be null on subsequent logins)
     * @throws RuntimeException
     *             if ID token parsing fails
     */
    public AppleIdTokenClaims parseIdToken(String idToken) {
        try {
            // NOTE: For v1, skip signature verification (Policy P10)
            // Production should verify signature with Apple's public keys

            // Parse JWT without verification (extract payload only)
            int secondDotIndex = idToken.lastIndexOf('.');
            String unsignedToken = idToken.substring(0, secondDotIndex + 1);

            Claims claims = Jwts.parser().build().parseUnsecuredClaims(unsignedToken).getPayload();

            // Extract standard claims
            String sub = claims.get("sub", String.class);
            String email = claims.get("email", String.class);
            Boolean emailVerified = claims.get("email_verified", Boolean.class);

            // Name claim (only present on first sign-in)
            String displayName = null;
            Object nameObj = claims.get("name");
            if (nameObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nameMap = (Map<String, Object>) nameObj;
                String firstName = (String) nameMap.get("firstName");
                String lastName = (String) nameMap.get("lastName");

                if (firstName != null && lastName != null) {
                    displayName = firstName + " " + lastName;
                } else if (firstName != null) {
                    displayName = firstName;
                }
            }

            LOG.infof("Parsed Apple ID token: sub=%s, email=%s, emailVerified=%s, displayName=%s", sub, email,
                    emailVerified, displayName);

            return new AppleIdTokenClaims(sub, email, emailVerified, displayName);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to parse Apple ID token");
            throw new RuntimeException("Invalid Apple ID token", e);
        }
    }

    /**
     * Refresh Apple access token using refresh token.
     *
     * <p>
     * Calls POST https://appleid.apple.com/auth/token with grant_type=refresh_token.
     *
     * <p>
     * Apple refresh tokens expire after 6 months and cannot be refreshed - users must re-authenticate. The client
     * secret JWT MUST be regenerated for each refresh request (10-minute expiration).
     *
     * <p>
     * This method is called by {@code OAuthTokenRefreshJobHandler} to refresh access tokens before they expire,
     * preventing social integration failures.
     *
     * @param refreshToken
     *            the refresh token from initial token exchange
     * @return token response with new access_token, expires_in, and possibly rotated refresh_token
     * @throws RuntimeException
     *             if refresh fails (expired refresh token after 6 months, network error, invalid_grant)
     */
    public AppleTokenResponseType refreshAccessToken(String refreshToken) {
        // Generate fresh client secret JWT (10-minute expiration)
        // CRITICAL: Must regenerate for EVERY refresh request (cannot reuse cached JWT)
        String clientSecret = generateClientSecret();

        return restClient.refreshToken("refresh_token", refreshToken, clientId, clientSecret);
    }

    /**
     * Generate Apple client secret JWT.
     *
     * <p>
     * Creates a JWT signed with ES256 algorithm using Apple's private key (P8 file). The JWT includes:
     *
     * <ul>
     * <li>iss: Apple Team ID</li>
     * <li>iat: Current Unix timestamp</li>
     * <li>exp: 10 minutes from iat (Apple allows up to 6 months, but short-lived is recommended)</li>
     * <li>aud: https://appleid.apple.com</li>
     * <li>sub: Apple Service ID (client_id)</li>
     * </ul>
     *
     * <p>
     * The JWT header includes:
     *
     * <ul>
     * <li>alg: ES256</li>
     * <li>kid: Apple Key ID</li>
     * </ul>
     *
     * @return JWT string signed with ES256
     * @throws RuntimeException
     *             if private key loading or JWT signing fails
     */
    private String generateClientSecret() {
        try {
            // Load Apple private key from P8 file
            PrivateKey privateKey = loadPrivateKey(privateKeyPath);

            // Create JWT claims
            Instant now = Instant.now();
            Instant expiration = now.plusSeconds(600); // 10 minutes

            // Build and sign JWT
            String jwt = Jwts.builder().setHeaderParam("kid", keyId).setIssuer(teamId).setIssuedAt(Date.from(now))
                    .setExpiration(Date.from(expiration)).setAudience("https://appleid.apple.com").setSubject(clientId)
                    .signWith(privateKey, SignatureAlgorithm.ES256).compact();

            LOG.debugf("Generated Apple client secret JWT: iss=%s, sub=%s, exp=%s", teamId, clientId, expiration);

            return jwt;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to generate Apple client secret");
            throw new RuntimeException("Apple client secret generation failed", e);
        }
    }

    /**
     * Load Apple private key from P8 file.
     *
     * <p>
     * Reads the PKCS8-encoded private key from the file system, strips the PEM header/footer, decodes Base64, and
     * creates a PrivateKey instance using the EC (Elliptic Curve) algorithm.
     *
     * @param keyPath
     *            path to P8 file (e.g., /secrets/apple-auth-key.p8)
     * @return PrivateKey instance for ES256 signing
     * @throws Exception
     *             if file reading or key parsing fails
     */
    private PrivateKey loadPrivateKey(String keyPath) throws Exception {
        // Read P8 file
        String privateKeyContent = new String(Files.readAllBytes(Paths.get(keyPath)))
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        // Decode Base64
        byte[] keyBytes = Base64.getDecoder().decode(privateKeyContent);

        // Create PrivateKey instance with EC algorithm
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("EC");
        return keyFactory.generatePrivate(keySpec);
    }
}
