package villagecompute.homepage.services;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.data.models.ProfileCuratedArticle;
import villagecompute.homepage.data.models.ReservedUsername;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.data.models.UserProfile;
import villagecompute.homepage.exceptions.DuplicateResourceException;
import villagecompute.homepage.exceptions.ResourceNotFoundException;
import villagecompute.homepage.exceptions.ValidationException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ProfileService (Feature F11).
 *
 * <p>
 * Tests cover:
 * <ul>
 * <li>Profile creation with validation</li>
 * <li>Profile retrieval by ID/username/userId</li>
 * <li>Profile updates</li>
 * <li>Publish/unpublish workflow</li>
 * <li>View count tracking</li>
 * <li>Soft delete</li>
 * <li>Statistics aggregation</li>
 * <li>Curated article management</li>
 * </ul>
 */
@QuarkusTest
public class ProfileServiceTest {

    @Inject
    ProfileService profileService;

    private UUID testUserId;

    @BeforeEach
    @Transactional
    public void setUp() {
        // Clean up test data
        UserProfile.delete("username LIKE 'testservice%'");
        User.delete("email LIKE 'testservice%'");

        // Create test user
        User testUser = new User();
        testUser.email = "testservice" + System.currentTimeMillis() + "@example.com";
        testUser.isAnonymous = false;
        testUser.directoryTrustLevel = User.TRUST_LEVEL_UNTRUSTED;
        testUser.preferences = Map.of();
        testUser.createdAt = Instant.now();
        testUser.updatedAt = Instant.now();
        testUser.persist();
        testUserId = testUser.id;

        // Ensure reserved names are seeded
        if (ReservedUsername.countAll() == 0) {
            ReservedUsername.seedDefaults();
        }
    }

    @AfterEach
    @Transactional
    public void tearDown() {
        UserProfile.delete("username LIKE 'testservice%'");
        User.delete("id = ?1", testUserId);
    }

    // ========== Profile Creation Tests ==========

    @Test
    public void testCreateProfile_Success() {
        UserProfile profile = profileService.createProfile(testUserId, "testservice123");

        assertNotNull(profile.id);
        assertEquals(testUserId, profile.userId);
        assertEquals("testservice123", profile.username);
        assertFalse(profile.isPublished);
        assertEquals(0, profile.viewCount);
    }

    @Test
    public void testCreateProfile_UserNotFound() {
        UUID nonExistentUserId = UUID.randomUUID();

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class, () -> {
            profileService.createProfile(nonExistentUserId, "testservice123");
        });
        assertTrue(ex.getMessage().contains("User not found"));
    }

    @Test
    public void testCreateProfile_AnonymousUser() {
        // Create anonymous user in a separate transaction
        UUID anonUserId = QuarkusTransaction.requiringNew().call(() -> {
            User anonUser = new User();
            anonUser.email = "anon" + System.currentTimeMillis() + "@example.com";
            anonUser.isAnonymous = true;
            anonUser.directoryTrustLevel = User.TRUST_LEVEL_UNTRUSTED;
            anonUser.preferences = Map.of();
            anonUser.createdAt = Instant.now();
            anonUser.updatedAt = Instant.now();
            anonUser.persist();
            return anonUser.id;
        });

        try {
            ValidationException ex = assertThrows(ValidationException.class, () -> {
                profileService.createProfile(anonUserId, "testservice123");
            });
            assertTrue(ex.getMessage().contains("Anonymous users cannot create profiles"));
        } finally {
            // Clean up in a separate transaction
            QuarkusTransaction.requiringNew().run(() -> {
                User.delete("id = ?1", anonUserId);
            });
        }
    }

    @Test
    @Transactional
    public void testCreateProfile_DuplicateUsername() {
        profileService.createProfile(testUserId, "testservice123");

        // Create another user
        User user2 = new User();
        user2.email = "testservice2" + System.currentTimeMillis() + "@example.com";
        user2.isAnonymous = false;
        user2.directoryTrustLevel = User.TRUST_LEVEL_UNTRUSTED;
        user2.preferences = Map.of();
        user2.createdAt = Instant.now();
        user2.updatedAt = Instant.now();
        user2.persist();

        ValidationException ex = assertThrows(ValidationException.class, () -> {
            profileService.createProfile(user2.id, "testservice123");
        });
        assertTrue(ex.getMessage().contains("already taken"));

        // Clean up
        User.delete("id = ?1", user2.id);
    }

    @Test
    public void testCreateProfile_UserAlreadyHasProfile() {
        profileService.createProfile(testUserId, "testservice123");

        DuplicateResourceException ex = assertThrows(DuplicateResourceException.class, () -> {
            profileService.createProfile(testUserId, "testservice456");
        });
        assertTrue(ex.getMessage().contains("already has a profile"));
    }

    // ========== Profile Retrieval Tests ==========

    @Test
    public void testGetProfile_Success() {
        UserProfile created = profileService.createProfile(testUserId, "testservice123");

        UserProfile retrieved = profileService.getProfile(created.id);
        assertEquals(created.id, retrieved.id);
        assertEquals("testservice123", retrieved.username);
    }

    @Test
    public void testGetProfile_NotFound() {
        UUID nonExistentId = UUID.randomUUID();

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class, () -> {
            profileService.getProfile(nonExistentId);
        });
        assertTrue(ex.getMessage().contains("Profile not found"));
    }

    @Test
    public void testGetProfileByUsername_Success() {
        profileService.createProfile(testUserId, "testservice123");

        UserProfile retrieved = profileService.getProfileByUsername("testservice123");
        assertEquals("testservice123", retrieved.username);
    }

    @Test
    public void testGetProfileByUsername_NotFound() {
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class, () -> {
            profileService.getProfileByUsername("nonexistent");
        });
        assertTrue(ex.getMessage().contains("Profile not found"));
    }

    @Test
    public void testGetProfileByUserId_Success() {
        profileService.createProfile(testUserId, "testservice123");

        UserProfile retrieved = profileService.getProfileByUserId(testUserId);
        assertEquals(testUserId, retrieved.userId);
    }

    @Test
    public void testGetProfileByUserId_NotFound() {
        UUID nonExistentUserId = UUID.randomUUID();

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class, () -> {
            profileService.getProfileByUserId(nonExistentUserId);
        });
        assertTrue(ex.getMessage().contains("No profile found for user"));
    }

    // ========== Profile Update Tests ==========

    @Test
    public void testUpdateProfile_Success() {
        UserProfile profile = profileService.createProfile(testUserId, "testservice123");

        UserProfile updated = profileService.updateProfile(profile.id, "Display Name", "Bio text", "Location",
                "https://example.com", "https://avatar.example.com");

        assertEquals("Display Name", updated.displayName);
        assertEquals("Bio text", updated.bio);
        assertEquals("Location", updated.locationText);
        assertEquals("https://example.com", updated.websiteUrl);
        assertEquals("https://avatar.example.com", updated.avatarUrl);
    }

    @Test
    public void testUpdateSocialLinks_Success() {
        UserProfile profile = profileService.createProfile(testUserId, "testservice123");

        Map<String, Object> socialLinks = Map.of("twitter", "https://twitter.com/test", "github",
                "https://github.com/test");

        UserProfile updated = profileService.updateSocialLinks(profile.id, socialLinks);

        assertEquals(2, updated.socialLinks.size());
        assertEquals("https://twitter.com/test", updated.socialLinks.get("twitter"));
    }

    @Test
    public void testUpdateTemplate_Success() {
        UserProfile profile = profileService.createProfile(testUserId, "testservice123");

        Map<String, Object> config = Map.of("theme", "dark");
        UserProfile updated = profileService.updateTemplate(profile.id, UserProfile.TEMPLATE_YOUR_TIMES, config);

        assertEquals(UserProfile.TEMPLATE_YOUR_TIMES, updated.template);
        assertEquals("dark", updated.templateConfig.get("theme"));
    }

    @Test
    public void testUpdateTemplate_InvalidTemplate() {
        UserProfile profile = profileService.createProfile(testUserId, "testservice123");

        ValidationException ex = assertThrows(ValidationException.class, () -> {
            profileService.updateTemplate(profile.id, "invalid_template", Map.of());
        });
        assertTrue(ex.getMessage().contains("Invalid template"));
    }

    // ========== Publish/Unpublish Tests ==========

    @Test
    public void testPublishProfile_Success() {
        UserProfile profile = profileService.createProfile(testUserId, "testservice123");
        assertFalse(profile.isPublished);

        UserProfile published = profileService.publishProfile(profile.id);
        assertTrue(published.isPublished);
    }

    @Test
    public void testUnpublishProfile_Success() {
        UserProfile profile = profileService.createProfile(testUserId, "testservice123");
        profileService.publishProfile(profile.id);

        UserProfile unpublished = profileService.unpublishProfile(profile.id);
        assertFalse(unpublished.isPublished);
    }

    // ========== View Count Tests ==========

    @Test
    public void testIncrementViewCount_Success() {
        UserProfile profile = profileService.createProfile(testUserId, "testservice123");
        assertEquals(0, profile.viewCount);

        profileService.incrementViewCount(profile.id, testUserId, "session123", "127.0.0.1", "Mozilla/5.0 Test Agent");

        UserProfile updated = profileService.getProfile(profile.id);
        assertEquals(1, updated.viewCount);
    }

    // ========== Soft Delete Tests ==========

    @Test
    public void testDeleteProfile_Success() {
        UserProfile profile = profileService.createProfile(testUserId, "testservice123");
        assertNull(profile.deletedAt);

        profileService.deleteProfile(profile.id);

        UserProfile deleted = UserProfile.findById(profile.id);
        assertNotNull(deleted.deletedAt);
    }

    // ========== Statistics Tests ==========

    @Test
    public void testGetStats_Success() {
        profileService.createProfile(testUserId, "testservice123");

        Map<String, Object> stats = profileService.getStats();

        assertNotNull(stats);
        assertTrue(stats.containsKey("total_profiles"));
        assertTrue(stats.containsKey("published_profiles"));
        assertTrue(stats.containsKey("total_views"));
    }

    // ========== Curated Article Tests ==========

    @Test
    public void testAddCuratedArticle_Success() {
        UserProfile profile = profileService.createProfile(testUserId, "testservice123");

        ProfileCuratedArticle article = profileService.addCuratedArticle(profile.id, UUID.randomUUID(),
                "https://example.com/article", "Test Article", "Test description", "https://example.com/image.jpg");

        assertNotNull(article.id);
        assertEquals(profile.id, article.profileId);
        assertEquals("https://example.com/article", article.originalUrl);
        assertTrue(article.isActive);
    }

    @Test
    public void testAddManualArticle_Success() {
        UserProfile profile = profileService.createProfile(testUserId, "testservice123");

        ProfileCuratedArticle article = profileService.addManualArticle(profile.id, "https://example.com/article",
                "Manual Article", "Manual description", "https://example.com/image.jpg");

        assertNotNull(article.id);
        assertNull(article.feedItemId);
        assertEquals("https://example.com/article", article.originalUrl);
    }

    @Test
    public void testUpdateArticleCustomization_Success() {
        UserProfile profile = profileService.createProfile(testUserId, "testservice123");
        ProfileCuratedArticle article = profileService.addManualArticle(profile.id, "https://example.com/article",
                "Original Title", "Original description", null);

        ProfileCuratedArticle updated = profileService.updateArticleCustomization(article.id, "Custom Headline",
                "Custom blurb", "https://example.com/custom.jpg");

        assertEquals("Custom Headline", updated.customHeadline);
        assertEquals("Custom blurb", updated.customBlurb);
        assertEquals("https://example.com/custom.jpg", updated.customImageUrl);
    }

    @Test
    public void testRemoveArticle_Success() {
        UserProfile profile = profileService.createProfile(testUserId, "testservice123");
        ProfileCuratedArticle article = profileService.addManualArticle(profile.id, "https://example.com/article",
                "Test Article", "Test description", null);

        assertTrue(article.isActive);

        profileService.removeArticle(article.id);

        ProfileCuratedArticle removed = ProfileCuratedArticle.findByIdOptional(article.id).orElseThrow();
        assertFalse(removed.isActive);
    }

    @Test
    public void testListArticles_Success() {
        UserProfile profile = profileService.createProfile(testUserId, "testservice123");
        profileService.addManualArticle(profile.id, "https://example.com/article1", "Article 1", "Description 1", null);
        profileService.addManualArticle(profile.id, "https://example.com/article2", "Article 2", "Description 2", null);

        List<ProfileCuratedArticle> articles = profileService.listArticles(profile.id);
        assertTrue(articles.size() >= 2);
    }

    @Test
    public void testListPublishedProfiles_Success() {
        UserProfile profile1 = profileService.createProfile(testUserId, "testservice1");
        profileService.publishProfile(profile1.id);

        List<UserProfile> published = profileService.listPublishedProfiles(0, 10);
        assertTrue(published.stream().anyMatch(p -> p.id.equals(profile1.id)));
    }
}
