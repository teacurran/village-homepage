package villagecompute.homepage.jobs;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.DirectoryCategory;
import villagecompute.homepage.data.models.DirectorySiteCategory;
import villagecompute.homepage.services.RankCalculationService;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Recalculates ranking scores for sites in Good Sites directory categories.
 *
 * <p>
 * <b>Execution Strategy:</b>
 * <ul>
 * <li>Processes all active categories sequentially</li>
 * <li>Calculates Wilson score for each site-category membership</li>
 * <li>Within each category, orders approved sites by wilsonScore DESC, then createdAt DESC</li>
 * <li>Assigns 1-indexed rank to each site (rank 1 = highest Wilson score)</li>
 * <li>Updates rankInCategory and wilsonScore fields in directory_site_categories table</li>
 * <li>Enables bubbling logic (sites with wilsonScore ≥ 0.5 AND rank ≤ 3 bubble to parent)</li>
 * </ul>
 *
 * <p>
 * <b>Ranking Algorithm:</b>
 * <ul>
 * <li>Primary sort: wilsonScore DESC (confidence-based ranking using Wilson score confidence interval)</li>
 * <li>Tiebreaker: createdAt DESC (earlier submission wins)</li>
 * <li>Rank assignment: position + 1 (first site gets rank 1)</li>
 * <li>Only approved sites receive ranks</li>
 * <li>Pending/rejected sites have null rank</li>
 * <li>Wilson score calculated before ranking using RankCalculationService</li>
 * </ul>
 *
 * <p>
 * <b>Bubbling Threshold (Feature F13.8):</b>
 * <ul>
 * <li>Sites with wilsonScore ≥ 0.5 AND rank ≤ 3 are eligible for bubbling</li>
 * <li>Bubbled sites appear in parent category with indicator badge</li>
 * <li>Bubbling query: {@link #findBubbledSites(java.util.UUID)}</li>
 * <li>UI sorting: direct sites first, then bubbled sites (both by wilsonScore DESC)</li>
 * </ul>
 *
 * <p>
 * <b>Performance Considerations:</b>
 * <ul>
 * <li>Processes one category at a time to avoid memory issues</li>
 * <li>Only updates ranks if changed (reduces DB writes)</li>
 * <li>Flushes changes per category for incremental persistence</li>
 * <li>Expected runtime: ~5 seconds for 1000 categories with 50 sites each</li>
 * </ul>
 *
 * <p>
 * <b>Metrics Emitted:</b>
 * <ul>
 * <li>rank_recalculation.categories.processed (counter)</li>
 * <li>rank_recalculation.sites.ranked (counter)</li>
 * <li>rank_recalculation.duration (timer)</li>
 * </ul>
 *
 * <p>
 * <b>Scheduled Execution:</b> Hourly via RankRecalculationScheduler (cron: 0 0 * * * ? - top of every hour)
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P7: Participates in unified async orchestration framework</li>
 * <li>Feature F13.8: Bubbling logic for top-ranked sites</li>
 * </ul>
 *
 * @see DirectorySiteCategory#rankInCategory
 * @see DirectoryCategory#findByParentId(java.util.UUID)
 * @see RankRecalculationScheduler
 */
@ApplicationScoped
public class RankRecalculationJobHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(RankRecalculationJobHandler.class);

    /**
     * Wilson score threshold for bubbling sites to parent category. Value 0.5 represents statistically significant
     * positive rating (95% confidence).
     */
    private static final double BUBBLING_WILSON_SCORE_THRESHOLD = 0.5;

    /**
     * Rank threshold for bubbling (top 3 sites per category).
     */
    private static final int BUBBLING_RANK_THRESHOLD = 3;

    /**
     * Epsilon for comparing Wilson scores (8 decimal places precision).
     */
    private static final double WILSON_SCORE_EPSILON = 0.00000001;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    RankCalculationService rankCalculationService;

    @Override
    public JobType handlesType() {
        return JobType.RANK_RECALCULATION;
    }

    @Override
    @Transactional
    public void execute(Long jobId, Map<String, Object> payload) throws Exception {
        LOG.infof("Starting rank recalculation job (jobId=%d)", jobId);

        Timer.Sample sample = Timer.start(meterRegistry);

        // Process all active categories
        List<DirectoryCategory> allCategories = DirectoryCategory.findActive();
        LOG.infof("Found %d active categories to process", allCategories.size());

        int totalSitesRanked = 0;
        int categoriesProcessed = 0;

        for (DirectoryCategory category : allCategories) {
            int sitesRanked = recalculateRanksForCategory(category.id);
            totalSitesRanked += sitesRanked;
            categoriesProcessed++;

            if (categoriesProcessed % 10 == 0) {
                LOG.infof("Progress: processed %d/%d categories, ranked %d sites", categoriesProcessed,
                        allCategories.size(), totalSitesRanked);
            }

            // Flush changes per category
            DirectorySiteCategory.flush();
        }

        // Emit metrics
        meterRegistry.counter("rank_recalculation.categories.processed").increment(categoriesProcessed);
        meterRegistry.counter("rank_recalculation.sites.ranked").increment(totalSitesRanked);

        sample.stop(Timer.builder("rank_recalculation.duration").register(meterRegistry));

        LOG.infof("Rank recalculation completed: categories=%d, sites_ranked=%d", categoriesProcessed,
                totalSitesRanked);
    }

    /**
     * Recalculates Wilson scores and ranks for all approved sites in a single category.
     *
     * <p>
     * Sites are ordered by wilsonScore DESC, then createdAt DESC. Rank is assigned as position + 1 (1-indexed). Only
     * updates ranks/Wilson scores if they have changed to minimize database writes.
     *
     * @param categoryId
     *            Category UUID to process
     * @return Number of sites ranked in this category
     */
    private int recalculateRanksForCategory(java.util.UUID categoryId) {
        // Query approved sites ordered by wilsonScore DESC, createdAt DESC
        List<DirectorySiteCategory> sites = DirectorySiteCategory.findApprovedInCategory(categoryId);

        if (sites.isEmpty()) {
            return 0;
        }

        // Recalculate Wilson scores and assign ranks (1-indexed)
        int ranksUpdated = 0;
        for (int i = 0; i < sites.size(); i++) {
            DirectorySiteCategory siteCategory = sites.get(i);
            int newRank = i + 1;

            // Recalculate Wilson score
            double newWilsonScore = rankCalculationService.recalculateWilsonScoreForSiteCategory(siteCategory);

            // Check if rank or Wilson score changed (epsilon comparison for doubles)
            boolean rankChanged = siteCategory.rankInCategory == null || siteCategory.rankInCategory != newRank;
            boolean wilsonScoreChanged = siteCategory.wilsonScore == null
                    || Math.abs(siteCategory.wilsonScore - newWilsonScore) > WILSON_SCORE_EPSILON;

            // Only update if rank OR Wilson score changed
            if (rankChanged || wilsonScoreChanged) {
                siteCategory.rankInCategory = newRank;
                siteCategory.wilsonScore = newWilsonScore;
                siteCategory.updatedAt = Instant.now();
                siteCategory.persist();
                ranksUpdated++;
            }
        }

        if (ranksUpdated > 0) {
            LOG.debugf("Updated %d ranks in category %s", ranksUpdated, categoryId);
        }

        return sites.size();
    }

    /**
     * Finds bubbled sites for a parent category.
     *
     * <p>
     * Bubbling criteria (Feature F13.8):
     * <ul>
     * <li>Site must be in a child category of the specified parent</li>
     * <li>Site must have wilsonScore ≥ 0.5 (statistically significant positive rating)</li>
     * <li>Site must have rank ≤ 3 in its category</li>
     * <li>Site must have status = 'approved'</li>
     * </ul>
     *
     * <p>
     * <b>UI Display:</b> Bubbled sites show with yellow background and badge indicating source category. They are
     * sorted AFTER direct sites but still ordered by wilsonScore DESC within the bubbled group.
     *
     * <p>
     * <b>Example Query Result:</b>
     *
     * <pre>
     * Parent: "Computers"
     * Child: "Programming" (contains site "GitHub" with wilsonScore=0.85, rank=1)
     * Child: "Linux" (contains site "Ubuntu" with wilsonScore=0.72, rank=2)
     *
     * Result: [GitHub (from Programming), Ubuntu (from Linux)]
     * </pre>
     *
     * @param parentCategoryId
     *            Parent category UUID
     * @return List of bubbled sites sorted by Wilson score DESC
     */
    public static List<DirectorySiteCategory> findBubbledSites(java.util.UUID parentCategoryId) {
        if (parentCategoryId == null) {
            return List.of();
        }

        // Find all child categories
        List<DirectoryCategory> children = DirectoryCategory.findByParentId(parentCategoryId);

        if (children.isEmpty()) {
            return List.of();
        }

        // Build query to find bubbled sites across all children
        // Sites must meet: wilsonScore >= 0.5 AND rank <= 3 AND status = 'approved'
        StringBuilder query = new StringBuilder();
        query.append("categoryId IN (");

        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                query.append(", ");
            }
            query.append("?").append(i + 1);
        }

        query.append(") AND status = 'approved' ");
        query.append("AND wilsonScore >= ").append(BUBBLING_WILSON_SCORE_THRESHOLD).append(" ");
        query.append("AND rankInCategory <= ").append(BUBBLING_RANK_THRESHOLD).append(" ");
        query.append("ORDER BY wilsonScore DESC, createdAt DESC");

        // Extract child IDs as parameters
        Object[] params = children.stream().map(c -> c.id).toArray();

        return DirectorySiteCategory.find(query.toString(), params).list();
    }
}
