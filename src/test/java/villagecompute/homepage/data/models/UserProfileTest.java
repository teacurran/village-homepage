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
 * Tests for UserProfile model (Feature F11).
 *
 * <p>
 * Coverage:
 * <ul>
 * <li>Username validation (length, characters, reserved names)</li>
 * <li>Template validation</li>
 * <li>Profile creation (duplicates, one-per-user)</li>
 * <li>Publish/unpublish workflow</li>
 * <li>View count tracking</li>
 * <li>Soft delete (90-day retention per Policy P1)</li>
 * <li>Query methods (findByUsername, findByUserId, findPublished)</li>
 * </ul>
 */
@QuarkusTest
public class UserProfileTest {

    private UUID testUserId;

    @BeforeEach
    @Transactional
    public void setUp() {
        // Create test user
        User testUser = new User();
        testUser.email = "testprofile" + System.currentTimeMillis() + "@example.com";
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
        // Clean up profiles created during tests
        UserProfile.delete("userId = ?1", testUserId);
        User.delete("id = ?1", testUserId);
    }

    // ========== Username Validation Tests ==========

    @Test
    public void testNormalizeUsername_Valid() {
        String normalized = UserProfile.normalizeUsername("Valid_User-123");
        assertEquals("valid_user-123", normalized);
    }

    @Test
    public void testNormalizeUsername_TooShort() {
        ValidationException ex = assertThrows(ValidationException.class, () -> {
            UserProfile.normalizeUsername("ab");
        });
        assertTrue(ex.getMessage().contains("at least 3 characters"));
    }

    @Test
    public void testNormalizeUsername_TooLong() {
        ValidationException ex = assertThrows(ValidationException.class, () -> {
            UserProfile.normalizeUsername("a".repeat(31));
        });
        assertTrue(ex.getMessage().contains("30 characters or less"));
    }

    @Test
    public void testNormalizeUsername_InvalidCharacters() {
        ValidationException ex = assertThrows(ValidationException.class, () -> {
            UserProfile.normalizeUsername("user name");
        });
        assertTrue(ex.getMessage().contains("letters, numbers, underscore, and dash"));
    }

    @Test
    public void testNormalizeUsername_Blank() {
        ValidationException ex = assertThrows(ValidationException.class, () -> {
            UserProfile.normalizeUsername("");
        });
        assertTrue(ex.getMessage().contains("cannot be blank"));
    }

    @Test
    public void testNormalizeUsername_ReservedName() {
        ValidationException ex = assertThrows(ValidationException.class, () -> {
            UserProfile.normalizeUsername("admin");
        });
        assertTrue(ex.getMessage().contains("reserved"));
    }

    // ========== Template Validation Tests ==========

    @Test
    public void testValidateTemplate_Valid() {
        assertTrue(UserProfile.validateTemplate(UserProfile.TEMPLATE_PUBLIC_HOMEPAGE));
        assertTrue(UserProfile.validateTemplate(UserProfile.TEMPLATE_YOUR_TIMES));
        assertTrue(UserProfile.validateTemplate(UserProfile.TEMPLATE_YOUR_REPORT));
    }

    @Test
    public void testValidateTemplate_Invalid() {
        ValidationException ex = assertThrows(ValidationException.class, () -> {
            UserProfile.validateTemplate("invalid_template");
        });
        assertTrue(ex.getMessage().contains("Invalid template"));
    }

    @Test
    public void testValidateTemplate_Blank() {
        ValidationException ex = assertThrows(ValidationException.class, () -> {
            UserProfile.validateTemplate("");
        });
        assertTrue(ex.getMessage().contains("cannot be blank"));
    }

    // ========== Profile Creation Tests ==========

    @Test
    @Transactional
    public void testCreateProfile_Success() {
        UserProfile profile = UserProfile.createProfile(testUserId, "testuser123");

        assertNotNull(profile.id);
        assertEquals(testUserId, profile.userId);
        assertEquals("testuser123", profile.username);
        assertEquals(UserProfile.TEMPLATE_PUBLIC_HOMEPAGE, profile.template);
        assertFalse(profile.isPublished);
        assertEquals(0, profile.viewCount);
        assertNotNull(profile.createdAt);
        assertNotNull(profile.updatedAt);
        assertNull(profile.deletedAt);
    }

    @Test
    @Transactional
    public void testCreateProfile_DuplicateUsername() {
        UserProfile.createProfile(testUserId, "testuser123");

        // Create another user
        User user2 = new User();
        user2.email = "testprofile2" + System.currentTimeMillis() + "@example.com";
        user2.isAnonymous = false;
        user2.directoryTrustLevel = User.TRUST_LEVEL_UNTRUSTED;
        user2.preferences = Map.of();
        user2.createdAt = Instant.now();
        user2.updatedAt = Instant.now();
        user2.persist();

        ValidationException ex = assertThrows(ValidationException.class, () -> {
            UserProfile.createProfile(user2.id, "testuser123");
        });
        assertTrue(ex.getMessage().contains("already taken"));

        // Clean up
        User.delete("id = ?1", user2.id);
    }

    @Test
    @Transactional
    public void testCreateProfile_OnePerUser() {
        UserProfile.createProfile(testUserId, "testuser123");

        ValidationException ex = assertThrows(ValidationException.class, () -> {
            UserProfile.createProfile(testUserId, "testuser456");
        });
        assertTrue(ex.getMessage().contains("already has a profile"));
    }

    @Test
    @Transactional
    public void testCreateProfile_NullUserId() {
        ValidationException ex = assertThrows(ValidationException.class, () -> {
            UserProfile.createProfile(null, "testuser123");
        });
        assertTrue(ex.getMessage().contains("User ID cannot be null"));
    }

    // ========== Query Method Tests ==========

    @Test
    @Transactional
    public void testFindByUsername() {
        UserProfile profile = UserProfile.createProfile(testUserId, "findme");

        Optional<UserProfile> found = UserProfile.findByUsername("findme");
        assertTrue(found.isPresent());
        assertEquals(profile.id, found.get().id);

        // Case-insensitive
        Optional<UserProfile> foundUpper = UserProfile.findByUsername("FINDME");
        assertTrue(foundUpper.isPresent());
        assertEquals(profile.id, foundUpper.get().id);
    }

    @Test
    @Transactional
    public void testFindByUsername_NotFound() {
        Optional<UserProfile> found = UserProfile.findByUsername("nonexistent");
        assertFalse(found.isPresent());
    }

    @Test
    @Transactional
    public void testFindByUserId() {
        UserProfile profile = UserProfile.createProfile(testUserId, "testuser123");

        Optional<UserProfile> found = UserProfile.findByUserId(testUserId);
        assertTrue(found.isPresent());
        assertEquals(profile.id, found.get().id);
    }

    @Test
    @Transactional
    public void testFindByUserId_NotFound() {
        Optional<UserProfile> found = UserProfile.findByUserId(UUID.randomUUID());
        assertFalse(found.isPresent());
    }

    @Test
    @Transactional
    public void testFindPublished() {
        // Create unpublished profile
        UserProfile unpublished = UserProfile.createProfile(testUserId, "unpublished");

        List<UserProfile> published = UserProfile.findPublished();
        assertFalse(published.stream().anyMatch(p -> p.id.equals(unpublished.id)));

        // Publish profile
        unpublished.publish();

        List<UserProfile> publishedAfter = UserProfile.findPublished();
        assertTrue(publishedAfter.stream().anyMatch(p -> p.id.equals(unpublished.id)));
    }

    // ========== Publish/Unpublish Workflow Tests ==========

    @Test
    @Transactional
    public void testPublish() {
        UserProfile profile = UserProfile.createProfile(testUserId, "testuser123");
        assertFalse(profile.isPublished);

        profile.publish();
        assertTrue(profile.isPublished);

        // Verify persisted
        UserProfile reloaded = UserProfile.findById(profile.id);
        assertTrue(reloaded.isPublished);
    }

    @Test
    @Transactional
    public void testUnpublish() {
        UserProfile profile = UserProfile.createProfile(testUserId, "testuser123");
        profile.publish();
        assertTrue(profile.isPublished);

        profile.unpublish();
        assertFalse(profile.isPublished);

        // Verify persisted
        UserProfile reloaded = UserProfile.findById(profile.id);
        assertFalse(reloaded.isPublished);
    }

    // ========== View Count Tests ==========

    @Test
    @Transactional
    public void testIncrementViewCount() {
        UserProfile profile = UserProfile.createProfile(testUserId, "testuser123");
        assertEquals(0, profile.viewCount);

        profile.incrementViewCount();
        assertEquals(1, profile.viewCount);

        profile.incrementViewCount();
        assertEquals(2, profile.viewCount);

        // Verify persisted
        UserProfile reloaded = UserProfile.findById(profile.id);
        assertEquals(2, reloaded.viewCount);
    }

    // ========== Soft Delete Tests ==========

    @Test
    @Transactional
    public void testSoftDelete() {
        UserProfile profile = UserProfile.createProfile(testUserId, "testuser123");
        assertNull(profile.deletedAt);

        profile.softDelete();
        assertNotNull(profile.deletedAt);

        // Verify persisted
        UserProfile reloaded = UserProfile.findById(profile.id);
        assertNotNull(reloaded.deletedAt);

        // Verify not found in queries
        Optional<UserProfile> found = UserProfile.findByUsername("testuser123");
        assertFalse(found.isPresent());
    }

    @Test
    @Transactional
    public void testFindPendingPurge() {
        UserProfile profile = UserProfile.createProfile(testUserId, "testuser123");
        profile.softDelete();

        // Set deleted_at to 91 days ago
        profile.deletedAt = Instant.now().minusSeconds(91 * 24 * 60 * 60);
        profile.persist();

        List<UserProfile> pending = UserProfile.findPendingPurge();
        assertTrue(pending.stream().anyMatch(p -> p.id.equals(profile.id)));
    }

    // ========== Update Method Tests ==========

    @Test
    @Transactional
    public void testUpdateProfile() {
        UserProfile profile = UserProfile.createProfile(testUserId, "testuser123");

        profile.updateProfile("Display Name", "Bio text", "Location", "https://example.com", "https://avatar.com");

        assertEquals("Display Name", profile.displayName);
        assertEquals("Bio text", profile.bio);
        assertEquals("Location", profile.locationText);
        assertEquals("https://example.com", profile.websiteUrl);
        assertEquals("https://avatar.com", profile.avatarUrl);
    }

    @Test
    @Transactional
    public void testUpdateSocialLinks() {
        UserProfile profile = UserProfile.createProfile(testUserId, "testuser123");

        Map<String, Object> socialLinks = Map.of("twitter", "https://twitter.com/user", "github",
                "https://github.com/user");

        profile.updateSocialLinks(socialLinks);

        assertEquals(2, profile.socialLinks.size());
        assertEquals("https://twitter.com/user", profile.socialLinks.get("twitter"));
    }

    @Test
    @Transactional
    public void testUpdateTemplate() {
        UserProfile profile = UserProfile.createProfile(testUserId, "testuser123");
        assertEquals(UserProfile.TEMPLATE_PUBLIC_HOMEPAGE, profile.template);

        Map<String, Object> config = Map.of("theme", "dark");
        profile.updateTemplate(UserProfile.TEMPLATE_YOUR_TIMES, config);

        assertEquals(UserProfile.TEMPLATE_YOUR_TIMES, profile.template);
        assertEquals("dark", profile.templateConfig.get("theme"));
    }

    // ========== Count Method Tests ==========

    @Test
    @Transactional
    public void testCountPublished() {
        long initialCount = UserProfile.countPublished();

        UserProfile profile1 = UserProfile.createProfile(testUserId, "testuser1");
        assertEquals(initialCount, UserProfile.countPublished());

        profile1.publish();
        assertEquals(initialCount + 1, UserProfile.countPublished());
    }

    @Test
    @Transactional
    public void testCountAll() {
        long initialCount = UserProfile.countAll();

        UserProfile.createProfile(testUserId, "testuser123");
        assertEquals(initialCount + 1, UserProfile.countAll());
    }
}
