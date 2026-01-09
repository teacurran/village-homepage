package villagecompute.homepage.jobs;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.ListingPromotion;

import java.util.List;
import java.util.Map;

/**
 * Job handler for expiring featured listing promotions.
 *
 * <p>
 * Runs daily to identify and delete expired featured promotions (expires_at <= NOW()). Bump promotions do not expire
 * and are not processed by this handler.
 *
 * <p>
 * <b>Execution Cadence:</b> Daily at 3am UTC (configured in scheduler)
 *
 * <p>
 * <b>Queue:</b> DEFAULT
 *
 * <p>
 * <b>Business Logic:</b>
 * <ol>
 * <li>Query listing_promotions WHERE type='featured' AND expires_at <= NOW()</li>
 * <li>Delete expired promotion records (hard delete, not soft-delete)</li>
 * <li>Log count of expired promotions</li>
 * </ol>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>F12.8: Listing fees & monetization (featured promotion duration: 7 days)</li>
 * </ul>
 */
@ApplicationScoped
public class PromotionExpirationJobHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(PromotionExpirationJobHandler.class);

    @Override
    public JobType handlesType() {
        return JobType.PROMOTION_EXPIRATION;
    }

    @Override
    public void execute(Long jobId, Map<String, Object> payload) throws Exception {
        LOG.infof("Starting promotion expiration job: jobId=%d", jobId);

        try {
            // Find expired featured promotions
            List<ListingPromotion> expiredPromotions = ListingPromotion.findExpiredFeatured();
            int count = expiredPromotions.size();

            LOG.infof("Found %d expired featured promotions", count);

            // Delete expired promotions
            for (ListingPromotion promotion : expiredPromotions) {
                ListingPromotion.deleteExpired(promotion.id);
            }

            LOG.infof("Promotion expiration job completed successfully, jobId=%d, expired=%d", jobId, count);

        } catch (Exception e) {
            LOG.errorf(e, "Promotion expiration job failed: jobId=%d", jobId);
            throw new RuntimeException("Promotion expiration job failed", e);
        }
    }
}
