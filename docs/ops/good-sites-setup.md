# Good Sites Directory - Category Management

This document describes category management operations for the Good Sites web directory (Feature F13.1).

## Overview

The Good Sites directory uses a hierarchical category system (Yahoo Directory / DMOZ style) with:
- **7 root categories**: Arts, Business, Computers, News, Recreation, Science, Society
- **~130 subcategories** across all roots
- **Unlimited depth** for future expansion (currently 2 levels)
- **Admin-only CRUD** via REST API (super_admin role required)

## Database Schema

### Table: directory_categories

```sql
CREATE TABLE directory_categories (
    id UUID PRIMARY KEY,
    parent_id UUID REFERENCES directory_categories(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    slug TEXT NOT NULL UNIQUE,
    description TEXT,
    icon_url TEXT,
    sort_order INT NOT NULL DEFAULT 0,
    link_count INT NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
```

**Key Fields:**
- `parent_id`: NULL for root categories, UUID for children (cascades on delete)
- `slug`: Globally unique URL identifier (e.g., "computers-opensource")
- `link_count`: Cached count updated by hourly rank job (do NOT manually modify)
- `icon_url`: Optional 32px icon URL (hosted in R2 or external CDN)

## Initial Setup

### 1. Apply Migration

```bash
cd /path/to/village-homepage/migrations
mvn migration:up -Dmigration.env=development
```

This creates the `directory_categories` table with all indexes.

### 2. Load Seed Data

```bash
psql -h $POSTGRES_HOST -p $POSTGRES_PORT -U $POSTGRES_USER -d $POSTGRES_DB \
     -f migrations/seeds/directory_categories.sql
```

This populates ~130 categories (7 roots + ~123 children).

**Verify seed data:**
```sql
-- Should return 7 root categories
SELECT * FROM directory_categories WHERE parent_id IS NULL ORDER BY sort_order;

-- Should return ~130 total categories
SELECT COUNT(*) FROM directory_categories;

-- Check specific hierarchy (Computers category)
SELECT c.name, c.slug, c.sort_order
FROM directory_categories c
WHERE c.parent_id = (SELECT id FROM directory_categories WHERE slug = 'computers')
ORDER BY c.sort_order;
```

## Category Management

### Admin REST API

All endpoints require `super_admin` role (Policy I2.T8).

**Base URL:** `https://homepage.villagecompute.com/admin/api/directory/categories`

### List All Categories

```bash
curl -X GET https://homepage.villagecompute.com/admin/api/directory/categories \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**Response:** Flat list ordered by `parent_id NULLS FIRST, sort_order`.

### Get Category Tree

```bash
curl -X GET https://homepage.villagecompute.com/admin/api/directory/categories/tree \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**Response:** Hierarchical tree with nested children.

```json
[
  {
    "id": "11111111-2222-3333-4444-333333333333",
    "name": "Computers & Internet",
    "slug": "computers",
    "sort_order": 3,
    "children": [
      {
        "id": "...",
        "name": "Software",
        "slug": "computers-software",
        "sort_order": 1,
        "children": []
      }
    ]
  }
]
```

### Get Single Category

```bash
curl -X GET https://homepage.villagecompute.com/admin/api/directory/categories/{id} \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

### Create Category

```bash
curl -X POST https://homepage.villagecompute.com/admin/api/directory/categories \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Artificial Intelligence",
    "slug": "computers-ai",
    "description": "AI, machine learning, and neural networks",
    "parent_id": "11111111-2222-3333-4444-333333333333",
    "icon_url": null,
    "sort_order": 20,
    "is_active": true
  }'
```

**Validation Rules:**
- `slug`: Lowercase letters, numbers, hyphens only (e.g., `computers-ai`)
- `slug`: Must be globally unique (not scoped to parent)
- `parent_id`: Must reference existing category (or NULL for root)
- `name`: 1-100 characters
- `description`: Max 500 characters (optional)

### Update Category

```bash
curl -X PATCH https://homepage.villagecompute.com/admin/api/directory/categories/{id} \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "AI & Machine Learning",
    "description": "Updated description with more detail",
    "sort_order": 11,
    "is_active": true
  }'
```

**Partial Updates:** Only provided fields are updated (null fields ignored).

### Delete Category

```bash
curl -X DELETE https://homepage.villagecompute.com/admin/api/directory/categories/{id} \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**Safety Checks:**
- Returns `409 Conflict` if category has children (delete children first)
- Returns `409 Conflict` if category has associated sites (reassign sites first)

## Common Operations

### Reorder Categories

To change category display order, update `sort_order` fields:

```bash
# Move "Software" to top position (sort_order = 1)
curl -X PATCH https://homepage.villagecompute.com/admin/api/directory/categories/{software-id} \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sort_order": 1}'
```

Categories within the same parent are sorted by `sort_order` ascending.

### Disable Category

To hide a category from public directory (admin can still see it):

```bash
curl -X PATCH https://homepage.villagecompute.com/admin/api/directory/categories/{id} \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"is_active": false}'
```

### Add Category Icon

Icon URLs should point to 32px SVG or PNG icons (simple line art style):

```bash
curl -X PATCH https://homepage.villagecompute.com/admin/api/directory/categories/{id} \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"icon_url": "https://cdn.villagecompute.com/icons/computers.svg"}'
```

**Icon Guidelines:**
- Format: SVG preferred, PNG acceptable
- Size: 32x32 pixels
- Style: Simple line art, single color
- Hosting: Cloudflare R2 or external CDN

## Advanced: Direct Database Access

For bulk operations, connect directly to PostgreSQL:

```bash
psql -h $POSTGRES_HOST -p $POSTGRES_PORT -U $POSTGRES_USER -d $POSTGRES_DB
```

### Find Categories by Parent

```sql
SELECT id, name, slug, sort_order, link_count, is_active
FROM directory_categories
WHERE parent_id = '11111111-2222-3333-4444-333333333333'  -- Computers
ORDER BY sort_order;
```

### Count Sites per Category

```sql
SELECT c.name, c.slug, c.link_count
FROM directory_categories c
WHERE c.is_active = true AND c.link_count > 0
ORDER BY c.link_count DESC
LIMIT 20;
```

**Note:** `link_count` is updated hourly by background job. Do NOT manually modify.

### Recursive Category Path

Get full breadcrumb path for a category:

```sql
WITH RECURSIVE category_path AS (
  SELECT id, parent_id, name, slug, 1 as depth
  FROM directory_categories
  WHERE slug = 'computers-software-opensource'
  UNION ALL
  SELECT dc.id, dc.parent_id, dc.name, dc.slug, cp.depth + 1
  FROM directory_categories dc
  JOIN category_path cp ON dc.id = cp.parent_id
)
SELECT name, slug, depth FROM category_path ORDER BY depth DESC;
```

**Result:**
```
name                  | slug                           | depth
----------------------|--------------------------------|------
Computers & Internet  | computers                      | 3
Software              | computers-software             | 2
Open Source           | computers-software-opensource  | 1
```

## Monitoring

### Category Health Checks

```sql
-- Categories without any sites (may need promotion)
SELECT name, slug, link_count
FROM directory_categories
WHERE is_active = true AND link_count = 0
ORDER BY name;

-- Inactive categories (hidden from public)
SELECT name, slug, updated_at
FROM directory_categories
WHERE is_active = false
ORDER BY updated_at DESC;

-- Orphaned categories (parent deleted but child remains - should never happen due to CASCADE)
SELECT c.name, c.slug, c.parent_id
FROM directory_categories c
WHERE c.parent_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM directory_categories p WHERE p.id = c.parent_id);
```

### Performance Queries

```sql
-- Top 10 most popular categories (by site count)
SELECT name, slug, link_count
FROM directory_categories
WHERE is_active = true
ORDER BY link_count DESC
LIMIT 10;

-- Categories added in last 30 days
SELECT name, slug, created_at
FROM directory_categories
WHERE created_at > NOW() - INTERVAL '30 days'
ORDER BY created_at DESC;
```

## Troubleshooting

### Duplicate Slug Error

**Error:** `409 Conflict - Category slug already exists: computers`

**Solution:** Slugs must be globally unique. Use a different slug (e.g., `computers-new` or `tech-computers`).

### Parent Not Found Error

**Error:** `409 Conflict - Parent category not found: 00000000-0000-0000-0000-000000000000`

**Solution:** Verify parent category exists. Use tree endpoint to see full hierarchy:
```bash
curl -X GET https://homepage.villagecompute.com/admin/api/directory/categories/tree \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq '.[] | {id, name}'
```

### Cannot Delete Category

**Error:** `409 Conflict - Cannot delete category with children`

**Solution:** Delete or reassign child categories first. List children:
```sql
SELECT id, name, slug FROM directory_categories WHERE parent_id = '{parent-id}';
```

## Integration with Good Sites Features

### Karma System (I5.T4)

Categories link to moderators via `directory_category_moderators` table. Moderators can only moderate sites in their assigned categories.

### Site Submissions (I5.T2)

Sites can exist in multiple categories via `directory_site_categories` junction table. Each site-category relationship has separate vote counts.

### Bubbling Algorithm (I5.T5)

Top-ranked sites in child categories can "bubble up" to parent categories based on vote thresholds. Category hierarchy determines bubbling paths.

## Security

- **Admin API:** Requires `super_admin` role (enforced by `@RolesAllowed`)
- **Rate Limiting:** Category mutations rate-limited per admin user
- **Audit Trail:** All category changes logged to feature flag audit table
- **Slug Validation:** Enforced at database level (unique index) and API level (regex pattern)

## Support

For issues with category management:
1. Check logs: `kubectl logs -n homepage deployment/homepage-api --tail=100`
2. Verify database state: Connect via psql and run health checks above
3. Contact Platform team: Slack #homepage-support
