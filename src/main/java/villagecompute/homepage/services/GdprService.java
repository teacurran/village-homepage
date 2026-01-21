package villagecompute.homepage.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.SignedUrlType;
import villagecompute.homepage.api.types.StorageUploadResultType;
import villagecompute.homepage.data.models.AccountMergeAudit;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.exceptions.ResourceNotFoundException;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Service for GDPR-compliant data export and deletion operations per Policy P1.
 *
 * <p>
 * Implements:
 * <ul>
 * <li><b>GDPR Article 15</b> (Right to access): Aggregates all user data across 20+ tables, serializes to JSON, uploads
 * to R2 with 7-day signed URL</li>
 * <li><b>GDPR Article 17</b> (Right to erasure): Cascades deletion to all related entities and R2 objects in atomic
 * transaction</li>
 * </ul>
 *
 * <p>
 * <b>Data Coverage:</b>
 * <ul>
 * <li>Core: User, UserProfile, ProfileCuratedArticle</li>
 * <li>Social: SocialToken, SocialPost</li>
 * <li>Marketplace: MarketplaceListing, MarketplaceListingImage, MarketplaceMessage, ListingPromotion, ListingFlag,
 * PaymentRefund</li>
 * <li>Good Sites: DirectorySite, DirectoryVote, KarmaAudit</li>
 * <li>Analytics: LinkClick, FeatureFlagEvaluation (partitioned, 90-day retention)</li>
 * <li>System: AccountMergeAudit, RateLimitViolation, ImpersonationAudit</li>
 * </ul>
 *
 * <p>
 * <b>Policy P1 Retention:</b> Export ZIPs have 7-day signed URL expiry. Deletion is permanent and immediate (no soft
 * delete).
 *
 * @see villagecompute.homepage.jobs.GdprExportJobHandler
 * @see villagecompute.homepage.jobs.GdprDeletionJobHandler
 */
@ApplicationScoped
public class GdprService {

    private static final Logger LOG = Logger.getLogger(GdprService.class);

    @Inject
    StorageGateway storageGateway;

    @Inject
    Tracer tracer;

    @Inject
    EntityManager entityManager;

    @ConfigProperty(name = "villagecompute.gdpr.export-ttl-days", defaultValue = "7")
    int exportTtlDays;

    /**
     * Exports all user data to JSON, packages as ZIP, uploads to R2, and returns signed URL.
     *
     * <p>
     * <b>Export Format:</b> ZIP file containing user_data.json with nested structure:
     *
     * <pre>
     * {
     *   "user": { ... },
     *   "profile": { ... },
     *   "curated_articles": [ ... ],
     *   "social_tokens": [ ... ],  // OAuth tokens (encrypted)
     *   "social_posts": [ ... ],
     *   "marketplace_listings": [ ... ],
     *   "marketplace_messages": [ ... ],
     *   "directory_sites": [ ... ],
     *   "directory_votes": [ ... ],
     *   "karma_audit": [ ... ],
     *   "account_merge_audit": [ ... ],
     *   "analytics": {
     *     "link_clicks": [ ... ],  // Only if analytics_consent = true
     *     "feature_flag_evaluations": [ ... ]  // Only if analytics_consent = true
     *   }
     * }
     * </pre>
     *
     * @param userId the user's ID
     * @return signed URL valid for {@code export-ttl-days} days
     * @throws Exception if export fails
     */
    public String exportUserData(UUID userId) throws Exception {
        Span span = tracer.spanBuilder("gdpr.export").setAttribute("user_id", userId.toString()).startSpan();

        try (Scope scope = span.makeCurrent()) {
            LOG.infof("Starting GDPR export for user %s", userId);

            User user = User.findById(userId);
            if (user == null) {
                throw new ResourceNotFoundException("User not found: " + userId);
            }

            // 1. Aggregate all user data
            Map<String, Object> exportData = new HashMap<>();
            exportData.put("export_timestamp", Instant.now().toString());
            exportData.put("export_policy_version", "1.0");
            exportData.put("user", serializeUser(user));

            // Profile data
            exportData.put("profile", queryOptional("SELECT p FROM UserProfile p WHERE p.userId = :userId",
                    Map.of("userId", userId)));
            exportData.put("curated_articles",
                    queryList("SELECT ca FROM ProfileCuratedArticle ca WHERE ca.profileId IN "
                            + "(SELECT p.id FROM UserProfile p WHERE p.userId = :userId)", Map.of("userId", userId)));

            // Social data
            exportData.put("social_tokens",
                    queryList("SELECT st FROM SocialToken st WHERE st.userId = :userId", Map.of("userId", userId)));
            exportData.put("social_posts",
                    queryList("SELECT sp FROM SocialPost sp WHERE sp.userId = :userId", Map.of("userId", userId)));

            // Marketplace data
            exportData.put("marketplace_listings", queryList(
                    "SELECT ml FROM MarketplaceListing ml WHERE ml.sellerId = :userId", Map.of("userId", userId)));
            exportData.put("marketplace_messages",
                    queryList("SELECT mm FROM MarketplaceMessage mm WHERE mm.sellerId = :userId OR mm.buyerId = :userId",
                            Map.of("userId", userId)));
            exportData.put("listing_promotions",
                    queryList("SELECT lp FROM ListingPromotion lp WHERE lp.listingId IN "
                            + "(SELECT ml.id FROM MarketplaceListing ml WHERE ml.sellerId = :userId)",
                            Map.of("userId", userId)));
            exportData.put("listing_flags",
                    queryList("SELECT lf FROM ListingFlag lf WHERE lf.flaggedBy = :userId", Map.of("userId", userId)));
            exportData.put("payment_refunds",
                    queryList("SELECT pr FROM PaymentRefund pr WHERE pr.userId = :userId", Map.of("userId", userId)));

            // Good Sites data
            exportData.put("directory_sites",
                    queryList("SELECT ds FROM DirectorySite ds WHERE ds.submittedBy = :userId",
                            Map.of("userId", userId)));
            exportData.put("directory_votes",
                    queryList("SELECT dv FROM DirectoryVote dv WHERE dv.userId = :userId", Map.of("userId", userId)));
            exportData.put("karma_audit",
                    queryList("SELECT ka FROM KarmaAudit ka WHERE ka.userId = :userId", Map.of("userId", userId)));

            // Audit trails
            exportData.put("account_merge_audit", AccountMergeAudit.findByAuthenticatedUser(userId).stream()
                    .map(AccountMergeAudit::toSnapshot).toList());
            exportData.put("rate_limit_violations", queryList(
                    "SELECT rv FROM RateLimitViolation rv WHERE rv.userId = :userId", Map.of("userId", userId)));
            exportData.put("impersonation_audit",
                    queryList("SELECT ia FROM ImpersonationAudit ia WHERE ia.adminId = :userId OR ia.targetUserId = :userId",
                            Map.of("userId", userId)));

            // Analytics data (only if consent given, per Policy P14)
            if (user.analyticsConsent) {
                Map<String, Object> analytics = new HashMap<>();
                analytics.put("link_clicks",
                        queryList("SELECT lc FROM LinkClick lc WHERE lc.userId = :userId", Map.of("userId", userId)));
                analytics.put("feature_flag_evaluations",
                        queryList("SELECT ffe FROM FeatureFlagEvaluation ffe WHERE ffe.userId = :userId",
                                Map.of("userId", userId)));
                exportData.put("analytics", analytics);
            } else {
                exportData.put("analytics", Map.of("note", "Analytics data excluded (consent not given)"));
            }

            span.addEvent("data.aggregated",
                    Attributes.of(AttributeKey.longKey("entity_count"), (long) exportData.size()));

            // 2. Serialize to JSON
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(exportData);

            span.addEvent("json.serialized", Attributes.of(AttributeKey.longKey("json_bytes"), (long) json.length()));

            // 3. Create ZIP file
            byte[] zipBytes = createZip("user_data.json", json.getBytes());

            span.addEvent("zip.created", Attributes.of(AttributeKey.longKey("zip_bytes"), (long) zipBytes.length));

            // 4. Upload to R2
            String filename = String.format("gdpr-export-%s-%d.zip", userId, System.currentTimeMillis());
            StorageUploadResultType result = storageGateway.upload(StorageGateway.BucketType.GDPR_EXPORTS, "exports/",
                    filename, zipBytes, "application/zip");

            span.addEvent("r2.uploaded", Attributes.of(AttributeKey.stringKey("object_key"), result.objectKey()));

            // 5. Generate signed URL (7 days)
            int ttlMinutes = exportTtlDays * 24 * 60;
            SignedUrlType signedUrl = storageGateway.generateSignedUrl(StorageGateway.BucketType.GDPR_EXPORTS,
                    result.objectKey(), ttlMinutes);

            span.addEvent("signed_url.generated",
                    Attributes.of(AttributeKey.stringKey("expires_at"), signedUrl.expiresAt().toString()));

            LOG.infof("GDPR export completed for user %s: %s (expires %s)", userId, signedUrl.url(),
                    signedUrl.expiresAt());
            return signedUrl.url();

        } finally {
            span.end();
        }
    }

    /**
     * Deletes all user data with cascading to related tables and R2 objects.
     *
     * <p>
     * <b>CRITICAL:</b> This method is wrapped in a transaction via {@link QuarkusTransaction#requiringNew()} to ensure
     * atomicity. All deletes succeed together or roll back together.
     *
     * <p>
     * <b>Deletion Order:</b> Child entities first (foreign key constraints), then R2 objects, finally user record.
     *
     * @param userId the user's ID
     * @throws Exception if deletion fails
     */
    public void deleteUserData(UUID userId) throws Exception {
        Span span = tracer.spanBuilder("gdpr.deletion").setAttribute("user_id", userId.toString()).startSpan();

        try (Scope scope = span.makeCurrent()) {
            LOG.infof("Starting GDPR deletion for user %s", userId);

            QuarkusTransaction.requiringNew().run(() -> {
                User user = User.findById(userId);
                if (user == null) {
                    throw new ResourceNotFoundException("User not found: " + userId);
                }

                // 1. Delete marketplace images from R2
                deleteMarketplaceImages(userId, span);

                // 2. Cascade delete database records (order matters for FK constraints)
                // NOTE: Using native queries to bypass entity mappings and ensure clean deletes
                int totalDeleted = 0;

                // Audit trails (no FK constraints, can delete first)
                totalDeleted += deleteEntity("AccountMergeAudit",
                        "anonymous_user_id = :userId OR authenticated_user_id = :userId", userId);
                totalDeleted += deleteEntity("ImpersonationAudit", "admin_id = :userId OR target_user_id = :userId",
                        userId);
                totalDeleted += deleteEntity("RateLimitViolation", "user_id = :userId", userId);
                totalDeleted += deleteEntity("KarmaAudit", "user_id = :userId", userId);

                // Analytics (partitioned tables)
                totalDeleted += deleteEntity("LinkClick", "user_id = :userId", userId);
                totalDeleted += deleteEntity("FeatureFlagEvaluation", "user_id = :userId", userId);

                // Good Sites
                totalDeleted += deleteEntity("DirectoryVote", "user_id = :userId", userId);
                totalDeleted += deleteEntity("DirectorySite", "submitted_by = :userId", userId);

                // Social
                totalDeleted += deleteEntity("SocialPost", "user_id = :userId", userId);
                totalDeleted += deleteEntity("SocialToken", "user_id = :userId", userId);

                // Marketplace (child → parent order)
                totalDeleted += deleteEntity("MarketplaceMessage", "seller_id = :userId OR buyer_id = :userId", userId);
                totalDeleted += deleteEntity("ListingFlag", "flagged_by = :userId", userId);
                totalDeleted += deleteEntity("PaymentRefund", "user_id = :userId", userId);

                // Listings and related (must delete related entities first)
                String listingIdsQuery = "SELECT id FROM marketplace_listings WHERE seller_id = :userId";
                totalDeleted += entityManager.createNativeQuery(
                        "DELETE FROM listing_promotions WHERE listing_id IN (" + listingIdsQuery + ")")
                        .setParameter("userId", userId).executeUpdate();
                totalDeleted += entityManager
                        .createNativeQuery(
                                "DELETE FROM marketplace_listing_images WHERE listing_id IN (" + listingIdsQuery + ")")
                        .setParameter("userId", userId).executeUpdate();
                totalDeleted += deleteEntity("MarketplaceListing", "seller_id = :userId", userId);

                // Profiles and related
                String profileIdsQuery = "SELECT id FROM user_profiles WHERE user_id = :userId";
                totalDeleted += entityManager.createNativeQuery(
                        "DELETE FROM profile_curated_articles WHERE profile_id IN (" + profileIdsQuery + ")")
                        .setParameter("userId", userId).executeUpdate();
                totalDeleted += deleteEntity("UserProfile", "user_id = :userId", userId);

                // GDPR requests (audit of this very request)
                totalDeleted += deleteEntity("GdprRequest", "user_id = :userId", userId);

                // 3. Finally delete user record
                user.delete();
                totalDeleted++;

                span.addEvent("deletion.completed",
                        Attributes.of(AttributeKey.longKey("total_records_deleted"), (long) totalDeleted));

                LOG.infof("GDPR deletion completed for user %s (%d records deleted)", userId, totalDeleted);
            });

        } finally {
            span.end();
        }
    }

    /**
     * Deletes all marketplace listing images from R2 for a user.
     */
    private void deleteMarketplaceImages(UUID userId, Span parentSpan) {
        Span span = tracer.spanBuilder("gdpr.delete_marketplace_images").setAttribute("user_id", userId.toString())
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            List<Object[]> imageKeys = entityManager.createNativeQuery(
                    "SELECT thumbnail_key, list_view_key, full_size_key FROM marketplace_listing_images "
                            + "WHERE listing_id IN (SELECT id FROM marketplace_listings WHERE seller_id = :userId)")
                    .setParameter("userId", userId).getResultList();

            int deletedCount = 0;
            for (Object[] keys : imageKeys) {
                String thumbnailKey = (String) keys[0];
                String listViewKey = (String) keys[1];
                String fullSizeKey = (String) keys[2];

                if (thumbnailKey != null) {
                    storageGateway.delete(StorageGateway.BucketType.LISTINGS, thumbnailKey);
                    deletedCount++;
                }
                if (listViewKey != null) {
                    storageGateway.delete(StorageGateway.BucketType.LISTINGS, listViewKey);
                    deletedCount++;
                }
                if (fullSizeKey != null) {
                    storageGateway.delete(StorageGateway.BucketType.LISTINGS, fullSizeKey);
                    deletedCount++;
                }
            }

            span.addEvent("r2.images_deleted", Attributes.of(AttributeKey.longKey("count"), (long) deletedCount));
            LOG.infof("Deleted %d marketplace images from R2 for user %s", deletedCount, userId);

        } finally {
            span.end();
        }
    }

    /**
     * Helper to delete entity records using native query.
     */
    private int deleteEntity(String entityName, String whereClause, UUID userId) {
        String tableName = toSnakeCase(entityName);
        String sql = String.format("DELETE FROM %s WHERE %s", tableName, whereClause);
        int deleted = entityManager.createNativeQuery(sql).setParameter("userId", userId).executeUpdate();
        if (deleted > 0) {
            LOG.debugf("Deleted %d records from %s for user %s", deleted, tableName, userId);
        }
        return deleted;
    }

    /**
     * Converts PascalCase entity name to snake_case table name.
     */
    private String toSnakeCase(String input) {
        return input.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    /**
     * Creates a ZIP file containing a single file.
     */
    private byte[] createZip(String filename, byte[] content) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            ZipEntry entry = new ZipEntry(filename);
            zos.putNextEntry(entry);
            zos.write(content);
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    /**
     * Serializes a User entity to a map (excluding sensitive internal fields).
     */
    private Map<String, Object> serializeUser(User user) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", user.id.toString());
        data.put("email", user.email);
        data.put("oauth_provider", user.oauthProvider);
        data.put("display_name", user.displayName);
        data.put("avatar_url", user.avatarUrl);
        data.put("preferences", user.preferences);
        data.put("is_anonymous", user.isAnonymous);
        data.put("directory_karma", user.directoryKarma);
        data.put("directory_trust_level", user.directoryTrustLevel);
        data.put("admin_role", user.adminRole);
        data.put("analytics_consent", user.analyticsConsent);
        data.put("is_banned", user.isBanned);
        data.put("banned_at", user.bannedAt);
        data.put("ban_reason", user.banReason);
        data.put("created_at", user.createdAt);
        data.put("updated_at", user.updatedAt);
        return data;
    }

    /**
     * Executes a JPA query and returns the first result as an Optional.
     */
    private Object queryOptional(String jpql, Map<String, Object> params) {
        var query = entityManager.createQuery(jpql);
        params.forEach(query::setParameter);
        try {
            return query.getSingleResult();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Executes a JPA query and returns all results as a list.
     */
    private List<?> queryList(String jpql, Map<String, Object> params) {
        var query = entityManager.createQuery(jpql);
        params.forEach(query::setParameter);
        return query.getResultList();
    }
}
