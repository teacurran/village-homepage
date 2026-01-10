package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import villagecompute.homepage.data.models.ListingFlag;
import villagecompute.homepage.data.models.MarketplaceListing;
import villagecompute.homepage.data.models.PaymentRefund;
import villagecompute.homepage.data.models.User;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Response type for moderation queue analytics dashboard.
 *
 * <p>
 * Aggregates key metrics for monitoring moderation workload, flag trends, refund automation, and ban enforcement.
 */
public record ModerationStatsType(@JsonProperty("pending_flags") long pendingFlags,

        @JsonProperty("flagged_listings") long flaggedListings,

        @JsonProperty("approved_flags_24h") long approvedFlags24h,

        @JsonProperty("dismissed_flags_24h") long dismissedFlags24h,

        @JsonProperty("auto_refunds_issued_24h") long autoRefundsIssued24h,

        @JsonProperty("banned_users_24h") long bannedUsers24h,

        @JsonProperty("flag_reason_counts") Map<String, Long> flagReasonCounts,

        @JsonProperty("average_review_time_hours") Double averageReviewTimeHours) {
    /**
     * Computes moderation statistics from current database state.
     *
     * @return aggregated moderation stats
     */
    public static ModerationStatsType compute() {
        long pending = ListingFlag.countByStatus("pending");
        long flagged = MarketplaceListing.count("status = 'flagged'");

        Instant yesterday = Instant.now().minus(Duration.ofHours(24));
        long approved = ListingFlag.count("status = 'approved' AND reviewedAt > ?1", yesterday);
        long dismissed = ListingFlag.count("status = 'dismissed' AND reviewedAt > ?1", yesterday);

        long refunds = PaymentRefund.count("reason = 'moderation_rejection' AND createdAt > ?1", yesterday);
        long banned = User.count("isBanned = true AND bannedAt > ?1", yesterday);

        Map<String, Long> reasonCounts = computeReasonCounts();
        Double avgReviewTime = computeAverageReviewTime();

        return new ModerationStatsType(pending, flagged, approved, dismissed, refunds, banned, reasonCounts,
                avgReviewTime);
    }

    /**
     * Computes count of pending flags by reason.
     *
     * @return map of reason to count
     */
    private static Map<String, Long> computeReasonCounts() {
        Map<String, Long> counts = new HashMap<>();
        List<Object[]> results = ListingFlag
                .find("SELECT reason, COUNT(*) FROM ListingFlag WHERE status = 'pending' GROUP BY reason")
                .project(Object[].class).list();

        for (Object[] row : results) {
            counts.put((String) row[0], (Long) row[1]);
        }

        return counts;
    }

    /**
     * Computes average time from flag creation to review for flags reviewed in last 7 days.
     *
     * @return average review time in hours, or null if no data
     */
    private static Double computeAverageReviewTime() {
        Instant sevenDaysAgo = Instant.now().minus(Duration.ofDays(7));
        List<ListingFlag> reviewed = ListingFlag.list("reviewedAt > ?1 AND reviewedAt IS NOT NULL", sevenDaysAgo);

        if (reviewed.isEmpty()) {
            return null;
        }

        long totalSeconds = 0;
        for (ListingFlag flag : reviewed) {
            totalSeconds += Duration.between(flag.createdAt, flag.reviewedAt).getSeconds();
        }

        double avgSeconds = (double) totalSeconds / reviewed.size();
        return avgSeconds / 3600.0; // Convert to hours
    }
}
