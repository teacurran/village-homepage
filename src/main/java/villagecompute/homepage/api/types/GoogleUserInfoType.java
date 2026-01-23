package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Google OAuth 2.0 user info response.
 *
 * <p>
 * Returned by GET https://www.googleapis.com/oauth2/v2/userinfo with Bearer token.
 *
 * @param sub
 *            Google user ID (unique, stable identifier)
 * @param email
 *            user's email address
 * @param emailVerified
 *            whether email has been verified by Google
 * @param name
 *            full name
 * @param givenName
 *            first name
 * @param familyName
 *            last name
 * @param picture
 *            profile picture URL
 * @param locale
 *            user's locale (e.g., "en")
 */
public record GoogleUserInfoType(String sub, String email, @JsonProperty("email_verified") boolean emailVerified,
        String name, @JsonProperty("given_name") String givenName, @JsonProperty("family_name") String familyName,
        String picture, String locale) {
}
