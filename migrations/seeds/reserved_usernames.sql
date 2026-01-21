-- //
-- // File: reserved_usernames.sql
-- // Description: Seed reserved usernames for namespace protection
-- //
-- // Feature: F11.2 - Reserved Names
-- // Policy: P13 - User-generated content moderation (prevent impersonation)
-- //
-- // This seed file populates the reserved_usernames table with usernames reserved
-- // for system use, features, admin roles, and common pages. Reserved names prevent
-- // impersonation and namespace conflicts.
-- //
-- // Categories:
-- //   - System: Infrastructure and core services
-- //   - Features: Application features and modules
-- //   - Admin: Admin roles and management
-- //   - Common: Standard pages and navigation
-- //

-- System reserved names (infrastructure)
INSERT INTO reserved_usernames (username, reason) VALUES
('admin', 'System: Reserved for administration'),
('api', 'System: Reserved for API endpoints'),
('cdn', 'System: Reserved for CDN resources'),
('www', 'System: Reserved for WWW prefix'),
('assets', 'System: Reserved for static assets'),
('static', 'System: Reserved for static resources'),
('public', 'System: Reserved for public resources'),
('app', 'System: Reserved for application'),
('web', 'System: Reserved for web services'),
('mail', 'System: Reserved for email services'),
('ftp', 'System: Reserved for file transfer'),
('ssh', 'System: Reserved for secure shell'),
('vpn', 'System: Reserved for VPN services'),
('proxy', 'System: Reserved for proxy services')
ON CONFLICT (username) DO NOTHING;

-- Feature reserved names (application features)
INSERT INTO reserved_usernames (username, reason) VALUES
('good-sites', 'Feature: Reserved for Good Sites directory'),
('goodsites', 'Feature: Reserved for Good Sites directory (alt)'),
('marketplace', 'Feature: Reserved for Marketplace'),
('calendar', 'Feature: Reserved for Calendar'),
('directory', 'Feature: Reserved for web directory'),
('search', 'Feature: Reserved for search functionality'),
('feed', 'Feature: Reserved for RSS feeds'),
('feeds', 'Feature: Reserved for RSS feeds (plural)'),
('rss', 'Feature: Reserved for RSS'),
('widget', 'Feature: Reserved for widgets'),
('widgets', 'Feature: Reserved for widgets (plural)')
ON CONFLICT (username) DO NOTHING;

-- Admin role reserved names (prevent impersonation)
INSERT INTO reserved_usernames (username, reason) VALUES
('support', 'Admin: Reserved for support role'),
('ops', 'Admin: Reserved for operations role'),
('moderator', 'Admin: Reserved for moderator role'),
('mod', 'Admin: Reserved for moderator role (short)'),
('root', 'Admin: Reserved for root access'),
('superuser', 'Admin: Reserved for superuser'),
('administrator', 'Admin: Reserved for administrator'),
('sysadmin', 'Admin: Reserved for system admin'),
('webmaster', 'Admin: Reserved for webmaster')
ON CONFLICT (username) DO NOTHING;

-- Common reserved names (standard pages/features)
INSERT INTO reserved_usernames (username, reason) VALUES
('help', 'Common: Reserved for help page'),
('about', 'Common: Reserved for about page'),
('contact', 'Common: Reserved for contact page'),
('terms', 'Common: Reserved for terms of service'),
('privacy', 'Common: Reserved for privacy policy'),
('blog', 'Common: Reserved for blog'),
('news', 'Common: Reserved for news'),
('faq', 'Common: Reserved for FAQ'),
('status', 'Common: Reserved for status page'),
('legal', 'Common: Reserved for legal information'),
('security', 'Common: Reserved for security information'),
('login', 'Common: Reserved for login page'),
('logout', 'Common: Reserved for logout'),
('signup', 'Common: Reserved for signup page'),
('signin', 'Common: Reserved for signin page'),
('register', 'Common: Reserved for registration'),
('account', 'Common: Reserved for account management'),
('profile', 'Common: Reserved for profile pages'),
('settings', 'Common: Reserved for settings page'),
('dashboard', 'Common: Reserved for dashboard')
ON CONFLICT (username) DO NOTHING;
