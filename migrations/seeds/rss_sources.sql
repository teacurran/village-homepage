-- Manual RSS source seed helper. Execute via:
--   psql -h $POSTGRES_HOST -p $POSTGRES_PORT -U $POSTGRES_USER -d $POSTGRES_DB -f migrations/seeds/rss_sources.sql

INSERT INTO rss_sources (id, name, url, category, is_system, user_id, refresh_interval_minutes, is_active, created_at, updated_at)
VALUES
    -- Technology Feeds
    (gen_random_uuid(), 'TechCrunch', 'https://techcrunch.com/feed/', 'Technology', true, null, 60, true, NOW(), NOW()),
    (gen_random_uuid(), 'Hacker News', 'https://news.ycombinator.com/rss', 'Technology', true, null, 60, true, NOW(), NOW()),
    (gen_random_uuid(), 'Ars Technica', 'https://feeds.arstechnica.com/arstechnica/index', 'Technology', true, null, 60, true, NOW(), NOW()),
    (gen_random_uuid(), 'The Verge', 'https://www.theverge.com/rss/index.xml', 'Technology', true, null, 60, true, NOW(), NOW()),

    -- Business & Finance Feeds
    (gen_random_uuid(), 'Reuters Business', 'https://www.reutersagency.com/feed/?taxonomy=best-topics&post_type=best', 'Business & Finance', true, null, 15, true, NOW(), NOW()),
    (gen_random_uuid(), 'Financial Times', 'https://www.ft.com/?format=rss', 'Business & Finance', true, null, 15, true, NOW(), NOW()),
    (gen_random_uuid(), 'MarketWatch', 'https://www.marketwatch.com/rss/', 'Business & Finance', true, null, 15, true, NOW(), NOW()),

    -- World News Feeds
    (gen_random_uuid(), 'BBC World News', 'https://feeds.bbci.co.uk/news/world/rss.xml', 'World News', true, null, 15, true, NOW(), NOW()),
    (gen_random_uuid(), 'The Guardian International', 'https://www.theguardian.com/international/rss', 'World News', true, null, 15, true, NOW(), NOW()),
    (gen_random_uuid(), 'Al Jazeera', 'https://www.aljazeera.com/xml/rss/all.xml', 'World News', true, null, 15, true, NOW(), NOW()),

    -- Science & Health Feeds
    (gen_random_uuid(), 'Scientific American', 'https://www.scientificamerican.com/feed/', 'Science & Health', true, null, 360, true, NOW(), NOW()),
    (gen_random_uuid(), 'Nature News', 'https://www.nature.com/nature.rss', 'Science & Health', true, null, 360, true, NOW(), NOW()),
    (gen_random_uuid(), 'Science Daily', 'https://www.sciencedaily.com/rss/all.xml', 'Science & Health', true, null, 360, true, NOW(), NOW()),

    -- Politics Feeds
    (gen_random_uuid(), 'Politico', 'https://www.politico.com/rss/politics08.xml', 'Politics', true, null, 30, true, NOW(), NOW()),
    (gen_random_uuid(), 'The Hill', 'https://thehill.com/feed/', 'Politics', true, null, 30, true, NOW(), NOW()),

    -- Entertainment Feeds
    (gen_random_uuid(), 'Entertainment Weekly', 'https://ew.com/feed/', 'Entertainment', true, null, 120, true, NOW(), NOW()),
    (gen_random_uuid(), 'Variety', 'https://variety.com/feed/', 'Entertainment', true, null, 120, true, NOW(), NOW()),

    -- Sports Feeds
    (gen_random_uuid(), 'ESPN', 'https://www.espn.com/espn/rss/news', 'Sports', true, null, 30, true, NOW(), NOW()),
    (gen_random_uuid(), 'Sports Illustrated', 'https://www.si.com/rss/si_topstories.rss', 'Sports', true, null, 30, true, NOW(), NOW())

ON CONFLICT (url) DO UPDATE
    SET name = EXCLUDED.name,
        category = EXCLUDED.category,
        refresh_interval_minutes = EXCLUDED.refresh_interval_minutes,
        is_active = EXCLUDED.is_active,
        updated_at = NOW();
