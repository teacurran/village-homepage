-- Rate limit configurations for email notifications (Task I6.T7)
-- Policy F14.3 - Email Communication

-- Profile notification rate limits
INSERT INTO rate_limit_configs (action_type, tier, max_requests, window_seconds, enabled, description)
VALUES
  ('email.profile_notification', 'logged_in', 5, 3600, true,
   'Profile publish/unpublish notifications for logged-in users'),
  ('email.profile_notification', 'trusted', 10, 3600, true,
   'Profile publish/unpublish notifications for trusted users');

-- Analytics alert rate limits (for ops team)
INSERT INTO rate_limit_configs (action_type, tier, max_requests, window_seconds, enabled, description)
VALUES
  ('email.analytics_alert', 'trusted', 3, 3600, true,
   'AI budget threshold alerts sent to ops team');

-- GDPR notification rate limits
INSERT INTO rate_limit_configs (action_type, tier, max_requests, window_seconds, enabled, description)
VALUES
  ('email.gdpr_notification', 'logged_in', 1, 86400, true,
   'GDPR export ready and deletion complete notifications');
