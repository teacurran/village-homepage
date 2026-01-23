package villagecompute.homepage.jobs;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.OAuthState;

/**
 * Scheduled job to clean up expired OAuth state tokens.
 *
 * <p>
 * OAuth state tokens expire after 5 minutes, but remain in the database until explicitly deleted. This job runs every
 * hour to delete expired tokens and prevent database bloat.
 *
 * <p>
 * Benefits:
 * <ul>
 * <li>Keeps oauth_states table small (improves query performance)
 * <li>Prevents index bloat on state column
 * <li>Reduces storage footprint
 * </ul>
 *
 * <p>
 * Note: This is a safety net. Normal OAuth flows already delete tokens immediately after validation (single-use
 * pattern). This job only cleans up tokens from abandoned login flows (e.g., user closed browser before completing
 * OAuth callback).
 */
@ApplicationScoped
public class OAuthStateCleanupScheduler {

    private static final Logger LOG = Logger.getLogger(OAuthStateCleanupScheduler.class);

    /**
     * Delete expired OAuth state tokens.
     *
     * <p>
     * Runs every hour (at minute 0). Deletes all tokens with expiresAt < current timestamp.
     */
    @Scheduled(
            cron = "0 0 * * * ?")
    @Transactional
    public void cleanupExpiredStates() {
        long deleted = OAuthState.delete("expiresAt < ?1", Instant.now());

        if (deleted > 0) {
            LOG.infof("SECURITY: Deleted %d expired OAuth state tokens", deleted);
        }
    }
}
