package villagecompute.homepage.api.types;

/**
 * Facebook Graph API user info response.
 *
 * <p>
 * Returned by GET https://graph.facebook.com/v18.0/me?fields=id,name,email,picture with Bearer token.
 *
 * <p>
 * Note: Picture URL is nested in JSON structure: picture.data.url
 *
 * @param id
 *            Facebook user ID (unique, stable identifier)
 * @param name
 *            full name
 * @param email
 *            user's email address (may be null if permission denied)
 * @param picture
 *            nested picture data
 */
public record FacebookUserInfoType(String id, String name, String email, FacebookPicture picture) {
}
