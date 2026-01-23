package villagecompute.homepage.data.models;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Parameters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.AiTagsType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Feed item entity implementing the Panache ActiveRecord pattern for aggregated news articles.
 *
 * <p>
 * Stores RSS/Atom feed items with deduplication via {@code item_guid} and optional AI tagging via LangChain4j. AI tags
 * include topics, sentiment, categories, and confidence scores per Policy P2/P10 (AI budget control).
 *
 * <p>
 * <b>Schema Mapping:</b>
 * <ul>
 * <li>{@code id} (UUID, PK) - Primary identifier</li>
 * <li>{@code source_id} (UUID, FK) - Reference to rss_sources</li>
 * <li>{@code title} (TEXT) - Article title</li>
 * <li>{@code url} (TEXT) - Article URL</li>
 * <li>{@code description} (TEXT) - Article summary/description</li>
 * <li>{@code content} (TEXT) - Full article content (if available)</li>
 * <li>{@code item_guid} (TEXT, UNIQUE) - RSS GUID for deduplication</li>
 * <li>{@code content_hash} (TEXT) - MD5 hash for similarity detection</li>
 * <li>{@code author} (TEXT) - Article author</li>
 * <li>{@code published_at} (TIMESTAMPTZ) - Publication timestamp</li>
 * <li>{@code ai_tags} (JSONB) - AI-generated tags (topics, sentiment, categories)</li>
 * <li>{@code ai_tagged} (BOOLEAN) - AI tagging completion flag</li>
 * <li>{@code fetched_at} (TIMESTAMPTZ) - Fetch timestamp</li>
 * </ul>
 *
 * <p>
 * <b>Partitioning:</b> Monthly partitioning by {@code published_at} is planned but not yet implemented. See
 * {@code docs/ops/feed-governance.md} for partition strategy and 90-day retention policy per Policy P14.
 *
 * @see RssSource for feed sources
 * @see AiTagsType for AI tag structure
 */
@Entity
@Table(
        name = "feed_items")
@NamedQuery(
        name = FeedItem.QUERY_FIND_BY_GUID,
        query = FeedItem.JPQL_FIND_BY_GUID)
@NamedQuery(
        name = FeedItem.QUERY_FIND_BY_SOURCE,
        query = FeedItem.JPQL_FIND_BY_SOURCE)
@NamedQuery(
        name = FeedItem.QUERY_FIND_RECENT,
        query = FeedItem.JPQL_FIND_RECENT)
@NamedQuery(
        name = FeedItem.QUERY_FIND_UNTAGGED,
        query = FeedItem.JPQL_FIND_UNTAGGED)
@NamedQuery(
        name = FeedItem.QUERY_FIND_BY_CONTENT_HASH,
        query = FeedItem.JPQL_FIND_BY_CONTENT_HASH)
public class FeedItem extends PanacheEntityBase {

    private static final Logger LOG = Logger.getLogger(FeedItem.class);

    public static final String JPQL_FIND_BY_GUID = "SELECT f FROM FeedItem f WHERE f.itemGuid = :guid";
    public static final String QUERY_FIND_BY_GUID = "FeedItem.findByGuid";

    public static final String JPQL_FIND_BY_SOURCE = "SELECT f FROM FeedItem f WHERE f.sourceId = :sourceId ORDER BY f.publishedAt DESC";
    public static final String QUERY_FIND_BY_SOURCE = "FeedItem.findBySource";

    public static final String JPQL_FIND_RECENT = "SELECT f FROM FeedItem f ORDER BY f.publishedAt DESC";
    public static final String QUERY_FIND_RECENT = "FeedItem.findRecent";

    public static final String JPQL_FIND_UNTAGGED = "SELECT f FROM FeedItem f WHERE f.aiTagged = false";
    public static final String QUERY_FIND_UNTAGGED = "FeedItem.findUntagged";

    public static final String JPQL_FIND_BY_CONTENT_HASH = "SELECT f FROM FeedItem f WHERE f.contentHash = :contentHash";
    public static final String QUERY_FIND_BY_CONTENT_HASH = "FeedItem.findByContentHash";

    @Id
    @GeneratedValue
    @Column(
            nullable = false)
    public UUID id;

    @Column(
            name = "source_id",
            nullable = false)
    public UUID sourceId;

    @Column(
            nullable = false)
    public String title;

    @Column(
            nullable = false)
    public String url;

    @Column
    public String description;

    @Column
    public String content;

    @Column(
            name = "item_guid",
            nullable = false)
    public String itemGuid;

    @Column(
            name = "content_hash")
    public String contentHash;

    @Column
    public String author;

    @Column(
            name = "published_at",
            nullable = false)
    public Instant publishedAt;

    @Column(
            name = "ai_tags",
            columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    public AiTagsType aiTags;

    @Column(
            name = "ai_tagged",
            nullable = false)
    public boolean aiTagged;

    @Column(
            name = "fetched_at",
            nullable = false)
    public Instant fetchedAt;

    /**
     * OpenAI embedding vector (1536 dimensions) for semantic search on feed content.
     *
     * <p>
     * Stored as PostgreSQL vector type via pgvector extension. Used for cosine similarity semantic search to find
     * semantically related articles beyond keyword matching. Generated by
     * {@link villagecompute.homepage.services.SemanticSearchService} when content is indexed.
     *
     * <p>
     * Nullable - embeddings may not be generated immediately upon feed item creation. Background job generates
     * embeddings asynchronously subject to AI budget constraints per Policy P2/P10.
     *
     * @see villagecompute.homepage.services.SemanticSearchService#indexContentEmbedding(FeedItem)
     */
    @Column(
            name = "content_embedding",
            columnDefinition = "vector(1536)")
    @Type(villagecompute.homepage.util.PgVectorType.class)
    public float[] contentEmbedding;

    /**
     * Finds a feed item by its RSS GUID.
     *
     * @param guid
     *            the RSS item GUID
     * @return Optional containing the item if found
     */
    public static Optional<FeedItem> findByGuid(String guid) {
        if (guid == null || guid.isBlank()) {
            return Optional.empty();
        }
        return find("#" + QUERY_FIND_BY_GUID, Parameters.with("guid", guid)).firstResultOptional();
    }

    /**
     * Finds feed items by source ID, ordered by published date descending.
     *
     * @param sourceId
     *            the RSS source UUID
     * @return List of feed items from the specified source
     */
    public static List<FeedItem> findBySource(UUID sourceId) {
        if (sourceId == null) {
            return List.of();
        }
        return find("#" + QUERY_FIND_BY_SOURCE, Parameters.with("sourceId", sourceId)).list();
    }

    /**
     * Finds recent feed items across all sources, ordered by published date descending.
     *
     * @param limit
     *            maximum number of items to return
     * @return List of recent feed items
     */
    public static List<FeedItem> findRecent(int limit) {
        return find("#" + QUERY_FIND_RECENT).page(0, limit).list();
    }

    /**
     * Finds recent feed items with pagination.
     *
     * @param offset
     *            offset (0-indexed)
     * @param limit
     *            page size
     * @return List of recent feed items
     */
    public static List<FeedItem> findRecent(int offset, int limit) {
        return find("#" + QUERY_FIND_RECENT).page(offset / limit, limit).list();
    }

    /**
     * Finds feed items not yet tagged by AI (for BULK queue job picker).
     *
     * @return List of untagged feed items
     */
    public static List<FeedItem> findUntagged() {
        return find("#" + QUERY_FIND_UNTAGGED).list();
    }

    /**
     * Finds feed items by content hash (for similarity detection).
     *
     * @param contentHash
     *            the MD5 content hash
     * @return List of items with matching content hash
     */
    public static List<FeedItem> findByContentHash(String contentHash) {
        if (contentHash == null || contentHash.isBlank()) {
            return List.of();
        }
        return find("#" + QUERY_FIND_BY_CONTENT_HASH, Parameters.with("contentHash", contentHash)).list();
    }

    /**
     * Creates a new feed item with audit timestamp.
     *
     * @param item
     *            the item to persist
     * @return the persisted item with generated ID
     */
    public static FeedItem create(FeedItem item) {
        QuarkusTransaction.requiringNew().run(() -> {
            item.fetchedAt = Instant.now();
            item.persist();
            LOG.debugf("Created feed item: id=%s, title=%s, source_id=%s", item.id, item.title, item.sourceId);
        });
        return item;
    }

    /**
     * Updates AI tags for a feed item and marks as tagged.
     *
     * @param item
     *            the item to update
     * @param tags
     *            the AI-generated tags
     */
    public static void updateAiTags(FeedItem item, AiTagsType tags) {
        QuarkusTransaction.requiringNew().run(() -> {
            item.aiTags = tags;
            item.aiTagged = true;
            item.persist();
            LOG.debugf("Updated AI tags for feed item: id=%s, topics=%s", item.id,
                    tags.topics() != null ? tags.topics().size() : 0);
        });
    }
}
