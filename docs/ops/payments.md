# Payment System Operations Guide

This document provides operational guidance for the Village Homepage marketplace payment system integrated with Stripe per Policy P3 and Feature F12.8.

## Overview

The payment system handles:
- **Posting fees** for select categories (Housing, Jobs, Vehicles)
- **Promotions** (featured listings and bumps)
- **Refunds** (automatic within 24h, manual admin review after 24h)
- **Chargebacks** (dispute tracking and user bans at 2+ chargebacks)

## Architecture

### Components

1. **StripeClient** - HTTP client for Stripe API (Payment Intents, Refunds)
2. **StripeWebhookVerifier** - HMAC-SHA256 signature verification
3. **PaymentService** - Business logic orchestration
4. **MarketplacePaymentResource** - User-facing REST endpoints
5. **StripeWebhookResource** - Webhook event handler
6. **PaymentAdminResource** - Admin refund management
7. **PromotionExpirationJobHandler** - Daily job to clean up expired featured promotions

### Database Schema

**marketplace_listings**:
- `payment_intent_id` - Stripe Payment Intent ID for correlation
- `status` - Includes 'pending_payment' for unpaid listings

**listing_promotions**:
- Tracks featured promotions (7 days) and bumps (instant)
- `stripe_payment_intent_id` - Unique constraint for idempotency

**payment_refunds**:
- Tracks all refunds through lifecycle: pending → approved/rejected → processed
- `reason` - technical_failure, moderation_rejection, user_request, chargeback
- `status` - pending, approved, rejected, processed

## Configuration

### Required Config Properties

```properties
# Stripe API credentials (sourced from Kubernetes secret in production)
stripe.secret-key=sk_live_...
stripe.webhook-secret=whsec_...
```

### Kubernetes Secrets

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: homepage-stripe-credentials
  namespace: village-homepage
type: Opaque
stringData:
  secret-key: "sk_live_CHANGE_ME"
  webhook-secret: "whsec_CHANGE_ME"
```

## Payment Workflows

### 1. Posting Fee Payment

**Flow**:
1. User creates listing in category with posting_fee > $0
2. Listing status set to 'pending_payment'
3. User calls `POST /api/marketplace/listings/{id}/checkout`
4. Backend creates Stripe Payment Intent, returns `client_secret`
5. Frontend uses Stripe.js to collect payment details and confirm payment
6. Stripe sends `payment_intent.succeeded` webhook
7. Backend transitions listing status to 'active', sets `expires_at`

**Categories with Posting Fees**:
- Housing: $5
- Jobs: $10
- Cars & Trucks: $5
- Motorcycles: $3
- RVs & Campers: $5
- Boats: $5

### 2. Listing Promotions

**Featured Promotion** ($5 for 7 days):
1. User calls `POST /api/marketplace/listings/{id}/promote` with `{"type": "featured"}`
2. Backend validates listing is active, creates Payment Intent
3. After payment success, creates `listing_promotions` record with expires_at = NOW() + 7 days
4. Promotion automatically expires after 7 days (cleaned up by PromotionExpirationJobHandler)

**Bump Promotion** ($2 per bump, max 1/24h):
1. User calls `POST /api/marketplace/listings/{id}/promote` with `{"type": "bump"}`
2. Backend validates no bump within last 24h, creates Payment Intent
3. After payment success, creates `listing_promotions` record and updates listing.last_bumped_at
4. Listing appears at top of chronological order

### 3. Refund Workflows

**Automatic Refunds** (within 24h):
- **Technical failures**: System error during listing publication
- **Moderation rejections**: Listing rejected within 24h of payment
- PaymentService.processAutomaticRefund() creates Stripe refund immediately
- Refund record created with status='processed'

**Manual Refunds** (after 24h):
1. User requests refund (future feature: user-facing form)
2. Refund record created with status='pending'
3. Admin reviews via `GET /admin/api/marketplace/refunds`
4. Admin approves: `POST /admin/api/marketplace/refunds/{id}/approve`
   - Backend calls Stripe API to process refund
   - Status transitions: pending → approved → processed
5. Admin rejects: `POST /admin/api/marketplace/refunds/{id}/reject`
   - Status transitions: pending → rejected
   - User notified (future feature: email notification)

### 4. Chargeback Handling

**Flow**:
1. Customer disputes charge with credit card company
2. Stripe sends `charge.dispute.created` webhook
3. Backend creates refund record with reason='chargeback', status='processed'
4. Backend counts chargebacks for user
5. If user has 2+ chargebacks, ban user (Policy P3)

**Dispute Evidence**:
- All Payment Intent metadata is stored (listing_id, user_id, category_id, payment_type)
- Listing details retained for 90 days (soft-delete policy)
- Evidence can be submitted via Stripe Dashboard for dispute resolution

## Webhook Processing

### Endpoint

```
POST /webhooks/stripe
Header: Stripe-Signature
Content-Type: application/json
```

### Supported Event Types

| Event Type | Handler | Description |
|------------|---------|-------------|
| payment_intent.succeeded | PaymentService.handlePaymentSuccess() | Payment completed, activate listing or create promotion |
| payment_intent.payment_failed | PaymentService.handlePaymentFailure() | Payment failed, log and increment metrics |
| charge.refunded | PaymentService.handleRefund() | Refund processed, update refund record |
| charge.dispute.created | PaymentService.processChargeback() | Chargeback initiated, record and check ban threshold |

### Security

**CRITICAL**: All webhook payloads MUST be verified using HMAC-SHA256 signature to prevent spoofing attacks.

Verification algorithm:
1. Extract timestamp and v1 signature from `Stripe-Signature` header
2. Construct signed payload: `timestamp + "." + raw_body`
3. Compute HMAC-SHA256 using webhook secret
4. Constant-time comparison with v1 signature
5. Check timestamp is within 5 minutes (prevent replay attacks)

### Testing Webhooks

**Stripe CLI**:
```bash
# Install Stripe CLI
brew install stripe/stripe-cli/stripe

# Login
stripe login

# Forward webhooks to local dev server
stripe listen --forward-to http://localhost:8080/webhooks/stripe

# Trigger test event
stripe trigger payment_intent.succeeded
```

**Manual Testing**:
```bash
# Create Payment Intent
curl -X POST https://api.stripe.com/v1/payment_intents \
  -u sk_test_...: \
  -d "amount=500" \
  -d "currency=usd" \
  -d "metadata[listing_id]=550e8400-e29b-41d4-a716-446655440000" \
  -d "metadata[user_id]=660f9500-f3ac-52e5-b827-557766551111" \
  -d "metadata[payment_type]=posting_fee"
```

## Monitoring

### Metrics

**Payment Metrics** (Prometheus endpoint: `/q/metrics`):

```
# Payment Intent creation
homepage_payments_intents_created_total{type="posting_fee"} 42
homepage_payments_intents_created_total{type="promotion_featured"} 15
homepage_payments_intents_created_total{type="promotion_bump"} 87

# Payment outcomes
homepage_payments_succeeded_total{type="posting_fee"} 40
homepage_payments_failed_total{type="posting_fee"} 2

# Refunds
homepage_refunds_processed_total 5

# Webhooks
homepage_webhooks_received_total{event_type="payment_intent.succeeded"} 40
homepage_webhooks_received_total{event_type="payment_intent.payment_failed"} 2
```

### Alerts

**Recommended Grafana alerts**:

1. **Payment Success Rate < 95%**:
   ```promql
   (sum(rate(homepage_payments_succeeded_total[5m])) /
    sum(rate(homepage_payments_intents_created_total[5m]))) < 0.95
   ```

2. **Webhook Delivery Failures**:
   - Monitor Stripe Dashboard for failed webhook deliveries
   - Stripe retries webhooks up to 3 days with exponential backoff

3. **Refund Rate > 10%**:
   ```promql
   (sum(rate(homepage_refunds_processed_total[1h])) /
    sum(rate(homepage_payments_succeeded_total[1h]))) > 0.10
   ```

### Logs

**Payment Event Logs**:
```
# Payment Intent created
INFO  Creating posting payment: listingId=..., userId=..., paymentIntent=pi_...

# Webhook received
INFO  Processing Stripe webhook: eventId=evt_..., type=payment_intent.succeeded

# Listing activated
INFO  Activated listing after payment: listingId=..., expiresAt=...

# Refund processed
INFO  Processed automatic refund: listingId=..., refundId=re_..., amount=500 cents

# Chargeback detected
WARN  Processing chargeback: paymentIntent=pi_..., userId=..., chargebackCount=2
```

## Troubleshooting

### Issue: Payment Intent created but listing still pending_payment

**Diagnosis**:
1. Check if webhook was delivered: Stripe Dashboard → Webhooks → View logs
2. Check webhook signature verification: Search logs for "Invalid Stripe webhook signature"
3. Check payment status: `stripe payment_intents retrieve pi_...`

**Resolution**:
- If webhook failed, manually trigger webhook via Stripe Dashboard
- If signature invalid, verify webhook secret in Kubernetes secret matches Stripe Dashboard
- If payment incomplete, user needs to retry payment

### Issue: Refund not processed

**Diagnosis**:
1. Query refund record: `SELECT * FROM payment_refunds WHERE id = '...'`
2. Check Stripe Dashboard → Payments → Refunds
3. Check logs for Stripe API errors

**Resolution**:
- If status='pending', admin needs to approve/reject
- If status='approved' but no stripe_refund_id, Stripe API call failed - retry via admin endpoint
- If Stripe refund succeeded but status not updated, manually update: `UPDATE payment_refunds SET status='processed', stripe_refund_id='re_...' WHERE id='...'`

### Issue: User banned incorrectly for chargebacks

**Diagnosis**:
1. Query chargeback count: `SELECT COUNT(*) FROM payment_refunds WHERE user_id='...' AND reason='chargeback'`
2. Check Stripe Dashboard → Disputes for dispute details

**Resolution**:
- If dispute was resolved in our favor, create credit for user
- If chargeback count incorrect, investigate duplicate webhook processing

### Issue: Featured promotion not expiring

**Diagnosis**:
1. Check PromotionExpirationJobHandler execution: Search logs for "Promotion expiration job"
2. Query expired promotions: `SELECT * FROM listing_promotions WHERE type='featured' AND expires_at <= NOW()`

**Resolution**:
- If job not running, check scheduler configuration
- If promotions found but not deleted, manually run job: `PromotionExpirationJobHandler.handle(null)`

## Operational Procedures

### Rolling Restart (Zero Downtime)

1. Stripe webhooks have automatic retry with exponential backoff (up to 3 days)
2. Rolling restart window should be < 10 minutes to avoid webhook timeout
3. No special procedures needed - Stripe will retry failed webhooks

### Webhook Secret Rotation

1. Create new webhook endpoint in Stripe Dashboard
2. Copy new webhook secret
3. Update Kubernetes secret: `kubectl edit secret homepage-stripe-credentials -n village-homepage`
4. Rollout deployment: `kubectl rollout restart deployment homepage -n village-homepage`
5. Wait for rollout completion: `kubectl rollout status deployment homepage -n village-homepage`
6. Delete old webhook endpoint in Stripe Dashboard
7. Monitor logs for webhook signature verification errors

### Backfilling Missing Promotions

If webhook processing failed and promotions were not created:

```sql
-- Find payments with missing promotions
SELECT
  ml.id AS listing_id,
  ml.payment_intent_id,
  ml.created_at
FROM marketplace_listings ml
WHERE ml.status = 'active'
  AND ml.payment_intent_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM listing_promotions lp
    WHERE lp.stripe_payment_intent_id = ml.payment_intent_id
  );

-- Manually create promotion record
INSERT INTO listing_promotions (id, listing_id, type, stripe_payment_intent_id, amount_cents, starts_at, expires_at, created_at, updated_at)
VALUES (
  gen_random_uuid(),
  '550e8400-e29b-41d4-a716-446655440000',
  'featured',
  'pi_1Ab2Cd3Ef4Gh5678',
  500,
  NOW(),
  NOW() + INTERVAL '7 days',
  NOW(),
  NOW()
);
```

## Database Maintenance

### Retention Policy

- **payment_refunds**: Retain indefinitely for audit/compliance
- **listing_promotions**: Expired featured promotions deleted daily by job
- **marketplace_listings.payment_intent_id**: Retain for 90 days after soft-delete

### Partition Management

If payment_refunds table grows large (>10M rows), consider partitioning by created_at:

```sql
-- Convert to partitioned table (requires maintenance window)
CREATE TABLE payment_refunds_new (LIKE payment_refunds INCLUDING ALL)
PARTITION BY RANGE (created_at);

-- Create partitions
CREATE TABLE payment_refunds_2025_01 PARTITION OF payment_refunds_new
  FOR VALUES FROM ('2025-01-01') TO ('2025-02-01');

-- Migrate data
INSERT INTO payment_refunds_new SELECT * FROM payment_refunds;

-- Swap tables
ALTER TABLE payment_refunds RENAME TO payment_refunds_old;
ALTER TABLE payment_refunds_new RENAME TO payment_refunds;

-- Verify
SELECT COUNT(*) FROM payment_refunds;
SELECT COUNT(*) FROM payment_refunds_old;

-- Drop old table after verification
DROP TABLE payment_refunds_old;
```

## Disaster Recovery

### Stripe Data as Source of Truth

Stripe retains all payment data permanently. In case of database corruption:

1. Query Stripe API for all Payment Intents: `stripe payment_intents list --limit 100`
2. For each Payment Intent, extract metadata: listing_id, user_id, payment_type
3. Reconcile with database:
   - If listing missing payment_intent_id, update listing
   - If promotion missing, create promotion record
   - If refund missing, create refund record (if Stripe refund exists)

### Backup Strategy

- **Database**: Daily automated backups (RDS/PostgreSQL)
- **Stripe**: No backup needed (Stripe is authoritative source)
- **Secrets**: Stored in sealed-secrets repo (Git)

## Security Considerations

### PCI Compliance

- **Scope**: OUT OF SCOPE (no card data stored or processed on our servers)
- **Stripe.js**: All payment details collected via Stripe.js (client-side)
- **client_secret**: Transmitted over HTTPS, not logged, expires after use

### Secrets Management

- **Stripe secret key**: Stored in Kubernetes secret, rotated annually
- **Webhook secret**: Stored in Kubernetes secret, rotated on compromise
- **Access control**: Only super_admin role can approve/reject refunds

### Fraud Prevention

Per Policy P3:
- **Chargeback tracking**: Users with 2+ chargebacks are banned
- **AI-assisted fraud detection**: Future feature (analyze listing content for scam patterns)
- **Rate limiting**: Payment endpoints rate-limited to prevent abuse

## Support Escalation

### Stripe Support

- **Support Portal**: https://support.stripe.com
- **API Issues**: File ticket with API request ID from logs
- **Disputes**: Use Stripe Dashboard → Disputes to submit evidence

### Internal Escalation

- **Payment failures**: Ops team (check logs, Stripe Dashboard)
- **Refund policy questions**: Support team + product owner
- **Security incidents**: Security team (immediate webhook secret rotation)

## Appendix: Stripe API Reference

### Create Payment Intent

```bash
curl -X POST https://api.stripe.com/v1/payment_intents \
  -u sk_live_...: \
  -d "amount=500" \
  -d "currency=usd" \
  -d "automatic_payment_methods[enabled]=true" \
  -d "metadata[listing_id]=..." \
  -d "metadata[user_id]=..."
```

### Create Refund

```bash
curl -X POST https://api.stripe.com/v1/refunds \
  -u sk_live_...: \
  -d "payment_intent=pi_..." \
  -d "reason=requested_by_customer"
```

### Retrieve Payment Intent

```bash
curl https://api.stripe.com/v1/payment_intents/pi_... \
  -u sk_live_...:
```

## Contact

- **Ops Team**: ops@villagecompute.com
- **On-Call**: PagerDuty rotation (payment-system-oncall)
- **Documentation**: https://github.com/VillageCompute/village-homepage/tree/main/docs/ops
