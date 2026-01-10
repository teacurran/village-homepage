package villagecompute.homepage.jobs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.mail.Address;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import villagecompute.homepage.observability.LoggingConfig;
import villagecompute.homepage.services.MessageRelayService;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Job handler for inbound email processing via IMAP polling (Feature F14.3).
 *
 * <p>
 * This handler polls an IMAP inbox every 1 minute to process marketplace reply messages. When a seller replies to a
 * buyer inquiry, their reply is sent to the platform relay address (reply-{messageId}@villagecompute.com). This job
 * picks up those replies and relays them to the original buyer's email address.
 *
 * <p>
 * <b>Execution Flow:</b>
 * <ol>
 * <li>Connect to IMAP server using configured credentials</li>
 * <li>Open INBOX folder in READ_WRITE mode</li>
 * <li>Fetch unread messages (UNSEEN flag)</li>
 * <li>For each message:
 * <ul>
 * <li>Extract To address (e.g., reply-abc123@villagecompute.com)</li>
 * <li>Parse message ID from address (reply-abc123 → msg-abc123)</li>
 * <li>Lookup original inquiry via MarketplaceMessage.findByMessageId()</li>
 * <li>Call {@link MessageRelayService#sendReply} to relay to buyer</li>
 * <li>Mark email as SEEN to prevent reprocessing</li>
 * </ul>
 * </li>
 * <li>Close folder and disconnect from IMAP</li>
 * </ol>
 *
 * <p>
 * <b>IMAP Configuration:</b> Connection settings are loaded from application.properties:
 * <ul>
 * <li>{@code email.imap.host} - IMAP server hostname (e.g., localhost for Mailpit, imap.gmail.com for Gmail)</li>
 * <li>{@code email.imap.port} - IMAP port (1143 for Mailpit, 993 for Gmail SSL)</li>
 * <li>{@code email.imap.username} - IMAP username (empty for Mailpit in dev mode)</li>
 * <li>{@code email.imap.password} - IMAP password (empty for Mailpit in dev mode)</li>
 * <li>{@code email.imap.folder} - Folder to poll (default: INBOX)</li>
 * <li>{@code email.imap.ssl} - Enable SSL/TLS (false for Mailpit, true for production)</li>
 * </ul>
 *
 * <p>
 * <b>Email Address Parsing:</b> Reply-To addresses follow pattern: {@code reply-{uuid}@villagecompute.com}
 *
 * <p>
 * The UUID is extracted and converted back to original message ID format: {@code msg-{uuid}@villagecompute.com}
 *
 * <p>
 * <b>Error Handling:</b>
 * <ul>
 * <li>IMAP connection failures: Log error and retry next scheduled run (fail gracefully)</li>
 * <li>Invalid message format: Mark as SEEN and skip (avoid reprocessing spam)</li>
 * <li>Missing original message: Mark as SEEN and log warning (orphaned reply)</li>
 * <li>Relay send failure: Leave UNSEEN for retry on next poll (transient SMTP errors)</li>
 * </ul>
 *
 * <p>
 * <b>Telemetry:</b> Exports the following OpenTelemetry span attributes:
 * <ul>
 * <li>{@code job.id} - Job database primary key</li>
 * <li>{@code job.type} - INBOUND_EMAIL</li>
 * <li>{@code job.queue} - DEFAULT</li>
 * <li>{@code messages_found} - Total unread messages in inbox</li>
 * <li>{@code messages_processed} - Messages successfully relayed</li>
 * <li>{@code messages_skipped} - Messages skipped (invalid format, orphaned)</li>
 * </ul>
 *
 * <p>
 * And Micrometer metrics:
 * <ul>
 * <li>{@code marketplace.messages.inbound.total} (Counter) - Total inbound messages processed</li>
 * <li>{@code marketplace.messages.inbound.duration} (Timer) - Job execution duration</li>
 * <li>{@code marketplace.messages.inbound.errors.total} (Counter) - Processing errors</li>
 * </ul>
 *
 * <p>
 * <b>Payload Structure:</b> No payload required - job runs on fixed schedule (every 1 minute)
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>F14.3: IMAP polling for marketplace reply relay</li>
 * <li>P6: Privacy via masked email relay</li>
 * <li>P7: Unified job orchestration via DelayedJobService</li>
 * </ul>
 *
 * @see MessageRelayService for relay logic
 * @see MessageRelayJobHandler for outbound message relay
 */
@ApplicationScoped
public class InboundEmailProcessor implements JobHandler {

    private static final Logger LOG = Logger.getLogger(InboundEmailProcessor.class);

    private static final Pattern REPLY_ADDRESS_PATTERN = Pattern.compile("reply-([0-9a-f-]{36})@villagecompute\\.com",
            Pattern.CASE_INSENSITIVE);

    @ConfigProperty(
            name = "email.imap.host")
    String imapHost;

    @ConfigProperty(
            name = "email.imap.port")
    int imapPort;

    @ConfigProperty(
            name = "email.imap.username",
            defaultValue = "")
    String imapUsername;

    @ConfigProperty(
            name = "email.imap.password",
            defaultValue = "")
    String imapPassword;

    @ConfigProperty(
            name = "email.imap.folder",
            defaultValue = "INBOX")
    String imapFolder;

    @ConfigProperty(
            name = "email.imap.ssl",
            defaultValue = "false")
    boolean imapSsl;

    @Inject
    MessageRelayService messageRelayService;

    @Inject
    Tracer tracer;

    @Inject
    MeterRegistry meterRegistry;

    @Override
    public JobType handlesType() {
        return JobType.INBOUND_EMAIL;
    }

    @Override
    public void execute(Long jobId, Map<String, Object> payload) throws Exception {
        Span span = tracer.spanBuilder("job.inbound_email").setAttribute("job.id", jobId)
                .setAttribute("job.type", JobType.INBOUND_EMAIL.name())
                .setAttribute("job.queue", JobQueue.DEFAULT.name()).startSpan();

        Timer.Sample timerSample = Timer.start(meterRegistry);

        try (Scope scope = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            LoggingConfig.setJobId(jobId);
            LoggingConfig.setRequestOrigin("InboundEmailProcessor");

            LOG.infof("Starting inbound email processing job %d", jobId);

            int messagesProcessed = 0;
            int messagesSkipped = 0;
            int messagesFound = 0;

            Store store = null;
            Folder folder = null;

            try {
                // Connect to IMAP server
                Properties props = new Properties();
                props.put("mail.store.protocol", "imap");
                props.put("mail.imap.host", imapHost);
                props.put("mail.imap.port", String.valueOf(imapPort));
                props.put("mail.imap.ssl.enable", String.valueOf(imapSsl));
                props.put("mail.imap.starttls.enable", "false"); // Use SSL instead of STARTTLS

                Session session = Session.getInstance(props);
                store = session.getStore("imap");

                // Connect with credentials (empty for Mailpit in dev mode)
                if (imapUsername.isBlank()) {
                    store.connect(imapHost, "", "");
                } else {
                    store.connect(imapHost, imapUsername, imapPassword);
                }

                LOG.debugf("Connected to IMAP: host=%s, port=%d, ssl=%s", imapHost, imapPort, imapSsl);

                // Open inbox folder
                folder = store.getFolder(imapFolder);
                folder.open(Folder.READ_WRITE);

                // Fetch unread messages
                Message[] messages = folder.search(new jakarta.mail.search.FlagTerm(new Flags(Flags.Flag.SEEN), false));
                messagesFound = messages.length;

                span.setAttribute("messages_found", messagesFound);

                if (messagesFound == 0) {
                    LOG.infof("No unread messages found in inbox");
                    timerSample.stop(Timer.builder("marketplace.messages.inbound.duration").register(meterRegistry));
                    return;
                }

                LOG.infof("Found %d unread messages to process", messagesFound);

                // Process each message
                for (Message message : messages) {
                    try {
                        boolean processed = processMessage(message);
                        if (processed) {
                            messagesProcessed++;
                            // Mark as seen to prevent reprocessing
                            message.setFlag(Flags.Flag.SEEN, true);
                        } else {
                            messagesSkipped++;
                            // Mark as seen anyway to avoid spam reprocessing
                            message.setFlag(Flags.Flag.SEEN, true);
                        }
                    } catch (Exception e) {
                        LOG.errorf(e, "Failed to process individual message: %s", e.getMessage());
                        span.recordException(e);

                        Counter.builder("marketplace.messages.inbound.errors.total")
                                .tag("error_type", e.getClass().getSimpleName()).register(meterRegistry).increment();

                        // Do NOT mark as seen - retry on next poll for transient errors
                    }
                }

            } catch (MessagingException e) {
                LOG.errorf(e, "IMAP connection failed: %s", e.getMessage());
                span.recordException(e);

                Counter.builder("marketplace.messages.inbound.errors.total")
                        .tag("error_type", "imap_connection_failure").register(meterRegistry).increment();

                // Fail gracefully - retry next scheduled run
                throw e;

            } finally {
                // Clean up IMAP resources
                if (folder != null && folder.isOpen()) {
                    try {
                        folder.close(false); // Don't expunge
                    } catch (MessagingException e) {
                        LOG.warnf(e, "Failed to close IMAP folder: %s", e.getMessage());
                    }
                }

                if (store != null && store.isConnected()) {
                    try {
                        store.close();
                    } catch (MessagingException e) {
                        LOG.warnf(e, "Failed to close IMAP store: %s", e.getMessage());
                    }
                }
            }

            span.setAttribute("messages_processed", messagesProcessed);
            span.setAttribute("messages_skipped", messagesSkipped);

            // Export success metrics
            Counter.builder("marketplace.messages.inbound.total").register(meterRegistry).increment(messagesProcessed);

            timerSample.stop(Timer.builder("marketplace.messages.inbound.duration").register(meterRegistry));

            LOG.infof("Inbound email processing job %d completed: %d processed, %d skipped, %d found", jobId,
                    messagesProcessed, messagesSkipped, messagesFound);

        } finally {
            LoggingConfig.clearMDC();
            span.end();
        }
    }

    /**
     * Processes a single inbound email message.
     *
     * <p>
     * Extracts To address, parses original message ID, and relays to buyer via MessageRelayService.
     *
     * @param message
     *            the email message
     * @return true if processed successfully, false if skipped (invalid format)
     * @throws MessagingException
     *             if IMAP operations fail
     * @throws IOException
     *             if message content cannot be read
     */
    private boolean processMessage(Message message) throws MessagingException, IOException {

        // Extract To address (e.g., reply-abc123@villagecompute.com)
        Address[] toAddresses = message.getRecipients(Message.RecipientType.TO);
        if (toAddresses == null || toAddresses.length == 0) {
            LOG.warnf("Message has no To address, skipping");
            return false;
        }

        String toAddress = ((InternetAddress) toAddresses[0]).getAddress();

        // Extract original message ID from reply address
        String originalMessageId = extractMessageIdFromReplyAddress(toAddress);
        if (originalMessageId == null) {
            LOG.warnf("Invalid reply address format: %s, skipping", toAddress);
            return false;
        }

        // Extract subject and body
        String subject = message.getSubject();
        String body = extractMessageBody(message);

        if (body == null || body.isBlank()) {
            LOG.warnf("Message has empty body, skipping: messageId=%s", originalMessageId);
            return false;
        }

        LOG.infof("Processing reply: originalMessageId=%s, subject=%s", originalMessageId, subject);

        // Relay to buyer via MessageRelayService
        try {
            messageRelayService.sendReply(originalMessageId, body, subject);
            LOG.infof("Successfully relayed reply: originalMessageId=%s", originalMessageId);
            return true;

        } catch (IllegalStateException e) {
            // Original message not found - orphaned reply
            LOG.warnf("Original message not found for reply: %s, skipping", originalMessageId);
            return false;

        } catch (Exception e) {
            // Relay send failure - re-throw to leave UNSEEN for retry
            LOG.errorf(e, "Failed to relay reply: originalMessageId=%s", originalMessageId);
            throw e;
        }
    }

    /**
     * Extracts message body content from email.
     *
     * <p>
     * Handles both plain text and multipart MIME messages. For multipart, extracts first text/plain part.
     *
     * @param message
     *            the email message
     * @return message body as plain text, or null if extraction fails
     */
    private String extractMessageBody(Message message) {
        try {
            Object content = message.getContent();

            if (content instanceof String) {
                return (String) content;
            }

            if (content instanceof jakarta.mail.Multipart) {
                jakarta.mail.Multipart multipart = (jakarta.mail.Multipart) content;
                for (int i = 0; i < multipart.getCount(); i++) {
                    jakarta.mail.BodyPart bodyPart = multipart.getBodyPart(i);
                    if (bodyPart.isMimeType("text/plain")) {
                        return (String) bodyPart.getContent();
                    }
                }
            }

            LOG.warnf("Unsupported message content type: %s", content.getClass().getName());
            return null;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to extract message body: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Extracts original message ID from reply address.
     *
     * <p>
     * Pattern: reply-{uuid}@villagecompute.com → msg-{uuid}@villagecompute.com
     *
     * @param replyAddress
     *            the reply-to email address
     * @return the original message ID, or null if format invalid
     */
    private String extractMessageIdFromReplyAddress(String replyAddress) {
        if (replyAddress == null || replyAddress.isBlank()) {
            return null;
        }

        Matcher matcher = REPLY_ADDRESS_PATTERN.matcher(replyAddress);
        if (!matcher.find()) {
            return null;
        }

        String uuid = matcher.group(1);
        return "msg-" + uuid + "@villagecompute.com";
    }
}
