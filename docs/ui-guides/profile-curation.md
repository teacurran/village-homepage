# Profile Curation Guide

This guide covers the curated article feature for user profiles, including feed picker, slot assignment, and metadata refresh.

## Overview

Users can curate articles on their public profiles by:
1. Selecting articles from existing RSS feed items
2. Adding manual URLs with automatic metadata extraction
3. Assigning articles to template-specific slots
4. Customizing article display (headline, blurb, image)

## Template Slot Capacity

Each profile template has different slot constraints:

### public_homepage (Flexible Grid)
- **Slot:** `grid`
- **Capacity:** Unlimited
- **Layout:** Flexible CSS grid with no hard positioning limits

### your_times (Newspaper Layout)
- **Slot:** `headline` - Capacity: **1** (main story)
- **Slot:** `secondary` - Capacity: **3** (supporting stories)
- **Slot:** `sidebar` - Capacity: **2** (sidebar content)
- **Layout:** Fixed newspaper-style grid

### your_report (Section-Based)
- **Slots:** `top_stories`, `business`, `technology`, `sports`, `entertainment`, `opinion`
- **Capacity:** Unlimited per section
- **Layout:** Section-based organization

## API Endpoints

### 1. Feed Picker (GET /api/profiles/{id}/feed-items)

Returns paginated list of recent RSS feed items for curation.

**Request:**
```http
GET /api/profiles/550e8400-e29b-41d4-a716-446655440000/feed-items?offset=0&limit=20
Authorization: Bearer <token>
```

**Response:**
```json
{
  "items": [
    {
      "id": "660e8400-e29b-41d4-a716-446655440000",
      "source_id": "770e8400-e29b-41d4-a716-446655440000",
      "title": "Breaking News: Important Event",
      "url": "https://example.com/article",
      "description": "Article summary...",
      "author": "Jane Doe",
      "published_at": "2026-01-20T10:00:00Z",
      "ai_tags": {
        "topics": ["politics", "economy"],
        "sentiment": "neutral",
        "categories": ["news"]
      },
      "ai_tagged": true,
      "fetched_at": "2026-01-20T10:05:00Z"
    }
  ],
  "offset": 0,
  "limit": 20,
  "total": 20
}
```

**Parameters:**
- `offset` (int, optional): Pagination offset (default: 0)
- `limit` (int, optional): Page size (default: 20, max: 100)

### 2. Add Article from Feed (POST /api/profiles/{id}/articles)

Add a curated article from an existing feed item.

**Request:**
```http
POST /api/profiles/550e8400-e29b-41d4-a716-446655440000/articles
Authorization: Bearer <token>
Content-Type: application/json

{
  "feed_item_id": "660e8400-e29b-41d4-a716-446655440000",
  "original_url": "https://example.com/article",
  "original_title": "Article Title",
  "original_description": "Article description",
  "original_image_url": "https://example.com/image.jpg"
}
```

**Response:**
```json
{
  "id": "770e8400-e29b-41d4-a716-446655440000",
  "profile_id": "550e8400-e29b-41d4-a716-446655440000",
  "feed_item_id": "660e8400-e29b-41d4-a716-446655440000",
  "original_url": "https://example.com/article",
  "original_title": "Article Title",
  "original_description": "Article description",
  "original_image_url": "https://example.com/image.jpg",
  "custom_headline": null,
  "custom_blurb": null,
  "custom_image_url": null,
  "effective_headline": "Article Title",
  "effective_description": "Article description",
  "effective_image_url": "https://example.com/image.jpg",
  "slot_assignment": {},
  "is_active": true,
  "created_at": "2026-01-20T12:00:00Z",
  "updated_at": "2026-01-20T12:00:00Z"
}
```

### 3. Add Manual Article (POST /api/profiles/{id}/articles)

Add a manually-entered article with automatic metadata fetch.

**Request:**
```http
POST /api/profiles/550e8400-e29b-41d4-a716-446655440000/articles
Authorization: Bearer <token>
Content-Type: application/json

{
  "original_url": "https://example.com/article",
  "original_title": "Manual Entry Title",
  "original_description": "Manual entry description",
  "original_image_url": "https://example.com/image.jpg"
}
```

**Note:** When `feed_item_id` is omitted, a metadata refresh job is automatically scheduled to fetch OpenGraph metadata (og:title, og:description, og:image) from the URL.

### 4. Assign to Slot (PUT /api/profiles/{id}/articles/{articleId}/slot)

Assign a curated article to a template slot.

**Request:**
```http
PUT /api/profiles/550e8400-e29b-41d4-a716-446655440000/articles/770e8400-e29b-41d4-a716-446655440000/slot
Authorization: Bearer <token>
Content-Type: application/json

{
  "template": "your_times",
  "slot": "headline",
  "position": 0,
  "custom_styles": {
    "font_size": "large",
    "text_align": "center"
  }
}
```

**Response:**
```json
{
  "id": "770e8400-e29b-41d4-a716-446655440000",
  "profile_id": "550e8400-e29b-41d4-a716-446655440000",
  "slot_assignment": {
    "template": "your_times",
    "slot": "headline",
    "position": 0,
    "custom_styles": {
      "font_size": "large",
      "text_align": "center"
    }
  },
  "is_active": true
}
```

**Validation Errors:**
- `400 Bad Request` - Invalid slot name for template
- `400 Bad Request` - Slot capacity exceeded
- `400 Bad Request` - Position out of range
- `403 Forbidden` - Not profile owner
- `404 Not Found` - Article or profile not found

### 5. Get Slot Info (GET /api/profiles/{id}/slots)

Get available slots and capacities for a template.

**Request:**
```http
GET /api/profiles/550e8400-e29b-41d4-a716-446655440000/slots?template=your_times
Authorization: Bearer <token>
```

**Response:**
```json
{
  "template": "your_times",
  "available_slots": ["headline", "secondary", "sidebar"],
  "slot_capacities": {
    "headline": 1,
    "secondary": 3,
    "sidebar": 2
  }
}
```

### 6. Update Article Customization (PUT /api/profiles/{id}/articles/{articleId})

Customize article display fields.

**Request:**
```http
PUT /api/profiles/550e8400-e29b-41d4-a716-446655440000/articles/770e8400-e29b-41d4-a716-446655440000
Authorization: Bearer <token>
Content-Type: application/json

{
  "custom_headline": "My Custom Headline",
  "custom_blurb": "My custom description for this article",
  "custom_image_url": "https://example.com/my-custom-image.jpg"
}
```

**Response:**
```json
{
  "id": "770e8400-e29b-41d4-a716-446655440000",
  "custom_headline": "My Custom Headline",
  "custom_blurb": "My custom description for this article",
  "custom_image_url": "https://example.com/my-custom-image.jpg",
  "effective_headline": "My Custom Headline",
  "effective_description": "My custom description for this article",
  "effective_image_url": "https://example.com/my-custom-image.jpg"
}
```

**Note:** `effective_*` fields show the display value (custom if set, otherwise original).

### 7. Remove Article (DELETE /api/profiles/{id}/articles/{articleId})

Deactivate a curated article (soft delete).

**Request:**
```http
DELETE /api/profiles/550e8400-e29b-41d4-a716-446655440000/articles/770e8400-e29b-41d4-a716-446655440000
Authorization: Bearer <token>
```

**Response:**
```http
204 No Content
```

## Metadata Refresh

### Automatic Refresh

When a manual article is added (no `feed_item_id`), a metadata refresh job is automatically scheduled to:
1. Fetch the URL via HTTP GET (5-second timeout)
2. Parse OpenGraph meta tags (og:title, og:description, og:image)
3. Fallback to standard HTML `<title>` and `<meta name="description">` if OpenGraph missing
4. Update `original_title`, `original_description`, `original_image_url` fields

### Refresh Schedule

- **Cadence:** Daily at 2am UTC
- **Job Type:** `PROFILE_METADATA_REFRESH`
- **Queue:** `DEFAULT`
- **Handler:** `ProfileMetadataRefreshJobHandler`

### Failure Handling

If metadata fetch fails (timeout, 404, invalid HTML):
- Job logs warning but does NOT fail
- Existing metadata is preserved
- Metric: `profile.metadata_refresh.total{status="failure"}` incremented

## UI Workflow

### Typical User Flow

1. **User navigates to profile editor** → Selects "Curate Articles" tab
2. **User clicks "Add Article"** → Modal shows two tabs:
   - **Feed Picker:** Browse recent RSS feed items with preview
   - **Manual Entry:** Enter URL with optional metadata override
3. **User selects article** → Article appears in staging area with default slot assignment
4. **User drags article to slot** → PUT request to `/api/profiles/{id}/articles/{articleId}/slot`
5. **User customizes headline/blurb** → PUT request to `/api/profiles/{id}/articles/{articleId}`
6. **User publishes profile** → Articles become visible at `/u/{username}`

### UI Hints for Slot State

The UI should display slot capacity hints:

```javascript
// Example Vue.js component logic
const slotState = {
  headline: { capacity: 1, filled: 1, available: 0 },
  secondary: { capacity: 3, filled: 1, available: 2 },
  sidebar: { capacity: 2, filled: 0, available: 2 }
};

// Visual indicators:
// - Green: Available slots (filled < capacity)
// - Orange: Partially filled (0 < filled < capacity)
// - Red: Full (filled === capacity)
```

## Error Handling

### Common Validation Errors

| Error | Status | Message | Solution |
|-------|--------|---------|----------|
| Invalid slot name | 400 | "Invalid slot for your_times template: invalid_slot (valid slots: [headline, secondary, sidebar])" | Use valid slot name from `GET /api/profiles/{id}/slots` |
| Slot full | 400 | "Slot 'headline' is full (capacity: 1, current: 1)" | Choose different slot or remove existing article |
| Position out of range | 400 | "Position 3 exceeds slot capacity (max: 2)" | Use position within slot capacity |
| Not owner | 403 | "Access denied" | User must own profile |
| Article not found | 404 | "Article not found: {id}" | Check article ID |
| Profile not found | 404 | "Profile not found: {id}" | Check profile ID |

## Testing

### Manual Testing Checklist

- [ ] Add article from feed picker → Verify metadata populated
- [ ] Add manual URL → Verify metadata refresh job scheduled
- [ ] Assign article to slot → Verify slot_assignment updated
- [ ] Try to overfill slot → Verify 400 error with capacity message
- [ ] Customize headline/blurb → Verify effective_* fields updated
- [ ] Remove article → Verify is_active=false
- [ ] Check public profile → Verify articles display correctly
- [ ] Wait for metadata refresh → Verify original_* fields updated

### API Testing (curl)

```bash
# 1. Get feed items
curl -H "Authorization: Bearer $TOKEN" \
  "https://homepage.villagecompute.com/api/profiles/550e8400-e29b-41d4-a716-446655440000/feed-items?limit=10"

# 2. Add article from feed
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"feed_item_id":"660e8400-e29b-41d4-a716-446655440000","original_url":"https://example.com/article","original_title":"Title","original_description":"Desc","original_image_url":"https://example.com/img.jpg"}' \
  "https://homepage.villagecompute.com/api/profiles/550e8400-e29b-41d4-a716-446655440000/articles"

# 3. Assign to slot
curl -X PUT -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"template":"your_times","slot":"headline","position":0,"custom_styles":{}}' \
  "https://homepage.villagecompute.com/api/profiles/550e8400-e29b-41d4-a716-446655440000/articles/770e8400-e29b-41d4-a716-446655440000/slot"

# 4. Get slot info
curl -H "Authorization: Bearer $TOKEN" \
  "https://homepage.villagecompute.com/api/profiles/550e8400-e29b-41d4-a716-446655440000/slots?template=your_times"
```

## Monitoring

### Metrics

- `profile.metadata_refresh.total{status="success"}` - Successful metadata refreshes
- `profile.metadata_refresh.total{status="failure"}` - Failed metadata refreshes
- `job.profile_metadata_refresh` - OpenTelemetry spans for job execution

### Logs

```bash
# View metadata refresh job logs
kubectl logs -l app=village-homepage --tail=100 | grep ProfileMetadataRefreshJobHandler

# View slot assignment logs
kubectl logs -l app=village-homepage --tail=100 | grep SlotCapacityValidator
```

## Architecture Notes

### Slot Assignment JSONB Schema

The `slot_assignment` field in `profile_curated_articles` table stores:

```json
{
  "template": "your_times",
  "slot": "headline",
  "position": 0,
  "custom_styles": {
    "font_size": "large"
  }
}
```

This design allows:
- Template-specific positioning without schema changes
- Custom styling per article
- Future extensibility (drag coordinates, z-index, etc.)

### Metadata Fetch Strategy

OpenGraph metadata fetch priority:
1. `og:title` → HTML `<title>` → Extract from URL
2. `og:description` → `<meta name="description">` → null
3. `og:image` → null (no fallback)

All fetched content is sanitized (HTML tags stripped) for XSS protection.

## Related Documentation

- **Feature Spec:** `docs/features/F11-public-profiles.md`
- **Database Schema:** `migrations/src/main/resources/migrations/` (profile_curated_articles table)
- **Job Architecture:** `docs/ops/async-workloads.md` (PROFILE_METADATA_REFRESH job)
