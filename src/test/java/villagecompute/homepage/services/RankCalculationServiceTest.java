package villagecompute.homepage.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Wilson score calculation service.
 *
 * <p>
 * Wilson score confidence interval provides statistically sound ranking that accounts for sample size uncertainty.
 * These tests verify correct behavior across edge cases and validate that the algorithm rewards statistical
 * significance.
 *
 * <p>
 * Note: This is a unit test (not @QuarkusTest) because RankCalculationService is a stateless service with no
 * dependencies. No database or Quarkus container is required.
 */
public class RankCalculationServiceTest {

    private RankCalculationService rankCalculationService;

    @BeforeEach
    public void setup() {
        rankCalculationService = new RankCalculationService();
    }

    /**
     * Edge case: No votes should return 0.0 (no confidence).
     */
    @Test
    public void testCalculateWilsonScore_noVotes_returnsZero() {
        double score = rankCalculationService.calculateWilsonScore(0, 0);
        assertEquals(0.0, score, 0.001, "No votes should return 0.0");
    }

    /**
     * Edge case: 1 upvote with no downvotes has low confidence (~0.206).
     */
    @Test
    public void testCalculateWilsonScore_oneUpvote_lowConfidence() {
        double score = rankCalculationService.calculateWilsonScore(1, 0);
        assertEquals(0.206, score, 0.01, "1 upvote should return ~0.206 (low confidence)");
    }

    /**
     * 10 upvotes with no downvotes has medium-high confidence (~0.817).
     */
    @Test
    public void testCalculateWilsonScore_tenUpvotes_mediumConfidence() {
        double score = rankCalculationService.calculateWilsonScore(10, 0);
        assertEquals(0.817, score, 0.01, "10 upvotes should return ~0.817 (medium-high confidence)");
    }

    /**
     * 100 upvotes with no downvotes has very high confidence (~0.980).
     */
    @Test
    public void testCalculateWilsonScore_hundredUpvotes_highConfidence() {
        double score = rankCalculationService.calculateWilsonScore(100, 0);
        assertEquals(0.980, score, 0.01, "100 upvotes should return ~0.980 (very high confidence)");
    }

    /**
     * 50/50 split (controversial) has low score (~0.280).
     */
    @Test
    public void testCalculateWilsonScore_controversial_lowScore() {
        double score = rankCalculationService.calculateWilsonScore(5, 5);
        assertEquals(0.280, score, 0.01, "50/50 split should return ~0.280 (controversial)");
    }

    /**
     * Key insight: More votes wins at same upvote ratio.
     *
     * <p>
     * 100 upvotes (100% ratio) should rank higher than 10 upvotes (100% ratio) because of higher statistical
     * significance.
     */
    @Test
    public void testCalculateWilsonScore_moreVotesWinsAtSameRatio() {
        double score10 = rankCalculationService.calculateWilsonScore(10, 0);
        double score100 = rankCalculationService.calculateWilsonScore(100, 0);
        assertTrue(score100 > score10, "100 upvotes should rank higher than 10 upvotes at same ratio");
    }

    /**
     * Edge case: All downvotes (0 upvotes) should return very low score (clamped to >= 0.0). Note: Wilson score for
     * 0/10 ratio is technically ~0.095, but could be clamped to 0.0 for simplicity.
     */
    @Test
    public void testCalculateWilsonScore_allDownvotes_returnsLowScore() {
        double score = rankCalculationService.calculateWilsonScore(0, 10);
        assertTrue(score >= 0.0 && score <= 0.1, "All downvotes should return very low score (close to 0.0)");
    }

    /**
     * Validation: Negative upvotes should throw IllegalArgumentException.
     */
    @Test
    public void testCalculateWilsonScore_negativeUpvotes_throwsException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            rankCalculationService.calculateWilsonScore(-1, 0);
        });
        assertTrue(exception.getMessage().contains("non-negative"), "Exception message should mention non-negative");
    }

    /**
     * Validation: Negative downvotes should throw IllegalArgumentException.
     */
    @Test
    public void testCalculateWilsonScore_negativeDownvotes_throwsException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            rankCalculationService.calculateWilsonScore(0, -1);
        });
        assertTrue(exception.getMessage().contains("non-negative"), "Exception message should mention non-negative");
    }

    /**
     * Realistic example: 99 upvotes, 1 downvote (99% ratio) should have high score.
     */
    @Test
    public void testCalculateWilsonScore_highRatioWithManyVotes_highScore() {
        double score = rankCalculationService.calculateWilsonScore(99, 1);
        assertEquals(0.953, score, 0.01, "99/1 ratio should return ~0.953 (high confidence)");
    }

    /**
     * Statistical property: Site with better ratio should rank higher, even with fewer votes.
     *
     * <p>
     * 20 upvotes, 0 downvotes (100% ratio) should rank higher than 50 upvotes, 50 downvotes (50% ratio).
     */
    @Test
    public void testCalculateWilsonScore_betterRatioWinsEvenWithFewerVotes() {
        double score20_0 = rankCalculationService.calculateWilsonScore(20, 0); // 100% ratio
        double score50_50 = rankCalculationService.calculateWilsonScore(50, 50); // 50% ratio
        assertTrue(score20_0 > score50_50, "Better upvote ratio should rank higher even with fewer votes");
    }

    /**
     * Clamping: Wilson score should never exceed 1.0.
     */
    @Test
    public void testCalculateWilsonScore_neverExceedsOne() {
        double score = rankCalculationService.calculateWilsonScore(1000, 0);
        assertTrue(score <= 1.0, "Wilson score should never exceed 1.0");
    }

    /**
     * Clamping: Wilson score should never be negative.
     */
    @Test
    public void testCalculateWilsonScore_neverNegative() {
        double score = rankCalculationService.calculateWilsonScore(0, 1000);
        assertTrue(score >= 0.0, "Wilson score should never be negative");
    }

    /**
     * Realistic example: Slightly positive (6 upvotes, 4 downvotes) should have moderate score.
     */
    @Test
    public void testCalculateWilsonScore_slightlyPositive_moderateScore() {
        double score = rankCalculationService.calculateWilsonScore(6, 4);
        assertTrue(score > 0.3 && score < 0.6,
                "Slightly positive rating (60% upvotes) should have moderate score (0.3-0.6)");
    }

    /**
     * Regression test: Verify exact Wilson score for known inputs.
     *
     * <p>
     * 10 upvotes, 2 downvotes should produce Wilson score ≈ 0.589.
     */
    @Test
    public void testCalculateWilsonScore_regressionTest_10up2down() {
        double score = rankCalculationService.calculateWilsonScore(10, 2);
        assertEquals(0.589, score, 0.01, "10 upvotes, 2 downvotes should return ~0.589");
    }

    /**
     * Statistical property: Adding upvotes should increase Wilson score.
     */
    @Test
    public void testCalculateWilsonScore_addingUpvotesIncreasesScore() {
        double score5 = rankCalculationService.calculateWilsonScore(5, 0);
        double score10 = rankCalculationService.calculateWilsonScore(10, 0);
        assertTrue(score10 > score5, "Adding upvotes should increase Wilson score");
    }

    /**
     * Statistical property: Adding downvotes should decrease Wilson score.
     */
    @Test
    public void testCalculateWilsonScore_addingDownvotesDecreasesScore() {
        double score10_0 = rankCalculationService.calculateWilsonScore(10, 0);
        double score10_5 = rankCalculationService.calculateWilsonScore(10, 5);
        assertTrue(score10_5 < score10_0, "Adding downvotes should decrease Wilson score");
    }
}
