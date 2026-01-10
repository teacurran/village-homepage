-- Directory category seed data for Good Sites web directory per Feature F13.1
-- Execute via:
--   psql -h $POSTGRES_HOST -p $POSTGRES_PORT -U $POSTGRES_USER -d $POSTGRES_DB -f migrations/seeds/directory_categories.sql
--
-- This script populates the full category hierarchy:
-- - 7 root categories (Arts, Business, Computers, News, Recreation, Science, Society)
-- - ~130 subcategories following classic Yahoo Directory / DMOZ taxonomy
-- - Icon URLs can be populated later (nulls for initial launch)

-- =============================================================================
-- ROOT CATEGORIES
-- =============================================================================

INSERT INTO directory_categories (id, parent_id, name, slug, description, icon_url, sort_order, link_count, is_active, created_at, updated_at)
VALUES
    -- Main Categories (fixed UUIDs for stable references)
    ('11111111-2222-3333-4444-111111111111', NULL, 'Arts & Entertainment', 'arts',
     'Movies, music, television, books, photography, and performing arts', NULL, 1, 0, true, NOW(), NOW()),
    ('11111111-2222-3333-4444-222222222222', NULL, 'Business & Economy', 'business',
     'Companies, finance, jobs, real estate, and investing', NULL, 2, 0, true, NOW(), NOW()),
    ('11111111-2222-3333-4444-333333333333', NULL, 'Computers & Internet', 'computers',
     'Software, hardware, programming, web design, and internet culture', NULL, 3, 0, true, NOW(), NOW()),
    ('11111111-2222-3333-4444-444444444444', NULL, 'News & Media', 'news',
     'Newspapers, magazines, weather, broadcast media, and current events', NULL, 4, 0, true, NOW(), NOW()),
    ('11111111-2222-3333-4444-555555555555', NULL, 'Recreation & Sports', 'recreation',
     'Sports, travel, hobbies, food, outdoors, and entertainment', NULL, 5, 0, true, NOW(), NOW()),
    ('11111111-2222-3333-4444-666666666666', NULL, 'Science & Technology', 'science',
     'Engineering, biology, physics, space, and environmental science', NULL, 6, 0, true, NOW(), NOW()),
    ('11111111-2222-3333-4444-777777777777', NULL, 'Society & Culture', 'society',
     'People, religion, politics, organizations, and cultural topics', NULL, 7, 0, true, NOW(), NOW())

ON CONFLICT (slug) DO UPDATE
    SET name = EXCLUDED.name,
        description = EXCLUDED.description,
        sort_order = EXCLUDED.sort_order,
        updated_at = NOW();

-- =============================================================================
-- ARTS & ENTERTAINMENT SUBCATEGORIES (parent: 11111111-2222-3333-4444-111111111111)
-- =============================================================================

INSERT INTO directory_categories (id, parent_id, name, slug, description, icon_url, sort_order, link_count, is_active, created_at, updated_at)
VALUES
    (gen_random_uuid(), '11111111-2222-3333-4444-111111111111', 'Movies', 'arts-movies',
     'Film reviews, databases, streaming services, and movie news', NULL, 1, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-111111111111', 'Music', 'arts-music',
     'Music streaming, reviews, artist pages, and instruments', NULL, 2, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-111111111111', 'Television', 'arts-television',
     'TV shows, streaming platforms, episode guides, and reviews', NULL, 3, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-111111111111', 'Books & Literature', 'arts-books',
     'Book reviews, author pages, libraries, and publishing', NULL, 4, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-111111111111', 'Photography', 'arts-photography',
     'Photo galleries, equipment reviews, tutorials, and portfolios', NULL, 5, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-111111111111', 'Performing Arts', 'arts-performing',
     'Theater, dance, opera, and live performances', NULL, 6, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-111111111111', 'Visual Arts', 'arts-visual',
     'Painting, sculpture, galleries, and museums', NULL, 7, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-111111111111', 'Comics & Animation', 'arts-comics',
     'Comics, manga, webcomics, and animated series', NULL, 8, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-111111111111', 'Radio', 'arts-radio',
     'Online radio, podcasts, and broadcast stations', NULL, 9, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-111111111111', 'Celebrities', 'arts-celebrities',
     'Celebrity news, fan sites, and entertainment gossip', NULL, 10, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-111111111111', 'Humor', 'arts-humor',
     'Comedy, jokes, memes, and funny content', NULL, 11, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-111111111111', 'Awards', 'arts-awards',
     'Film awards, music awards, and entertainment ceremonies', NULL, 12, 0, true, NOW(), NOW()),

ON CONFLICT (slug) DO NOTHING;

-- =============================================================================
-- BUSINESS & ECONOMY SUBCATEGORIES (parent: 11111111-2222-3333-4444-222222222222)
-- =============================================================================

INSERT INTO directory_categories (id, parent_id, name, slug, description, icon_url, sort_order, link_count, is_active, created_at, updated_at)
VALUES
    (gen_random_uuid(), '11111111-2222-3333-4444-222222222222', 'Finance', 'business-finance',
     'Banking, investing, stock market, and personal finance', NULL, 1, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-222222222222', 'Jobs & Careers', 'business-jobs',
     'Job boards, career advice, resumes, and employment', NULL, 2, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-222222222222', 'Real Estate', 'business-real-estate',
     'Property listings, mortgages, and real estate market news', NULL, 3, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-222222222222', 'Investing', 'business-investing',
     'Stocks, bonds, ETFs, cryptocurrency, and investment strategies', NULL, 4, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-222222222222', 'Small Business', 'business-small-business',
     'Entrepreneurship, startups, and small business resources', NULL, 5, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-222222222222', 'Marketing & Advertising', 'business-marketing',
     'Digital marketing, SEO, advertising platforms, and analytics', NULL, 6, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-222222222222', 'E-commerce', 'business-ecommerce',
     'Online shopping, retail platforms, and payment systems', NULL, 7, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-222222222222', 'Insurance', 'business-insurance',
     'Health, life, auto, and business insurance providers', NULL, 8, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-222222222222', 'Accounting', 'business-accounting',
     'Tax preparation, bookkeeping, and accounting software', NULL, 9, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-222222222222', 'Business News', 'business-news',
     'Financial news, market analysis, and business journalism', NULL, 10, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-222222222222', 'Consulting', 'business-consulting',
     'Business consulting, strategy, and professional services', NULL, 11, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-222222222222', 'International Trade', 'business-trade',
     'Import, export, global commerce, and logistics', NULL, 12, 0, true, NOW(), NOW()),

ON CONFLICT (slug) DO NOTHING;

-- =============================================================================
-- COMPUTERS & INTERNET SUBCATEGORIES (parent: 11111111-2222-3333-4444-333333333333)
-- =============================================================================

INSERT INTO directory_categories (id, parent_id, name, slug, description, icon_url, sort_order, link_count, is_active, created_at, updated_at)
VALUES
    (gen_random_uuid(), '11111111-2222-3333-4444-333333333333', 'Software', 'computers-software',
     'Applications, operating systems, and software development tools', NULL, 1, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-333333333333', 'Hardware', 'computers-hardware',
     'Computer components, peripherals, and hardware reviews', NULL, 2, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-333333333333', 'Programming', 'computers-programming',
     'Coding tutorials, languages, frameworks, and developer tools', NULL, 3, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-333333333333', 'Web Design', 'computers-web-design',
     'HTML, CSS, UI/UX design, and web development resources', NULL, 4, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-333333333333', 'Security', 'computers-security',
     'Cybersecurity, privacy, antivirus, and security best practices', NULL, 5, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-333333333333', 'Networking', 'computers-networking',
     'Network protocols, infrastructure, and administration', NULL, 6, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-333333333333', 'Mobile', 'computers-mobile',
     'Mobile apps, smartphones, tablets, and mobile development', NULL, 7, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-333333333333', 'Gaming', 'computers-gaming',
     'Video games, game development, reviews, and gaming news', NULL, 8, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-333333333333', 'Open Source', 'computers-opensource',
     'Open source projects, Linux, and collaborative development', NULL, 9, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-333333333333', 'Cloud Computing', 'computers-cloud',
     'AWS, Azure, GCP, and cloud infrastructure services', NULL, 10, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-333333333333', 'AI & Machine Learning', 'computers-ai',
     'Artificial intelligence, ML frameworks, and data science', NULL, 11, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-333333333333', 'Internet Culture', 'computers-internet-culture',
     'Memes, forums, social media, and online communities', NULL, 12, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-333333333333', 'Data Science', 'computers-data-science',
     'Big data, analytics, visualization, and statistics', NULL, 13, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-333333333333', 'Blockchain', 'computers-blockchain',
     'Cryptocurrency, blockchain technology, and Web3', NULL, 14, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-333333333333', 'Tech News', 'computers-tech-news',
     'Technology news, reviews, and industry updates', NULL, 15, 0, true, NOW(), NOW()),

ON CONFLICT (slug) DO NOTHING;

-- =============================================================================
-- NEWS & MEDIA SUBCATEGORIES (parent: 11111111-2222-3333-4444-444444444444)
-- =============================================================================

INSERT INTO directory_categories (id, parent_id, name, slug, description, icon_url, sort_order, link_count, is_active, created_at, updated_at)
VALUES
    (gen_random_uuid(), '11111111-2222-3333-4444-444444444444', 'Newspapers', 'news-newspapers',
     'National and local newspaper websites', NULL, 1, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-444444444444', 'Magazines', 'news-magazines',
     'Online magazines and periodicals', NULL, 2, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-444444444444', 'Weather', 'news-weather',
     'Weather forecasts, radar, and meteorology', NULL, 3, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-444444444444', 'Broadcast Media', 'news-broadcast',
     'TV news networks and streaming news channels', NULL, 4, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-444444444444', 'World News', 'news-world',
     'International news and global current events', NULL, 5, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-444444444444', 'Politics', 'news-politics',
     'Political news, elections, and government coverage', NULL, 6, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-444444444444', 'Local News', 'news-local',
     'City and regional news sources', NULL, 7, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-444444444444', 'Investigative Journalism', 'news-investigative',
     'In-depth reporting and investigative news organizations', NULL, 8, 0, true, NOW(), NOW()),

ON CONFLICT (slug) DO NOTHING;

-- =============================================================================
-- RECREATION & SPORTS SUBCATEGORIES (parent: 11111111-2222-3333-4444-555555555555)
-- =============================================================================

INSERT INTO directory_categories (id, parent_id, name, slug, description, icon_url, sort_order, link_count, is_active, created_at, updated_at)
VALUES
    (gen_random_uuid(), '11111111-2222-3333-4444-555555555555', 'Sports', 'recreation-sports',
     'Sports news, scores, teams, and leagues', NULL, 1, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-555555555555', 'Travel', 'recreation-travel',
     'Travel guides, booking sites, and destination information', NULL, 2, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-555555555555', 'Hobbies', 'recreation-hobbies',
     'Crafts, collecting, DIY projects, and hobby communities', NULL, 3, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-555555555555', 'Food & Drink', 'recreation-food',
     'Recipes, restaurants, cooking, and culinary culture', NULL, 4, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-555555555555', 'Outdoors', 'recreation-outdoors',
     'Hiking, camping, fishing, and outdoor activities', NULL, 5, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-555555555555', 'Fitness', 'recreation-fitness',
     'Exercise, gyms, workout routines, and health tracking', NULL, 6, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-555555555555', 'Automotive', 'recreation-automotive',
     'Cars, motorcycles, racing, and automotive enthusiasts', NULL, 7, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-555555555555', 'Pets', 'recreation-pets',
     'Pet care, training, adoption, and animal welfare', NULL, 8, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-555555555555', 'Home & Garden', 'recreation-home-garden',
     'Gardening, home improvement, and interior design', NULL, 9, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-555555555555', 'Fantasy Sports', 'recreation-fantasy-sports',
     'Fantasy football, baseball, basketball, and daily fantasy', NULL, 10, 0, true, NOW(), NOW()),

ON CONFLICT (slug) DO NOTHING;

-- =============================================================================
-- SCIENCE & TECHNOLOGY SUBCATEGORIES (parent: 11111111-2222-3333-4444-666666666666)
-- =============================================================================

INSERT INTO directory_categories (id, parent_id, name, slug, description, icon_url, sort_order, link_count, is_active, created_at, updated_at)
VALUES
    (gen_random_uuid(), '11111111-2222-3333-4444-666666666666', 'Engineering', 'science-engineering',
     'Civil, mechanical, electrical, and engineering disciplines', NULL, 1, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-666666666666', 'Biology', 'science-biology',
     'Life sciences, genetics, ecology, and biological research', NULL, 2, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-666666666666', 'Physics', 'science-physics',
     'Quantum mechanics, astrophysics, and theoretical physics', NULL, 3, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-666666666666', 'Chemistry', 'science-chemistry',
     'Organic chemistry, materials science, and chemical research', NULL, 4, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-666666666666', 'Space', 'science-space',
     'Astronomy, space exploration, NASA, and planetary science', NULL, 5, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-666666666666', 'Environment', 'science-environment',
     'Climate change, conservation, and environmental science', NULL, 6, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-666666666666', 'Mathematics', 'science-mathematics',
     'Pure and applied mathematics, statistics, and theory', NULL, 7, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-666666666666', 'Medicine', 'science-medicine',
     'Medical research, health studies, and clinical trials', NULL, 8, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-666666666666', 'Psychology', 'science-psychology',
     'Behavioral science, cognitive studies, and mental health research', NULL, 9, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-666666666666', 'Earth Science', 'science-earth',
     'Geology, oceanography, meteorology, and earth systems', NULL, 10, 0, true, NOW(), NOW()),

ON CONFLICT (slug) DO NOTHING;

-- =============================================================================
-- SOCIETY & CULTURE SUBCATEGORIES (parent: 11111111-2222-3333-4444-777777777777)
-- =============================================================================

INSERT INTO directory_categories (id, parent_id, name, slug, description, icon_url, sort_order, link_count, is_active, created_at, updated_at)
VALUES
    (gen_random_uuid(), '11111111-2222-3333-4444-777777777777', 'Religion', 'society-religion',
     'Faith communities, theology, and religious resources', NULL, 1, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-777777777777', 'Politics & Government', 'society-politics',
     'Political parties, government agencies, and civic engagement', NULL, 2, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-777777777777', 'History', 'society-history',
     'Historical events, archives, and historical research', NULL, 3, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-777777777777', 'Education', 'society-education',
     'Schools, universities, online learning, and educational resources', NULL, 4, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-777777777777', 'Nonprofits', 'society-nonprofits',
     'Charities, foundations, and nonprofit organizations', NULL, 5, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-777777777777', 'Law', 'society-law',
     'Legal resources, courts, and legal profession', NULL, 6, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-777777777777', 'People', 'society-people',
     'Genealogy, biographies, and personal homepages', NULL, 7, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-777777777777', 'Organizations', 'society-organizations',
     'Associations, clubs, and community organizations', NULL, 8, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-777777777777', 'Issues', 'society-issues',
     'Social issues, activism, and advocacy', NULL, 9, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-777777777777', 'Relationships', 'society-relationships',
     'Dating, marriage, family, and relationship advice', NULL, 10, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-777777777777', 'Ethnicity & Culture', 'society-ethnicity',
     'Cultural heritage, ethnic communities, and diversity', NULL, 11, 0, true, NOW(), NOW()),
    (gen_random_uuid(), '11111111-2222-3333-4444-777777777777', 'Philosophy', 'society-philosophy',
     'Ethics, logic, metaphysics, and philosophical thought', NULL, 12, 0, true, NOW(), NOW()),

ON CONFLICT (slug) DO NOTHING;
