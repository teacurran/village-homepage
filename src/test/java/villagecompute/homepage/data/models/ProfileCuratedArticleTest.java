package villagecompute.homepage.data.models;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.exceptions.ValidationException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ProfileCuratedArticle model (Feature F11).
 *
 * <p>
 * Coverage:
 * <ul>
 * <li>Article creation from feed item</li>
 * <li>Manual article creation</li>
 * <li>Customization (headline, blurb, image overrides)</li>
 * <li>Effective field getters (custom fallback to original)</li>
 * <li>Active/inactive toggle</li>
 * <li>Query methods (findByProfile, findActive, findByFeedItem)</li>
 * <li>Slot assignment updates</li>
 * </ul>
 */
@QuarkusTest
public class ProfileCuratedArticleTest {

    private UUID testUserId;
    private UUID testProfileId;

    @BeforeEach
    @Transactional
    public void setUp() {
        // Create test user
        User testUser = new User();
        testUser.email = "testarticle" + System.currentTimeMillis() + "@example.com";
        testUser.isAnonymous = false;
        testUser.directoryTrustLevel = User.TRUST_LEVEL_UNTRUSTED;
        testUser.preferences = Map.of();
        testUser.createdAt = Instant.now();
        testUser.updatedAt = Instant.now();
        testUser.persist();
        testUserId = testUser.id;

        // Create test profile
        UserProfile testProfile = UserProfile.createProfile(testUserId, "testarticle" + System.currentTimeMillis());
        testProfileId = testProfile.id;
    }

    @AfterEach
    @Transactional
    public void tearDown() {
        // Clean up (cascade will handle articles)
        UserProfile.delete("id = ?1", testProfileId);
        User.delete("id = ?1", testUserId);
    }

    // ========== Article Creation Tests ==========

    @Test
    @Transactional
    public void testCreateFromFeedItem_Success() {
        UUID feedItemId = UUID.randomUUID();
        ProfileCuratedArticle article = ProfileCuratedArticle.createFromFeedItem(testProfileId, feedItemId,
                "https://example.com/article", "Test Article", "Test description", "https://example.com/image.jpg");

        assertNotNull(article.id);
        assertEquals(testProfileId, article.profileId);
        assertEquals(feedItemId, article.feedItemId);
        assertEquals("https://example.com/article", article.originalUrl);
        assertEquals("Test Article", article.originalTitle);
        assertEquals("Test description", article.originalDescription);
        assertEquals("https://example.com/image.jpg", article.originalImageUrl);
        assertTrue(article.isActive);
        assertNotNull(article.createdAt);
        assertNotNull(article.updatedAt);
    }

    @Test
    @Transactional
    public void testCreateFromFeedItem_NullProfileId() {
        ValidationException ex = assertThrows(ValidationException.class, () -> {
            ProfileCuratedArticle.createFromFeedItem(null, UUID.randomUUID(), "https://example.com/article",
                    "Test Article", "Test description", "https://example.com/image.jpg");
        });
        assertTrue(ex.getMessage().contains("Profile ID cannot be null"));
    }

    @Test
    @Transactional
    public void testCreateFromFeedItem_BlankUrl() {
        ValidationException ex = assertThrows(ValidationException.class, () -> {
            ProfileCuratedArticle.createFromFeedItem(testProfileId, UUID.randomUUID(), "", "Test Article",
                    "Test description", "https://example.com/image.jpg");
        });
        assertTrue(ex.getMessage().contains("Original URL cannot be blank"));
    }

    @Test
    @Transactional
    public void testCreateFromFeedItem_BlankTitle() {
        ValidationException ex = assertThrows(ValidationException.class, () -> {
            ProfileCuratedArticle.createFromFeedItem(testProfileId, UUID.randomUUID(), "https://example.com/article",
                    "", "Test description", "https://example.com/image.jpg");
        });
        assertTrue(ex.getMessage().contains("Original title cannot be blank"));
    }

    @Test
    @Transactional
    public void testCreateManual_Success() {
        ProfileCuratedArticle article = ProfileCuratedArticle.createManual(testProfileId, "https://example.com/article",
                "Manual Article", "Manual description", "https://example.com/image.jpg");

        assertNotNull(article.id);
        assertEquals(testProfileId, article.profileId);
        assertNull(article.feedItemId); // Manual entry has no feed item
        assertEquals("https://example.com/article", article.originalUrl);
        assertEquals("Manual Article", article.originalTitle);
        assertTrue(article.isActive);
    }

    // ========== Query Method Tests ==========

    @Test
    @Transactional
    public void testFindByProfile() {
        ProfileCuratedArticle article1 = ProfileCuratedArticle.createManual(testProfileId,
                "https://example.com/article1", "Article 1", "Description 1", null);

        ProfileCuratedArticle article2 = ProfileCuratedArticle.createManual(testProfileId,
                "https://example.com/article2", "Article 2", "Description 2", null);

        List<ProfileCuratedArticle> articles = ProfileCuratedArticle.findByProfile(testProfileId);
        assertTrue(articles.size() >= 2);
        assertTrue(articles.stream().anyMatch(a -> a.id.equals(article1.id)));
        assertTrue(articles.stream().anyMatch(a -> a.id.equals(article2.id)));
    }

    @Test
    @Transactional
    public void testFindActive() {
        ProfileCuratedArticle active = ProfileCuratedArticle.createManual(testProfileId, "https://example.com/active",
                "Active Article", "Active description", null);

        ProfileCuratedArticle inactive = ProfileCuratedArticle.createManual(testProfileId,
                "https://example.com/inactive", "Inactive Article", "Inactive description", null);
        inactive.deactivate();

        List<ProfileCuratedArticle> activeArticles = ProfileCuratedArticle.findActive(testProfileId);
        assertTrue(activeArticles.stream().anyMatch(a -> a.id.equals(active.id)));
        assertFalse(activeArticles.stream().anyMatch(a -> a.id.equals(inactive.id)));
    }

    @Test
    @Transactional
    public void testFindByFeedItem() {
        UUID feedItemId = UUID.randomUUID();
        ProfileCuratedArticle article = ProfileCuratedArticle.createFromFeedItem(testProfileId, feedItemId,
                "https://example.com/article", "Test Article", "Test description", "https://example.com/image.jpg");

        List<ProfileCuratedArticle> articles = ProfileCuratedArticle.findByFeedItem(feedItemId);
        assertTrue(articles.stream().anyMatch(a -> a.id.equals(article.id)));
    }

    @Test
    @Transactional
    public void testFindByIdOptional() {
        ProfileCuratedArticle article = ProfileCuratedArticle.createManual(testProfileId, "https://example.com/article",
                "Test Article", "Test description", null);

        Optional<ProfileCuratedArticle> found = ProfileCuratedArticle.findByIdOptional(article.id);
        assertTrue(found.isPresent());
        assertEquals(article.id, found.get().id);

        Optional<ProfileCuratedArticle> notFound = ProfileCuratedArticle.findByIdOptional(UUID.randomUUID());
        assertFalse(notFound.isPresent());
    }

    // ========== Count Method Tests ==========

    @Test
    @Transactional
    public void testCountActive() {
        long initialCount = ProfileCuratedArticle.countActive(testProfileId);

        ProfileCuratedArticle article = ProfileCuratedArticle.createManual(testProfileId, "https://example.com/article",
                "Test Article", "Test description", null);

        assertEquals(initialCount + 1, ProfileCuratedArticle.countActive(testProfileId));

        article.deactivate();
        assertEquals(initialCount, ProfileCuratedArticle.countActive(testProfileId));
    }

    @Test
    @Transactional
    public void testCountAll() {
        long initialCount = ProfileCuratedArticle.countAll(testProfileId);

        ProfileCuratedArticle article = ProfileCuratedArticle.createManual(testProfileId, "https://example.com/article",
                "Test Article", "Test description", null);

        assertEquals(initialCount + 1, ProfileCuratedArticle.countAll(testProfileId));

        article.deactivate();
        assertEquals(initialCount + 1, ProfileCuratedArticle.countAll(testProfileId)); // Still counts inactive
    }

    // ========== Customization Tests ==========

    @Test
    @Transactional
    public void testUpdateCustomization() {
        ProfileCuratedArticle article = ProfileCuratedArticle.createManual(testProfileId, "https://example.com/article",
                "Original Title", "Original description", "https://example.com/original.jpg");

        assertNull(article.customHeadline);
        assertNull(article.customBlurb);
        assertNull(article.customImageUrl);

        article.updateCustomization("Custom Headline", "Custom blurb", "https://example.com/custom.jpg");

        assertEquals("Custom Headline", article.customHeadline);
        assertEquals("Custom blurb", article.customBlurb);
        assertEquals("https://example.com/custom.jpg", article.customImageUrl);
    }

    @Test
    @Transactional
    public void testUpdateCustomization_ClearFields() {
        ProfileCuratedArticle article = ProfileCuratedArticle.createManual(testProfileId, "https://example.com/article",
                "Original Title", "Original description", null);

        article.updateCustomization("Custom Headline", "Custom blurb", "https://example.com/custom.jpg");
        assertNotNull(article.customHeadline);

        // Clear customization
        article.updateCustomization(null, null, null);
        assertNull(article.customHeadline);
        assertNull(article.customBlurb);
        assertNull(article.customImageUrl);
    }

    // ========== Effective Field Tests ==========

    @Test
    @Transactional
    public void testGetEffectiveHeadline_PreferCustom() {
        ProfileCuratedArticle article = ProfileCuratedArticle.createManual(testProfileId, "https://example.com/article",
                "Original Title", "Original description", null);

        assertEquals("Original Title", article.getEffectiveHeadline());

        article.customHeadline = "Custom Headline";
        assertEquals("Custom Headline", article.getEffectiveHeadline());
    }

    @Test
    @Transactional
    public void testGetEffectiveHeadline_FallbackToOriginal() {
        ProfileCuratedArticle article = ProfileCuratedArticle.createManual(testProfileId, "https://example.com/article",
                "Original Title", "Original description", null);

        article.customHeadline = ""; // Blank custom headline
        assertEquals("Original Title", article.getEffectiveHeadline());
    }

    @Test
    @Transactional
    public void testGetEffectiveDescription_PreferCustom() {
        ProfileCuratedArticle article = ProfileCuratedArticle.createManual(testProfileId, "https://example.com/article",
                "Original Title", "Original description", null);

        assertEquals("Original description", article.getEffectiveDescription());

        article.customBlurb = "Custom blurb";
        assertEquals("Custom blurb", article.getEffectiveDescription());
    }

    @Test
    @Transactional
    public void testGetEffectiveImageUrl_PreferCustom() {
        ProfileCuratedArticle article = ProfileCuratedArticle.createManual(testProfileId, "https://example.com/article",
                "Original Title", "Original description", "https://example.com/original.jpg");

        assertEquals("https://example.com/original.jpg", article.getEffectiveImageUrl());

        article.customImageUrl = "https://example.com/custom.jpg";
        assertEquals("https://example.com/custom.jpg", article.getEffectiveImageUrl());
    }

    // ========== Active/Inactive Toggle Tests ==========

    @Test
    @Transactional
    public void testActivate() {
        ProfileCuratedArticle article = ProfileCuratedArticle.createManual(testProfileId, "https://example.com/article",
                "Test Article", "Test description", null);
        article.deactivate();
        assertFalse(article.isActive);

        article.activate();
        assertTrue(article.isActive);

        // Verify persisted
        ProfileCuratedArticle reloaded = ProfileCuratedArticle.findByIdOptional(article.id).orElseThrow();
        assertTrue(reloaded.isActive);
    }

    @Test
    @Transactional
    public void testDeactivate() {
        ProfileCuratedArticle article = ProfileCuratedArticle.createManual(testProfileId, "https://example.com/article",
                "Test Article", "Test description", null);
        assertTrue(article.isActive);

        article.deactivate();
        assertFalse(article.isActive);

        // Verify persisted
        ProfileCuratedArticle reloaded = ProfileCuratedArticle.findByIdOptional(article.id).orElseThrow();
        assertFalse(reloaded.isActive);
    }

    // ========== Slot Assignment Tests ==========

    @Test
    @Transactional
    public void testUpdateSlotAssignment() {
        ProfileCuratedArticle article = ProfileCuratedArticle.createManual(testProfileId, "https://example.com/article",
                "Test Article", "Test description", null);

        assertTrue(article.slotAssignment.isEmpty());

        Map<String, Object> slotConfig = Map.of("slot", "hero", "position", 1);
        article.updateSlotAssignment(slotConfig);

        assertEquals(2, article.slotAssignment.size());
        assertEquals("hero", article.slotAssignment.get("slot"));
        assertEquals(1, article.slotAssignment.get("position"));
    }
}
