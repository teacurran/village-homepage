package villagecompute.homepage.services;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.DirectorySiteCategory;

/**
 * Service for calculating ranking scores using Wilson score confidence interval.
 *
 * <p>
 * The Wilson score provides a lower bound on the true probability of a positive rating, accounting for sample size
 * uncertainty. This ensures that sites with more votes are ranked higher when upvote ratios are similar.
 *
 * <p>
 * <b>Algorithm:</b> Wilson score confidence interval (95% confidence level)
 * <ul>
 * <li>Formula: (p̂ + z²/2n - z × √(p̂(1-p̂)/n + z²/4n²)) / (1 + z²/n)</li>
 * <li>Where: n = total votes, p̂ = upvotes/n, z = 1.96 (95% confidence)</li>
 * <li>Range: 0.0 (worst) to 1.0 (best)</li>
 * </ul>
 *
 * <p>
 * <b>Edge Cases:</b>
 * <ul>
 * <li>0 votes → 0.0 (no confidence)</li>
 * <li>1 upvote, 0 downvotes → 0.206 (low confidence)</li>
 * <li>10 upvotes, 0 downvotes → 0.817 (medium-high confidence)</li>
 * <li>100 upvotes, 0 downvotes → 0.980 (very high confidence)</li>
 * <li>5 upvotes, 5 downvotes → 0.237 (controversial, 50/50 split)</li>
 * </ul>
 *
 * <p>
 * <b>Key Insight:</b> Sites with more votes are ranked higher at the same upvote ratio, because Wilson score rewards
 * statistical significance.
 *
 * <p>
 * Example: A site with 10/10 upvotes (Wilson score 0.817) ranks higher than a site with 1/1 upvotes (Wilson score
 * 0.206), even though both have 100% upvote ratio.
 *
 * @see <a href="https://en.wikipedia.org/wiki/Binomial_proportion_confidence_interval#Wilson_score_interval">Wilson
 *      Score Interval</a>
 */
@ApplicationScoped
public class RankCalculationService {

    private static final Logger LOG = Logger.getLogger(RankCalculationService.class);

    /**
     * Z-score for 95% confidence level (two-tailed test). Standard value: 1.96 (±1.96 standard deviations covers 95% of
     * normal distribution)
     */
    private static final double Z_SCORE_95_PERCENT = 1.96;

    /**
     * Calculates Wilson score confidence interval lower bound.
     *
     * <p>
     * This provides a conservative estimate of the true upvote proportion, penalizing sites with low sample sizes.
     *
     * <p>
     * <b>Mathematical Derivation:</b>
     * <ol>
     * <li>Start with binomial proportion p̂ = upvotes / total_votes</li>
     * <li>Add continuity correction: z²/(2n)</li>
     * <li>Subtract error margin: z × √(variance)</li>
     * <li>Normalize by: (1 + z²/n)</li>
     * </ol>
     *
     * @param upvotes
     *            Number of upvotes (must be >= 0)
     * @param downvotes
     *            Number of downvotes (must be >= 0)
     * @return Wilson score (0.0 to 1.0), or 0.0 if no votes
     * @throws IllegalArgumentException
     *             if upvotes or downvotes are negative
     */
    public double calculateWilsonScore(int upvotes, int downvotes) {
        if (upvotes < 0 || downvotes < 0) {
            throw new IllegalArgumentException("Upvotes and downvotes must be non-negative (got upvotes=" + upvotes
                    + ", downvotes=" + downvotes + ")");
        }

        // Edge case: no votes
        if (upvotes + downvotes == 0) {
            return 0.0;
        }

        double n = upvotes + downvotes;
        double p = upvotes / n;
        double z = Z_SCORE_95_PERCENT;

        // Wilson score formula components
        double zSquaredOver2n = z * z / (2 * n);
        double pTimesOneMinusP = p * (1 - p);
        double zSquaredOver4nSquared = z * z / (4 * n * n);
        double sqrtTerm = Math.sqrt((pTimesOneMinusP + zSquaredOver4nSquared) / n);

        // Wilson score lower bound
        double numerator = p + zSquaredOver2n - z * sqrtTerm;
        double denominator = 1 + z * z / n;

        double score = numerator / denominator;

        // Clamp to [0, 1] range for safety (handles floating-point edge cases)
        double clampedScore = Math.max(0.0, Math.min(1.0, score));

        LOG.tracef("Wilson score calculated: upvotes=%d, downvotes=%d, score=%.8f", Integer.valueOf(upvotes),
                Integer.valueOf(downvotes), Double.valueOf(clampedScore));

        return clampedScore;
    }

    /**
     * Updates Wilson score for a site-category membership.
     *
     * <p>
     * This method calculates the Wilson score from cached upvotes/downvotes and persists it to the entity. This should
     * be called whenever vote counts change.
     *
     * <p>
     * <b>Usage:</b> Called by:
     * <ul>
     * <li>{@link villagecompute.homepage.jobs.RankRecalculationJobHandler} during hourly rank recalculation</li>
     * <li>{@link DirectorySiteCategory#updateAggregates()} when vote counts change</li>
     * </ul>
     *
     * @param siteCategory
     *            The site-category membership to update
     * @return The calculated Wilson score (0.0 to 1.0)
     */
    public double recalculateWilsonScoreForSiteCategory(DirectorySiteCategory siteCategory) {
        if (siteCategory == null) {
            throw new IllegalArgumentException("siteCategory cannot be null");
        }

        double wilsonScore = calculateWilsonScore(siteCategory.upvotes, siteCategory.downvotes);
        siteCategory.wilsonScore = wilsonScore;

        LOG.debugf("Recalculated Wilson score for site-category %s: %.6f (upvotes=%d, downvotes=%d)", siteCategory.id,
                wilsonScore, siteCategory.upvotes, siteCategory.downvotes);

        return wilsonScore;
    }
}
