package villagecompute.homepage.api.types;

/**
 * Apple Sign-In ID token claims.
 *
 * <p>
 * Decoded from the JWT ID token returned by Apple's token endpoint. Contains user identity information.
 *
 * <p>
 * Important: The {@code name} claim is ONLY provided on the first sign-in. All subsequent logins omit this claim. Your
 * code must handle missing names gracefully by using a fallback (email prefix or "Apple User").
 *
 * <p>
 * Privacy Note: Apple supports "Hide My Email" - users can provide relay emails like
 * {@code abc123xyz@privaterelay.appleid.com}. These relay emails are valid and should be stored as-is.
 *
 * @param sub
 *            Apple user ID (unique, stable identifier for this user)
 * @param email
 *            user email (may be Apple relay email like {@code @privaterelay.appleid.com})
 * @param emailVerified
 *            email verification status (always true for Apple)
 * @param displayName
 *            user's full name (null on subsequent logins after first sign-in)
 */
public record AppleIdTokenClaims(String sub, String email, Boolean emailVerified, String displayName) {
}
