-- Add embedding vector columns for semantic search (Feature I4.T5)
-- OpenAI text-embedding-3-small produces 1536-dimensional float vectors
-- Using pgvector extension for efficient similarity search
-- Note: Anthropic API does not provide embedding models; using OpenAI for embeddings

-- Add embedding column to feed_items
ALTER TABLE feed_items
ADD COLUMN content_embedding vector(1536);

COMMENT ON COLUMN feed_items.content_embedding IS
'OpenAI embedding vector (1536 dimensions) for semantic search on feed content';

-- Add embedding column to marketplace_listings
ALTER TABLE marketplace_listings
ADD COLUMN description_embedding vector(1536);

COMMENT ON COLUMN marketplace_listings.description_embedding IS
'OpenAI embedding vector (1536 dimensions) for semantic search on listing descriptions';

-- Add embedding column to directory_sites
ALTER TABLE directory_sites
ADD COLUMN description_embedding vector(1536);

COMMENT ON COLUMN directory_sites.description_embedding IS
'OpenAI embedding vector (1536 dimensions) for semantic search on site descriptions';

-- Create IVFFlat indexes for fast approximate nearest neighbor search
-- IVFFlat divides the vector space into lists for efficient similarity search
-- vector_cosine_ops uses cosine distance metric (range 0-2, where 0 = identical)

-- Index on feed_items embeddings
-- Note: IVFFlat indexes require data to exist before creation, so we create them
-- but they will be empty until embeddings are generated
CREATE INDEX IF NOT EXISTS idx_feed_items_embedding
ON feed_items
USING ivfflat (content_embedding vector_cosine_ops)
WITH (lists = 100);

COMMENT ON INDEX idx_feed_items_embedding IS
'IVFFlat index for cosine similarity search on feed item content embeddings';

-- Index on marketplace_listings embeddings
CREATE INDEX IF NOT EXISTS idx_listings_embedding
ON marketplace_listings
USING ivfflat (description_embedding vector_cosine_ops)
WITH (lists = 100);

COMMENT ON INDEX idx_listings_embedding IS
'IVFFlat index for cosine similarity search on listing description embeddings';

-- Index on directory_sites embeddings
CREATE INDEX IF NOT EXISTS idx_sites_embedding
ON directory_sites
USING ivfflat (description_embedding vector_cosine_ops)
WITH (lists = 100);

COMMENT ON INDEX idx_sites_embedding IS
'IVFFlat index for cosine similarity search on site description embeddings';

-- Note: The 'lists' parameter controls the IVFFlat index granularity
-- lists = 100 is suitable for small-to-medium datasets (< 1M vectors)
-- For larger datasets, consider increasing to sqrt(num_vectors)
