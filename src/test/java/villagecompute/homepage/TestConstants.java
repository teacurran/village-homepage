package villagecompute.homepage;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Test constants for all integration and unit tests.
 *
 * <p>
 * Organized by domain for easy discovery:
 * <ul>
 * <li>User domain: email addresses, display names, session hashes, OAuth</li>
 * <li>Widget domain: widget IDs, types, and configurations</li>
 * <li>Theme domain: theme modes, colors, contrast levels</li>
 * <li>Marketplace domain: listings, categories, prices</li>
 * <li>Directory domain: sites, categories, URLs, statuses</li>
 * <li>Feed domain: RSS sources, feed items</li>
 * <li>Stock domain: symbols and exchanges</li>
 * <li>Location domain: cities, coordinates</li>
 * <li>External API: test keys for third-party services</li>
 * <li>Error messages: validation errors</li>
 * </ul>
 *
 * <p>
 * Per Foundation Blueprint Section 3.5: "All repeated strings MUST be constants."
 *
 * <p>
 * <b>Ref:</b> Task I1.T3, Foundation Blueprint Section 3.5
 *
 * @see BaseIntegrationTest
 */
public final class TestConstants {

    /** Prevent instantiation. */
    private TestConstants() {
    }

    // ========== USER DOMAIN ==========

    /** Valid test email address. */
    public static final String VALID_EMAIL = "test@example.com";

    /** Secondary valid test email address. */
    public static final String VALID_EMAIL_2 = "user@villagecompute.com";

    /** Invalid email address (missing domain). */
    public static final String INVALID_EMAIL = "invalid-email";

    /** Invalid email address (missing local part). */
    public static final String INVALID_EMAIL_NO_LOCAL = "@nodomain.com";

    /** Invalid email address (missing @ symbol). */
    public static final String INVALID_EMAIL_NO_AT = "missing-at-domain.com";

    /** Test user display name. */
    public static final String TEST_USER_DISPLAY_NAME = "Test User";

    /** Test UUID for user entity. */
    public static final UUID TEST_USER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** Test session hash for anonymous user. */
    public static final String TEST_SESSION_HASH = "test-session-hash-12345";

    // ========== OAUTH DOMAIN ==========

    /** OAuth provider: Google. */
    public static final String OAUTH_PROVIDER_GOOGLE = "google";

    /** OAuth provider: Facebook. */
    public static final String OAUTH_PROVIDER_FACEBOOK = "facebook";

    /** OAuth provider: Apple. */
    public static final String OAUTH_PROVIDER_APPLE = "apple";

    /** Test Google OAuth ID. */
    public static final String OAUTH_GOOGLE_ID = "google-123";

    /** Test Facebook OAuth ID. */
    public static final String OAUTH_FACEBOOK_ID = "facebook-456";

    /** Test avatar URL. */
    public static final String AVATAR_URL = "https://example.com/avatar.jpg";

    // OAuth flow test data
    /** Test OAuth authorization code. */
    public static final String TEST_OAUTH_CODE = "test-auth-code-12345";

    /** Test OAuth state token. */
    public static final String TEST_OAUTH_STATE = "test-state-token";

    /** Test OAuth redirect URI. */
    public static final String TEST_OAUTH_REDIRECT_URI = "http://localhost:8080/oauth/callback";

    // Google OAuth test data
    /** Test Google OAuth access token. */
    public static final String TEST_GOOGLE_ACCESS_TOKEN = "ya29.test-google-token";

    /** Test Google OAuth user ID. */
    public static final String TEST_GOOGLE_USER_ID = "google-user-12345";

    /** Test Google OAuth email. */
    public static final String TEST_GOOGLE_EMAIL = "test@gmail.com";

    /** Test Google OAuth display name. */
    public static final String TEST_GOOGLE_NAME = "Google Test User";

    /** Test Google OAuth refresh token. */
    public static final String TEST_GOOGLE_REFRESH_TOKEN = "1//0gHdj8s-test-refresh";

    // Facebook OAuth test data
    /** Test Facebook OAuth access token. */
    public static final String TEST_FACEBOOK_ACCESS_TOKEN = "EAAtest-facebook-token";

    /** Test Facebook OAuth user ID. */
    public static final String TEST_FACEBOOK_USER_ID = "facebook-user-12345";

    /** Test Facebook OAuth email. */
    public static final String TEST_FACEBOOK_EMAIL = "test@facebook.com";

    /** Test Facebook OAuth display name. */
    public static final String TEST_FACEBOOK_NAME = "Facebook Test User";

    // Apple OAuth test data
    /** Test Apple OAuth ID token (JWT). */
    public static final String TEST_APPLE_ID_TOKEN = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.test";

    /** Test Apple OAuth user ID (sub claim). */
    public static final String TEST_APPLE_USER_ID = "apple-user-12345";

    /** Test Apple OAuth email. */
    public static final String TEST_APPLE_EMAIL = "test@appleid.com";

    /** Test Apple OAuth display name. */
    public static final String TEST_APPLE_NAME = "Apple Test User";

    /** Test Apple OAuth refresh token. */
    public static final String TEST_APPLE_REFRESH_TOKEN = "apple-refresh-token-test";

    // ========== WIDGET DOMAIN ==========

    /** Widget ID: news. */
    public static final String WIDGET_ID_NEWS = "news";

    /** Widget ID: weather. */
    public static final String WIDGET_ID_WEATHER = "weather";

    /** Widget ID: stocks. */
    public static final String WIDGET_ID_STOCKS = "stocks";

    /** Widget ID: social. */
    public static final String WIDGET_ID_SOCIAL = "social";

    /** Widget ID: search. */
    public static final String WIDGET_ID_SEARCH = "search";

    /** Widget ID: custom. */
    public static final String WIDGET_ID_CUSTOM = "custom";

    /** Widget type: news feed. */
    public static final String WIDGET_TYPE_NEWS_FEED = "news_feed";

    /** Widget type: weather. */
    public static final String WIDGET_TYPE_WEATHER = "weather";

    /** Widget type: stocks. */
    public static final String WIDGET_TYPE_STOCKS = "stocks";

    /** Widget type: social feed. */
    public static final String WIDGET_TYPE_SOCIAL_FEED = "social_feed";

    /** Widget type: search bar. */
    public static final String WIDGET_TYPE_SEARCH_BAR = "search_bar";

    /** Widget type: RSS feed. */
    public static final String WIDGET_TYPE_RSS_FEED = "rss_feed";

    // ========== THEME DOMAIN ==========

    /** Theme mode: system. */
    public static final String THEME_MODE_SYSTEM = "system";

    /** Theme mode: dark. */
    public static final String THEME_MODE_DARK = "dark";

    /** Theme mode: light. */
    public static final String THEME_MODE_LIGHT = "light";

    /** Theme accent color: orange-red. */
    public static final String THEME_ACCENT_COLOR_1 = "#FF5733";

    /** Theme accent color: green. */
    public static final String THEME_ACCENT_COLOR_2 = "#00FF00";

    /** Theme contrast: standard. */
    public static final String THEME_CONTRAST_STANDARD = "standard";

    /** Theme contrast: high. */
    public static final String THEME_CONTRAST_HIGH = "high";

    // ========== STOCK DOMAIN ==========

    /** Stock symbol: Apple. */
    public static final String STOCK_SYMBOL_AAPL = "AAPL";

    /** Stock symbol: Google. */
    public static final String STOCK_SYMBOL_GOOGL = "GOOGL";

    /** Stock symbol: Microsoft. */
    public static final String STOCK_SYMBOL_MSFT = "MSFT";

    /** Stock symbol: Tesla. */
    public static final String STOCK_SYMBOL_TSLA = "TSLA";

    /** Stock symbol: Amazon. */
    public static final String STOCK_SYMBOL_AMZN = "AMZN";

    /** Stock symbol: GameStop. */
    public static final String STOCK_SYMBOL_GME = "GME";

    // ========== NEWS TOPIC DOMAIN ==========

    /** News topic: technology. */
    public static final String NEWS_TOPIC_TECHNOLOGY = "technology";

    /** News topic: science. */
    public static final String NEWS_TOPIC_SCIENCE = "science";

    /** News topic: business. */
    public static final String NEWS_TOPIC_BUSINESS = "business";

    /** News topic: health. */
    public static final String NEWS_TOPIC_HEALTH = "health";

    /** News topic: sports. */
    public static final String NEWS_TOPIC_SPORTS = "sports";

    /** News topic: entertainment. */
    public static final String NEWS_TOPIC_ENTERTAINMENT = "entertainment";

    // ========== LOCATION DOMAIN ==========

    /** Location: San Francisco, CA. */
    public static final String LOCATION_SF = "San Francisco, CA";

    /** Location: New York, NY. */
    public static final String LOCATION_NY = "New York, NY";

    /** Location: London, UK. */
    public static final String LOCATION_LONDON = "London, UK";

    /** Test city ID for San Francisco. */
    public static final Long TEST_CITY_ID_SF = 123L;

    /** Test city ID for New York. */
    public static final Long TEST_CITY_ID_NY = 456L;

    /** Test city ID for London. */
    public static final Long TEST_CITY_ID_LONDON = 789L;

    /** San Francisco latitude. */
    public static final double LATITUDE_SF = 37.7749;

    /** San Francisco longitude. */
    public static final double LONGITUDE_SF = -122.4194;

    /** New York latitude. */
    public static final double LATITUDE_NY = 40.7128;

    /** New York longitude. */
    public static final double LONGITUDE_NY = -74.0060;

    /** London latitude. */
    public static final double LATITUDE_LONDON = 51.5074;

    /** London longitude. */
    public static final double LONGITUDE_LONDON = -0.1278;

    // ========== MARKETPLACE DOMAIN ==========

    /** Test marketplace listing title. */
    public static final String TEST_LISTING_TITLE = "Vintage Bicycle";

    /** Test marketplace listing description. */
    public static final String TEST_LISTING_DESCRIPTION = "Great condition";

    /** Test marketplace listing price. */
    public static final BigDecimal TEST_LISTING_PRICE = new BigDecimal("250.00");

    /** Marketplace category: for-sale. */
    public static final String MARKETPLACE_CATEGORY_FOR_SALE = "for-sale";

    /** Marketplace category: housing. */
    public static final String MARKETPLACE_CATEGORY_HOUSING = "housing";

    /** Marketplace category: jobs. */
    public static final String MARKETPLACE_CATEGORY_JOBS = "jobs";

    /** Marketplace category: services. */
    public static final String MARKETPLACE_CATEGORY_SERVICES = "services";

    // ========== DIRECTORY DOMAIN ==========

    /** Test directory site URL. */
    public static final String TEST_SITE_URL = "https://example.com";

    /** Test directory site URL 2 (Hacker News). */
    public static final String TEST_SITE_URL_2 = "https://news.ycombinator.com";

    /** Test directory site URL 3. */
    public static final String TEST_SITE_URL_3 = "https://example1.com";

    /** Test directory site URL 4. */
    public static final String TEST_SITE_URL_4 = "https://example2.com";

    /** Pending site URL. */
    public static final String PENDING_SITE_URL = "https://pending1.com";

    /** Approved site URL. */
    public static final String APPROVED_SITE_URL = "https://approved1.com";

    /** Test directory site domain. */
    public static final String TEST_SITE_DOMAIN = "example.com";

    /** Test directory site domain 2 (Hacker News). */
    public static final String TEST_SITE_DOMAIN_2 = "news.ycombinator.com";

    /** Test directory site title. */
    public static final String TEST_SITE_TITLE = "Example Site";

    /** Test directory site title 2 (Hacker News). */
    public static final String TEST_SITE_TITLE_2 = "Hacker News";

    /** Test directory site title 3. */
    public static final String TEST_SITE_TITLE_3 = "Site 1";

    /** Test directory site title 4. */
    public static final String TEST_SITE_TITLE_4 = "Site 2";

    /** Pending site title. */
    public static final String PENDING_SITE_TITLE = "Pending 1";

    /** Approved site title. */
    public static final String APPROVED_SITE_TITLE = "Approved 1";

    /** Test directory site description. */
    public static final String TEST_SITE_DESCRIPTION = "A test site";

    /** Test directory category slug. */
    public static final String TEST_CATEGORY_SLUG = "technology";

    /** Directory status: pending. */
    public static final String DIRECTORY_STATUS_PENDING = "pending";

    /** Directory status: approved. */
    public static final String DIRECTORY_STATUS_APPROVED = "approved";

    /** Directory status: rejected. */
    public static final String DIRECTORY_STATUS_REJECTED = "rejected";

    /** Trust level: untrusted. */
    public static final String TRUST_LEVEL_UNTRUSTED = "untrusted";

    /** Trust level: trusted. */
    public static final String TRUST_LEVEL_TRUSTED = "trusted";

    // ========== FEED DOMAIN ==========

    /** Test RSS feed URL. */
    public static final String TEST_RSS_URL = "https://example.com/rss.xml";

    /** Test RSS feed title. */
    public static final String TEST_FEED_TITLE = "Example RSS Feed";

    /** Test feed item title. */
    public static final String TEST_FEED_ITEM_TITLE = "Breaking News: Something Happened";

    // ========== EXTERNAL API KEYS ==========

    /** Test Anthropic API key (placeholder). */
    public static final String ANTHROPIC_API_KEY_TEST = "test-anthropic-api-key-12345";

    /** Test Alpha Vantage API key (placeholder). */
    public static final String ALPHA_VANTAGE_API_KEY_TEST = "test-alpha-vantage-key-abcde";

    // ========== ERROR MESSAGES ==========

    /** Validation error: invalid email format. */
    public static final String VALIDATION_ERROR_EMAIL = "Invalid email format";

    /** Validation error: required field missing. */
    public static final String VALIDATION_ERROR_REQUIRED = "This field is required";

    /** Validation error: URL invalid. */
    public static final String VALIDATION_ERROR_URL = "Invalid URL format";

    /** Validation error: price must be positive. */
    public static final String VALIDATION_ERROR_PRICE = "Price must be positive";
}
