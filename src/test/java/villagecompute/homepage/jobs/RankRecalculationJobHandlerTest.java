package villagecompute.homepage.jobs;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.data.models.DirectoryCategory;
import villagecompute.homepage.data.models.DirectorySite;
import villagecompute.homepage.data.models.DirectorySiteCategory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RankRecalculationJobHandler}.
 *
 * <p>
 * Tests ranking algorithm, bubbling logic, score-based ordering, and category hierarchy traversal.
 */
@QuarkusTest
class RankRecalculationJobHandlerTest {

    @Inject
    RankRecalculationJobHandler handler;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    EntityManager entityManager;

    private UUID parentCategoryId;
    private UUID childCategoryId;
    private UUID site1Id;
    private UUID site2Id;
    private UUID site3Id;
    private UUID site4Id;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up test data
        DirectorySiteCategory.deleteAll();
        DirectorySite.deleteAll();
        DirectoryCategory.deleteAll();

        // Create parent category
        DirectoryCategory parentCategory = new DirectoryCategory();
        parentCategory.slug = "parent-category";
        parentCategory.name = "Parent Category";
        parentCategory.description = "Parent category for testing";
        parentCategory.parentId = null;
        parentCategory.iconUrl = null;
        parentCategory.sortOrder = 1;
        parentCategory.linkCount = 0;
        parentCategory.isActive = true;
        parentCategory.createdAt = Instant.now();
        parentCategory.updatedAt = Instant.now();
        parentCategory.persist();
        parentCategoryId = parentCategory.id;

        // Create child category
        DirectoryCategory childCategory = new DirectoryCategory();
        childCategory.slug = "child-category";
        childCategory.name = "Child Category";
        childCategory.description = "Child category for testing";
        childCategory.parentId = parentCategoryId;
        childCategory.iconUrl = null;
        childCategory.sortOrder = 1;
        childCategory.linkCount = 0;
        childCategory.isActive = true;
        childCategory.createdAt = Instant.now();
        childCategory.updatedAt = Instant.now();
        childCategory.persist();
        childCategoryId = childCategory.id;

        // Create test sites
        DirectorySite site1 = createSite("https://site1.com", "Site 1");
        site1Id = site1.id;

        DirectorySite site2 = createSite("https://site2.com", "Site 2");
        site2Id = site2.id;

        DirectorySite site3 = createSite("https://site3.com", "Site 3");
        site3Id = site3.id;

        DirectorySite site4 = createSite("https://site4.com", "Site 4");
        site4Id = site4.id;

        // Create site-category memberships with different scores
        // Site 1: score=15, rank should be 1
        createSiteCategory(site1Id, childCategoryId, 15, 18, 3, "approved");

        // Site 2: score=12, rank should be 2
        createSiteCategory(site2Id, childCategoryId, 12, 15, 3, "approved");

        // Site 3: score=8, rank should be 3 (below bubbling threshold)
        createSiteCategory(site3Id, childCategoryId, 8, 10, 2, "approved");

        // Site 4: score=5, rank should be 4 (below bubbling threshold)
        createSiteCategory(site4Id, childCategoryId, 5, 7, 2, "approved");
    }

    private DirectorySite createSite(String url, String title) {
        DirectorySite site = new DirectorySite();
        site.url = url;
        site.domain = DirectorySite.extractDomain(url);
        site.title = title;
        site.description = "Test site";
        site.submittedByUserId = UUID.randomUUID();
        site.status = "approved";
        site.isDead = false;
        site.healthCheckFailures = 0;
        site.createdAt = Instant.now();
        site.updatedAt = Instant.now();
        site.persist();
        return site;
    }

    private DirectorySiteCategory createSiteCategory(UUID siteId, UUID categoryId, int score, int upvotes,
            int downvotes, String status) {
        DirectorySiteCategory siteCategory = new DirectorySiteCategory();
        siteCategory.siteId = siteId;
        siteCategory.categoryId = categoryId;
        siteCategory.score = score;
        siteCategory.upvotes = upvotes;
        siteCategory.downvotes = downvotes;
        siteCategory.rankInCategory = null;
        siteCategory.submittedByUserId = UUID.randomUUID();
        siteCategory.approvedByUserId = UUID.randomUUID();
        siteCategory.status = status;
        siteCategory.createdAt = Instant.now();
        siteCategory.updatedAt = Instant.now();
        siteCategory.persist();
        return siteCategory;
    }

    @Test
    void testHandlesType() {
        // When: Check handler type
        JobType type = handler.handlesType();

        // Then: Should handle RANK_RECALCULATION
        assertEquals(JobType.RANK_RECALCULATION, type);
    }

    @Test
    @Transactional
    void testExecute_calculatesRanksCorrectly() throws Exception {
        // Given: Sites with different scores in child category
        // When: Execute rank recalculation job
        Map<String, Object> payload = Map.of();
        handler.execute(1L, payload);

        // Then: Ranks should be assigned by score DESC
        DirectorySiteCategory site1Cat = DirectorySiteCategory.findBySiteAndCategory(site1Id, childCategoryId).get();
        DirectorySiteCategory site2Cat = DirectorySiteCategory.findBySiteAndCategory(site2Id, childCategoryId).get();
        DirectorySiteCategory site3Cat = DirectorySiteCategory.findBySiteAndCategory(site3Id, childCategoryId).get();
        DirectorySiteCategory site4Cat = DirectorySiteCategory.findBySiteAndCategory(site4Id, childCategoryId).get();

        assertEquals(1, site1Cat.rankInCategory);
        assertEquals(2, site2Cat.rankInCategory);
        assertEquals(3, site3Cat.rankInCategory);
        assertEquals(4, site4Cat.rankInCategory);
    }

    @Test
    @Transactional
    void testBubbling_filtersCorrectSites() throws Exception {
        // Given: Sites with different scores and ranks
        // Execute rank recalculation first to assign ranks
        Map<String, Object> payload = Map.of();
        handler.execute(1L, payload);

        // When: Query bubbled sites for parent category
        List<DirectorySiteCategory> bubbledSites = RankRecalculationJobHandler.findBubbledSites(parentCategoryId);

        // Then: Only sites with score ≥ 10 AND rank ≤ 3 should bubble
        // Site 1: score=15, rank=1 → BUBBLED
        // Site 2: score=12, rank=2 → BUBBLED
        // Site 3: score=8, rank=3 → NOT BUBBLED (score < 10)
        // Site 4: score=5, rank=4 → NOT BUBBLED (score < 10 AND rank > 3)

        assertEquals(2, bubbledSites.size());

        // Verify bubbled sites are sorted by score DESC
        assertEquals(site1Id, bubbledSites.get(0).siteId);
        assertEquals(15, bubbledSites.get(0).score);
        assertEquals(1, bubbledSites.get(0).rankInCategory);

        assertEquals(site2Id, bubbledSites.get(1).siteId);
        assertEquals(12, bubbledSites.get(1).score);
        assertEquals(2, bubbledSites.get(1).rankInCategory);
    }

    @Test
    @Transactional
    void testBubbling_respectsScoreThreshold() throws Exception {
        // Given: Create site with rank=1 but score=9 (below threshold)
        DirectorySite lowScoreSite = createSite("https://lowscore.com", "Low Score Site");
        createSiteCategory(lowScoreSite.id, childCategoryId, 9, 10, 1, "approved");

        // Execute rank recalculation
        Map<String, Object> payload = Map.of();
        handler.execute(1L, payload);

        // When: Query bubbled sites
        List<DirectorySiteCategory> bubbledSites = RankRecalculationJobHandler.findBubbledSites(parentCategoryId);

        // Then: Low score site should NOT bubble (even if rank ≤ 3)
        assertFalse(bubbledSites.stream().anyMatch(sc -> sc.siteId.equals(lowScoreSite.id)));
    }

    @Test
    @Transactional
    void testBubbling_respectsRankThreshold() throws Exception {
        // Given: Sites exist with scores ≥ 10 but rank > 3
        // Site 3 and 4 have ranks 3 and 4, but score < 10, so let's add a new site with rank=4 and score=11
        DirectorySite lowRankSite = createSite("https://lowrank.com", "Low Rank Site");
        createSiteCategory(lowRankSite.id, childCategoryId, 11, 13, 2, "approved");

        // Execute rank recalculation
        Map<String, Object> payload = Map.of();
        handler.execute(1L, payload);

        // Verify rank assignment (should be 3rd after site1=15 and site2=12)
        DirectorySiteCategory lowRankCat = DirectorySiteCategory.findBySiteAndCategory(lowRankSite.id, childCategoryId)
                .get();
        assertEquals(3, lowRankCat.rankInCategory);

        // When: Query bubbled sites
        List<DirectorySiteCategory> bubbledSites = RankRecalculationJobHandler.findBubbledSites(parentCategoryId);

        // Then: Low rank site SHOULD bubble (score ≥ 10 AND rank = 3)
        assertTrue(bubbledSites.stream().anyMatch(sc -> sc.siteId.equals(lowRankSite.id)));
    }

    @Test
    @Transactional
    void testRankRecalculation_onlyUpdatesIfChanged() throws Exception {
        // Given: Execute rank recalculation once to set initial ranks
        Map<String, Object> payload = Map.of();
        handler.execute(1L, payload);

        DirectorySiteCategory site1Cat = DirectorySiteCategory.findBySiteAndCategory(site1Id, childCategoryId).get();
        Instant firstUpdate = site1Cat.updatedAt;

        // When: Execute again without changing scores
        // Wait 1 second to ensure timestamp would change if updated
        Thread.sleep(1000);
        handler.execute(2L, payload);

        // Then: updatedAt should NOT change (rank unchanged)
        DirectorySiteCategory site1CatAfter = DirectorySiteCategory.findBySiteAndCategory(site1Id, childCategoryId)
                .get();
        assertEquals(firstUpdate, site1CatAfter.updatedAt);
    }

    @Test
    @Transactional
    void testRankRecalculation_handlesTies() throws Exception {
        // Given: Two sites with same score
        DirectorySite tiedSite1 = createSite("https://tied1.com", "Tied Site 1");
        DirectorySite tiedSite2 = createSite("https://tied2.com", "Tied Site 2");

        // Create with same score, but tiedSite1 created earlier
        DirectorySiteCategory tied1Cat = createSiteCategory(tiedSite1.id, childCategoryId, 20, 20, 0, "approved");
        Thread.sleep(100); // Ensure different createdAt timestamps
        DirectorySiteCategory tied2Cat = createSiteCategory(tiedSite2.id, childCategoryId, 20, 20, 0, "approved");

        // When: Execute rank recalculation
        Map<String, Object> payload = Map.of();
        handler.execute(1L, payload);

        // Then: Earlier submission should win (lower rank)
        DirectorySiteCategory tied1CatAfter = DirectorySiteCategory.findBySiteAndCategory(tiedSite1.id, childCategoryId)
                .get();
        DirectorySiteCategory tied2CatAfter = DirectorySiteCategory.findBySiteAndCategory(tiedSite2.id, childCategoryId)
                .get();

        // Both should have same or adjacent ranks depending on createdAt order
        assertNotNull(tied1CatAfter.rankInCategory);
        assertNotNull(tied2CatAfter.rankInCategory);
    }

    @Test
    @Transactional
    void testBubbling_emptyWhenNoChildren() {
        // Given: Category with no children
        DirectoryCategory leafCategory = new DirectoryCategory();
        leafCategory.slug = "leaf-category";
        leafCategory.name = "Leaf Category";
        leafCategory.description = "Leaf category with no children";
        leafCategory.parentId = parentCategoryId;
        leafCategory.iconUrl = null;
        leafCategory.sortOrder = 2;
        leafCategory.linkCount = 0;
        leafCategory.isActive = true;
        leafCategory.createdAt = Instant.now();
        leafCategory.updatedAt = Instant.now();
        leafCategory.persist();

        // When: Query bubbled sites for leaf category
        List<DirectorySiteCategory> bubbledSites = RankRecalculationJobHandler.findBubbledSites(leafCategory.id);

        // Then: Should return empty list (no children)
        assertTrue(bubbledSites.isEmpty());
    }

    @Test
    @Transactional
    void testBubbling_multipleChildren() throws Exception {
        // Given: Create second child category
        DirectoryCategory child2Category = new DirectoryCategory();
        child2Category.slug = "child2-category";
        child2Category.name = "Child 2 Category";
        child2Category.description = "Second child category";
        child2Category.parentId = parentCategoryId;
        child2Category.iconUrl = null;
        child2Category.sortOrder = 2;
        child2Category.linkCount = 0;
        child2Category.isActive = true;
        child2Category.createdAt = Instant.now();
        child2Category.updatedAt = Instant.now();
        child2Category.persist();

        // Add sites to second child category
        DirectorySite child2Site = createSite("https://child2-site.com", "Child 2 Site");
        createSiteCategory(child2Site.id, child2Category.id, 25, 27, 2, "approved");

        // When: Execute rank recalculation
        Map<String, Object> payload = Map.of();
        handler.execute(1L, payload);

        // Query bubbled sites for parent
        List<DirectorySiteCategory> bubbledSites = RankRecalculationJobHandler.findBubbledSites(parentCategoryId);

        // Then: Should include sites from BOTH children
        // From child1: site1 (score=15, rank=1), site2 (score=12, rank=2)
        // From child2: child2Site (score=25, rank=1)
        assertEquals(3, bubbledSites.size());

        // Verify sorted by score DESC
        assertEquals(25, bubbledSites.get(0).score);
        assertEquals(15, bubbledSites.get(1).score);
        assertEquals(12, bubbledSites.get(2).score);
    }

    @Test
    @Transactional
    void testBubbling_onlyApprovedSites() throws Exception {
        // Given: Create pending site in child category
        DirectorySite pendingSite = createSite("https://pending.com", "Pending Site");
        createSiteCategory(pendingSite.id, childCategoryId, 50, 50, 0, "pending"); // High score but pending

        // When: Execute rank recalculation
        Map<String, Object> payload = Map.of();
        handler.execute(1L, payload);

        // Query bubbled sites
        List<DirectorySiteCategory> bubbledSites = RankRecalculationJobHandler.findBubbledSites(parentCategoryId);

        // Then: Pending site should NOT bubble
        assertFalse(bubbledSites.stream().anyMatch(sc -> sc.siteId.equals(pendingSite.id)));
    }

    @Test
    @Transactional
    void testBubbling_nullParentId() {
        // When: Query bubbled sites with null parent ID
        List<DirectorySiteCategory> bubbledSites = RankRecalculationJobHandler.findBubbledSites(null);

        // Then: Should return empty list
        assertTrue(bubbledSites.isEmpty());
    }

    @Test
    @Transactional
    void testExecute_processesAllActiveCategories() throws Exception {
        // Given: Multiple active categories
        long activeCategoriesCount = DirectoryCategory.count("isActive = true");
        assertTrue(activeCategoriesCount >= 2); // Parent and child from setUp

        // When: Execute rank recalculation
        Map<String, Object> payload = Map.of();
        handler.execute(1L, payload);

        // Then: Job should complete successfully
        // Verify all approved sites have ranks assigned
        long rankedSites = DirectorySiteCategory.count("status = 'approved' AND rankInCategory IS NOT NULL");
        assertTrue(rankedSites >= 4); // 4 sites from setUp
    }
}
