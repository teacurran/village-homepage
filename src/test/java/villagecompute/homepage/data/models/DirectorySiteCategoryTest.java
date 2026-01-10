package villagecompute.homepage.data.models;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.testing.H2TestResource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DirectorySiteCategory junction entity covering site-category memberships and voting.
 *
 * <p>
 * Test coverage:
 * <ul>
 * <li>Site can exist in multiple categories</li>
 * <li>Each site-category has separate status/voting</li>
 * <li>Approval increments category link count</li>
 * <li>Vote aggregate updates (upvotes, downvotes, score)</li>
 * <li>Moderation queue per category</li>
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(H2TestResource.class)
class DirectorySiteCategoryTest {

    private UUID testUserId;
    private UUID testSiteId;
    private UUID testCategoryId;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clear all entities
        DirectoryVote.deleteAll();
        DirectorySiteCategory.deleteAll();
        DirectorySite.deleteAll();
        DirectoryCategory.deleteAll();
        User.deleteAll();

        // Create test user
        User testUser = new User();
        testUser.email = "test@example.com";
        testUser.displayName = "Test User";
        testUser.isAnonymous = false;
        testUser.preferences = new java.util.HashMap<>();
        testUser.directoryKarma = 0;
        testUser.directoryTrustLevel = "untrusted";
        testUser.analyticsConsent = false;
        testUser.isBanned = false;
        testUser.createdAt = Instant.now();
        testUser.updatedAt = Instant.now();
        testUser.persist();
        testUserId = testUser.id;

        // Create test category
        DirectoryCategory category = new DirectoryCategory();
        category.parentId = null;
        category.name = "Test Category";
        category.slug = "test";
        category.description = "Test";
        category.sortOrder = 1;
        category.linkCount = 0;
        category.isActive = true;
        category.createdAt = Instant.now();
        category.updatedAt = Instant.now();
        category.persist();
        testCategoryId = category.id;

        // Create test site
        DirectorySite site = new DirectorySite();
        site.url = "https://example.com";
        site.domain = "example.com";
        site.title = "Example Site";
        site.submittedByUserId = testUserId;
        site.status = "pending";
        site.isDead = false;
        site.createdAt = Instant.now();
        site.updatedAt = Instant.now();
        site.persist();
        testSiteId = site.id;
    }

    /**
     * Test: Create site-category membership.
     */
    @Test
    @Transactional
    void testCreateSiteCategory() {
        DirectorySiteCategory sc = new DirectorySiteCategory();
        sc.siteId = testSiteId;
        sc.categoryId = testCategoryId;
        sc.score = 0;
        sc.upvotes = 0;
        sc.downvotes = 0;
        sc.submittedByUserId = testUserId;
        sc.status = "pending";
        sc.createdAt = Instant.now();
        sc.updatedAt = Instant.now();
        sc.persist();

        assertNotNull(sc.id);
        assertEquals(testSiteId, sc.siteId);
        assertEquals(testCategoryId, sc.categoryId);
        assertEquals("pending", sc.status);
        assertEquals(0, sc.score);
    }

    /**
     * Test: Find site-category memberships by site ID.
     */
    @Test
    @Transactional
    void testFindBySiteId() {
        DirectorySiteCategory sc = createTestSiteCategory();

        List<DirectorySiteCategory> found = DirectorySiteCategory.findBySiteId(testSiteId);

        assertEquals(1, found.size());
        assertEquals(testCategoryId, found.get(0).categoryId);
    }

    /**
     * Test: Site exists in multiple categories.
     */
    @Test
    @Transactional
    void testSiteInMultipleCategories() {
        // Create second category
        DirectoryCategory category2 = new DirectoryCategory();
        category2.parentId = null;
        category2.name = "Second Category";
        category2.slug = "second";
        category2.description = "Second";
        category2.sortOrder = 2;
        category2.linkCount = 0;
        category2.isActive = true;
        DirectoryCategory created = DirectoryCategory.create(category2);
        UUID category2Id = created.id;

        // Add site to both categories
        DirectorySiteCategory sc1 = createTestSiteCategory();

        DirectorySiteCategory sc2 = new DirectorySiteCategory();
        sc2.siteId = testSiteId;
        sc2.categoryId = category2Id;
        sc2.score = 0;
        sc2.upvotes = 0;
        sc2.downvotes = 0;
        sc2.submittedByUserId = testUserId;
        sc2.status = "pending";
        sc2.createdAt = Instant.now();
        sc2.updatedAt = Instant.now();
        sc2.persist();

        List<DirectorySiteCategory> siteCategories = DirectorySiteCategory.findBySiteId(testSiteId);

        assertEquals(2, siteCategories.size());
    }

    /**
     * Test: Approve site-category increments link count.
     */
    @Test
    @Transactional
    void testApproveSiteCategory_IncrementsLinkCount() {
        DirectorySiteCategory sc = createTestSiteCategory();
        sc.status = "pending";
        sc.persist();

        // Get initial link count
        DirectoryCategory catBefore = DirectoryCategory.findById(testCategoryId);
        int initialCount = catBefore.linkCount;

        // Approve
        sc.approve(testUserId);

        // Verify link count incremented
        DirectoryCategory catAfter = DirectoryCategory.findById(testCategoryId);
        assertEquals(initialCount + 1, catAfter.linkCount);
        assertEquals("approved", sc.status);
        assertEquals(testUserId, sc.approvedByUserId);
    }

    /**
     * Test: Find approved sites in category.
     */
    @Test
    @Transactional
    void testFindApprovedInCategory() {
        DirectorySiteCategory pending = createTestSiteCategory();
        pending.status = "pending";
        pending.persist();

        DirectorySiteCategory approved = createSecondSiteCategory();
        approved.status = "approved";
        approved.persist();

        List<DirectorySiteCategory> approvedSites = DirectorySiteCategory.findApprovedInCategory(testCategoryId);

        assertEquals(1, approvedSites.size());
        assertEquals("approved", approvedSites.get(0).status);
    }

    /**
     * Test: Find pending submissions in category.
     */
    @Test
    @Transactional
    void testFindPendingInCategory() {
        DirectorySiteCategory pending = createTestSiteCategory();
        pending.status = "pending";
        pending.persist();

        DirectorySiteCategory approved = createSecondSiteCategory();
        approved.status = "approved";
        approved.persist();

        List<DirectorySiteCategory> pendingSites = DirectorySiteCategory.findPendingInCategory(testCategoryId);

        assertEquals(1, pendingSites.size());
        assertEquals("pending", pendingSites.get(0).status);
    }

    /**
     * Test: Check if site already in category.
     */
    @Test
    @Transactional
    void testFindBySiteAndCategory() {
        DirectorySiteCategory sc = createTestSiteCategory();

        Optional<DirectorySiteCategory> found = DirectorySiteCategory.findBySiteAndCategory(testSiteId, testCategoryId);

        assertTrue(found.isPresent());
        assertEquals(sc.id, found.get().id);
    }

    /**
     * Test: Update vote aggregates.
     */
    @Test
    @Transactional
    void testUpdateAggregates() {
        DirectorySiteCategory sc = createTestSiteCategory();

        // Create votes
        createVote(sc.id, testUserId, (short) 1); // upvote

        User user2 = createUser("user2@example.com");
        createVote(sc.id, user2.id, (short) -1); // downvote

        User user3 = createUser("user3@example.com");
        createVote(sc.id, user3.id, (short) 1); // upvote

        // Update aggregates
        sc.updateAggregates();

        assertEquals(2, sc.upvotes);
        assertEquals(1, sc.downvotes);
        assertEquals(1, sc.score); // 2 - 1 = 1
    }

    // Helper methods

    private DirectorySiteCategory createTestSiteCategory() {
        DirectorySiteCategory sc = new DirectorySiteCategory();
        sc.siteId = testSiteId;
        sc.categoryId = testCategoryId;
        sc.score = 0;
        sc.upvotes = 0;
        sc.downvotes = 0;
        sc.submittedByUserId = testUserId;
        sc.status = "pending";
        sc.createdAt = Instant.now();
        sc.updatedAt = Instant.now();
        sc.persist();
        return sc;
    }

    private DirectorySiteCategory createSecondSiteCategory() {
        // Create second site
        DirectorySite site2 = new DirectorySite();
        site2.url = "https://example2.com";
        site2.domain = "example2.com";
        site2.title = "Example 2";
        site2.submittedByUserId = testUserId;
        site2.status = "pending";
        site2.isDead = false;
        site2.createdAt = Instant.now();
        site2.updatedAt = Instant.now();
        site2.persist();

        DirectorySiteCategory sc = new DirectorySiteCategory();
        sc.siteId = site2.id;
        sc.categoryId = testCategoryId;
        sc.score = 0;
        sc.upvotes = 0;
        sc.downvotes = 0;
        sc.submittedByUserId = testUserId;
        sc.status = "pending";
        sc.createdAt = Instant.now();
        sc.updatedAt = Instant.now();
        sc.persist();
        return sc;
    }

    private DirectoryVote createVote(UUID siteCategoryId, UUID userId, short vote) {
        DirectoryVote v = new DirectoryVote();
        v.siteCategoryId = siteCategoryId;
        v.userId = userId;
        v.vote = vote;
        v.createdAt = Instant.now();
        v.updatedAt = Instant.now();
        v.persist();
        return v;
    }

    private User createUser(String email) {
        User user = new User();
        user.email = email;
        user.displayName = email;
        user.isAnonymous = false;
        user.preferences = new java.util.HashMap<>();
        user.directoryKarma = 0;
        user.directoryTrustLevel = "untrusted";
        user.analyticsConsent = false;
        user.isBanned = false;
        user.createdAt = Instant.now();
        user.updatedAt = Instant.now();
        user.persist();
        return user;
    }
}
