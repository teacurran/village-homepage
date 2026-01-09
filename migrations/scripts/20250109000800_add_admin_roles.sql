-- // add_admin_roles
-- Adds admin role support to the users table to enable role-based access control.
-- Roles mirror village-storefront constants per Foundation Blueprint Section 3.7.1:
--   - super_admin: Full system access, can manage users/roles, feature flags, rate limits, system config
--   - support: Can view user data, assist with issues, cannot modify system config
--   - ops: Can view metrics, trigger jobs, adjust rate limits, cannot modify user roles
--   - read_only: Can view dashboards and logs, no write access
--
-- This migration supports Task I2.T8 RBAC implementation per Iteration 2 goals.

ALTER TABLE users ADD COLUMN admin_role TEXT
    CHECK (admin_role IS NULL OR admin_role IN ('super_admin', 'support', 'ops', 'read_only'));

-- Add tracking fields for audit trail
ALTER TABLE users ADD COLUMN admin_role_granted_by UUID REFERENCES users(id);
ALTER TABLE users ADD COLUMN admin_role_granted_at TIMESTAMPTZ;

-- Index for efficient admin user queries
CREATE INDEX idx_users_admin_role ON users(admin_role) WHERE admin_role IS NOT NULL;

-- Index for audit queries (who granted roles)
CREATE INDEX idx_users_role_granted_by ON users(admin_role_granted_by) WHERE admin_role_granted_by IS NOT NULL;

-- //@UNDO

DROP INDEX IF EXISTS idx_users_role_granted_by;
DROP INDEX IF EXISTS idx_users_admin_role;
ALTER TABLE users DROP COLUMN IF EXISTS admin_role_granted_at;
ALTER TABLE users DROP COLUMN IF EXISTS admin_role_granted_by;
ALTER TABLE users DROP COLUMN IF EXISTS admin_role;
