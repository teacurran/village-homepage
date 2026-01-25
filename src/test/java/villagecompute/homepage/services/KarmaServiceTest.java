package villagecompute.homepage.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import villagecompute.homepage.data.models.DirectoryCategory;
import villagecompute.homepage.data.models.DirectorySite;
import villagecompute.homepage.data.models.DirectorySiteCategory;
import villagecompute.homepage.data.models.KarmaAudit;
import villagecompute.homepage.data.models.User;

/**
 * Unit tests for {@link KarmaService}.
 *
 * <p>
 * Tests cover:
 * <ul>
 * <li>Karma awards for approved submissions (+5)</li>
 * <li>Karma deductions for rejected submissions (-2)</li>
 * <li>Karma awards for upvotes received (+1)</li>
 * <li>Karma deductions for downvotes received (-1)</li>
 * <li>Karma adjustments for vote changes</li>
 * <li>Karma reversals for vote deletions</li>
 * <li>Trust level auto-promotion (untrusted → trusted at 10+ karma)</li>
 * <li>Manual admin karma adjustments</li>
 * <li>Manual trust level changes</li>
 * <li>Audit trail creation</li>
 * </ul>
 */
@QuarkusTest
class KarmaServiceTest {

    @Inject
    KarmaService karmaService;

    private UUID testUserId;
    private UUID testSiteCategoryId;
    private UUID testCategoryId;
    private UUID testSiteId;
    private UUID adminUserId;

    /**
     * Sets up test data before each test.
     */
    @BeforeEach
    @Transactional
    public void setupTestData() {
        // Clean up existing test data
        KarmaAudit.delete("1=1");
        DirectorySiteCategory.delete("1=1");
        DirectorySite.delete("1=1");
        DirectoryCategory.delete("1=1");
        User.delete("1=1");

        // Create test user (submitter)
        User testUser = new User();
        testUser.email = "karma_test_" + System.currentTimeMillis() + "@example.com";
        testUser.directoryKarma = 5; // Start at 5 karma
        testUser.directoryTrustLevel = User.TRUST_LEVEL_UNTRUSTED;
        testUser.preferences = java.util.Map.of();
        testUser.isAnonymous = false;
        testUser.createdAt = Instant.now();
        testUser.updatedAt = Instant.now();
        testUser.persist();
        testUserId = testUser.id;

        // Create admin user
        User adminUser = new User();
        adminUser.email = "admin_" + System.currentTimeMillis() + "@example.com";
        adminUser.directoryKarma = 100;
        adminUser.directoryTrustLevel = User.TRUST_LEVEL_MODERATOR;
        adminUser.preferences = java.util.Map.of();
        adminUser.isAnonymous = false;
        adminUser.createdAt = Instant.now();
        adminUser.updatedAt = Instant.now();
        adminUser.persist();
        adminUserId = adminUser.id;

        // Create test category
        DirectoryCategory category = new DirectoryCategory();
        category.parentId = null;
        category.name = "Test Category";
        category.slug = "test-category-" + System.currentTimeMillis();
        category.description = "Test category for karma tests";
        category.sortOrder = 1;
        category.linkCount = 0;
        category.isActive = true;
        category.createdAt = Instant.now();
        category.updatedAt = Instant.now();
        category.persist();
        testCategoryId = category.id;

        // Create test site
        DirectorySite site = new DirectorySite();
        site.url = "https://karma-test-" + System.currentTimeMillis() + ".example.com";
        site.domain = "karma-test.example.com";
        site.title = "Karma Test Site";
        site.description = "A test site for karma tests";
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
     * Tests karma award for approved submission.
     */
    @Test
    @Transactional
    public void testAwardForApprovedSubmission_Awards5Karma() {
        // Given: User starts with 5 karma
        User user = User.findById(testUserId);
        assertEquals(5, user.directoryKarma);

        // When: Submission is approved
        karmaService.awardForApprovedSubmission(testSiteCategoryId);

        // Then: User gains +5 karma (total 10)
        user = User.findById(testUserId);
        assertEquals(10, user.directoryKarma);

        // And: Auto-promoted to trusted at threshold
        assertEquals(User.TRUST_LEVEL_TRUSTED, user.directoryTrustLevel);

        // And: Audit record created
        long auditCount = KarmaAudit.count("userId = ?1 AND triggerType = ?2", testUserId,
                KarmaAudit.TRIGGER_SUBMISSION_APPROVED);
        assertEquals(1, auditCount);
    }

    /**
     * Tests karma deduction for rejected submission.
     */
    @Test
    @Transactional
    public void testDeductForRejectedSubmission_Deducts2Karma() {
        // Given: User starts with 5 karma
        User user = User.findById(testUserId);
        assertEquals(5, user.directoryKarma);

        // When: Submission is rejected
        karmaService.deductForRejectedSubmission(testSiteCategoryId);

        // Then: User loses -2 karma (total 3)
        user = User.findById(testUserId);
        assertEquals(3, user.directoryKarma);

        // And: Remains untrusted (below threshold)
        assertEquals(User.TRUST_LEVEL_UNTRUSTED, user.directoryTrustLevel);

        // And: Audit record created
        long auditCount = KarmaAudit.count("userId = ?1 AND triggerType = ?2", testUserId,
                KarmaAudit.TRIGGER_SUBMISSION_REJECTED);
        assertEquals(1, auditCount);
    }

    /**
     * Tests karma cannot go negative.
     */
    @Test
    @Transactional
    public void testDeductForRejectedSubmission_KarmaFloorIsZero() {
        // Given: User starts with 5 karma, but receives multiple rejections
        User user = User.findById(testUserId);
        assertEquals(5, user.directoryKarma);

        // When: User receives 3 rejections (3 × -2 = -6, but floor is 0)
        for (int i = 0; i < 3; i++) {
            // Create additional site-category for each rejection
            DirectorySiteCategory sc = new DirectorySiteCategory();
            sc.siteId = testSiteId;
            sc.categoryId = testCategoryId;
            sc.score = 0;
            sc.upvotes = 0;
            sc.downvotes = 0;
            sc.submittedByUserId = testUserId;
            sc.status = "rejected";
            sc.createdAt = Instant.now();
            sc.updatedAt = Instant.now();
            sc.persist();

            karmaService.deductForRejectedSubmission(sc.id);
        }

        // Then: Karma floors at 0 (not negative)
        user = User.findById(testUserId);
        assertEquals(0, user.directoryKarma);
    }

    /**
     * Tests karma award for upvote received.
     */
    @Test
    @Transactional
    public void testAwardForUpvoteReceived_Awards1Karma() {
        // Given: User starts with 5 karma
        User user = User.findById(testUserId);
        assertEquals(5, user.directoryKarma);

        // When: User receives upvote on submitted site
        UUID voteId = UUID.randomUUID();
        karmaService.awardForUpvoteReceived(testSiteCategoryId, voteId);

        // Then: User gains +1 karma (total 6)
        user = User.findById(testUserId);
        assertEquals(6, user.directoryKarma);

        // And: Audit record created
        long auditCount = KarmaAudit.count("userId = ?1 AND triggerType = ?2", testUserId,
                KarmaAudit.TRIGGER_VOTE_RECEIVED);
        assertEquals(1, auditCount);
    }

    /**
     * Tests karma deduction for downvote received.
     */
    @Test
    @Transactional
    public void testDeductForDownvoteReceived_Deducts1Karma() {
        // Given: User starts with 5 karma
        User user = User.findById(testUserId);
        assertEquals(5, user.directoryKarma);

        // When: User receives downvote on submitted site
        UUID voteId = UUID.randomUUID();
        karmaService.deductForDownvoteReceived(testSiteCategoryId, voteId);

        // Then: User loses -1 karma (total 4)
        user = User.findById(testUserId);
        assertEquals(4, user.directoryKarma);
    }

    /**
     * Tests karma adjustment for vote change (upvote to downvote).
     */
    @Test
    @Transactional
    public void testProcessVoteChange_UpvoteToDownvote_AdjustsNeg2Karma() {
        // Given: User starts with 5 karma
        User user = User.findById(testUserId);
        assertEquals(5, user.directoryKarma);

        // When: Vote changes from +1 to -1 (net change = -2)
        UUID voteId = UUID.randomUUID();
        karmaService.processVoteChange(testSiteCategoryId, voteId, (short) 1, (short) -1);

        // Then: User loses -2 karma (total 3)
        user = User.findById(testUserId);
        assertEquals(3, user.directoryKarma);
    }

    /**
     * Tests karma adjustment for vote change (downvote to upvote).
     */
    @Test
    @Transactional
    public void testProcessVoteChange_DownvoteToUpvote_AdjustsPos2Karma() {
        // Given: User starts with 5 karma
        User user = User.findById(testUserId);
        assertEquals(5, user.directoryKarma);

        // When: Vote changes from -1 to +1 (net change = +2)
        UUID voteId = UUID.randomUUID();
        karmaService.processVoteChange(testSiteCategoryId, voteId, (short) -1, (short) 1);

        // Then: User gains +2 karma (total 7)
        user = User.findById(testUserId);
        assertEquals(7, user.directoryKarma);
    }

    /**
     * Tests karma adjustment for vote change with no change is no-op.
     */
    @Test
    @Transactional
    public void testProcessVoteChange_SameValue_NoChange() {
        // Given: User starts with 5 karma
        User user = User.findById(testUserId);
        assertEquals(5, user.directoryKarma);

        // When: Vote "changes" from +1 to +1 (no change)
        UUID voteId = UUID.randomUUID();
        karmaService.processVoteChange(testSiteCategoryId, voteId, (short) 1, (short) 1);

        // Then: Karma unchanged (still 5)
        user = User.findById(testUserId);
        assertEquals(5, user.directoryKarma);
    }

    /**
     * Tests karma reversal for vote deletion (upvote deleted).
     */
    @Test
    @Transactional
    public void testProcessVoteDeleted_UpvoteDeleted_ReversesNeg1Karma() {
        // Given: User has 6 karma (5 + 1 from upvote)
        UUID voteId = UUID.randomUUID();
        karmaService.awardForUpvoteReceived(testSiteCategoryId, voteId);
        User user = User.findById(testUserId);
        assertEquals(6, user.directoryKarma);

        // When: Upvote is deleted (reverse +1 → -1)
        karmaService.processVoteDeleted(testSiteCategoryId, voteId, (short) 1);

        // Then: Karma reverts to 5
        user = User.findById(testUserId);
        assertEquals(5, user.directoryKarma);
    }

    /**
     * Tests karma reversal for vote deletion (downvote deleted).
     */
    @Test
    @Transactional
    public void testProcessVoteDeleted_DownvoteDeleted_ReversesPos1Karma() {
        // Given: User has 4 karma (5 - 1 from downvote)
        UUID voteId = UUID.randomUUID();
        karmaService.deductForDownvoteReceived(testSiteCategoryId, voteId);
        User user = User.findById(testUserId);
        assertEquals(4, user.directoryKarma);

        // When: Downvote is deleted (reverse -1 → +1)
        karmaService.processVoteDeleted(testSiteCategoryId, voteId, (short) -1);

        // Then: Karma reverts to 5
        user = User.findById(testUserId);
        assertEquals(5, user.directoryKarma);
    }

    /**
     * Tests trust level auto-promotion at 10+ karma.
     */
    @Test
    @Transactional
    public void testAutoPromotion_At10Karma_PromotesToTrusted() {
        // Given: User starts with 5 karma (untrusted)
        User user = User.findById(testUserId);
        assertEquals(5, user.directoryKarma);
        assertEquals(User.TRUST_LEVEL_UNTRUSTED, user.directoryTrustLevel);

        // When: User gains +5 karma (approved submission)
        karmaService.awardForApprovedSubmission(testSiteCategoryId);

        // Then: User promoted to trusted (karma = 10)
        user = User.findById(testUserId);
        assertEquals(10, user.directoryKarma);
        assertEquals(User.TRUST_LEVEL_TRUSTED, user.directoryTrustLevel);

        // And: Audit record shows promotion
        KarmaAudit audit = KarmaAudit.find("userId = ?1 ORDER BY createdAt DESC", testUserId).firstResult();
        assertEquals(User.TRUST_LEVEL_UNTRUSTED, audit.oldTrustLevel);
        assertEquals(User.TRUST_LEVEL_TRUSTED, audit.newTrustLevel);
    }

    /**
     * Tests trust level stays trusted even if karma drops below 10.
     */
    @Test
    @Transactional
    public void testNoAutoDemotion_KarmaDropsBelow10_StaysTrusted() {
        // Given: User has been promoted to trusted (10 karma)
        karmaService.awardForApprovedSubmission(testSiteCategoryId); // 5 → 10, promoted
        User user = User.findById(testUserId);
        assertEquals(10, user.directoryKarma);
        assertEquals(User.TRUST_LEVEL_TRUSTED, user.directoryTrustLevel);

        // When: User loses -2 karma (rejected submission)
        // Create additional site-category for rejection
        DirectorySiteCategory sc = new DirectorySiteCategory();
        sc.siteId = testSiteId;
        sc.categoryId = testCategoryId;
        sc.score = 0;
        sc.upvotes = 0;
        sc.downvotes = 0;
        sc.submittedByUserId = testUserId;
        sc.status = "rejected";
        sc.createdAt = Instant.now();
        sc.updatedAt = Instant.now();
        sc.persist();
        karmaService.deductForRejectedSubmission(sc.id);

        // Then: Karma drops to 8, but trust level unchanged (no auto-demotion)
        user = User.findById(testUserId);
        assertEquals(8, user.directoryKarma);
        assertEquals(User.TRUST_LEVEL_TRUSTED, user.directoryTrustLevel);
    }

    /**
     * Tests admin manual karma adjustment.
     */
    @Test
    @Transactional
    public void testAdminAdjustKarma_ManualAdjustment_UpdatesKarmaAndLogsAudit() {
        // Given: User starts with 5 karma
        User user = User.findById(testUserId);
        assertEquals(5, user.directoryKarma);

        // When: Admin manually adjusts karma by +10
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("reason_detail", "Exceptional contribution to community");
        karmaService.adminAdjustKarma(testUserId, 10, "Manual adjustment for exceptional contribution", adminUserId,
                metadata);

        // Then: Karma increased to 15
        user = User.findById(testUserId);
        assertEquals(15, user.directoryKarma);

        // And: Auto-promoted to trusted
        assertEquals(User.TRUST_LEVEL_TRUSTED, user.directoryTrustLevel);

        // And: Audit record shows admin adjustment
        KarmaAudit audit = KarmaAudit.find("userId = ?1 ORDER BY createdAt DESC", testUserId).firstResult();
        assertEquals(KarmaAudit.TRIGGER_ADMIN_ADJUSTMENT, audit.triggerType);
        assertEquals(adminUserId, audit.adjustedByUserId);
    }

    /**
     * Tests admin manual negative karma adjustment.
     */
    @Test
    @Transactional
    public void testAdminAdjustKarma_NegativeAdjustment_DeductsKarma() {
        // Given: User starts with 5 karma
        User user = User.findById(testUserId);
        assertEquals(5, user.directoryKarma);

        // When: Admin manually deducts -3 karma
        karmaService.adminAdjustKarma(testUserId, -3, "Penalty for spam", adminUserId, null);

        // Then: Karma decreased to 2
        user = User.findById(testUserId);
        assertEquals(2, user.directoryKarma);
    }

    /**
     * Tests admin manual karma adjustment with delta=0 is ignored.
     */
    @Test
    @Transactional
    public void testAdminAdjustKarma_ZeroDelta_Ignored() {
        // Given: User starts with 5 karma
        User user = User.findById(testUserId);
        assertEquals(5, user.directoryKarma);

        // When: Admin attempts to adjust by 0
        karmaService.adminAdjustKarma(testUserId, 0, "No-op adjustment", adminUserId, null);

        // Then: Karma unchanged
        user = User.findById(testUserId);
        assertEquals(5, user.directoryKarma);

        // And: No audit record created
        long auditCount = KarmaAudit.count("userId = ?1 AND adjustedByUserId = ?2", testUserId, adminUserId);
        assertEquals(0, auditCount);
    }

    /**
     * Tests admin manual karma adjustment with non-existent user throws exception.
     */
    @Test
    @Transactional
    public void testAdminAdjustKarma_NonExistentUser_ThrowsException() {
        // Given: Random non-existent user ID
        UUID nonExistentUserId = UUID.randomUUID();

        // When/Then: Should throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> {
            karmaService.adminAdjustKarma(nonExistentUserId, 10, "Test", adminUserId, null);
        });
    }

    /**
     * Tests manual trust level change (promote to moderator).
     */
    @Test
    @Transactional
    public void testSetTrustLevel_PromoteToModerator_UpdatesTrustLevelAndLogsAudit() {
        // Given: User is trusted
        karmaService.awardForApprovedSubmission(testSiteCategoryId); // Promote to trusted
        User user = User.findById(testUserId);
        assertEquals(User.TRUST_LEVEL_TRUSTED, user.directoryTrustLevel);

        // When: Admin promotes to moderator
        karmaService.setTrustLevel(testUserId, User.TRUST_LEVEL_MODERATOR, "Promoted for excellent moderation work",
                adminUserId);

        // Then: Trust level changed to moderator
        user = User.findById(testUserId);
        assertEquals(User.TRUST_LEVEL_MODERATOR, user.directoryTrustLevel);

        // And: Karma unchanged
        assertEquals(10, user.directoryKarma);

        // And: Audit record created
        KarmaAudit audit = KarmaAudit.find("userId = ?1 ORDER BY createdAt DESC", testUserId).firstResult();
        assertEquals(User.TRUST_LEVEL_TRUSTED, audit.oldTrustLevel);
        assertEquals(User.TRUST_LEVEL_MODERATOR, audit.newTrustLevel);
        assertEquals(10, audit.oldKarma); // Karma unchanged
        assertEquals(10, audit.newKarma);
    }

    /**
     * Tests manual trust level change (demote to untrusted).
     */
    @Test
    @Transactional
    public void testSetTrustLevel_DemoteToUntrusted_UpdatesTrustLevel() {
        // Given: User is trusted
        karmaService.awardForApprovedSubmission(testSiteCategoryId); // Promote to trusted
        User user = User.findById(testUserId);
        assertEquals(User.TRUST_LEVEL_TRUSTED, user.directoryTrustLevel);

        // When: Admin demotes to untrusted
        karmaService.setTrustLevel(testUserId, User.TRUST_LEVEL_UNTRUSTED, "Demotion for trust violations",
                adminUserId);

        // Then: Trust level changed to untrusted
        user = User.findById(testUserId);
        assertEquals(User.TRUST_LEVEL_UNTRUSTED, user.directoryTrustLevel);
    }

    /**
     * Tests manual trust level change with invalid level throws exception.
     */
    @Test
    @Transactional
    public void testSetTrustLevel_InvalidLevel_ThrowsException() {
        // When/Then: Should throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> {
            karmaService.setTrustLevel(testUserId, "invalid_level", "Test", adminUserId);
        });
    }

    /**
     * Tests manual trust level change with same level is ignored.
     */
    @Test
    @Transactional
    public void testSetTrustLevel_SameLevel_Ignored() {
        // Given: User is untrusted
        User user = User.findById(testUserId);
        assertEquals(User.TRUST_LEVEL_UNTRUSTED, user.directoryTrustLevel);

        // When: Admin sets to same level (untrusted)
        karmaService.setTrustLevel(testUserId, User.TRUST_LEVEL_UNTRUSTED, "No-op change", adminUserId);

        // Then: Trust level unchanged
        user = User.findById(testUserId);
        assertEquals(User.TRUST_LEVEL_UNTRUSTED, user.directoryTrustLevel);

        // And: No audit record created
        long auditCount = KarmaAudit.count("userId = ?1 AND adjustedByUserId = ?2", testUserId, adminUserId);
        assertEquals(0, auditCount);
    }
}
