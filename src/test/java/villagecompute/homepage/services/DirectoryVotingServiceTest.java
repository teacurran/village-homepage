package villagecompute.homepage.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import villagecompute.homepage.data.models.DirectoryCategory;
import villagecompute.homepage.data.models.DirectorySite;
import villagecompute.homepage.data.models.DirectorySiteCategory;
import villagecompute.homepage.data.models.DirectoryVote;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.exceptions.ResourceNotFoundException;
import villagecompute.homepage.exceptions.ValidationException;

/**
 * Unit tests for {@link DirectoryVotingService}.
 *
 * <p>
 * Tests cover:
 * <ul>
 * <li>Vote creation (upvote/downvote)</li>
 * <li>Vote updates (changing vote direction)</li>
 * <li>Vote deletion</li>
 * <li>Validation (invalid vote values, non-existent entities)</li>
 * <li>Karma integration (via KarmaService)</li>
 * <li>Aggregate updates on DirectorySiteCategory</li>
 * </ul>
 *
 * <p>
 * Note: Karma adjustments are verified through KarmaService integration. See KarmaServiceTest for detailed karma
 * calculation tests.
 */
@QuarkusTest
class DirectoryVotingServiceTest {

    @Inject
    DirectoryVotingService votingService;

    @Inject
    KarmaService karmaService;

    private UUID testUserId;
    private UUID testSiteCategoryId;
    private UUID testCategoryId;
    private UUID testSiteId;

    /**
     * Sets up test data before each test.
     */
    @BeforeEach
    @Transactional
    public void setupTestData() {
        // Clean up existing test data
        DirectoryVote.delete("1=1");
        DirectorySiteCategory.delete("1=1");
        DirectorySite.delete("1=1");
        DirectoryCategory.delete("1=1");
        User.delete("1=1");

        // Create test user
        User testUser = new User();
        testUser.email = "voter_test_" + System.currentTimeMillis() + "@example.com";
        testUser.directoryKarma = 5;
        testUser.directoryTrustLevel = "untrusted";
        testUser.preferences = java.util.Map.of();
        testUser.isAnonymous = false;
        testUser.createdAt = Instant.now();
        testUser.updatedAt = Instant.now();
        testUser.persist();
        testUserId = testUser.id;

        // Create test category
        DirectoryCategory category = new DirectoryCategory();
        category.parentId = null;
        category.name = "Test Category";
        category.slug = "test-category-" + System.currentTimeMillis();
        category.description = "Test category for voting tests";
        category.sortOrder = 1;
        category.linkCount = 0;
        category.isActive = true;
        category.createdAt = Instant.now();
        category.updatedAt = Instant.now();
        category.persist();
        testCategoryId = category.id;

        // Create test site
        DirectorySite site = new DirectorySite();
        site.url = "https://vote-test-" + System.currentTimeMillis() + ".example.com";
        site.domain = "vote-test.example.com";
        site.title = "Vote Test Site";
        site.description = "A test site for voting tests";
        site.submittedByUserId = testUserId;
        site.status = "approved";
        site.isDead = false;
        site.createdAt = Instant.now();
        site.updatedAt = Instant.now();
        site.persist();
        testSiteId = site.id;

        // Create site-category membership
        DirectorySiteCategory siteCategory = new DirectorySiteCategory();
        siteCategory.siteId = testSiteId;
        siteCategory.categoryId = testCategoryId;
        siteCategory.score = 0;
        siteCategory.upvotes = 0;
        siteCategory.downvotes = 0;
        siteCategory.rankInCategory = 1;
        siteCategory.submittedByUserId = testUserId;
        siteCategory.status = "approved";
        siteCategory.createdAt = Instant.now();
        siteCategory.updatedAt = Instant.now();
        siteCategory.persist();
        testSiteCategoryId = siteCategory.id;
    }

    /**
     * Tests successful upvote creation.
     */
    @Test
    @Transactional
    public void testCastVote_NewUpvote_CreatesVoteAndUpdatesAggregates() {
        // Given: No existing vote
        assertTrue(DirectoryVote.findByUserAndSiteCategory(testSiteCategoryId, testUserId).isEmpty());

        // When: User casts upvote
        votingService.castVote(testSiteCategoryId, testUserId, (short) 1);

        // Then: Vote is created
        Optional<DirectoryVote> voteOpt = DirectoryVote.findByUserAndSiteCategory(testSiteCategoryId, testUserId);
        assertTrue(voteOpt.isPresent());
        assertEquals((short) 1, voteOpt.get().vote);

        // And: Aggregates are updated
        DirectorySiteCategory siteCategory = DirectorySiteCategory.findById(testSiteCategoryId);
        assertEquals(1, siteCategory.upvotes);
        assertEquals(0, siteCategory.downvotes);
        assertEquals(1, siteCategory.score); // score = upvotes - downvotes
    }

    /**
     * Tests successful downvote creation.
     */
    @Test
    @Transactional
    public void testCastVote_NewDownvote_CreatesVoteAndUpdatesAggregates() {
        // Given: No existing vote
        assertTrue(DirectoryVote.findByUserAndSiteCategory(testSiteCategoryId, testUserId).isEmpty());

        // When: User casts downvote
        votingService.castVote(testSiteCategoryId, testUserId, (short) -1);

        // Then: Vote is created
        Optional<DirectoryVote> voteOpt = DirectoryVote.findByUserAndSiteCategory(testSiteCategoryId, testUserId);
        assertTrue(voteOpt.isPresent());
        assertEquals((short) -1, voteOpt.get().vote);

        // And: Aggregates are updated
        DirectorySiteCategory siteCategory = DirectorySiteCategory.findById(testSiteCategoryId);
        assertEquals(0, siteCategory.upvotes);
        assertEquals(1, siteCategory.downvotes);
        assertEquals(-1, siteCategory.score);
    }

    /**
     * Tests changing vote from upvote to downvote.
     */
    @Test
    @Transactional
    public void testCastVote_ChangeUpvoteToDownvote_UpdatesVoteAndAggregates() {
        // Given: User has already cast upvote
        votingService.castVote(testSiteCategoryId, testUserId, (short) 1);
        DirectorySiteCategory before = DirectorySiteCategory.findById(testSiteCategoryId);
        assertEquals(1, before.upvotes);
        assertEquals(0, before.downvotes);

        // When: User changes to downvote
        votingService.castVote(testSiteCategoryId, testUserId, (short) -1);

        // Then: Vote is updated
        Optional<DirectoryVote> voteOpt = DirectoryVote.findByUserAndSiteCategory(testSiteCategoryId, testUserId);
        assertTrue(voteOpt.isPresent());
        assertEquals((short) -1, voteOpt.get().vote);

        // And: Aggregates reflect the change
        DirectorySiteCategory after = DirectorySiteCategory.findById(testSiteCategoryId);
        assertEquals(0, after.upvotes);
        assertEquals(1, after.downvotes);
        assertEquals(-1, after.score);
    }

    /**
     * Tests changing vote from downvote to upvote.
     */
    @Test
    @Transactional
    public void testCastVote_ChangeDownvoteToUpvote_UpdatesVoteAndAggregates() {
        // Given: User has already cast downvote
        votingService.castVote(testSiteCategoryId, testUserId, (short) -1);
        DirectorySiteCategory before = DirectorySiteCategory.findById(testSiteCategoryId);
        assertEquals(0, before.upvotes);
        assertEquals(1, before.downvotes);

        // When: User changes to upvote
        votingService.castVote(testSiteCategoryId, testUserId, (short) 1);

        // Then: Vote is updated
        Optional<DirectoryVote> voteOpt = DirectoryVote.findByUserAndSiteCategory(testSiteCategoryId, testUserId);
        assertTrue(voteOpt.isPresent());
        assertEquals((short) 1, voteOpt.get().vote);

        // And: Aggregates reflect the change
        DirectorySiteCategory after = DirectorySiteCategory.findById(testSiteCategoryId);
        assertEquals(1, after.upvotes);
        assertEquals(0, after.downvotes);
        assertEquals(1, after.score);
    }

    /**
     * Tests casting same vote twice is idempotent.
     */
    @Test
    @Transactional
    public void testCastVote_SameVoteTwice_IsIdempotent() {
        // Given: User has already cast upvote
        votingService.castVote(testSiteCategoryId, testUserId, (short) 1);
        DirectorySiteCategory before = DirectorySiteCategory.findById(testSiteCategoryId);
        assertEquals(1, before.upvotes);

        // When: User casts same upvote again
        votingService.castVote(testSiteCategoryId, testUserId, (short) 1);

        // Then: Aggregates unchanged (idempotent)
        DirectorySiteCategory after = DirectorySiteCategory.findById(testSiteCategoryId);
        assertEquals(1, after.upvotes);
        assertEquals(0, after.downvotes);
        assertEquals(1, after.score);
    }

    /**
     * Tests validation of invalid vote value.
     */
    @Test
    @Transactional
    public void testCastVote_InvalidVoteValue_ThrowsValidationException() {
        // When/Then: Invalid vote value (must be +1 or -1)
        assertThrows(ValidationException.class, () -> {
            votingService.castVote(testSiteCategoryId, testUserId, (short) 0);
        });

        assertThrows(ValidationException.class, () -> {
            votingService.castVote(testSiteCategoryId, testUserId, (short) 2);
        });
    }

    /**
     * Tests voting on non-existent site-category.
     */
    @Test
    @Transactional
    public void testCastVote_NonExistentSiteCategory_ThrowsResourceNotFoundException() {
        // Given: Random non-existent UUID
        UUID nonExistentId = UUID.randomUUID();

        // When/Then: Should throw ResourceNotFoundException
        assertThrows(ResourceNotFoundException.class, () -> {
            votingService.castVote(nonExistentId, testUserId, (short) 1);
        });
    }

    /**
     * Tests voting on pending (not yet approved) site-category.
     */
    @Test
    @Transactional
    public void testCastVote_PendingSiteCategory_ThrowsValidationException() {
        // Given: Site-category in pending status
        DirectorySiteCategory siteCategory = DirectorySiteCategory.findById(testSiteCategoryId);
        siteCategory.status = "pending";
        siteCategory.persist();

        // When/Then: Should throw ValidationException
        assertThrows(ValidationException.class, () -> {
            votingService.castVote(testSiteCategoryId, testUserId, (short) 1);
        });
    }

    /**
     * Tests successful vote removal.
     */
    @Test
    @Transactional
    public void testRemoveVote_ExistingVote_DeletesVoteAndUpdatesAggregates() {
        // Given: User has cast upvote
        votingService.castVote(testSiteCategoryId, testUserId, (short) 1);
        DirectorySiteCategory before = DirectorySiteCategory.findById(testSiteCategoryId);
        assertEquals(1, before.upvotes);

        // When: User removes vote
        votingService.removeVote(testSiteCategoryId, testUserId);

        // Then: Vote is deleted
        Optional<DirectoryVote> voteOpt = DirectoryVote.findByUserAndSiteCategory(testSiteCategoryId, testUserId);
        assertFalse(voteOpt.isPresent());

        // And: Aggregates are updated
        DirectorySiteCategory after = DirectorySiteCategory.findById(testSiteCategoryId);
        assertEquals(0, after.upvotes);
        assertEquals(0, after.downvotes);
        assertEquals(0, after.score);
    }

    /**
     * Tests removing non-existent vote.
     */
    @Test
    @Transactional
    public void testRemoveVote_NonExistentVote_ThrowsResourceNotFoundException() {
        // Given: No existing vote
        assertTrue(DirectoryVote.findByUserAndSiteCategory(testSiteCategoryId, testUserId).isEmpty());

        // When/Then: Should throw ResourceNotFoundException
        assertThrows(ResourceNotFoundException.class, () -> {
            votingService.removeVote(testSiteCategoryId, testUserId);
        });
    }

    /**
     * Tests getUserVote returns empty when no vote exists.
     */
    @Test
    @Transactional
    public void testGetUserVote_NoVote_ReturnsEmpty() {
        // When: Get user vote when none exists
        Optional<Short> vote = votingService.getUserVote(testSiteCategoryId, testUserId);

        // Then: Should return empty
        assertFalse(vote.isPresent());
    }

    /**
     * Tests getUserVote returns upvote.
     */
    @Test
    @Transactional
    public void testGetUserVote_Upvote_ReturnsPositiveOne() {
        // Given: User has cast upvote
        votingService.castVote(testSiteCategoryId, testUserId, (short) 1);

        // When: Get user vote
        Optional<Short> vote = votingService.getUserVote(testSiteCategoryId, testUserId);

        // Then: Should return +1
        assertTrue(vote.isPresent());
        assertEquals((short) 1, vote.get());
    }

    /**
     * Tests getUserVote returns downvote.
     */
    @Test
    @Transactional
    public void testGetUserVote_Downvote_ReturnsNegativeOne() {
        // Given: User has cast downvote
        votingService.castVote(testSiteCategoryId, testUserId, (short) -1);

        // When: Get user vote
        Optional<Short> vote = votingService.getUserVote(testSiteCategoryId, testUserId);

        // Then: Should return -1
        assertTrue(vote.isPresent());
        assertEquals((short) -1, vote.get());
    }

    /**
     * Tests multiple users voting on same site-category.
     */
    @Test
    @Transactional
    public void testCastVote_MultipleUsers_AggregatesCorrect() {
        // Given: Create second user
        User user2 = new User();
        user2.email = "voter2_test_" + System.currentTimeMillis() + "@example.com";
        user2.directoryKarma = 5;
        user2.directoryTrustLevel = "untrusted";
        user2.preferences = java.util.Map.of();
        user2.isAnonymous = false;
        user2.createdAt = Instant.now();
        user2.updatedAt = Instant.now();
        user2.persist();

        // When: User 1 upvotes, User 2 downvotes
        votingService.castVote(testSiteCategoryId, testUserId, (short) 1);
        votingService.castVote(testSiteCategoryId, user2.id, (short) -1);

        // Then: Aggregates reflect both votes
        DirectorySiteCategory siteCategory = DirectorySiteCategory.findById(testSiteCategoryId);
        assertEquals(1, siteCategory.upvotes);
        assertEquals(1, siteCategory.downvotes);
        assertEquals(0, siteCategory.score); // 1 - 1 = 0
    }
}
