package villagecompute.homepage.jobs;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.EmailDeliveryLog;
import villagecompute.homepage.services.EmailService;

import java.time.Instant;
import java.util.List;

/**
 * Background job for processing queued email deliveries (Feature I5.T3).
 *
 * <p>
 * Processes {@link EmailDeliveryLog} records with status = QUEUED every 1 minute. Sends emails via {@link EmailService}
 * and updates delivery status (SENT/FAILED) with retry tracking.
 *
 * <h3>Job Schedule:</h3>
 * <ul>
 * <li>Interval: Every 1 minute (Quarkus @Scheduled)</li>
 * <li>Batch size: 50 emails per run</li>
 * <li>Query: SELECT * FROM email_delivery_logs WHERE status = 'QUEUED' ORDER BY created_at ASC LIMIT 50</li>
 * </ul>
 *
 * <h3>Processing Flow:</h3>
 * <ol>
 * <li>Query queued emails (oldest first, FIFO ordering)</li>
 * <li>For each email:
 * <ul>
 * <li>Update status to SENDING (prevent duplicate processing)</li>
 * <li>Call EmailService.sendEmail() with HTML and text bodies</li>
 * <li>On success: Update status to SENT, set sentAt timestamp</li>
 * <li>On failure: Increment retryCount, update status to QUEUED (if retries < 3) or FAILED (if retries >= 3)</li>
 * </ul>
 * </li>
 * <li>Log metrics (sent, failed, skipped counts)</li>
 * </ol>
 *
 * <h3>Retry Strategy:</h3>
 * <ul>
 * <li>Maximum retry attempts: 3</li>
 * <li>Failed emails with retryCount < 3 are returned to QUEUED status</li>
 * <li>Failed emails with retryCount >= 3 are marked FAILED (terminal state)</li>
 * <li>Exponential backoff (1s, 2s, 4s) handled by EmailService, not this job</li>
 * </ul>
 *
 * <h3>Error Handling:</h3>
 * <p>
 * All exceptions are caught and logged to prevent job from crashing. Email failures are isolated to individual messages
 * (one failure doesn't block the entire batch).
 *
 * <h3>Transaction Management:</h3>
 * <p>
 * Method is @Transactional to ensure atomic status updates. If transaction fails, email status is not updated and will
 * be retried on next job run.
 *
 * <h3>Observability:</h3>
 * <p>
 * Job logs metrics after each run: sent count, failed count, skipped count. Failed deliveries include error message in
 * EmailDeliveryLog.errorMessage for debugging.
 *
 * <h3>Policy References:</h3>
 * <ul>
 * <li>F14.3: Email Communication</li>
 * <li>I5.T3: Email notification service with async delivery</li>
 * <li>P14: Email delivery reliability and error handling</li>
 * </ul>
 *
 * @see EmailDeliveryLog for delivery log entity and named queries
 * @see EmailService for email sending with retry logic
 * @see NotificationService for email queueing via queueEmailDelivery
 */
@ApplicationScoped
public class EmailDeliveryJob {

    private static final Logger LOG = Logger.getLogger(EmailDeliveryJob.class);

    /**
     * Maximum number of emails to process per job run (batch size).
     */
    private static final int BATCH_SIZE = 50;

    /**
     * Maximum retry attempts for failed email sends.
     */
    private static final int MAX_RETRIES = 3;

    @Inject
    EmailService emailService;

    /**
     * Processes queued email deliveries every 1 minute.
     *
     * <p>
     * Queries up to {@link #BATCH_SIZE} queued emails (oldest first) and sends them via {@link EmailService}. Updates
     * delivery status based on send result:
     * <ul>
     * <li><b>Success:</b> status = SENT, sentAt = now</li>
     * <li><b>Failure (retries < 3):</b> status = QUEUED, retryCount++</li>
     * <li><b>Failure (retries >= 3):</b> status = FAILED, errorMessage set</li>
     * </ul>
     *
     * <p>
     * Job runs every 1 minute via Quarkus scheduler. If no queued emails exist, job completes immediately without
     * processing.
     *
     * <p>
     * All exceptions are caught and logged to prevent job from crashing. Email failures are isolated to individual
     * messages.
     */
    @Scheduled(
            every = "1m")
    @Transactional
    public void processQueuedEmails() {
        LOG.debug("EmailDeliveryJob: Processing queued emails");

        // Query queued emails (oldest first, FIFO)
        List<EmailDeliveryLog> queued = EmailDeliveryLog.findQueued(BATCH_SIZE);
        if (queued.isEmpty()) {
            LOG.debug("No queued emails to process");
            return;
        }

        LOG.infof("Processing %d queued emails", queued.size());
        int sent = 0, failed = 0, skipped = 0;

        for (EmailDeliveryLog log : queued) {
            try {
                // Update status to SENDING (prevent duplicate processing by concurrent jobs)
                log.status = EmailDeliveryLog.DeliveryStatus.SENDING;
                log.persist();

                // Send email via EmailService (handles retry with exponential backoff)
                emailService.sendEmail(log.emailAddress, log.subject, log.htmlBody, log.textBody);

                // Update status to SENT on success
                log.status = EmailDeliveryLog.DeliveryStatus.SENT;
                log.sentAt = Instant.now();
                log.persist();
                sent++;

                LOG.debugf("Email sent successfully: id=%s, to=%s, subject=%s", log.id, log.emailAddress, log.subject);

            } catch (Exception e) {
                // Handle failure with retry logic
                log.retryCount++;

                if (log.retryCount >= MAX_RETRIES) {
                    // Max retries exceeded, mark as terminal failure
                    log.status = EmailDeliveryLog.DeliveryStatus.FAILED;
                    log.errorMessage = e.getMessage();
                    failed++;
                    LOG.errorf(e, "Email send failed after %d retries: id=%s, to=%s, subject=%s", MAX_RETRIES, log.id,
                            log.emailAddress, log.subject);
                } else {
                    // Retry available, return to queue
                    log.status = EmailDeliveryLog.DeliveryStatus.QUEUED;
                    skipped++;
                    LOG.warnf("Email send failed (retry %d/%d): id=%s, to=%s, error=%s", log.retryCount, MAX_RETRIES,
                            log.id, log.emailAddress, e.getMessage());
                }

                log.persist();
            }
        }

        LOG.infof("Email delivery complete: sent=%d, failed=%d, skipped=%d", sent, failed, skipped);
    }
}
