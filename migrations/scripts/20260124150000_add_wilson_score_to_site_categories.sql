-- Description: Add Wilson score field for confidence-based ranking of directory sites
--
-- The Wilson score confidence interval provides a statistically sound ranking metric
-- that accounts for sample size uncertainty. This ensures that sites with more votes
-- are ranked higher when upvote ratios are similar.
--
-- Wilson score formula (95% confidence level):
--   (p̂ + z²/2n - z × √(p̂(1-p̂)/n + z²/4n²)) / (1 + z²/n)
--   where: n = total votes, p̂ = upvotes/n, z = 1.96
--
-- Range: 0.0 (worst) to 1.0 (best)

-- Migration UP
ALTER TABLE directory_site_categories
ADD COLUMN wilson_score DECIMAL(10, 8) DEFAULT 0.0 NOT NULL;

COMMENT ON COLUMN directory_site_categories.wilson_score IS
'Wilson score confidence interval lower bound (95% confidence) for ranking. Range: 0.0-1.0. Accounts for sample size uncertainty.';

-- Create index for ORDER BY queries (descending for performance)
CREATE INDEX idx_site_categories_wilson_score ON directory_site_categories(wilson_score DESC);

-- Migration DOWN (rollback)
-- Uncomment below to rollback
-- DROP INDEX IF EXISTS idx_site_categories_wilson_score;
-- ALTER TABLE directory_site_categories DROP COLUMN wilson_score;
