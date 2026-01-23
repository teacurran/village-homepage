-- Add AI category suggestion column to marketplace_listings
-- This column stores AI-generated category recommendations in JSONB format for human review
-- before applying the suggested category to the actual category_id field.

ALTER TABLE marketplace_listings
ADD COLUMN ai_category_suggestion JSONB;

COMMENT ON COLUMN marketplace_listings.ai_category_suggestion IS
'AI-generated category suggestion stored as JSON for human review before applying. Contains: category, subcategory, confidenceScore (0.0-1.0), and reasoning. Suggestions with confidenceScore < 0.7 should be flagged for manual review.';

-- Create index for finding listings without AI suggestions
-- This supports the scheduled job that finds uncategorized listings for batch processing
CREATE INDEX idx_marketplace_listings_needs_categorization
ON marketplace_listings((ai_category_suggestion IS NULL))
WHERE status = 'active';

COMMENT ON INDEX idx_marketplace_listings_needs_categorization IS
'Index to efficiently find active listings that need AI categorization. Used by scheduled job to batch process uncategorized listings.';
