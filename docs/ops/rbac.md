# Role-Based Access Control (RBAC)

This document describes the role-based access control system for Village Homepage admin operations.

## Overview

Village Homepage implements a four-tier admin role system that mirrors the `village-storefront` constants per Foundation Blueprint Section 3.7.1. All admin actions are logged for audit and compliance purposes.

The RBAC system controls access to:
- Admin APIs for system configuration
- Feature flag management
- Rate limit adjustments
- User role provisioning
- Impersonation capabilities
- System metrics and logs

## Admin Roles

### super_admin

**Full system access** with unrestricted permissions.

**Permissions:**
- Manage all system configuration
- Assign and revoke admin roles (including creating other super admins)
- Manage feature flags and rollout percentages
- Adjust rate limits and override thresholds
- View and modify all user data
- Impersonate any user for support purposes
- Access all audit logs and analytics
- Trigger manual job execution and queue management

**Use Cases:**
- System administrators
- Engineering leads
- Initial bootstrap user

**Security Notes:**
- The first super_admin is created via the bootstrap endpoint (one-time operation)
- Super admins cannot revoke their own role to prevent system lockout
- All super admin actions are audited

### support

**Customer support role** with read access to user data and limited write permissions.

**Permissions:**
- View user profiles and preferences
- View user activity and homepage configurations
- Assist with account issues (password resets, account recovery)
- View support-related audit logs
- Access customer support dashboards

**Restrictions:**
- Cannot modify system configuration
- Cannot assign roles or manage admins
- Cannot adjust feature flags or rate limits
- Cannot access system internals or infrastructure

**Use Cases:**
- Customer support representatives
- Community managers
- Trust & safety team members

### ops

**Operations role** focused on system health, monitoring, and operational tasks.

**Permissions:**
- View system metrics and performance dashboards
- Trigger manual job execution (feed refresh, cache clear, etc.)
- Adjust rate limits for abuse mitigation
- View operational logs and error traces
- Access system health checks and alerting
- Manage job queues (pause, resume, retry)

**Restrictions:**
- Cannot modify user roles or assign permissions
- Cannot permanently alter system configuration
- Cannot access individual user data (beyond aggregated metrics)
- Cannot manage feature flags

**Use Cases:**
- DevOps engineers
- Site reliability engineers (SRE)
- On-call responders

### read_only

**Read-only access** to dashboards and logs without any write permissions.

**Permissions:**
- View system dashboards and metrics
- View anonymized user analytics
- Access operational logs (read-only)
- View feature flag states (but cannot modify)
- View rate limit configurations (but cannot modify)

**Restrictions:**
- Cannot modify any system state
- Cannot trigger actions or execute jobs
- Cannot access personally identifiable information (PII)
- Cannot impersonate users

**Use Cases:**
- Auditors and compliance reviewers
- Product managers reviewing metrics
- Executive stakeholders monitoring system health

## Bootstrap Process

The first `super_admin` is created through a secure bootstrap process:

### Prerequisites

1. Set the `BOOTSTRAP_TOKEN` environment variable to a secure random string (minimum 32 characters)
2. Deploy the application to your target environment
3. Note: The bootstrap endpoint is **one-time use only** and becomes disabled after the first super admin is created

### Bootstrap Steps

1. **Generate a bootstrap token** (if not already set):
   ```bash
   # Generate a secure random token
   openssl rand -base64 32
   # Set as environment variable
   export BOOTSTRAP_TOKEN="your-generated-token"
   ```

2. **Start the application**:
   ```bash
   ./mvnw quarkus:dev
   ```

3. **Initiate OAuth flow** with your preferred provider (Google, Facebook, or Apple):
   ```bash
   # Open in browser or use curl to get redirect URL
   curl -X POST http://localhost:8080/api/auth/login/google
   ```

4. **Complete the bootstrap** by providing your OAuth profile and the bootstrap token:
   ```bash
   curl -X POST http://localhost:8080/api/auth/bootstrap \
     -H "Content-Type: application/json" \
     -d '{
       "token": "your-bootstrap-token",
       "email": "admin@example.com",
       "provider": "google",
       "provider_user_id": "oauth-provider-id"
     }'
   ```

5. **Response** will include a JWT session token:
   ```json
   {
     "jwt": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
     "expires_at": "2025-01-09T13:00:00Z",
     "email": "admin@example.com",
     "provider": "google"
   }
   ```

6. **Verify** the super admin was created:
   ```bash
   curl -X GET http://localhost:8080/admin/api/users/roles \
     -H "Authorization: Bearer your-jwt-token"
   ```

### Bootstrap Security

- Bootstrap endpoint is rate-limited (5 requests per hour per IP)
- Returns 403 after the first super admin is created
- Token validation is constant-time to prevent timing attacks
- All bootstrap attempts are logged with IP address and timestamp

## Managing Admin Roles

### Assigning a Role

Only `super_admin` users can assign admin roles to other users.

**API Endpoint:** `PUT /admin/api/users/roles/{userId}`

**Request:**
```bash
curl -X PUT http://localhost:8080/admin/api/users/roles/{user-id} \
  -H "Authorization: Bearer super-admin-jwt" \
  -H "Content-Type: application/json" \
  -d '{
    "role": "support"
  }'
```

**Response:**
```json
{
  "user_id": "uuid",
  "email": "support@example.com",
  "display_name": "Support User",
  "admin_role": "support",
  "granted_at": "2025-01-09T12:00:00Z",
  "granted_by": "granter-user-id"
}
```

**Valid Roles:** `super_admin`, `support`, `ops`, `read_only`

### Revoking a Role

**API Endpoint:** `DELETE /admin/api/users/roles/{userId}`

**Request:**
```bash
curl -X DELETE http://localhost:8080/admin/api/users/roles/{user-id} \
  -H "Authorization: Bearer super-admin-jwt"
```

**Response:** `204 No Content` on success

**Important:** Users cannot revoke their own `super_admin` role to prevent system lockout.

### Listing Admin Users

**API Endpoint:** `GET /admin/api/users/roles`

**Optional query parameter:** `?role=super_admin` (filter by specific role)

**Request:**
```bash
# List all admin users
curl -X GET http://localhost:8080/admin/api/users/roles \
  -H "Authorization: Bearer super-admin-jwt"

# List only super admins
curl -X GET http://localhost:8080/admin/api/users/roles?role=super_admin \
  -H "Authorization: Bearer super-admin-jwt"
```

### Getting a Specific User's Role

**API Endpoint:** `GET /admin/api/users/roles/{userId}`

**Request:**
```bash
curl -X GET http://localhost:8080/admin/api/users/roles/{user-id} \
  -H "Authorization: Bearer super-admin-jwt"
```

## Impersonation

Admins with appropriate permissions can impersonate users for support purposes. All impersonation sessions are logged to the `impersonation_audit` table.

### Starting Impersonation

Only users with an admin role can impersonate others. The system records:
- Impersonator user ID
- Target user ID
- Session start time
- IP address
- User agent

### Impersonation Audit Trail

All impersonation events are stored in the `impersonation_audit` table with the following schema:

```sql
CREATE TABLE impersonation_audit (
    id UUID PRIMARY KEY,
    impersonator_id UUID REFERENCES users(id),
    target_user_id UUID REFERENCES users(id),
    started_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,  -- NULL for active sessions
    ip_address TEXT,
    user_agent TEXT,
    created_at TIMESTAMPTZ
);
```

### Impersonation Guard Rails

1. **Authentication Required:** Only authenticated admin users can impersonate
2. **Audit Logging:** All sessions are logged with full context
3. **Active Session Tracking:** System tracks which impersonations are currently active
4. **IP and User Agent Recording:** Forensic data captured for investigation
5. **Time-Limited Sessions:** Sessions automatically expire (implementation pending)

## Alignment with village-storefront

This RBAC implementation mirrors the role and permission structure from the `village-storefront` project to ensure consistency across VillageCompute services. The four role names (`super_admin`, `support`, `ops`, `read_only`) are standardized constants used across all Village projects.

### Differences from village-storefront

- **Single Role per User:** Village Homepage uses a single `admin_role` column rather than a many-to-many junction table. This simplifies the initial implementation while meeting current requirements.
- **Bootstrap Flow:** Village Homepage adds a specific bootstrap endpoint for first-admin creation, which may differ from storefront's provisioning approach.

### Future Enhancements

As the system evolves, we may:
- Add granular permission checks beyond role-based access
- Implement permission delegation (role-specific capabilities)
- Add time-limited role assignments
- Integrate with centralized identity provider (Keycloak, Auth0, etc.)

## Audit and Compliance

### Role Change Audit

All role assignments and revocations are logged via `AuthIdentityService` with:
- Timestamp
- Action (assign/revoke)
- Target user ID
- Admin performing the action
- Old and new role values

### Impersonation Audit

All impersonation sessions are permanently logged in `impersonation_audit` table per Section 3.7.1 requirements. Entries include:
- Full session lifecycle (start and end times)
- Actor identification (impersonator and target)
- Network metadata (IP, user agent)

### Retention Policy

- Role change logs: **Permanent retention**
- Impersonation audit: **Permanent retention**
- Both are critical for security investigations and compliance audits

## Troubleshooting

### Bootstrap Endpoint Returns 403

**Cause:** A super admin already exists in the system.

**Solution:** Use an existing super admin account to assign roles to new users via the role management API.

### Cannot Assign Role (403 Forbidden)

**Cause:** Current user does not have `super_admin` role.

**Solution:** Only super admins can manage roles. Contact an existing super admin to grant you the role.

### Bootstrap Token Invalid

**Cause:** The `BOOTSTRAP_TOKEN` environment variable is not set or doesn't match the provided token.

**Solution:**
1. Verify the environment variable is set: `echo $BOOTSTRAP_TOKEN`
2. Ensure the token in your request exactly matches the environment variable
3. Check for whitespace or encoding issues

### Rate Limit Exceeded on Bootstrap

**Cause:** Too many bootstrap attempts from the same IP address.

**Solution:** Wait for the rate limit window to reset (default: 1 hour) or contact a system administrator to clear the rate limit.

## Database Schema

### users Table (Extended)

```sql
ALTER TABLE users ADD COLUMN admin_role TEXT
    CHECK (admin_role IS NULL OR admin_role IN ('super_admin', 'support', 'ops', 'read_only'));

ALTER TABLE users ADD COLUMN admin_role_granted_by UUID REFERENCES users(id);
ALTER TABLE users ADD COLUMN admin_role_granted_at TIMESTAMPTZ;

CREATE INDEX idx_users_admin_role ON users(admin_role) WHERE admin_role IS NOT NULL;
```

### impersonation_audit Table

```sql
CREATE TABLE impersonation_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    impersonator_id UUID NOT NULL REFERENCES users(id),
    target_user_id UUID NOT NULL REFERENCES users(id),
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ended_at TIMESTAMPTZ,
    ip_address TEXT,
    user_agent TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_impersonation_impersonator ON impersonation_audit(impersonator_id, started_at DESC);
CREATE INDEX idx_impersonation_target ON impersonation_audit(target_user_id, started_at DESC);
CREATE INDEX idx_impersonation_active ON impersonation_audit(started_at DESC) WHERE ended_at IS NULL;
```

## References

- **Foundation Blueprint Section 3.7.1:** Access Control & Identity
- **Foundation Blueprint Section 3.8:** Cross-Cutting Authorization
- **Task I2.T8:** Admin role + permission import
- **village-storefront:** Role and permission constants (reference implementation)
