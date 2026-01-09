# Security & Secret Management

**Status:** Baseline Established (I1.T9)
**Owner:** Ops Team
**Related Policies:** P1 (GDPR/CCPA), P3 (Stripe), P5 (Social Data), P9 (Anonymous Cookies), P14 (Compliance)

---

## Overview

This document establishes the security baseline for Village Homepage, covering secret management, authentication mechanisms, cookie policies, credential rotation procedures, and compliance requirements. All security practices align with VillageCompute Project Standards and the architectural blueprint defined in `.codemachine/artifacts/architecture/01_Blueprint_Foundation.md`.

### Quick Reference

| Security Domain | Key Technologies | Policy Reference |
|-----------------|------------------|------------------|
| Authentication | Quarkus OIDC (Google, Facebook, Apple) | P1, P9 |
| Session Management | JWT + Anonymous Cookies | P9 |
| Secret Storage | Kubernetes Secrets + Sealed Secrets | All |
| Payment Security | Stripe (encrypted at rest) | P3, P14 |
| Social Token Protection | AES-256 Encryption via Vault/KMS | P5, P13 |
| API Key Management | Kubernetes Secrets + Quarterly Rotation | P3, P14 |
| Compliance | GDPR/CCPA Consent + Data Retention | P1, P14 |

---

## 1. Secret Management Strategy

### 1.1 Secret Sources & Hierarchy

Village Homepage uses a **layered configuration model** for secret management:

```
Development (Local)
└─ .env file (git-ignored, manual management)

Production (Kubernetes)
├─ Kubernetes Secrets (base layer, managed via sealed-secrets)
├─ ConfigMaps (non-secret configuration)
└─ Optional: HashiCorp Vault (dynamic secret injection)
```

**Decision Matrix:**

| Secret Type | Development | Production | Rotation Frequency |
|-------------|-------------|------------|-------------------|
| OAuth Credentials | `.env` | `homepage-oauth-credentials` | Quarterly |
| Stripe Keys | `.env` | `homepage-stripe-credentials` | Quarterly |
| External APIs | `.env` | `homepage-external-api-credentials` | Quarterly |
| S3/R2 Credentials | `.env` | `homepage-s3-credentials` | Quarterly |
| JWT Secret | `.env` | `homepage-jwt-credentials` | Annually |
| Bootstrap Token | `.env` | `homepage-bootstrap-credentials` | Single-use |
| Encryption Keys | `.env` | `homepage-encryption-credentials` | Annually |
| Database Password | `.env` | `homepage-database-credentials` | Annually |

### 1.2 Kubernetes Secret Management

All production secrets are managed via **Sealed Secrets** (Bitnami sealed-secrets controller) to enable GitOps workflows without exposing plaintext values.

**Workflow:**

1. **Create Secret Template:** Use `config/secrets-template.yaml` as a starting point
2. **Replace Placeholders:** Fill in real values (never commit to Git)
3. **Seal Secrets:** Transform using sealed-secrets CLI in the `../villagecompute` infrastructure repo
4. **Commit Sealed Secrets:** Safe to commit encrypted sealed-secret manifests to Git
5. **Deploy:** Apply via GitOps pipeline (Flux/ArgoCD)

**Example: Sealing a Secret**

```bash
# In the ../villagecompute infrastructure repository
cd infrastructure/homepage

# Create temporary unsealed secret file (never commit this)
cp ../../code/village-homepage/config/secrets-template.yaml secrets-temp.yaml

# Edit secrets-temp.yaml with real values
vim secrets-temp.yaml

# Seal the secret using the cluster's public key
kubeseal --format=yaml \
  --cert=sealed-secrets-public.pem \
  < secrets-temp.yaml \
  > homepage-oauth-credentials-sealed.yaml

# Safely delete the unsealed secret
shred -u secrets-temp.yaml

# Commit the sealed secret
git add homepage-oauth-credentials-sealed.yaml
git commit -m "chore(secrets): rotate OAuth credentials Q1 2026"
```

### 1.3 Local Development Secrets

**Setup Instructions:**

```bash
# Copy the example environment template
cp .env.example .env

# Edit .env with your development credentials
# NOTE: .env is git-ignored and will never be committed
vim .env

# For OAuth providers, register development applications:
# - Google: https://console.cloud.google.com/
# - Facebook: https://developers.facebook.com/
# - Apple: https://developer.apple.com/

# For external APIs, obtain free developer keys:
# - Alpha Vantage: https://www.alphavantage.co/support/#api-key
# - Stripe: https://dashboard.stripe.com/test/apikeys
# - OpenAI/Anthropic: Provider dashboards
```

**Security Rules for Local Development:**

- ✅ **DO:** Use test/sandbox keys from provider dashboards
- ✅ **DO:** Keep `.env` file permissions restricted (`chmod 600 .env`)
- ✅ **DO:** Use Stripe test keys (`pk_test_*`, `sk_test_*`)
- ❌ **DON'T:** Commit `.env` to Git (automatic git-ignore protection)
- ❌ **DON'T:** Share `.env` via Slack/email (use password managers if needed)
- ❌ **DON'T:** Use production keys in local development

### 1.4 Command Usage Constraints (Section 4)

Section 4 of the operations blueprint mandates **deterministic command usage** for any activity touching secrets or security infrastructure. The guardrails are non-negotiable:

- **No exploratory shelling:** Only run the commands explicitly documented in runbooks (e.g., the sealing workflow above). Ad-hoc `ls`, `cat`, or kubectl commands against sensitive namespaces are prohibited unless there is an incident ticket describing the exact intent.
- **Command logging:** Every manual action must capture `actor`, `timestamp`, `command string`, and the associated change request. Use the shared ops logging template or automation (`ops-tools` wrappers) so that audit trails remain complete.
- **Automation before humans:** Prefer the provided scripts (`kubeseal`, `tools/detect-secrets.cjs`, `node tools/install.cjs`) to manual shell pipelines. These scripts already encode the approved parameters from Section 4 and emit machine-readable logs.
- **Secret scanning gates:** Husky runs `npm run lint:secrets` on every commit to block inadvertent credential leaks locally, and GitHub Actions runs the same command in CI (Step 8 in `.github/workflows/build.yml`). Developers must not bypass these hooks; if a false positive arises, document the exemption in the PR description with the matching SAFE_PLACEHOLDER pattern.
- **Escalation rule:** If a new command is absolutely required (e.g., emerging CVE response), draft an addendum in this doc and obtain Security + Ops approval before executing it so the Section 4 directive stays enforceable.

---

## 2. Authentication & Authorization

### 2.1 OAuth Provider Configuration (P1, P9)

Village Homepage supports **three OAuth providers** via Quarkus OIDC:

| Provider | Scopes Required | User Data Collected |
|----------|----------------|---------------------|
| **Google** | `openid`, `email`, `profile` | Email, name, profile photo |
| **Facebook** | `email`, `public_profile` | Email, name, profile photo |
| **Apple** | `email`, `name` | Email, name (first login only) |

**Callback URLs (Quarkus OIDC):**

- Google: `https://homepage.villagecompute.com/auth/google/callback`
- Facebook: `https://homepage.villagecompute.com/auth/facebook/callback`
- Apple: `https://homepage.villagecompute.com/auth/apple/callback`

**Configuration Mapping (`.env` → Quarkus):**

Multi-tenant OIDC configuration lives in `src/main/resources/application.yaml`. Populate the following environment
variables:

```properties
# Google OAuth
GOOGLE_CLIENT_ID=<google-client-id>
GOOGLE_CLIENT_SECRET=<google-client-secret>

# Facebook OAuth
FACEBOOK_APP_ID=<facebook-app-id>
FACEBOOK_APP_SECRET=<facebook-app-secret>

# Apple Sign-In
APPLE_CLIENT_ID=<apple-client-id>
APPLE_CLIENT_SECRET=<apple-client-secret>

# Cookie flags (Policy P9)
COOKIE_SECURE=true               # Set false only in local dev
COOKIE_DOMAIN=.villagecompute.com

# Bootstrap & JWT
BOOTSTRAP_TOKEN=<64-hex-token>
JWT_SESSION_SECRET=<strong-hs256-secret>
OIDC_STATE_SECRET=<32-byte-cookie-encryption-secret>

# Optional rate limits (defaults already enforce P1/P14)
BOOTSTRAP_MAX_REQUESTS=5
BOOTSTRAP_WINDOW_SECONDS=3600
AUTH_LOGIN_MAX_REQUESTS=20
AUTH_LOGIN_WINDOW_SECONDS=60
```

**YAML Configuration Reference:**

```yaml
quarkus:
  oidc:
    tenant-enabled: true
    authentication:
      redirect-path: /api/auth/callback
    google:
      auth-server-url: https://accounts.google.com
      client-id: ${GOOGLE_CLIENT_ID}
      credentials:
        secret: ${GOOGLE_CLIENT_SECRET}
      authentication:
        scopes: openid,email,profile
    facebook:
      auth-server-url: https://www.facebook.com
      client-id: ${FACEBOOK_APP_ID}
      credentials:
        secret: ${FACEBOOK_APP_SECRET}
      authentication:
        scopes: email,public_profile
    apple:
      auth-server-url: https://appleid.apple.com
      client-id: ${APPLE_CLIENT_ID}
      credentials:
        secret: ${APPLE_CLIENT_SECRET}
      authentication:
        scopes: email,name

villagecompute:
  auth:
    cookie:
      name: vu_anon_id
      secure: ${COOKIE_SECURE:true}
    bootstrap:
      token: ${BOOTSTRAP_TOKEN:}
    jwt:
      secret: ${JWT_SESSION_SECRET:local-dev-secret-change-me}
```

**Login Endpoint Routing:**

- `GET /api/auth/login/{provider}` redirects (302) to Quarkus' internal `/q/oidc/login?tenant=<provider>` endpoint after
  passing rate limits enforced by `AuthIdentityService`/`RateLimitService`.
- The optional `?bootstrap=true` query flag keeps bootstrap context during OAuth flows.

**Security Guardrails:**

- All OAuth callbacks enforce HTTPS in production (configured via `quarkus.http.ssl-port`)
- CSRF protection enabled via Quarkus OIDC state parameter validation
- Token refresh handled automatically; refresh tokens encrypted at rest
- Account merging audit trail logged to `account_merge_audit` table (P1, P14)

### 2.2 Anonymous Account Cookies (P9)

**Cookie Name:** `vu_anon_id`
**Purpose:** Track anonymous users before login (enables widget customization without auth)
**Lifetime:** 1 year (31,536,000 seconds)

**Cookie Attributes (Production):**

```http
Set-Cookie: vu_anon_id=<UUID>;
  HttpOnly;
  Secure;
  SameSite=Lax;
  Path=/;
  Max-Age=31536000
```

**Attribute Enforcement:**

| Attribute | Development | Production | Justification |
|-----------|-------------|------------|---------------|
| `HttpOnly` | ✅ | ✅ | Prevents XSS access to cookie |
| `Secure` | ❌ | ✅ | HTTPS-only in production |
| `SameSite` | `Lax` | `Lax` | CSRF protection, allows OAuth redirects |
| `Domain` | (unset) | `.villagecompute.com` | Subdomain sharing |

**Implementation:**

Anonymous cookie settings are configured in `src/main/resources/application.yaml`:

```yaml
villagecompute:
  auth:
    cookie:
      name: vu_anon_id
      max-age: 31536000  # 1 year in seconds
      secure: ${COOKIE_SECURE:true}
      http-only: true
      same-site: Lax
      domain: ${COOKIE_DOMAIN:}
```

**Service Implementation:**

The `AuthIdentityService` handles cookie generation with proper security attributes:

```java
// Issue anonymous cookie (POST /api/auth/anonymous)
NewCookie cookie = authService.issueAnonymousCookie();
// Returns cookie with HttpOnly, Secure, SameSite=Lax attributes per Policy P9
```

**REST Endpoint:**

```bash
# Issue anonymous cookie
curl -X POST http://localhost:8080/api/auth/anonymous

# Response includes Set-Cookie header with vu_anon_id
```

**Privacy Compliance (P1, P14):**

- Anonymous cookie creation triggers GDPR/CCPA consent modal on first visit
- Consent preferences stored in separate `vu_consent` cookie
- Anonymous account data merged on login (see `account_merge_audit` table)
- Data export endpoint includes anonymous account history (`/api/user/export`)
- Account deletion purges anonymous session data within 90 days (soft delete timer)

### 2.3 JWT Session Management (P9)

**JWT Secret Configuration:**

```bash
# Generate production secret (min 64 characters)
openssl rand -base64 64

# Store in Kubernetes secret
kubectl create secret generic homepage-jwt-credentials \
  --from-literal=jwt-secret='<generated-secret>' \
  --namespace=village-homepage
```

**JWT Claims (Standard Payload):**

```json
{
  "sub": "user-uuid",
  "email": "user@example.com",
  "roles": ["user", "admin"],
  "exp": 1704067200,
  "iat": 1704063600,
  "iss": "village-homepage"
}
```

**Security Policies:**

- Token expiration: 1 hour (access token), 30 days (refresh token)
- Refresh token rotation: New refresh token issued on each use
- Blacklisting: Logout invalidates refresh token in database (`user_sessions` table)
- Rotation impact: Changing `JWT_SECRET` invalidates **all active sessions**

**Emergency Rotation Procedure:**

```bash
# 1. Generate new secret
NEW_SECRET=$(openssl rand -base64 64)

# 2. Update Kubernetes secret (zero-downtime via rolling update)
kubectl patch secret homepage-jwt-credentials \
  --namespace=village-homepage \
  --type=merge \
  -p "{\"data\":{\"jwt-secret\":\"$(echo -n $NEW_SECRET | base64)\"}}"

# 3. Restart pods to pick up new secret (Quarkus live reload)
kubectl rollout restart deployment/homepage --namespace=village-homepage

# 4. Monitor logs for authentication errors
kubectl logs -f deployment/homepage --namespace=village-homepage | grep "JWT"

# 5. Notify users of forced logout (optional: banner message)
```

### 2.4 Bootstrap Superuser Creation (P1)

**Purpose:** Secure first-time admin account creation without hardcoded credentials

**Workflow:**

1. **Generate Bootstrap Token:**
   ```bash
   openssl rand -hex 32
   ```

2. **Store in Kubernetes Secret:**
   ```bash
   kubectl create secret generic homepage-bootstrap-credentials \
     --from-literal=superuser-token='<generated-token>' \
     --namespace=village-homepage
   ```

3. **Access Bootstrap URL (One-Time Use):**
   ```
   https://homepage.villagecompute.com/api/auth/bootstrap?token=<bootstrap-token>
   ```

4. **Create Superuser Account:**
   - Bootstrap page displays OAuth provider links (Google, Facebook, Apple)
   - User clicks provider link to authenticate via `GET /api/auth/login/{provider}?bootstrap=true`
   - After the OAuth callback completes, POST to `/api/auth/bootstrap` with token + email + provider metadata:
     ```bash
     curl -X POST https://homepage.villagecompute.com/api/auth/bootstrap \
       -H 'Content-Type: application/json' \
       -d '{
             "token":"<bootstrap-token>",
             "email":"founder@villagecompute.com",
             "provider":"google",
             "providerUserId":"google-oauth-subject"
           }'
     ```
   - Endpoint returns JSON containing the signed JWT session and expiration timestamp.

5. **Token Invalidation:**
   - Token automatically invalidated after first use (admin existence check)
   - Subsequent access attempts return 403 Forbidden

**Security Constraints:**

- ✅ Token must be exactly 64 hex characters (32 bytes)
- ✅ URL expires after first successful use (403 when admin exists)
- ✅ Rate limited to 5 attempts per IP per hour (via `RateLimitService`)
- ⏳ Audit log entry created on success/failure (TODO: pending Task I2.T2)
- ❌ Token cannot be reused after superuser creation
- ❌ No default admin accounts (violates VillageCompute standards)

**Implementation Status (Task I2.T1):**

✅ **Completed:**
- Multi-tenant OIDC configuration (Google, Facebook, Apple) via `quarkus.oidc.<provider>`
- `/api/auth/login/{provider}` redirects into Quarkus `/q/oidc/login?tenant=...` with rate limiting + logging
- `AuthIdentityService` issues secure `vu_anon_id` cookies and enforces bootstrap guard (403 after first admin)
- JWT session token minted during bootstrap completion using HS256 secret from `JWT_SESSION_SECRET`
- `RateLimitService` enforces sliding windows for login + bootstrap (no longer stubbed) with violation logging
- Dev/test configuration + Quarkus tests validating cookie + bootstrap guard

⏳ **Pending (Task I2.T2):**
- Panache-backed admin existence checks instead of in-memory flag
- OAuth provider callback handling + account merge logic
- Persistent audit log + Kubernetes secret invalidation after bootstrap

---

## 3. Payment Security (Stripe) (P3, P14)

### 3.1 Stripe Key Management

**Key Types:**

| Key Type | Prefix | Storage Location | Usage |
|----------|--------|------------------|-------|
| Publishable Key | `pk_live_*` / `pk_test_*` | Client-side (public) | JavaScript Stripe.js library |
| Secret Key | `sk_live_*` / `sk_test_*` | Server-side (secret) | Backend API calls |
| Webhook Secret | `whsec_*` | Server-side (secret) | Webhook signature verification |

**Production Configuration:**

```yaml
# Kubernetes secret: homepage-stripe-credentials
apiVersion: v1
kind: Secret
metadata:
  name: homepage-stripe-credentials
stringData:
  publishable-key: "pk_live_CHANGE_ME"
  secret-key: "sk_live_CHANGE_ME"
  webhook-secret: "whsec_CHANGE_ME"
```

**Quarkus Mapping:**

```properties
STRIPE_PUBLISHABLE_KEY=${STRIPE_PUBLISHABLE_KEY}
STRIPE_SECRET_KEY=${STRIPE_SECRET_KEY}
STRIPE_WEBHOOK_SECRET=${STRIPE_WEBHOOK_SECRET}
```

### 3.2 Stripe Secret Encryption at Rest (P3, P14)

**Requirement:** Stripe customer payment methods and subscription tokens stored in the database **MUST be encrypted** using AES-256.

**Encryption Key Setup:**

```bash
# Generate 256-bit AES key (32 bytes = 64 hex characters)
openssl rand -hex 32

# Store in Kubernetes secret
kubectl create secret generic homepage-encryption-credentials \
  --from-literal=encryption-key='<generated-key>' \
  --namespace=village-homepage
```

**Database Schema (Example):**

```sql
CREATE TABLE stripe_customers (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    stripe_customer_id VARCHAR(255) NOT NULL,
    -- Encrypted payment method token
    payment_method_encrypted BYTEA,
    -- Encryption metadata
    encryption_key_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

**Encryption Implementation (Java):**

```java
// See VillageCompute Project Standards for full implementation
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class StripeTokenEncryption {
    private static final String ALGORITHM = "AES/GCM/NoPadding";

    public byte[] encrypt(String plaintext, String keyHex) {
        SecretKeySpec key = new SecretKeySpec(
            hexToBytes(keyHex), "AES"
        );
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
    }

    // Decryption and helper methods omitted for brevity
}
```

**Key Rotation Procedure:**

1. Generate new encryption key (keep old key accessible)
2. Update `homepage-encryption-credentials` with `encryption-key-v2`
3. Deploy code changes to support dual-key decryption
4. Background job re-encrypts all records with new key
5. Remove old key after migration completes (verify no `encryption_key_version=1` records remain)

### 3.3 Webhook Security

**Stripe Webhook Endpoint:** `https://homepage.villagecompute.com/api/webhooks/stripe`

**Signature Verification (Required):**

```java
import com.stripe.net.Webhook;

public void handleWebhook(String payload, String sigHeader) {
    try {
        Event event = Webhook.constructEvent(
            payload,
            sigHeader,
            stripeWebhookSecret
        );
        // Process event...
    } catch (SignatureVerificationException e) {
        // Reject webhook
        throw new UnauthorizedException("Invalid webhook signature");
    }
}
```

**Security Checklist:**

- ✅ Verify `Stripe-Signature` header on all webhook requests
- ✅ Reject webhooks with invalid signatures (return 400)
- ✅ Idempotency: Track `event.id` to prevent duplicate processing
- ✅ Rate limit webhook endpoint (100 req/min per IP)
- ✅ Log all webhook events to audit trail

---

## 4. Social Media Integration Security (P5, P13)

### 4.1 Meta Graph API Credentials

**Tokens Stored in Database:**

- **User Access Tokens:** Short-lived (1 hour), refreshed automatically
- **Long-Lived Tokens:** 60-day expiration, encrypted at rest
- **Page Access Tokens:** Never expire (encrypted at rest)

**Encryption Requirement (P5, P13):**

> "Social tokens and Stripe secrets \[must be\] encrypted with Quarkus Vault or KMS-backed secret engine; never log tokens even at debug level."
> — Blueprint Section 3.2

**Implementation:**

```sql
CREATE TABLE social_accounts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    provider VARCHAR(50) NOT NULL, -- 'instagram', 'facebook'
    -- Encrypted access token
    access_token_encrypted BYTEA NOT NULL,
    refresh_token_encrypted BYTEA,
    encryption_key_version INTEGER NOT NULL DEFAULT 1,
    token_expires_at TIMESTAMPTZ,
    last_synced_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

**Encryption Keys:** Use same `SECRET_ENCRYPTION_KEY` as Stripe (see Section 3.2)

### 4.2 Token Refresh & Rotation

**Background Job:** `SocialTokenRefreshJob` (runs every 30 minutes)

**Logic:**

1. Query `social_accounts` where `token_expires_at < NOW() + INTERVAL '1 day'`
2. Decrypt access token using `SECRET_ENCRYPTION_KEY`
3. Call Meta Graph API `/oauth/access_token` with refresh token
4. Encrypt and store new access token
5. Update `token_expires_at` and `last_synced_at`

**Security Rules:**

- ❌ **NEVER** log decrypted tokens (not even at `TRACE` level)
- ✅ Redact tokens in exception stack traces (`token=***REDACTED***`)
- ✅ Use separate database connection pool for encryption operations (prevent query logging)

### 4.3 Data Retention (P5, P13)

**Policy:** Social media data retained **indefinitely** (per blueprint P5/P13)

**Compliance Integration:**

- GDPR export: Include encrypted social tokens in `/api/user/export` (decrypt on-demand)
- GDPR deletion: Purge `social_accounts` and related `social_feed_items` on account deletion
- Soft delete: 90-day grace period before permanent purge

---

## 5. External API Key Management

### 5.1 API Key Inventory

| API Provider | Key Type | Rotation Frequency | Kubernetes Secret Key |
|--------------|----------|-------------------|----------------------|
| **Alpha Vantage** | API Key | Quarterly | `alpha-vantage-api-key` |
| **Meta Graph API** | App Secret | Quarterly | `meta-app-secret` |
| **LangChain4j (AI)** | Provider-specific | Quarterly | `langchain4j-api-key` |
| **Cloudflare R2** | S3 Access Key | Quarterly | `s3-access-key`, `s3-secret-key` |

### 5.2 Quarterly Rotation Playbook

**Schedule:** January 1, April 1, July 1, October 1 (or within 2 weeks)

**Procedure (Example: Alpha Vantage):**

```bash
# 1. Obtain new API key from provider dashboard
# https://www.alphavantage.co/support/#api-key

# 2. Update Kubernetes secret (zero-downtime)
kubectl patch secret homepage-external-api-credentials \
  --namespace=village-homepage \
  --type=merge \
  -p '{"data":{"alpha-vantage-api-key":"'$(echo -n "NEW_KEY" | base64)'"}}'

# 3. Verify new key works (test API call)
kubectl exec -it deployment/homepage --namespace=village-homepage -- \
  curl "https://www.alphavantage.co/query?function=TIME_SERIES_INTRADAY&symbol=IBM&interval=5min&apikey=NEW_KEY"

# 4. Monitor error rates for 24 hours
# (Check Grafana dashboard for API errors)

# 5. Revoke old key from provider dashboard
# (Wait 24 hours after deployment to ensure no issues)
```

### 5.3 Emergency Rotation (Leaked Credentials)

**Trigger Conditions:**

- Key exposed in public Git repository
- Key found in logs or error messages
- Unauthorized API usage detected
- Security vulnerability disclosure

**Emergency Procedure (< 1 hour):**

1. **Immediate:** Revoke compromised key from provider dashboard
2. **Generate:** Obtain new key from provider
3. **Deploy:** Update Kubernetes secret (emergency change, bypass GitOps if needed)
4. **Restart:** Rolling restart of application pods
5. **Verify:** Confirm services operational with new key
6. **Audit:** Investigate how leak occurred, update `.gitignore` / secret scanning rules
7. **Document:** Create incident report in `docs/ops/incidents/`

---

## 6. Secret Leak Prevention

### 6.1 Git Pre-Commit Hook (Local Development)

**Detection Tool:** `tools/detect-secrets.cjs`

**Patterns Detected:**

- AWS/S3 access keys (`AKIA[0-9A-Z]{16}`)
- Private keys (`-----BEGIN.*PRIVATE KEY-----`)
- High-entropy strings (Base64 > 40 chars, Hex > 64 chars)
- Known secret prefixes (`sk_live_`, `pk_live_`, `whsec_`, etc.)
- Hardcoded passwords (`password=`, `secret=`, `token=`)

**Installation (Automatic):**

```bash
# Runs during dependency installation
npm install
# OR
node tools/install.cjs
```

**Manual Invocation:**

```bash
# Scan all tracked files
npm run lint:secrets

# Scan specific file
node tools/detect-secrets.cjs src/main/resources/application.properties
```

**Bypass (Emergency Only):**

```bash
# Use with caution - requires justification in commit message
git commit --no-verify -m "chore: add public test key (safe to commit)"
```

### 6.2 CI Pipeline Secret Scanning

**GitHub Actions Workflow:** `.github/workflows/build.yml`

**Step Configuration:**

```yaml
- name: Scan for secrets
  run: npm run lint:secrets
  # Fails build if secrets detected
```

**Enforcement Policy:**

- ❌ CI build **FAILS** if secrets detected (blocks PR merge)
- ✅ False positives: Add to `.secretsignore` (requires security review approval)
- ✅ Test data: Use placeholder strings (`CHANGE_ME`, `YOUR_KEY_HERE`)

### 6.3 False Positive Management

**File:** `.secretsignore` (gitignored, local only)

**Example:**

```
# Safe test fixtures with high entropy
src/test/resources/fixtures/sample-encrypted-token.txt

# Known public keys (safe to commit)
config/public-keys/*.pem
```

**Review Process:**

1. Developer adds file to `.secretsignore`
2. Security review in PR (tag `@security-team`)
3. Approval required before merge

---

## 7. Compliance & Data Governance (P1, P14)

### 7.1 GDPR/CCPA Consent Management (P1)

**Consent Modal (First Visit):**

- Triggered on first page load (if `vu_consent` cookie absent)
- Collects consent for: Analytics, Marketing, Personalization
- Stored in `vu_consent` cookie (1-year expiration)

**Consent Storage:**

```sql
CREATE TABLE user_consents (
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(id), -- NULL for anonymous
    anonymous_id UUID, -- References anonymous account
    analytics_consent BOOLEAN NOT NULL,
    marketing_consent BOOLEAN NOT NULL,
    personalization_consent BOOLEAN NOT NULL,
    consented_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ip_address INET NOT NULL,
    user_agent TEXT NOT NULL
);
```

**Consent Withdrawal:**

- User settings page: `/settings/privacy`
- Updates `user_consents` record and cookie
- Immediate effect: Disables analytics tracking, personalization features

### 7.2 Data Export (P1, P14)

**Endpoint:** `GET /api/user/export`

**Response Format:** JSON (ZIP archive for large datasets)

**Included Data:**

- User profile (email, name, OAuth providers)
- Anonymous account history (merged sessions)
- Widget layouts and preferences
- Marketplace listings and messages
- Voting history (Good Sites directory)
- Audit logs (feature flag evaluations, admin actions)
- Social account metadata (tokens excluded, encrypted references included)

**Retention:** Export files auto-deleted after 7 days

### 7.3 Data Deletion (P1, P14)

**Endpoint:** `DELETE /api/user/account`

**Process:**

1. **Soft Delete:** Set `users.deleted_at = NOW()`
2. **Grace Period:** 90 days before permanent purge
3. **Scheduled Cleanup:** `UserDataPurgeJob` (daily)
4. **Cascade Deletions:**
   - Widget layouts
   - Marketplace listings (anonymize author)
   - Social accounts (decrypt and revoke tokens from Meta)
   - Audit logs (retain for 90 days per blueprint)
   - Feature flag evaluations (immediate purge)

**Audit Trail:**

```sql
INSERT INTO account_deletion_audit (
    user_id,
    deleted_at,
    purge_scheduled_at,
    deletion_reason,
    initiated_by
) VALUES (
    '...',
    NOW(),
    NOW() + INTERVAL '90 days',
    'User-initiated',
    '...'
);
```

### 7.4 Data Retention Windows (P4, P5, P13, P14)

**Configuration (`.env`):**

```properties
# Audit logs (90 days per blueprint)
AUDIT_LOG_RETENTION_DAYS=90

# Screenshots (indefinite per P4)
SCREENSHOT_RETENTION_DAYS=0

# Social data (indefinite per P5/P13)
SOCIAL_DATA_RETENTION_DAYS=0

# Soft deletes (90 days per blueprint)
SOFT_DELETE_PURGE_DAYS=90
```

**Cleanup Jobs:**

| Job | Schedule | Retention Rule |
|-----|----------|---------------|
| `AuditLogCleanupJob` | Daily 2:00 AM | DELETE WHERE created_at < NOW() - INTERVAL '90 days' |
| `ScreenshotCleanupJob` | Weekly | Disabled (indefinite retention) |
| `SocialDataCleanupJob` | Weekly | Disabled (indefinite retention) |
| `SoftDeletePurgeJob` | Daily 3:00 AM | DELETE WHERE deleted_at < NOW() - INTERVAL '90 days' |

---

## 8. Security Hardening Guidelines (Section 3.2)

### 8.1 Input Sanitization

**User-Generated Content (XSS Prevention):**

| Input Source | Sanitizer | Context |
|--------------|----------|---------|
| Marketplace descriptions | OWASP Java HTML Sanitizer | Before persistence + before rendering |
| Good Sites submissions | URL validation + domain allowlist | Prevent phishing/malware links |
| User profiles (bio) | OWASP Java HTML Sanitizer | Allow limited Markdown |
| Search queries | Parameterized SQL (MyBatis) | Prevent SQL injection |

**Sanitization Flow:**

```
User Input → Backend Validation → Sanitization → Database Storage
                ↓
            (Later)
                ↓
Database Retrieval → Re-Sanitization → Render to HTML (Qute Templates)
```

**Example (Java):**

```java
import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;

public class ContentSanitizer {
    private static final PolicyFactory POLICY =
        Sanitizers.FORMATTING
            .and(Sanitizers.LINKS)
            .and(Sanitizers.BLOCKS);

    public static String sanitize(String untrustedHTML) {
        return POLICY.sanitize(untrustedHTML);
    }
}
```

### 8.2 Rate Limiting (P14)

**Implementation:** `RateLimitService` (combined user_id/IP/user_agent keys)

**Limits (Configurable):**

| Endpoint | Limit | Window | Violation Action |
|----------|-------|--------|-----------------|
| `/api/vote` | 100 req | 1 hour | 429 Too Many Requests |
| `/api/listings` (POST) | 10 req | 1 hour | 429 + temporary ban (1 hour) |
| `/auth/login` | 5 req | 15 min | 429 + temporary ban (15 min) |
| `/admin/bootstrap` | 5 req | 1 hour | 429 + IP ban (24 hours) |
| `/api/webhooks/stripe` | 100 req | 1 min | 429 (no ban, legitimate traffic) |

**Rate Limit Storage:** Redis (in-memory, TTL-based)

**HMAC Token Signing (Prevent Tampering):**

```java
// Rate limit token structure: user_id:timestamp:hmac
String token = userId + ":" + timestamp + ":" +
    hmac(userId + timestamp, RATE_LIMIT_HMAC_SECRET);
```

### 8.3 Audit Trails (P14)

**Audit Events (Required):**

- Feature flag changes (admin dashboard)
- Admin impersonations (`@Impersonate` annotation)
- Payment refunds (Stripe integration)
- Karma adjustments (Good Sites moderation)
- Secret rotations (Kubernetes secret updates)

**Schema:**

```sql
CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL, -- 'feature_flag_change', 'admin_impersonate', etc.
    actor_id UUID REFERENCES users(id),
    target_id UUID, -- Entity affected (user, listing, etc.)
    reason TEXT,
    metadata JSONB, -- Event-specific details
    ip_address INET,
    user_agent TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_events_type ON audit_events(event_type);
CREATE INDEX idx_audit_events_actor ON audit_events(actor_id);
CREATE INDEX idx_audit_events_created ON audit_events(created_at);
```

**Retention:** 90 days (see Section 7.4)

---

## 9. Emergency Procedures

### 9.1 Compromised Secret Response

**Decision Tree:**

```
Secret Compromised?
├─ Yes, High Impact (OAuth, Stripe, JWT)
│   └─ Emergency Rotation (< 1 hour, see Section 5.3)
├─ Yes, Medium Impact (External APIs)
│   └─ Scheduled Rotation (< 24 hours)
└─ No, False Alarm
    └─ Document in security review log
```

### 9.2 Data Breach Response (P1, P14)

**Incident Response Plan:**

1. **Containment (0-2 hours):**
   - Identify scope of breach (which tables/users affected)
   - Revoke access tokens for affected users
   - Enable maintenance mode if needed

2. **Notification (< 72 hours, GDPR requirement):**
   - Email affected users with breach details
   - Notify data protection authority (if EU users affected)
   - Post public incident report (transparency)

3. **Remediation:**
   - Patch vulnerability (emergency deploy)
   - Rotate all secrets (even if not directly compromised)
   - Force password reset for affected users

4. **Post-Incident Review:**
   - Root cause analysis
   - Update runbooks / security policies
   - Schedule external security audit

---

## 10. Security Review Checklist

**Pre-Deployment (Production):**

- [ ] All secrets stored in Kubernetes secrets (no hardcoded values)
- [ ] Sealed secrets committed to GitOps repository
- [ ] `.env.example` updated with new variables (no real secrets)
- [ ] Secret scanning passes in CI pipeline
- [ ] OAuth callback URLs updated in provider dashboards
- [ ] Stripe webhook endpoint configured with correct `whsec_*`
- [ ] JWT secret rotated from development default
- [ ] Bootstrap token generated and stored in Kubernetes secret
- [ ] Encryption keys generated (AES-256 for Stripe/social tokens)
- [ ] Cookie attributes enforced (`Secure=true`, `HttpOnly=true`)
- [ ] Rate limiting enabled and tested
- [ ] Audit logging verified for all high-risk actions
- [ ] GDPR consent modal displays on first visit
- [ ] Data export endpoint functional
- [ ] Data deletion triggers soft delete (90-day purge timer)

**Quarterly Rotation (Ops Team):**

- [ ] Alpha Vantage API key rotated
- [ ] Meta Graph API secret rotated
- [ ] LangChain4j provider key rotated
- [ ] Cloudflare R2 access keys rotated
- [ ] Google OAuth secret rotated
- [ ] Facebook OAuth secret rotated
- [ ] Apple Sign-In key rotated
- [ ] Stripe keys reviewed (rotate if expiring)
- [ ] All rotation audit logs reviewed

**Annual Rotation:**

- [ ] JWT secret rotated (force all user logouts)
- [ ] Database encryption key rotated (background migration)
- [ ] Rate limit HMAC secret rotated

---

## 11. Additional Resources

**Internal Documentation:**

- [`docs/ops/dev-services.md`](./dev-services.md) - Local development environment setup
- [`docs/ops/ci-cd-pipeline.md`](./ci-cd-pipeline.md) - CI/CD security gates
- [`docs/ops/async-workloads.md`](./async-workloads.md) - Background job security considerations
- [`../villagecompute/infrastructure/`](../../../villagecompute/infrastructure/) - Kubernetes manifests

**External Standards:**

- [VillageCompute Java Project Standards](../../village-storefront/docs/java-project-standards.adoc)
- [OWASP Top 10 (2021)](https://owasp.org/www-project-top-ten/)
- [CWE/SANS Top 25 Software Errors](https://cwe.mitre.org/top25/)
- [GDPR Compliance Guide](https://gdpr.eu/)
- [Stripe Security Best Practices](https://stripe.com/docs/security/guide)

**Contact:**

- **Security Incidents:** `security@villagecompute.com`
- **Ops Team (Rotations):** `ops@villagecompute.com`
- **Compliance Questions:** `compliance@villagecompute.com`

---

**Document Version:** 1.0
**Last Updated:** 2026-01-08
**Next Review:** 2026-04-08 (Quarterly)
