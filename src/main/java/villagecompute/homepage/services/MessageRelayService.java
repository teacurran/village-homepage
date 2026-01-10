package villagecompute.homepage.services;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.MarketplaceListing;
import villagecompute.homepage.data.models.MarketplaceMessage;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Email relay service for marketplace masked communication (Features F12.6, F14.3).
 *
 * <p>
 * Provides email relay functionality to protect buyer/seller privacy via masked email addresses. All communication goes
 * through platform relay addresses instead of exposing real email addresses.
 *
 * <p>
 * <b>Relay Architecture:</b>
 * <ol>
 * <li>Buyer → Seller: Send inquiry to listing's masked email (listing-{uuid}@villagecompute.com)</li>
 * <li>System relays to seller's real email with Reply-To: reply-{messageId}@villagecompute.com</li>
 * <li>Seller → Buyer: Reply via email client to reply address</li>
 * <li>InboundEmailProcessor picks up reply via IMAP polling</li>
 * <li>System relays seller's reply to buyer's real email</li>
 * </ol>
 *
 * <p>
 * <b>Email Headers for Threading:</b>
 * <ul>
 * <li>Message-ID: &lt;msg-{uuid}@villagecompute.com&gt; - Unique identifier for message</li>
 * <li>In-Reply-To: &lt;parent-message-id&gt; - Parent message for threading (replies only)</li>
 * <li>References: &lt;original-msg-id&gt; &lt;intermediate-ids&gt; - Full thread chain</li>
 * <li>Reply-To: reply-{messageId}@villagecompute.com - Platform relay address for replies</li>
 * </ul>
 *
 * <p>
 * <b>Security & Privacy:</b>
 * <ul>
 * <li>Real email addresses never exposed to other party</li>
 * <li>All messages logged in marketplace_messages table for audit trail</li>
 * <li>Rate limiting prevents spam abuse (RateLimitService integration)</li>
 * <li>Spam detection via keyword analysis (future AI integration)</li>
 * </ul>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>F12.6: Email masking for contact relay</li>
 * <li>F14.3: IMAP polling for marketplace reply relay</li>
 * <li>P1: GDPR compliance - user controls contact info</li>
 * <li>P6: Privacy via masked email relay</li>
 * </ul>
 *
 * @see MessageRelayJobHandler for async job execution
 * @see InboundEmailProcessor for IMAP polling and reply processing
 */
@ApplicationScoped
public class MessageRelayService {

    private static final Logger LOG = Logger.getLogger(MessageRelayService.class);

    private static final String RELAY_DOMAIN = "villagecompute.com";
    private static final String PLATFORM_NAME = "Village Homepage";

    @Inject
    Mailer mailer;

    @Inject
    Template listingInquiry;

    @Inject
    Template listingReply;

    @Inject
    Template listingPublished;

    /**
     * Sends inquiry from buyer to seller via masked email relay.
     *
     * <p>
     * This method is called by MessageRelayJobHandler after rate limiting and validation. It sends the buyer's inquiry
     * to the seller's real email with Reply-To header set to platform relay address.
     *
     * @param listingId
     *            listing UUID
     * @param fromEmail
     *            buyer's real email
     * @param fromName
     *            buyer's display name
     * @param messageBody
     *            inquiry message content
     * @param messageSubject
     *            email subject line
     * @return the created MarketplaceMessage record
     * @throws IllegalStateException
     *             if listing not found or not active
     */
    public MarketplaceMessage sendInquiry(UUID listingId, String fromEmail, String fromName, String messageBody,
            String messageSubject) {

        // Fetch listing to get seller email
        MarketplaceListing listing = MarketplaceListing.findById(listingId);
        if (listing == null) {
            throw new IllegalStateException("Listing not found: " + listingId);
        }
        if (!"active".equals(listing.status)) {
            throw new IllegalStateException("Listing is not active: " + listingId);
        }

        String sellerEmail = listing.contactInfo.email();

        // Generate unique message ID and reply address
        String messageId = generateMessageId();
        String replyToAddress = generateReplyToAddress(messageId);
        UUID threadId = UUID.randomUUID();

        // Create message record (before sending to ensure audit trail even if send fails)
        MarketplaceMessage message = new MarketplaceMessage();
        message.listingId = listingId;
        message.messageId = messageId;
        message.inReplyTo = null; // Initial inquiry, no parent
        message.threadId = threadId;
        message.fromEmail = fromEmail;
        message.fromName = fromName;
        message.toEmail = sellerEmail;
        message.toName = null; // Seller name not known at this point
        message.subject = messageSubject;
        message.body = messageBody;
        message.direction = "buyer_to_seller";
        message.sentAt = null; // Will be set after successful send
        MarketplaceMessage.create(message);

        // Render email template
        String htmlBody = listingInquiry.data("listing", listing).data("fromName", fromName)
                .data("messageBody", messageBody).data("baseUrl", "https://homepage.villagecompute.com").render();

        // Send email with proper headers
        try {
            mailer.send(Mail.withHtml(sellerEmail, messageSubject, htmlBody)
                    .addHeader("Message-ID", "<" + messageId + ">").addHeader("Reply-To", replyToAddress)
                    .addHeader("X-Platform", PLATFORM_NAME).addHeader("X-Listing-ID", listingId.toString())
                    .setFrom("noreply@" + RELAY_DOMAIN));

            // Mark as sent
            message.sentAt = Instant.now();
            MarketplaceMessage.markSent(message.id, message.sentAt);

            LOG.infof("Sent inquiry: messageId=%s, listingId=%s, fromEmail=%s, toEmail=%s", messageId, listingId,
                    fromEmail, sellerEmail);

            return message;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to send inquiry: messageId=%s, listingId=%s", messageId, listingId);
            throw new RuntimeException("Failed to send inquiry email", e);
        }
    }

    /**
     * Sends reply from seller back to buyer via masked email relay.
     *
     * <p>
     * This method is called by InboundEmailProcessor after parsing incoming IMAP message. It relays the seller's reply
     * to the buyer's real email address.
     *
     * @param originalMessageId
     *            the original inquiry message ID
     * @param replyBody
     *            seller's reply content
     * @param replySubject
     *            email subject line
     * @return the created MarketplaceMessage record
     * @throws IllegalStateException
     *             if original message not found
     */
    public MarketplaceMessage sendReply(String originalMessageId, String replyBody, String replySubject) {

        // Lookup original message to get buyer email and thread info
        Optional<MarketplaceMessage> originalOpt = MarketplaceMessage.findByMessageId(originalMessageId);
        if (originalOpt.isEmpty()) {
            throw new IllegalStateException("Original message not found: " + originalMessageId);
        }

        MarketplaceMessage original = originalOpt.get();

        // Fetch listing for context
        MarketplaceListing listing = MarketplaceListing.findById(original.listingId);
        if (listing == null) {
            throw new IllegalStateException("Listing not found: " + original.listingId);
        }

        String buyerEmail = original.fromEmail; // Original sender is buyer
        String sellerEmail = original.toEmail; // Original recipient is seller

        // Generate unique message ID for reply
        String replyMessageId = generateMessageId();
        String replyToAddress = generateReplyToAddress(replyMessageId);

        // Create message record
        MarketplaceMessage reply = new MarketplaceMessage();
        reply.listingId = original.listingId;
        reply.messageId = replyMessageId;
        reply.inReplyTo = originalMessageId; // Thread with original inquiry
        reply.threadId = original.threadId; // Same conversation thread
        reply.fromEmail = sellerEmail;
        reply.fromName = null; // Seller name not known
        reply.toEmail = buyerEmail;
        reply.toName = original.fromName; // Buyer's name from original inquiry
        reply.subject = replySubject;
        reply.body = replyBody;
        reply.direction = "seller_to_buyer";
        reply.sentAt = null; // Will be set after successful send
        MarketplaceMessage.create(reply);

        // Render email template
        String htmlBody = listingReply.data("listing", listing).data("messageBody", replyBody)
                .data("originalSubject", original.subject).data("baseUrl", "https://homepage.villagecompute.com")
                .render();

        // Send email with proper threading headers
        try {
            mailer.send(Mail.withHtml(buyerEmail, replySubject, htmlBody)
                    .addHeader("Message-ID", "<" + replyMessageId + ">")
                    .addHeader("In-Reply-To", "<" + originalMessageId + ">")
                    .addHeader("References", "<" + originalMessageId + ">").addHeader("Reply-To", replyToAddress)
                    .addHeader("X-Platform", PLATFORM_NAME).addHeader("X-Listing-ID", original.listingId.toString())
                    .setFrom("noreply@" + RELAY_DOMAIN));

            // Mark as sent
            reply.sentAt = Instant.now();
            MarketplaceMessage.markSent(reply.id, reply.sentAt);

            LOG.infof("Sent reply: messageId=%s, inReplyTo=%s, listingId=%s, fromEmail=%s, toEmail=%s",
                    replyMessageId, originalMessageId, original.listingId, sellerEmail, buyerEmail);

            return reply;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to send reply: messageId=%s, inReplyTo=%s", replyMessageId, originalMessageId);
            throw new RuntimeException("Failed to send reply email", e);
        }
    }

    /**
     * Sends listing published confirmation email to seller.
     *
     * <p>
     * This method is called after a listing is successfully published (status → active). It confirms the listing is
     * live and provides the seller with their masked contact email.
     *
     * @param listingId
     *            listing UUID
     * @return true if sent successfully, false otherwise
     */
    public boolean sendListingPublishedEmail(UUID listingId) {

        MarketplaceListing listing = MarketplaceListing.findById(listingId);
        if (listing == null) {
            LOG.warnf("Cannot send published email - listing not found: %s", listingId);
            return false;
        }

        String sellerEmail = listing.contactInfo.email();
        String maskedEmail = listing.contactInfo.maskedEmail();

        String subject = "Your listing is now live - " + listing.title;

        // Render email template
        String htmlBody = listingPublished.data("listing", listing).data("maskedEmail", maskedEmail)
                .data("baseUrl", "https://homepage.villagecompute.com").render();

        try {
            mailer.send(Mail.withHtml(sellerEmail, subject, htmlBody).setFrom("noreply@" + RELAY_DOMAIN)
                    .addHeader("X-Platform", PLATFORM_NAME).addHeader("X-Listing-ID", listingId.toString()));

            LOG.infof("Sent listing published email: listingId=%s, sellerEmail=%s", listingId, sellerEmail);
            return true;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to send listing published email: listingId=%s", listingId);
            return false;
        }
    }

    /**
     * Generates unique email Message-ID for relay messages.
     *
     * <p>
     * Format: msg-{uuid}@villagecompute.com
     *
     * @return unique message ID
     */
    private String generateMessageId() {
        return "msg-" + UUID.randomUUID() + "@" + RELAY_DOMAIN;
    }

    /**
     * Generates Reply-To address for relay messages.
     *
     * <p>
     * Format: reply-{uuid}@villagecompute.com (extracted from message ID)
     *
     * @param messageId
     *            the email Message-ID (e.g., "msg-abc123@villagecompute.com")
     * @return reply-to address (e.g., "reply-abc123@villagecompute.com")
     */
    private String generateReplyToAddress(String messageId) {
        // Extract UUID from message ID: msg-{uuid}@domain → reply-{uuid}@domain
        String uuid = messageId.replace("msg-", "").replace("@" + RELAY_DOMAIN, "");
        return "reply-" + uuid + "@" + RELAY_DOMAIN;
    }

    /**
     * Extracts message UUID from reply-to address.
     *
     * <p>
     * Used by InboundEmailProcessor to lookup original message when processing replies.
     *
     * <p>
     * Format: reply-{uuid}@villagecompute.com → msg-{uuid}@villagecompute.com
     *
     * @param replyToAddress
     *            the reply-to email address
     * @return the original message ID, or null if format invalid
     */
    public static String extractMessageIdFromReplyAddress(String replyToAddress) {
        if (replyToAddress == null || !replyToAddress.startsWith("reply-")) {
            return null;
        }

        // reply-{uuid}@domain → msg-{uuid}@domain
        String uuid = replyToAddress.replace("reply-", "").replace("@" + RELAY_DOMAIN, "");
        return "msg-" + uuid + "@" + RELAY_DOMAIN;
    }

    /**
     * Validates message content for spam indicators.
     *
     * <p>
     * Simple keyword-based spam detection. Future: integrate with AI-based spam detection via LangChain4j.
     *
     * @param messageBody
     *            the message content
     * @return true if message appears to be spam, false otherwise
     */
    public static boolean containsSpamKeywords(String messageBody) {
        if (messageBody == null || messageBody.isBlank()) {
            return false;
        }

        String lowerBody = messageBody.toLowerCase();

        // Common spam keywords
        String[] spamKeywords = { "click here to unsubscribe", "you have won", "free money", "nigerian prince",
                "weight loss", "viagra", "casino", "lottery winner", "enlarge your", "bitcoin investment",
                "work from home guaranteed", "make money fast" };

        for (String keyword : spamKeywords) {
            if (lowerBody.contains(keyword)) {
                return true;
            }
        }

        return false;
    }
}
