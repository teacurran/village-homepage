# Marketplace Email Relay System

## Overview

The marketplace email relay system provides privacy-protected communication between buyers and sellers. Real email addresses are never exposed - all messages are relayed through platform addresses to protect user privacy.

**Features:** F12.6 (Email Masking), F14.3 (IMAP Polling)
**Policies:** P1 (GDPR), P6 (Privacy)

## Architecture

### Email Flow

#### Buyer → Seller (Initial Inquiry)

1. Buyer sends inquiry via `POST /api/marketplace/listings/{listingId}/contact`
2. API validates listing, checks rate limits, enqueues MESSAGE_RELAY job
3. MessageRelayJobHandler executes job:
   - Fetches listing to get seller's real email from `contactInfo.email`
   - Generates unique Message-ID: `msg-{uuid}@villagecompute.com`
   - Sends email to seller with Reply-To: `reply-{uuid}@villagecompute.com`
   - Stores message in `marketplace_messages` table
4. Seller receives email in their personal inbox

#### Seller → Buyer (Reply)

1. Seller clicks Reply in their email client
2. Email sent to `reply-{uuid}@villagecompute.com`
3. InboundEmailProcessor polls IMAP inbox every 1 minute
4. Processor finds new message:
   - Extracts UUID from reply address
   - Looks up original message via `marketplace_messages.message_id`
   - Fetches buyer's real email from original message
   - Relays seller's reply to buyer
5. Buyer receives reply in their personal inbox

### Components

| Component | Purpose | Frequency |
|-----------|---------|-----------|
| ListingContactResource | REST endpoint for inquiries | On-demand |
| MessageRelayJobHandler | Outbound relay (buyer→seller) | On-demand via job queue |
| InboundEmailProcessor | Inbound relay (seller→buyer) | Every 1 minute |
| MessageRelayService | Email sending logic | Called by handlers |
| MarketplaceMessage | Audit trail storage | Per message |

### Database Schema

```sql
CREATE TABLE marketplace_messages (
    id UUID PRIMARY KEY,
    listing_id UUID NOT NULL REFERENCES marketplace_listings(id) ON DELETE CASCADE,
    message_id TEXT NOT NULL UNIQUE,  -- msg-{uuid}@villagecompute.com
    in_reply_to TEXT,  -- Parent message for threading
    thread_id UUID,  -- Conversation grouping
    from_email TEXT NOT NULL,
    from_name TEXT,
    to_email TEXT NOT NULL,
    to_name TEXT,
    subject TEXT NOT NULL,
    body TEXT NOT NULL,
    direction TEXT NOT NULL,  -- buyer_to_seller | seller_to_buyer
    sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    is_spam BOOLEAN DEFAULT false,
    spam_score DECIMAL(3,2),
    flagged_for_review BOOLEAN DEFAULT false
);
```

## Configuration

### IMAP Settings

Located in `src/main/resources/application.yaml`:

```yaml
email:
  imap:
    host: ${EMAIL_IMAP_HOST:localhost}
    port: ${EMAIL_IMAP_PORT:1143}
    username: ${EMAIL_IMAP_USERNAME:}
    password: ${EMAIL_IMAP_PASSWORD:}
    folder: ${EMAIL_IMAP_FOLDER:INBOX}
    ssl: ${EMAIL_IMAP_SSL:false}
```

### Development (Mailpit)

```yaml
email:
  imap:
    host: localhost
    port: 1143
    username: ""  # Empty for Mailpit
    password: ""  # Empty for Mailpit
    folder: INBOX
    ssl: false
```

**Mailpit UI:** http://localhost:8025

### Production (Gmail Example)

```yaml
email:
  imap:
    host: imap.gmail.com
    port: 993
    username: marketplace@villagecompute.com
    password: ${GMAIL_APP_PASSWORD}  # Use App Password, not account password
    folder: INBOX
    ssl: true
```

**Gmail Setup:**
1. Enable IMAP in Gmail settings
2. Navigate to Settings → Security → 2-Step Verification → App Passwords and generate credentials
3. Use generated credential in configuration

### Production (AWS SES Example)

AWS SES does not support IMAP directly. Use AWS WorkMail or forward to Gmail/another IMAP provider.

## Rate Limiting

Protects against spam abuse via `RateLimitService`:

| Tier | Limit | Window |
|------|-------|--------|
| Anonymous | N/A | Messaging requires login |
| Logged In | 20 messages | Per hour |
| Trusted (karma ≥ 10) | 50 messages | Per hour |

Rate limit configuration stored in `rate_limit_configs` table. Violations logged to `rate_limit_violations`.

## Spam Prevention

### Current Measures

1. **Rate limiting** - Prevents mass messaging
2. **Keyword detection** - Basic spam keyword matching in `MessageRelayService.containsSpamKeywords()`
3. **Message length limits** - 10-10,000 characters
4. **Authentication required** - No anonymous messaging
5. **Audit trail** - All messages logged to `marketplace_messages` table

### Spam Keywords

Current keyword list (see `MessageRelayService.containsSpamKeywords()`):
- "click here to unsubscribe"
- "you have won"
- "free money"
- "nigerian prince"
- "weight loss", "viagra", "casino"
- "lottery winner", "enlarge your"
- "bitcoin investment"
- "work from home guaranteed", "make money fast"

### Future Enhancements (Not Yet Implemented)

- AI-based spam detection via LangChain4j
- IP-based rate limiting
- Automatic flagging for manual review

## Monitoring

### Metrics (Micrometer)

**Outbound Relay:**
- `marketplace.messages.relayed.total{direction=buyer_to_seller}` - Counter
- `marketplace.messages.relay.duration` - Timer
- `marketplace.messages.relay.errors.total{error_type}` - Counter

**Inbound Processing:**
- `marketplace.messages.inbound.total` - Counter
- `marketplace.messages.inbound.duration` - Timer
- `marketplace.messages.inbound.errors.total{error_type}` - Counter

### Telemetry (OpenTelemetry)

**Spans:**
- `job.message_relay` - Outbound relay job
- `job.inbound_email` - IMAP polling job

**Attributes:**
- `job.id`, `job.type`, `job.queue`
- `listing_id` - Listing UUID
- `message_id` - Email Message-ID
- `recipient_email_hash` - Hashed email (privacy-safe)
- `messages_found`, `messages_processed`, `messages_skipped`

### Logs

**Structured JSON logs** include:
- `trace_id` - Distributed tracing ID
- `job_id` - Job database ID
- `listing_id` - Listing UUID
- `message_id` - Email Message-ID

**Example:**
```json
{
  "timestamp": "2025-01-10T12:34:56.789Z",
  "level": "INFO",
  "message": "Sent inquiry: messageId=msg-abc123@villagecompute.com, listingId=...",
  "trace_id": "a1b2c3d4...",
  "job_id": 12345,
  "service.name": "village-homepage"
}
```

## Troubleshooting

### Messages Not Being Relayed

**Symptom:** Buyer sends inquiry but seller never receives email

**Diagnosis:**
1. Check job queue status: `SELECT * FROM delayed_jobs WHERE job_type = 'MESSAGE_RELAY' AND status != 'completed'`
2. Check job errors: `SELECT * FROM delayed_jobs WHERE job_type = 'MESSAGE_RELAY' AND status = 'failed'`
3. Check rate limit violations: `SELECT * FROM rate_limit_violations WHERE action_type = 'MESSAGE_SEND' ORDER BY created_at DESC LIMIT 10`
4. Check SMTP logs in application logs

**Common Causes:**
- Rate limit exceeded (HTTP 429 response)
- Listing not active (HTTP 400 response)
- SMTP credentials invalid (job fails with MessagingException)
- Quarkus Mailer misconfigured

**Resolution:**
- Verify listing status: `SELECT id, status FROM marketplace_listings WHERE id = '...'`
- Check rate limit config: `SELECT * FROM rate_limit_configs WHERE action_type = 'MESSAGE_SEND'`
- Verify SMTP settings in Mailpit UI (dev) or Gmail settings (prod)
- Retry failed job: Update `delayed_jobs.status` to 'pending'

### Replies Not Being Received

**Symptom:** Seller replies but buyer never receives response

**Diagnosis:**
1. Check IMAP connection: `SELECT * FROM delayed_jobs WHERE job_type = 'INBOUND_EMAIL' ORDER BY created_at DESC LIMIT 5`
2. Check message processing: `SELECT * FROM marketplace_messages WHERE direction = 'seller_to_buyer' ORDER BY created_at DESC LIMIT 10`
3. Check email in Mailpit UI (dev) - verify it arrived at inbox
4. Check application logs for IMAP errors

**Common Causes:**
- IMAP connection failure (wrong credentials, firewall blocking port 993)
- Reply sent to wrong address (not reply-{uuid}@villagecompute.com)
- Original message not found (orphaned reply)
- IMAP folder name wrong (not "INBOX")

**Resolution:**
- Test IMAP connection: `openssl s_client -connect imap.gmail.com:993 -crlf` (prod)
- Verify IMAP credentials in Kubernetes secret or environment variables
- Check Reply-To header in sent emails
- Verify InboundEmailProcessor is running every 1 minute

### High Spam Volume

**Symptom:** Many spam messages being relayed

**Diagnosis:**
1. Check flagged messages: `SELECT * FROM marketplace_messages WHERE is_spam = true OR flagged_for_review = true ORDER BY created_at DESC`
2. Check rate limit violations: `SELECT user_id, COUNT(*) FROM rate_limit_violations WHERE action_type = 'MESSAGE_SEND' GROUP BY user_id ORDER BY COUNT(*) DESC`
3. Identify abusive users: `SELECT from_email, COUNT(*) FROM marketplace_messages GROUP BY from_email HAVING COUNT(*) > 50`

**Resolution:**
- Ban abusive users (future: implement user banning)
- Lower rate limits: `UPDATE rate_limit_configs SET limit_count = 10 WHERE action_type = 'MESSAGE_SEND' AND tier = 'logged_in'`
- Improve spam keyword list in `MessageRelayService.containsSpamKeywords()`
- Enable AI spam detection (future enhancement)

### IMAP Connection Failures

**Symptom:** InboundEmailProcessor failing with MessagingException

**Diagnosis:**
1. Check recent job failures: `SELECT * FROM delayed_jobs WHERE job_type = 'INBOUND_EMAIL' AND status = 'failed' ORDER BY created_at DESC LIMIT 5`
2. Check application logs for stack traces
3. Test IMAP manually: `openssl s_client -connect imap.gmail.com:993`

**Common Causes:**
- Wrong IMAP credentials (invalid App Password for Gmail)
- Firewall blocking port 993 (prod) or 1143 (dev)
- Gmail account security blocking access
- Mailpit not running (dev)

**Resolution:**
- Verify credentials in Kubernetes secret: `kubectl get secret homepage-email-credentials -o yaml`
- Regenerate Gmail App Password if expired
- Check firewall rules for outbound port 993
- Restart Mailpit: `docker-compose restart mailpit` (dev)

### Message Threading Issues

**Symptom:** Email clients not showing replies in conversation thread

**Diagnosis:**
1. Check Message-ID headers in sent emails (view raw email)
2. Verify In-Reply-To header in replies
3. Check thread_id consistency: `SELECT thread_id, COUNT(*) FROM marketplace_messages GROUP BY thread_id`

**Common Causes:**
- Message-ID header missing or malformed
- In-Reply-To header not set for replies
- Email client not supporting threading

**Resolution:**
- Verify MessageRelayService is setting headers correctly
- Check Quarkus Mailer configuration
- Test with Gmail web UI (always supports threading)

## Compliance

### GDPR (Policy P1)

**Data Retention:**
- Messages stored in `marketplace_messages` table
- Retained for 90 days after listing expiration
- CASCADE delete when listing deleted
- User can request export via privacy export endpoint (future)

**Right to be Forgotten:**
- Deleting listing CASCADE deletes all messages
- Soft-delete listing → messages retained for 90 days → hard purge

**Data Processing:**
- Email addresses stored encrypted at rest (PostgreSQL SSL)
- Email addresses never logged in plaintext (hashed in telemetry)

### Privacy (Policy P6)

**Email Masking:**
- Buyer email never exposed to seller
- Seller email never exposed to buyer
- All communication relayed through platform addresses
- Real email addresses only in database (not public)

**Audit Trail:**
- All messages logged to `marketplace_messages` table
- Direction tracked (buyer_to_seller, seller_to_buyer)
- Timestamps recorded (sent_at, created_at)

## Maintenance

### Daily Tasks

- Monitor spam flagged messages: `SELECT COUNT(*) FROM marketplace_messages WHERE flagged_for_review = true`
- Check failed jobs: `SELECT COUNT(*) FROM delayed_jobs WHERE job_type IN ('MESSAGE_RELAY', 'INBOUND_EMAIL') AND status = 'failed'`
- Review rate limit violations: `SELECT COUNT(*) FROM rate_limit_violations WHERE created_at > NOW() - INTERVAL '24 hours'`

### Weekly Tasks

- Review spam keyword effectiveness (analyze false positives/negatives)
- Clean up old messages (90-day retention): Run `DELETE FROM marketplace_messages WHERE created_at < NOW() - INTERVAL '90 days'`
- Check IMAP mailbox size (prevent inbox overflow)

### Monthly Tasks

- Review rate limit configurations (adjust based on abuse patterns)
- Audit message relay metrics (identify trends, capacity planning)
- Test disaster recovery (backup/restore marketplace_messages table)

## Testing

### Local Testing with Mailpit

1. Start Mailpit: `docker-compose up mailpit`
2. Access UI: http://localhost:8025
3. Send test inquiry via REST endpoint
4. Verify email appears in Mailpit inbox
5. Reply to email (manually send to reply-{uuid}@villagecompute.com)
6. Wait 1 minute for InboundEmailProcessor to poll
7. Verify reply delivered to buyer email

### Integration Tests

Key test files:
- `MarketplaceMessageTest.java` - Entity tests
- `MessageRelayServiceTest.java` - Service tests (mocked Mailer)
- `MessageRelayJobHandlerTest.java` - Job handler tests
- `InboundEmailProcessorTest.java` - IMAP processing tests (mocked messages)
- `ListingContactResourceTest.java` - REST endpoint tests

Run tests: `./mvnw test`

### Manual Testing Checklist

- [ ] Send inquiry for active listing → Seller receives email
- [ ] Send inquiry for expired listing → HTTP 400 error
- [ ] Send inquiry as anonymous user → HTTP 401 error
- [ ] Send inquiry exceeding rate limit → HTTP 429 error
- [ ] Send inquiry with spam keywords → HTTP 400 error
- [ ] Reply to inquiry → Buyer receives email
- [ ] Verify email threading in Gmail (conversation view)
- [ ] Delete listing → Messages CASCADE deleted
- [ ] Check telemetry spans in Jaeger UI
- [ ] Check metrics in Prometheus/Grafana

## Support

### Escalation

**Level 1 (User Support):**
- "I sent a message but seller didn't respond" → Check listing status, rate limits
- "I didn't receive a reply" → Check IMAP processing, verify reply address

**Level 2 (Operations):**
- IMAP connection failures → Check credentials, firewall rules
- High spam volume → Adjust rate limits, update spam keywords

**Level 3 (Engineering):**
- Email relay architecture issues → Review MessageRelayService code
- Database performance problems → Optimize indexes on marketplace_messages

### Useful Queries

**Recent messages for listing:**
```sql
SELECT * FROM marketplace_messages
WHERE listing_id = '...'
ORDER BY created_at DESC;
```

**Messages in thread:**
```sql
SELECT * FROM marketplace_messages
WHERE thread_id = '...'
ORDER BY created_at ASC;
```

**Failed relay jobs:**
```sql
SELECT * FROM delayed_jobs
WHERE job_type = 'MESSAGE_RELAY'
AND status = 'failed'
ORDER BY created_at DESC
LIMIT 10;
```

**Top spam senders:**
```sql
SELECT from_email, COUNT(*)
FROM marketplace_messages
WHERE is_spam = true
GROUP BY from_email
ORDER BY COUNT(*) DESC
LIMIT 10;
```

## References

- **Feature Specs:** F12.6 (Email Masking), F14.3 (IMAP Polling)
- **Policy Docs:** P1 (GDPR), P6 (Privacy), P14 (Rate Limiting)
- **Code Docs:** `MessageRelayService.java`, `InboundEmailProcessor.java`
- **Migration:** `migrations/scripts/20250110002500_create_marketplace_messages.sql`
- **Mailpit Docs:** https://github.com/axllent/mailpit
