-- Marketplace category seed data for Craigslist-style structure per Feature F12.3
-- Execute via:
--   psql -h $POSTGRES_HOST -p $POSTGRES_PORT -U $POSTGRES_USER -d $POSTGRES_DB -f migrations/seeds/marketplace_categories.sql
--
-- This script populates the full category hierarchy:
-- - 5 main categories (For Sale, Housing, Jobs, Services, Community)
-- - ~120 subcategories matching Craigslist structure
-- - Fee schedules: most categories free, some premium for housing/jobs

-- =============================================================================
-- ROOT CATEGORIES
-- =============================================================================

INSERT INTO marketplace_categories (id, parent_id, name, slug, sort_order, is_active, fee_schedule, created_at, updated_at)
VALUES
    -- Main Categories
    ('11111111-1111-1111-1111-111111111111', NULL, 'For Sale', 'for-sale', 1, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    ('22222222-2222-2222-2222-222222222222', NULL, 'Housing', 'housing', 2, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    ('33333333-3333-3333-3333-333333333333', NULL, 'Jobs', 'jobs', 3, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    ('44444444-4444-4444-4444-444444444444', NULL, 'Services', 'services', 4, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    ('55555555-5555-5555-5555-555555555555', NULL, 'Community', 'community', 5, true,
     '{"posting_fee": 0, "featured_fee": 0, "bump_fee": 0}'::jsonb, NOW(), NOW())

ON CONFLICT (slug) DO UPDATE
    SET name = EXCLUDED.name,
        sort_order = EXCLUDED.sort_order,
        fee_schedule = EXCLUDED.fee_schedule,
        updated_at = NOW();

-- =============================================================================
-- FOR SALE SUBCATEGORIES (parent: 11111111-1111-1111-1111-111111111111)
-- =============================================================================

INSERT INTO marketplace_categories (id, parent_id, name, slug, sort_order, is_active, fee_schedule, created_at, updated_at)
VALUES
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Antiques', 'antiques', 1, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Appliances', 'appliances', 2, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Arts & Crafts', 'arts-crafts', 3, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Auto Parts', 'auto-parts', 4, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Bicycles', 'bicycles', 5, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Books', 'books', 6, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Business', 'business', 7, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'CDs/DVD/VHS', 'cds-dvd-vhs', 8, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Cell Phones', 'cell-phones', 9, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Clothing & Accessories', 'clothing-accessories', 10, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Collectibles', 'collectibles', 11, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Computer Parts', 'computer-parts', 12, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Computers', 'computers', 13, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Electronics', 'electronics', 14, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Farm & Garden', 'farm-garden', 15, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Furniture', 'furniture', 16, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Garage Sale', 'garage-sale', 17, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'General', 'general', 18, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Health & Beauty', 'health-beauty', 19, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Heavy Equipment', 'heavy-equipment', 20, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Household', 'household', 21, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Jewelry', 'jewelry', 22, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Materials', 'materials', 23, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Motorcycles', 'motorcycles', 24, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Musical Instruments', 'musical-instruments', 25, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Photo & Video', 'photo-video', 26, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Sporting Goods', 'sporting-goods', 27, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Tickets', 'tickets', 28, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Tools', 'tools', 29, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Toys & Games', 'toys-games', 30, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Video Gaming', 'video-gaming', 31, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW())

ON CONFLICT (slug) DO UPDATE
    SET name = EXCLUDED.name,
        parent_id = EXCLUDED.parent_id,
        sort_order = EXCLUDED.sort_order,
        fee_schedule = EXCLUDED.fee_schedule,
        updated_at = NOW();

-- =============================================================================
-- HOUSING SUBCATEGORIES (parent: 22222222-2222-2222-2222-222222222222)
-- =============================================================================

INSERT INTO marketplace_categories (id, parent_id, name, slug, sort_order, is_active, fee_schedule, created_at, updated_at)
VALUES
    (gen_random_uuid(), '22222222-2222-2222-2222-222222222222', 'Apartments / Housing', 'apartments-housing', 1, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '22222222-2222-2222-2222-222222222222', 'Office & Commercial', 'office-commercial', 2, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '22222222-2222-2222-2222-222222222222', 'Parking & Storage', 'parking-storage', 3, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '22222222-2222-2222-2222-222222222222', 'Real Estate', 'real-estate', 4, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '22222222-2222-2222-2222-222222222222', 'Room / Share', 'room-share', 5, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '22222222-2222-2222-2222-222222222222', 'Sublets / Temporary', 'sublets-temporary', 6, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '22222222-2222-2222-2222-222222222222', 'Vacation Rentals', 'vacation-rentals', 7, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '22222222-2222-2222-2222-222222222222', 'Wanted', 'housing-wanted', 8, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW())

ON CONFLICT (slug) DO UPDATE
    SET name = EXCLUDED.name,
        parent_id = EXCLUDED.parent_id,
        sort_order = EXCLUDED.sort_order,
        fee_schedule = EXCLUDED.fee_schedule,
        updated_at = NOW();

-- =============================================================================
-- JOBS SUBCATEGORIES (parent: 33333333-3333-3333-3333-333333333333)
-- =============================================================================

INSERT INTO marketplace_categories (id, parent_id, name, slug, sort_order, is_active, fee_schedule, created_at, updated_at)
VALUES
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Accounting & Finance', 'accounting-finance', 1, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Admin / Office', 'admin-office', 2, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Architect / Engineering', 'architect-engineering', 3, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Art / Media / Design', 'art-media-design', 4, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Biotech / Science', 'biotech-science', 5, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Business / Management', 'business-management', 6, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Customer Service', 'customer-service', 7, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Education / Teaching', 'education-teaching', 8, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Et Cetera', 'et-cetera', 9, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Food & Beverage', 'food-beverage', 10, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'General Labor', 'general-labor', 11, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Government', 'government', 12, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Human Resources', 'human-resources', 13, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Legal / Paralegal', 'legal-paralegal', 14, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Manufacturing', 'manufacturing', 15, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Marketing / PR / Ad', 'marketing-pr-ad', 16, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Medical / Health', 'medical-health', 17, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Nonprofit', 'nonprofit', 18, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Real Estate', 'jobs-real-estate', 19, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Retail / Wholesale', 'retail-wholesale', 20, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Sales', 'sales', 21, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Salon / Spa / Fitness', 'salon-spa-fitness', 22, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Security', 'security', 23, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Skilled Trade / Craft', 'skilled-trade-craft', 24, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Software / QA / DBA', 'software-qa-dba', 25, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Systems / Networking', 'systems-networking', 26, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Technical Support', 'technical-support', 27, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Transport', 'transport', 28, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'TV / Film / Video', 'tv-film-video', 29, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Web / Info Design', 'web-info-design', 30, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Writing / Editing', 'writing-editing', 31, true,
     '{"posting_fee": 0, "featured_fee": 10.00, "bump_fee": 5.00}'::jsonb, NOW(), NOW())

ON CONFLICT (slug) DO UPDATE
    SET name = EXCLUDED.name,
        parent_id = EXCLUDED.parent_id,
        sort_order = EXCLUDED.sort_order,
        fee_schedule = EXCLUDED.fee_schedule,
        updated_at = NOW();

-- =============================================================================
-- SERVICES SUBCATEGORIES (parent: 44444444-4444-4444-4444-444444444444)
-- =============================================================================

INSERT INTO marketplace_categories (id, parent_id, name, slug, sort_order, is_active, fee_schedule, created_at, updated_at)
VALUES
    (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 'Automotive', 'automotive', 1, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 'Beauty', 'beauty', 2, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 'Cell / Mobile', 'cell-mobile', 3, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 'Computer', 'computer', 4, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 'Creative', 'creative', 5, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 'Cycle', 'cycle', 6, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 'Event', 'event', 7, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 'Farm & Garden', 'services-farm-garden', 8, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 'Financial', 'financial', 9, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 'Household', 'services-household', 10, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 'Labor / Move', 'labor-move', 11, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 'Legal', 'services-legal', 12, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 'Lessons', 'lessons', 13, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 'Marine', 'marine', 14, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 'Pet', 'pet', 15, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 'Real Estate', 'services-real-estate', 16, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 'Skilled Trade', 'services-skilled-trade', 17, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 'Small Biz Ads', 'small-biz-ads', 18, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 'Travel / Vacation', 'travel-vacation', 19, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 'Write / Edit / Translate', 'write-edit-translate', 20, true,
     '{"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}'::jsonb, NOW(), NOW())

ON CONFLICT (slug) DO UPDATE
    SET name = EXCLUDED.name,
        parent_id = EXCLUDED.parent_id,
        sort_order = EXCLUDED.sort_order,
        fee_schedule = EXCLUDED.fee_schedule,
        updated_at = NOW();

-- =============================================================================
-- COMMUNITY SUBCATEGORIES (parent: 55555555-5555-5555-5555-555555555555)
-- =============================================================================

INSERT INTO marketplace_categories (id, parent_id, name, slug, sort_order, is_active, fee_schedule, created_at, updated_at)
VALUES
    (gen_random_uuid(), '55555555-5555-5555-5555-555555555555', 'Activities', 'activities', 1, true,
     '{"posting_fee": 0, "featured_fee": 0, "bump_fee": 0}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '55555555-5555-5555-5555-555555555555', 'Artists', 'artists', 2, true,
     '{"posting_fee": 0, "featured_fee": 0, "bump_fee": 0}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '55555555-5555-5555-5555-555555555555', 'Childcare', 'childcare', 3, true,
     '{"posting_fee": 0, "featured_fee": 0, "bump_fee": 0}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '55555555-5555-5555-5555-555555555555', 'Classes', 'classes', 4, true,
     '{"posting_fee": 0, "featured_fee": 0, "bump_fee": 0}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '55555555-5555-5555-5555-555555555555', 'Events', 'events', 5, true,
     '{"posting_fee": 0, "featured_fee": 0, "bump_fee": 0}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '55555555-5555-5555-5555-555555555555', 'General', 'community-general', 6, true,
     '{"posting_fee": 0, "featured_fee": 0, "bump_fee": 0}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '55555555-5555-5555-5555-555555555555', 'Groups', 'groups', 7, true,
     '{"posting_fee": 0, "featured_fee": 0, "bump_fee": 0}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '55555555-5555-5555-5555-555555555555', 'Local News', 'local-news', 8, true,
     '{"posting_fee": 0, "featured_fee": 0, "bump_fee": 0}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '55555555-5555-5555-5555-555555555555', 'Lost & Found', 'lost-found', 9, true,
     '{"posting_fee": 0, "featured_fee": 0, "bump_fee": 0}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '55555555-5555-5555-5555-555555555555', 'Musicians', 'musicians', 10, true,
     '{"posting_fee": 0, "featured_fee": 0, "bump_fee": 0}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '55555555-5555-5555-5555-555555555555', 'Pets', 'community-pets', 11, true,
     '{"posting_fee": 0, "featured_fee": 0, "bump_fee": 0}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '55555555-5555-5555-5555-555555555555', 'Politics', 'politics', 12, true,
     '{"posting_fee": 0, "featured_fee": 0, "bump_fee": 0}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '55555555-5555-5555-5555-555555555555', 'Rideshare', 'rideshare', 13, true,
     '{"posting_fee": 0, "featured_fee": 0, "bump_fee": 0}'::jsonb, NOW(), NOW()),
    (gen_random_uuid(), '55555555-5555-5555-5555-555555555555', 'Volunteers', 'volunteers', 14, true,
     '{"posting_fee": 0, "featured_fee": 0, "bump_fee": 0}'::jsonb, NOW(), NOW())

ON CONFLICT (slug) DO UPDATE
    SET name = EXCLUDED.name,
        parent_id = EXCLUDED.parent_id,
        sort_order = EXCLUDED.sort_order,
        fee_schedule = EXCLUDED.fee_schedule,
        updated_at = NOW();
