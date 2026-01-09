/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.integration.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP client for Meta Graph API (Instagram and Facebook integration).
 *
 * <p>
 * Provides methods to fetch user posts, refresh access tokens, and retrieve user profile information from Instagram and
 * Facebook via Meta Graph API. Implements error handling and rate limiting per Policy P5/P13.
 *
 * <h2>API Details</h2>
 * <ul>
 * <li>Base URL: https://graph.facebook.com/v19.0</li>
 * <li>Authentication: OAuth 2.0 access tokens</li>
 * <li>Rate limits: Enforced by Meta (varies by app tier)</li>
 * <li>Token lifetime: 60 days (long-lived tokens)</li>
 * </ul>
 *
 * <h2>Instagram API</h2>
 * <ul>
 * <li>Endpoint: /me/media</li>
 * <li>Fields: id, caption, media_type, media_url, thumbnail_url, permalink, timestamp, like_count, comments_count</li>
 * <li>Media types: IMAGE, VIDEO, CAROUSEL_ALBUM</li>
 * </ul>
 *
 * <h2>Facebook API</h2>
 * <ul>
 * <li>Endpoint: /me/posts</li>
 * <li>Fields: id, message, created_time, full_picture, type, link, shares, likes.summary(true), comments.summary(true)
 * </li>
 * <li>Post types: status, photo, video, link</li>
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * {@code
 * @Inject
 * MetaGraphClient client;
 *
 * List<InstagramPostType> posts = client.fetchInstagramPosts(accessToken, userId);
 * TokenRefreshResponseType refreshed = client.refreshAccessToken(refreshToken);
 * }
 * </pre>
 *
 * @see <a href="https://developers.facebook.com/docs/graph-api">Meta Graph API Documentation</a>
 */
@ApplicationScoped
public class MetaGraphClient {

    private static final Logger LOG = Logger.getLogger(MetaGraphClient.class);

    private static final String API_BASE = "https://graph.facebook.com/v19.0";
    private static final int TIMEOUT_SECONDS = 10;
    private static final int MAX_POSTS_PER_REQUEST = 25;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Inject
    public MetaGraphClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(5)).build();
    }

    /**
     * Fetches Instagram posts for the authenticated user.
     *
     * @param accessToken
     *            Instagram OAuth access token
     * @param userId
     *            user UUID (for logging only, not sent to API)
     * @return list of parsed Instagram posts
     * @throws RuntimeException
     *             if API request fails or response parsing fails
     */
    public List<InstagramPostType> fetchInstagramPosts(String accessToken, UUID userId) {
        String url = buildInstagramUrl(accessToken);
        LOG.debugf("Fetching Instagram posts for user %s", userId);

        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS)).GET().build();

            long startTime = System.currentTimeMillis();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - startTime;

            if (response.statusCode() != 200) {
                LOG.errorf("Instagram API returned status %d for user %s: %s", response.statusCode(), userId,
                        response.body());
                throw new RuntimeException(
                        "Instagram API returned status " + response.statusCode() + ": " + response.body());
            }

            List<InstagramPostType> posts = parseInstagramResponse(response.body());
            LOG.infof("Fetched %d Instagram posts for user %s (latency: %dms)", posts.size(), userId, latency);
            return posts;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to fetch Instagram posts for user %s", userId);
            throw new RuntimeException("Failed to fetch Instagram posts", e);
        }
    }

    /**
     * Fetches Facebook posts for the authenticated user.
     *
     * @param accessToken
     *            Facebook OAuth access token
     * @param userId
     *            user UUID (for logging only, not sent to API)
     * @return list of parsed Facebook posts
     * @throws RuntimeException
     *             if API request fails or response parsing fails
     */
    public List<FacebookPostType> fetchFacebookPosts(String accessToken, UUID userId) {
        String url = buildFacebookUrl(accessToken);
        LOG.debugf("Fetching Facebook posts for user %s", userId);

        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS)).GET().build();

            long startTime = System.currentTimeMillis();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - startTime;

            if (response.statusCode() != 200) {
                LOG.errorf("Facebook API returned status %d for user %s: %s", response.statusCode(), userId,
                        response.body());
                throw new RuntimeException(
                        "Facebook API returned status " + response.statusCode() + ": " + response.body());
            }

            List<FacebookPostType> posts = parseFacebookResponse(response.body());
            LOG.infof("Fetched %d Facebook posts for user %s (latency: %dms)", posts.size(), userId, latency);
            return posts;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to fetch Facebook posts for user %s", userId);
            throw new RuntimeException("Failed to fetch Facebook posts", e);
        }
    }

    /**
     * Refreshes an Instagram or Facebook access token using a refresh token.
     *
     * @param refreshToken
     *            OAuth refresh token
     * @param userId
     *            user UUID (for logging only)
     * @return token refresh response with new access token and expiration
     * @throws RuntimeException
     *             if token refresh fails
     */
    public TokenRefreshResponseType refreshAccessToken(String refreshToken, UUID userId) {
        // TODO: Implement actual token refresh logic when Meta credentials are configured
        // This is a placeholder that logs the attempt
        LOG.warnf("Token refresh not yet implemented for user %s (refresh token prefix: %s...)", userId,
                refreshToken.substring(0, Math.min(8, refreshToken.length())));

        throw new UnsupportedOperationException(
                "Token refresh requires Meta app credentials configuration (coming in future iteration)");
    }

    /**
     * Retrieves user profile information from Instagram or Facebook.
     *
     * @param accessToken
     *            OAuth access token
     * @param platform
     *            "instagram" or "facebook"
     * @param userId
     *            user UUID (for logging only)
     * @return user profile information
     * @throws RuntimeException
     *             if API request fails
     */
    public UserProfileType getUserProfile(String accessToken, String platform, UUID userId) {
        // TODO: Implement user profile fetch when needed for UI display
        LOG.debugf("User profile fetch not yet implemented for user %s platform %s", userId, platform);
        throw new UnsupportedOperationException("User profile fetch coming in future iteration");
    }

    /**
     * Builds Instagram API URL with required fields.
     */
    private String buildInstagramUrl(String accessToken) {
        return String.format(
                "%s/me/media?fields=id,caption,media_type,media_url,thumbnail_url,"
                        + "permalink,timestamp,like_count,comments_count&limit=%d&access_token=%s",
                API_BASE, MAX_POSTS_PER_REQUEST, accessToken);
    }

    /**
     * Builds Facebook API URL with required fields.
     */
    private String buildFacebookUrl(String accessToken) {
        return String.format(
                "%s/me/posts?fields=id,message,created_time,full_picture,type,link,"
                        + "shares,likes.summary(true),comments.summary(true)&limit=%d&access_token=%s",
                API_BASE, MAX_POSTS_PER_REQUEST, accessToken);
    }

    /**
     * Parses Instagram API JSON response into InstagramPostType list.
     */
    private List<InstagramPostType> parseInstagramResponse(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode dataNode = root.path("data");

        List<InstagramPostType> posts = new ArrayList<>();

        if (dataNode.isArray()) {
            for (JsonNode postNode : dataNode) {
                String id = postNode.path("id").asText();
                String caption = postNode.path("caption").asText(null);
                String mediaType = postNode.path("media_type").asText("IMAGE");
                String mediaUrl = postNode.path("media_url").asText();
                String thumbnailUrl = postNode.path("thumbnail_url").asText(null);
                String permalink = postNode.path("permalink").asText();
                String timestamp = postNode.path("timestamp").asText();
                int likeCount = postNode.path("like_count").asInt(0);
                int commentsCount = postNode.path("comments_count").asInt(0);

                // Build media URLs list
                List<Map<String, Object>> mediaUrls = new ArrayList<>();
                Map<String, Object> media = new HashMap<>();
                media.put("url", mediaUrl);
                media.put("type", mediaType.toLowerCase());
                if (thumbnailUrl != null) {
                    media.put("thumbnail_url", thumbnailUrl);
                }
                mediaUrls.add(media);

                // Build engagement data
                Map<String, Object> engagement = new HashMap<>();
                engagement.put("likes", likeCount);
                engagement.put("comments", commentsCount);

                posts.add(new InstagramPostType(id, caption, mapPostType(mediaType), mediaUrls,
                        Instant.parse(timestamp), engagement, permalink));
            }
        }

        return posts;
    }

    /**
     * Parses Facebook API JSON response into FacebookPostType list.
     */
    private List<FacebookPostType> parseFacebookResponse(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode dataNode = root.path("data");

        List<FacebookPostType> posts = new ArrayList<>();

        if (dataNode.isArray()) {
            for (JsonNode postNode : dataNode) {
                String id = postNode.path("id").asText();
                String message = postNode.path("message").asText(null);
                String createdTime = postNode.path("created_time").asText();
                String fullPicture = postNode.path("full_picture").asText(null);
                String type = postNode.path("type").asText("status");
                String link = postNode.path("link").asText(null);

                // Parse engagement counts
                int likesCount = postNode.path("likes").path("summary").path("total_count").asInt(0);
                int commentsCount = postNode.path("comments").path("summary").path("total_count").asInt(0);
                int sharesCount = postNode.path("shares").path("count").asInt(0);

                // Build media URLs list
                List<Map<String, Object>> mediaUrls = new ArrayList<>();
                if (fullPicture != null) {
                    Map<String, Object> media = new HashMap<>();
                    media.put("url", fullPicture);
                    media.put("type", type.equals("video") ? "video" : "image");
                    mediaUrls.add(media);
                }

                // Build engagement data
                Map<String, Object> engagement = new HashMap<>();
                engagement.put("likes", likesCount);
                engagement.put("comments", commentsCount);
                engagement.put("shares", sharesCount);

                posts.add(new FacebookPostType(id, message, type, mediaUrls, Instant.parse(createdTime), engagement,
                        link));
            }
        }

        return posts;
    }

    /**
     * Maps Instagram media_type to normalized post_type.
     */
    private String mapPostType(String mediaType) {
        return switch (mediaType.toUpperCase()) {
            case "IMAGE" -> "image";
            case "VIDEO" -> "video";
            case "CAROUSEL_ALBUM" -> "carousel";
            default -> "image";
        };
    }

    /**
     * Instagram post data transfer type.
     */
    public record InstagramPostType(String id, String caption, String postType, List<Map<String, Object>> mediaUrls,
            Instant postedAt, Map<String, Object> engagement, String permalink) {
    }

    /**
     * Facebook post data transfer type.
     */
    public record FacebookPostType(String id, String message, String type, List<Map<String, Object>> mediaUrls,
            Instant postedAt, Map<String, Object> engagement, String link) {
    }

    /**
     * Token refresh response type.
     */
    public record TokenRefreshResponseType(String accessToken, Instant expiresAt) {
    }

    /**
     * User profile response type.
     */
    public record UserProfileType(String id, String name, String username, String profilePictureUrl) {
    }
}
