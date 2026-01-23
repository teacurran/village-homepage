package villagecompute.homepage.api.types;

/**
 * Facebook picture container (nested in FacebookUserInfoType).
 *
 * @param data
 *            nested picture data with URL
 */
public record FacebookPicture(FacebookPictureData data) {
}
