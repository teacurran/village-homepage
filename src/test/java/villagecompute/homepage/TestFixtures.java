package villagecompute.homepage;

import villagecompute.homepage.data.models.*;

import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static villagecompute.homepage.TestConstants.*;

/**
 * Test fixture factory methods for creating persisted test entities.
 *
 * <p>
 * All factory methods:
 * <ul>
 * <li>Return fully persisted entities (call .persist() before returning)</li>
 * <li>Use TestConstants for default values</li>
 * <li>Support both default and parameterized versions</li>
 * <li>Generate valid timestamps (Instant.now())</li>
 * </ul>
 *
 * <p>
 * Per Foundation Blueprint Section 3.5: "All test entity creation via factory methods."
 *
 * <p>
 * <b>Usage Example:</b>
 *
 * <pre>
 * &#64;Test
 * &#64;TestTransaction
 * public void testUserListing() {
 *     User user = TestFixtures.createTestUser();
 *     MarketplaceListing listing = TestFixtures.createTestListing(user);
 *     assertNotNull(listing.id);
 * }
 * </pre>
 *
 * <p>
 * <b>Ref:</b> Task I1.T4, Foundation Blueprint Section 3.5
 *
 * @see TestConstants for default test values
 * @see BaseIntegrationTest for assertion helpers
 */
public final class TestFixtures {

    /** Prevent instantiation. */
    private TestFixtures() {
    }

    // ========== USER FIXTURES ==========

    /**
     * Creates a test user with default values from TestConstants.
     *
     * <p>
     * Default values:
     * <ul>
     * <li>email: {@link TestConstants#VALID_EMAIL}</li>
     * <li>displayName: {@link TestConstants#TEST_USER_DISPLAY_NAME}</li>
     * <li>isAnonymous: false</li>
     * <li>directoryTrustLevel: {@link TestConstants#TRUST_LEVEL_UNTRUSTED}</li>
     * </ul>
     *
     * @return persisted User entity with generated UUID
     */
    public static User createTestUser() {
        return createTestUser(VALID_EMAIL, TEST_USER_DISPLAY_NAME);
    }

    /**
     * Creates a test user with custom email and display name.
     *
     * @param email
     *            the user's email address
     * @param displayName
     *            the user's display name
     * @return persisted User entity with generated UUID
     */
    public static User createTestUser(String email, String displayName) {
        User user = new User();
        user.email = email;
        user.displayName = displayName;
        user.isAnonymous = false;
        user.preferences = new java.util.HashMap<>(); // CRITICAL: Use mutable HashMap for JSONB serialization
        user.directoryKarma = 0;
        user.directoryTrustLevel = TRUST_LEVEL_UNTRUSTED;
        user.analyticsConsent = false;
        user.isBanned = false;
        user.createdAt = Instant.now();
        user.updatedAt = Instant.now();
        user.lastActiveAt = Instant.now();
        user.persist();
        return user;
    }

    /**
     * Creates an authenticated OAuth user for testing.
     *
     * <p>
     * Default values:
     * <ul>
     * <li>isAnonymous: false</li>
     * <li>directoryTrustLevel: {@link TestConstants#TRUST_LEVEL_UNTRUSTED}</li>
     * <li>avatarUrl: {@link TestConstants#AVATAR_URL}</li>
     * </ul>
     *
     * @param email
     *            the user's email address
     * @param provider
     *            the OAuth provider ('google', 'facebook', 'apple')
     * @param providerId
     *            the OAuth provider's user ID
     * @return persisted User entity with OAuth credentials
     */
    public static User createOAuthUser(String email, String provider, String providerId) {
        return createOAuthUser(email, provider, providerId, email.split("@")[0]);
    }

    /**
     * Creates an authenticated OAuth user with custom display name.
     *
     * @param email
     *            the user's email address
     * @param provider
     *            the OAuth provider ('google', 'facebook', 'apple')
     * @param providerId
     *            the OAuth provider's user ID
     * @param displayName
     *            the user's display name
     * @return persisted User entity with OAuth credentials
     */
    public static User createOAuthUser(String email, String provider, String providerId, String displayName) {
        User user = new User();
        user.email = email;
        user.oauthProvider = provider;
        user.oauthId = providerId;
        user.displayName = displayName;
        user.avatarUrl = AVATAR_URL;
        user.isAnonymous = false;
        user.preferences = new java.util.HashMap<>();
        user.directoryKarma = 0;
        user.directoryTrustLevel = TRUST_LEVEL_UNTRUSTED;
        user.analyticsConsent = false;
        user.isBanned = false;
        user.createdAt = Instant.now();
        user.updatedAt = Instant.now();
        user.lastActiveAt = Instant.now();
        user.persist();
        return user;
    }

    /**
     * Creates an anonymous user for testing account merge scenarios.
     *
     * <p>
     * Default values:
     * <ul>
     * <li>isAnonymous: true</li>
     * <li>email: null</li>
     * <li>oauthProvider: null</li>
     * <li>oauthId: null</li>
     * </ul>
     *
     * @param sessionHash
     *            the anonymous session hash (for test reference, not stored in User entity)
     * @return persisted anonymous User entity
     */
    public static User createAnonymousUser(String sessionHash) {
        User user = new User();
        // Note: sessionHash is not a User field - it's tracked via cookies in production
        // For tests, we pass it as parameter but don't store it in the entity
        user.isAnonymous = true;
        user.displayName = "Anonymous " + sessionHash.substring(0, 8);
        user.preferences = new java.util.HashMap<>();
        user.directoryKarma = 0;
        user.directoryTrustLevel = TRUST_LEVEL_UNTRUSTED;
        user.analyticsConsent = false;
        user.isBanned = false;
        user.createdAt = Instant.now();
        user.updatedAt = Instant.now();
        user.lastActiveAt = Instant.now();
        user.persist();
        return user;
    }

    /**
     * Creates an anonymous user with test data for merge testing.
     *
     * <p>
     * Creates an anonymous user and associates:
     * <ul>
     * <li>One marketplace listing (active, 30-day expiration)</li>
     * <li>One notification (unread)</li>
     * <li>Preferences with theme settings</li>
     * </ul>
     *
     * <p>
     * This fixture is useful for testing account upgrade and data preservation during merge operations.
     *
     * @param sessionHash
     *            the anonymous session hash
     * @return persisted anonymous User entity with associated data
     */
    public static User createAnonymousUserWithData(String sessionHash) {
        User user = createAnonymousUser(sessionHash);

        // Add preferences
        user.preferences = new java.util.HashMap<>();
        user.preferences.put("theme", "dark");
        user.preferences.put("notifications_enabled", true);
        user.persist();

        // Add marketplace listing
        createTestListing(user);

        // Add notification
        UserNotification notification = new UserNotification();
        notification.userId = user.id;
        notification.type = "system";
        notification.title = "Welcome";
        notification.message = "Welcome to Village Homepage";
        notification.createdAt = Instant.now();
        notification.persist();

        return user;
    }

    // ========== MARKETPLACE LISTING FIXTURES ==========

    /**
     * Creates a test marketplace listing with default values from TestConstants.
     *
     * <p>
     * Default values:
     * <ul>
     * <li>title: {@link TestConstants#TEST_LISTING_TITLE}</li>
     * <li>description: {@link TestConstants#TEST_LISTING_DESCRIPTION}</li>
     * <li>price: {@link TestConstants#TEST_LISTING_PRICE}</li>
     * <li>status: "active"</li>
     * <li>expiresAt: 30 days from now</li>
     * </ul>
     *
     * <p>
     * <b>Note:</b> GeoCity location is not set (nullable) as geo data import is scheduled for I6.T2.
     *
     * @param owner
     *            the user who owns the listing
     * @return persisted MarketplaceListing entity with generated UUID
     */
    public static MarketplaceListing createTestListing(User owner) {
        return createTestListing(owner, TEST_LISTING_TITLE, TEST_LISTING_DESCRIPTION, TEST_LISTING_PRICE);
    }

    /**
     * Creates a test marketplace listing with custom field values.
     *
     * @param owner
     *            the user who owns the listing
     * @param title
     *            the listing title
     * @param description
     *            the listing description
     * @param price
     *            the listing price
     * @return persisted MarketplaceListing entity with generated UUID
     */
    public static MarketplaceListing createTestListing(User owner, String title, String description, BigDecimal price) {
        MarketplaceListing listing = new MarketplaceListing();
        listing.userId = owner.id;
        listing.categoryId = UUID.randomUUID(); // Placeholder until categories implemented
        listing.title = title;
        listing.description = description;
        listing.price = price;
        listing.contactInfo = new villagecompute.homepage.api.types.ContactInfoType(VALID_EMAIL, null,
                "listing-" + UUID.randomUUID() + "@villagecompute.com");
        listing.status = "active";
        listing.expiresAt = Instant.now().plus(Duration.ofDays(30));
        listing.reminderSent = false;
        listing.flagCount = 0L;
        listing.createdAt = Instant.now();
        listing.updatedAt = Instant.now();
        listing.persist();
        return listing;
    }

    // ========== DIRECTORY SITE FIXTURES ==========

    /**
     * Creates a test directory site with default values from TestConstants.
     *
     * <p>
     * Default values:
     * <ul>
     * <li>url: {@link TestConstants#TEST_SITE_URL}</li>
     * <li>domain: extracted from URL</li>
     * <li>title: {@link TestConstants#TEST_SITE_TITLE}</li>
     * <li>description: {@link TestConstants#TEST_SITE_DESCRIPTION}</li>
     * <li>status: {@link TestConstants#DIRECTORY_STATUS_APPROVED}</li>
     * </ul>
     *
     * @param submitter
     *            the user who submitted the site
     * @return persisted DirectorySite entity with generated UUID
     */
    public static DirectorySite createTestDirectorySite(User submitter) {
        return createTestDirectorySite(submitter, TEST_SITE_URL, TEST_SITE_TITLE);
    }

    /**
     * Creates a test directory site with custom URL and title.
     *
     * @param submitter
     *            the user who submitted the site
     * @param url
     *            the site URL
     * @param title
     *            the site title
     * @return persisted DirectorySite entity with generated UUID
     */
    public static DirectorySite createTestDirectorySite(User submitter, String url, String title) {
        DirectorySite site = new DirectorySite();
        site.url = url;
        try {
            URL urlObj = new URL(url);
            site.domain = urlObj.getHost();
        } catch (MalformedURLException e) {
            site.domain = "example.com"; // Fallback for malformed URLs
        }
        site.title = title;
        site.description = TEST_SITE_DESCRIPTION;
        site.submittedByUserId = submitter.id;
        site.status = DIRECTORY_STATUS_APPROVED; // Default to approved for most tests
        site.isDead = false;
        site.healthCheckFailures = 0;
        site.createdAt = Instant.now();
        site.updatedAt = Instant.now();
        site.persist();
        return site;
    }

    // ========== FEED ITEM FIXTURES ==========

    /**
     * Creates a test feed item with default values from TestConstants.
     *
     * <p>
     * Default values:
     * <ul>
     * <li>title: {@link TestConstants#TEST_FEED_ITEM_TITLE}</li>
     * <li>url: {@link TestConstants#TEST_SITE_URL}</li>
     * <li>itemGuid: "test-feed-item-" + random UUID</li>
     * <li>aiTagged: false</li>
     * </ul>
     *
     * @param source
     *            the RSS source this item belongs to
     * @return persisted FeedItem entity with generated UUID
     */
    public static FeedItem createTestFeedItem(RssSource source) {
        return createTestFeedItem(source, TEST_FEED_ITEM_TITLE);
    }

    /**
     * Creates a test feed item with custom title.
     *
     * @param source
     *            the RSS source this item belongs to
     * @param title
     *            the feed item title
     * @return persisted FeedItem entity with generated UUID
     */
    public static FeedItem createTestFeedItem(RssSource source, String title) {
        FeedItem item = new FeedItem();
        item.sourceId = source.id;
        item.title = title;
        item.url = TEST_SITE_URL;
        item.description = "Test feed item description";
        item.itemGuid = "test-feed-item-" + UUID.randomUUID(); // Ensure uniqueness
        item.publishedAt = Instant.now();
        item.aiTagged = false;
        item.fetchedAt = Instant.now();
        item.persist();
        return item;
    }

    // ========== RSS SOURCE FIXTURES ==========

    /**
     * Creates a test RSS source with default values from TestConstants.
     *
     * <p>
     * Default values:
     * <ul>
     * <li>url: {@link TestConstants#TEST_RSS_URL}</li>
     * <li>name: {@link TestConstants#TEST_FEED_TITLE}</li>
     * <li>isSystem: false (user feed)</li>
     * <li>isActive: true</li>
     * <li>refreshIntervalMinutes: 60</li>
     * </ul>
     *
     * @return persisted RssSource entity with generated UUID
     */
    public static RssSource createTestRssSource() {
        return createTestRssSource(TEST_RSS_URL, TEST_FEED_TITLE);
    }

    /**
     * Creates a test RSS source with custom feed URL and title.
     *
     * @param feedUrl
     *            the RSS feed URL
     * @param title
     *            the feed display name
     * @return persisted RssSource entity with generated UUID
     */
    public static RssSource createTestRssSource(String feedUrl, String title) {
        RssSource source = new RssSource();
        source.url = feedUrl;
        source.name = title;
        source.category = NEWS_TOPIC_TECHNOLOGY; // Default category from TestConstants
        source.isSystem = false; // User feed by default
        source.isActive = true;
        source.refreshIntervalMinutes = 60;
        source.errorCount = 0;
        source.createdAt = Instant.now();
        source.updatedAt = Instant.now();
        source.persist();
        return source;
    }

    // ========== DIRECTORY CATEGORY FIXTURES ==========

    /**
     * Creates a test directory category as a root category (no parent).
     *
     * <p>
     * Default values:
     * <ul>
     * <li>name: "Technology"</li>
     * <li>slug: {@link TestConstants#TEST_CATEGORY_SLUG}</li>
     * <li>parentId: null (root category)</li>
     * <li>isActive: true</li>
     * <li>sortOrder: 0</li>
     * </ul>
     *
     * @return persisted DirectoryCategory entity with generated UUID
     */
    public static DirectoryCategory createTestDirectoryCategory() {
        return createTestDirectoryCategory("Technology", TEST_CATEGORY_SLUG, null);
    }

    /**
     * Creates a test directory category with custom name, slug, and parent.
     *
     * @param name
     *            the category display name
     * @param slug
     *            the URL-friendly identifier
     * @param parent
     *            the parent category (null for root categories)
     * @return persisted DirectoryCategory entity with generated UUID
     */
    public static DirectoryCategory createTestDirectoryCategory(String name, String slug, DirectoryCategory parent) {
        DirectoryCategory category = new DirectoryCategory();
        category.name = name;
        category.slug = slug;
        category.parentId = parent != null ? parent.id : null;
        category.description = "Test category description";
        category.sortOrder = 0;
        category.linkCount = 0;
        category.isActive = true;
        category.createdAt = Instant.now();
        category.updatedAt = Instant.now();
        category.persist();
        return category;
    }
}
