package villagecompute.homepage.data.models;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Marketplace message entity for email relay tracking (Features F12.6, F14.3).
 *
 * <p>
 * Stores buyer-seller communication via masked email relay. Messages are relayed through the platform to protect
 * privacy - buyers and sellers never see each other's real email addresses.
 *
 * <p>
 * <b>Email Relay Flow:</b>
 * <ol>
 * <li>Buyer sends inquiry to listing's masked email (listing-{uuid}@villagecompute.com)</li>
 * <li>MessageRelayJobHandler relays message to seller's real email with Reply-To: reply-{messageId}@villagecompute.com</li>
 * <li>Seller replies via their email client</li>
 * <li>InboundEmailProcessor polls IMAP inbox, extracts messageId from Reply-To address</li>
 * <li>System relays seller's reply to buyer's real email</li>
 * <li>All messages stored in this table for audit trail and threading</li>
 * </ol>
 *
 * <p>
 * <b>Schema Mapping:</b>
 * <ul>
 * <li>{@code id} (UUID, PK) - Primary identifier</li>
 * <li>{@code listing_id} (UUID, FK) - Reference to marketplace_listings (CASCADE delete per P1)</li>
 * <li>{@code message_id} (TEXT, UNIQUE) - Email Message-ID header (e.g., msg-abc123@villagecompute.com)</li>
 * <li>{@code in_reply_to} (TEXT) - Parent message_id for threading (nullable for initial inquiries)</li>
 * <li>{@code thread_id} (UUID) - Groups related messages in same conversation</li>
 * <li>{@code from_email} (TEXT) - Sender's real email address</li>
 * <li>{@code from_name} (TEXT) - Sender's display name (nullable)</li>
 * <li>{@code to_email} (TEXT) - Recipient's real email address</li>
 * <li>{@code to_name} (TEXT) - Recipient's display name (nullable)</li>
 * <li>{@code subject} (TEXT) - Email subject line</li>
 * <li>{@code body} (TEXT) - Email body content (plain text)</li>
 * <li>{@code direction} (TEXT) - Message flow direction (buyer_to_seller, seller_to_buyer)</li>
 * <li>{@code sent_at} (TIMESTAMPTZ) - When email was successfully relayed (nullable if send failed)</li>
 * <li>{@code created_at} (TIMESTAMPTZ) - Record creation timestamp</li>
 * <li>{@code is_spam} (BOOLEAN) - Spam detection flag (future AI integration)</li>
 * <li>{@code spam_score} (DECIMAL) - AI spam probability 0.00-1.00 (nullable)</li>
 * <li>{@code flagged_for_review} (BOOLEAN) - Manual moderation flag</li>
 * </ul>
 *
 * <p>
 * <b>Message Threading:</b> Messages are threaded using standard email headers:
 * <ul>
 * <li>Message-ID: &lt;msg-{uuid}@villagecompute.com&gt;</li>
 * <li>In-Reply-To: &lt;parent-message-id&gt; (for replies)</li>
 * <li>References: &lt;original-message-id&gt; &lt;intermediate-ids&gt; (full thread chain)</li>
 * </ul>
 *
 * <p>
 * <b>Database Access Pattern:</b> All queries via static methods (Panache ActiveRecord). No separate repository class.
 *
 * <p>
 * <b>GDPR Compliance (P1):</b> Messages CASCADE delete when listing is deleted. Users can request export of their
 * message history via privacy export endpoint. Messages retained for 90 days after listing expiration per retention
 * policy.
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>F12.6: Email masking for contact relay</li>
 * <li>F14.3: IMAP polling for marketplace reply relay</li>
 * <li>P1: GDPR compliance - CASCADE delete on listing deletion</li>
 * <li>P6: Privacy via masked email relay</li>
 * <li>P14: Soft-delete lifecycle and 90-day retention</li>
 * </ul>
 *
 * @see MarketplaceListing for listing entity
 * @see villagecompute.homepage.services.MessageRelayService for relay logic
 * @see villagecompute.homepage.jobs.MessageRelayJobHandler for outbound relay
 * @see villagecompute.homepage.jobs.InboundEmailProcessor for inbound IMAP processing
 */
@Entity
@Table(
        name = "marketplace_messages")
public class MarketplaceMessage extends PanacheEntityBase {

    private static final Logger LOG = Logger.getLogger(MarketplaceMessage.class);

    public static final String QUERY_FIND_BY_LISTING_ID = "MarketplaceMessage.findByListingId";
    public static final String QUERY_FIND_BY_THREAD_ID = "MarketplaceMessage.findByThreadId";
    public static final String QUERY_FIND_BY_MESSAGE_ID = "MarketplaceMessage.findByMessageId";

    @Id
    @GeneratedValue
    @Column(
            nullable = false)
    public UUID id;

    @Column(
            name = "listing_id",
            nullable = false)
    public UUID listingId;

    @Column(
            name = "message_id",
            nullable = false,
            unique = true)
    public String messageId;

    @Column(
            name = "in_reply_to")
    public String inReplyTo;

    @Column(
            name = "thread_id")
    public UUID threadId;

    @Column(
            name = "from_email",
            nullable = false)
    public String fromEmail;

    @Column(
            name = "from_name")
    public String fromName;

    @Column(
            name = "to_email",
            nullable = false)
    public String toEmail;

    @Column(
            name = "to_name")
    public String toName;

    @Column(
            nullable = false)
    public String subject;

    @Column(
            nullable = false,
            columnDefinition = "TEXT")
    public String body;

    @Column(
            nullable = false)
    public String direction;  // buyer_to_seller, seller_to_buyer

    @Column(
            name = "sent_at")
    public Instant sentAt;

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt;

    @Column(
            name = "is_spam")
    public boolean isSpam;

    @Column(
            name = "spam_score")
    public BigDecimal spamScore;

    @Column(
            name = "flagged_for_review")
    public boolean flaggedForReview;

    /**
     * Finds all messages for a specific listing, ordered by creation date ascending (oldest first).
     *
     * <p>
     * Used for displaying message history in admin views and user "My Listings" pages. Returns messages in chronological
     * order to show conversation flow.
     *
     * @param listingId
     *            the listing UUID
     * @return List of messages ordered by creation date (oldest first)
     */
    public static List<MarketplaceMessage> findByListingId(UUID listingId) {
        if (listingId == null) {
            return List.of();
        }
        return find("listingId = ?1 ORDER BY createdAt ASC", listingId).list();
    }

    /**
     * Finds all messages in a specific thread, ordered by creation date ascending (oldest first).
     *
     * <p>
     * Used for displaying threaded conversation views in email clients and web UI. Returns messages in chronological
     * order to show conversation flow.
     *
     * @param threadId
     *            the thread UUID
     * @return List of messages in thread ordered by creation date (oldest first)
     */
    public static List<MarketplaceMessage> findByThreadId(UUID threadId) {
        if (threadId == null) {
            return List.of();
        }
        return find("threadId = ?1 ORDER BY createdAt ASC", threadId).list();
    }

    /**
     * Finds a message by its email Message-ID header.
     *
     * <p>
     * Used by InboundEmailProcessor to lookup original messages when processing replies. The messageId format is
     * {@code msg-{uuid}@villagecompute.com}.
     *
     * @param messageId
     *            the email Message-ID (e.g., "msg-abc123@villagecompute.com")
     * @return Optional containing message if found, empty otherwise
     */
    public static Optional<MarketplaceMessage> findByMessageId(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return Optional.empty();
        }
        return find("messageId = ?1", messageId).firstResultOptional();
    }

    /**
     * Finds messages flagged for moderation review.
     *
     * <p>
     * Used by admin moderation dashboard to review potentially abusive or spam messages. Returns messages ordered by
     * creation date descending (newest first).
     *
     * @return List of flagged messages ordered by creation date (newest first)
     */
    public static List<MarketplaceMessage> findFlaggedForReview() {
        return find("flaggedForReview = true ORDER BY createdAt DESC").list();
    }

    /**
     * Finds messages marked as spam.
     *
     * <p>
     * Used by spam analysis and admin review tools. Returns messages ordered by spam score descending (highest scores
     * first).
     *
     * @return List of spam messages ordered by spam score (highest first)
     */
    public static List<MarketplaceMessage> findSpam() {
        return find("isSpam = true ORDER BY spamScore DESC NULLS LAST, createdAt DESC").list();
    }

    /**
     * Creates a new marketplace message with audit timestamp.
     *
     * <p>
     * Sets createdAt to current time. The sentAt timestamp should be set by the caller after successful email relay.
     *
     * @param message
     *            the message to persist
     * @return the persisted message with generated ID
     */
    public static MarketplaceMessage create(MarketplaceMessage message) {
        QuarkusTransaction.requiringNew().run(() -> {
            message.createdAt = Instant.now();

            message.persist();
            LOG.infof(
                    "Created marketplace message: id=%s, listingId=%s, messageId=%s, direction=%s, threadId=%s",
                    message.id, message.listingId, message.messageId, message.direction, message.threadId);
        });
        return message;
    }

    /**
     * Updates the sent timestamp after successful email relay.
     *
     * <p>
     * Called by MessageRelayJobHandler after successfully sending email via Mailer. Marks the message as successfully
     * relayed.
     *
     * @param messageId
     *            the message UUID
     * @param sentAt
     *            the timestamp when email was sent
     */
    public static void markSent(UUID messageId, Instant sentAt) {
        QuarkusTransaction.requiringNew().run(() -> {
            MarketplaceMessage message = findById(messageId);
            if (message == null) {
                LOG.warnf("Attempted to mark sent for non-existent message: %s", messageId);
                return;
            }

            message.sentAt = sentAt;
            message.persist();

            LOG.debugf("Marked message as sent: id=%s, sentAt=%s", message.id, message.sentAt);
        });
    }

    /**
     * Flags a message for moderation review.
     *
     * <p>
     * Used by spam detection systems and user reporting to mark messages for manual review. Admin moderators can then
     * review flagged messages via admin dashboard.
     *
     * @param messageId
     *            the message UUID
     */
    public static void flagForReview(UUID messageId) {
        QuarkusTransaction.requiringNew().run(() -> {
            MarketplaceMessage message = findById(messageId);
            if (message == null) {
                LOG.warnf("Attempted to flag non-existent message: %s", messageId);
                return;
            }

            message.flaggedForReview = true;
            message.persist();

            LOG.infof("Flagged message for review: id=%s, listingId=%s, direction=%s", message.id,
                    message.listingId, message.direction);
        });
    }

    /**
     * Marks a message as spam and optionally sets spam score.
     *
     * <p>
     * Used by AI-based spam detection (future feature). Spam messages are excluded from normal listing views and
     * quarantined for review.
     *
     * @param messageId
     *            the message UUID
     * @param spamScore
     *            AI spam probability 0.00-1.00 (nullable)
     */
    public static void markSpam(UUID messageId, BigDecimal spamScore) {
        QuarkusTransaction.requiringNew().run(() -> {
            MarketplaceMessage message = findById(messageId);
            if (message == null) {
                LOG.warnf("Attempted to mark spam for non-existent message: %s", messageId);
                return;
            }

            message.isSpam = true;
            message.spamScore = spamScore;
            message.flaggedForReview = true;  // Auto-flag spam for review
            message.persist();

            LOG.warnf("Marked message as spam: id=%s, spamScore=%s, listingId=%s", message.id,
                    message.spamScore, message.listingId);
        });
    }

    /**
     * Counts total messages for a listing.
     *
     * <p>
     * Used for display in listing detail views ("X messages" counter).
     *
     * @param listingId
     *            the listing UUID
     * @return message count
     */
    public static long countByListingId(UUID listingId) {
        if (listingId == null) {
            return 0;
        }
        return count("listingId = ?1", listingId);
    }
}
