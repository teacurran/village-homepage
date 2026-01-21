# Profile Templates Guide

This guide explains the three customizable profile template types available in Village Homepage: **Public Homepage**, **Your Times**, and **Your Report**. Each template offers unique layouts and customization options.

## Overview

Profile templates allow users to create personalized public pages at `/u/{username}` with different layouts optimized for various content presentation styles.

### Template Types

| Template | Description | Best For | Layout Style |
|----------|-------------|----------|--------------|
| `public_homepage` | Customizable homepage with drag-and-drop widgets | Personal portals, aggregated content | Gridstack (12-column grid) |
| `your_times` | Newspaper-style layout with article slots | News curation, editorial content | CSS Grid (newspaper format) |
| `your_report` | Three-column link aggregator | Link collections, resource lists | Drudge Report style |

---

## Public Homepage Template

### Description

A flexible, gridstack-based layout similar to traditional homepage portals (Yahoo, iGoogle). Users can drag and drop widgets to create personalized layouts.

### Configuration Schema

```json
{
  "header_text": "My Custom Homepage",
  "accent_color": "#1890ff",
  "widgets": [
    {
      "type": "news",
      "position": { "x": 0, "y": 0, "w": 6, "h": 4 }
    },
    {
      "type": "weather",
      "position": { "x": 6, "y": 0, "w": 6, "h": 4 }
    }
  ],
  "custom_blocks": [
    {
      "content": "<p>Welcome to my page!</p>",
      "position": { "x": 0, "y": 4, "w": 12, "h": 2 }
    }
  ]
}
```

### Configuration Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `header_text` | string | No | Custom header text (defaults to "{displayName}'s Homepage") |
| `accent_color` | string | No | Hex color code for accents (default: #1890ff) |
| `widgets` | array | No | Array of widget configurations with positions |
| `custom_blocks` | array | No | Array of custom HTML content blocks |

### Widget Types

- `news` - Displays recent curated articles
- `weather` - Weather widget (placeholder)
- `custom` - Custom HTML content

### Widget Position Format

Each widget has a `position` object with:
- `x` - Horizontal position (0-11, based on 12-column grid)
- `y` - Vertical position (grid row index)
- `w` - Width in columns (1-12)
- `h` - Height in grid units

### Responsive Behavior

The gridstack layout adapts to screen size:
- **Desktop (≥992px)**: 12 columns
- **Tablet (768-991px)**: 6 columns
- **Mobile (<768px)**: 1 column (stacked)

### Customization UI

Users can edit the Public Homepage template through:
1. **Header Text Input** - Change the page title
2. **Accent Color Picker** - Choose theme color
3. **Drag-and-Drop Editor** - Rearrange widgets directly on the profile page

---

## Your Times Template

### Description

A newspaper-style layout inspired by traditional print media. Features a masthead, main headline, secondary stories, and sidebar articles.

### Configuration Schema

```json
{
  "masthead_text": "The Daily Times",
  "tagline": "All the news that's fit to curate",
  "color_scheme": "classic",
  "slots": {
    "main_headline": {
      "article_id": "uuid",
      "custom_headline": null,
      "custom_blurb": null
    },
    "secondary_1": {
      "article_id": "uuid",
      "custom_headline": "Breaking News"
    },
    "secondary_2": {
      "article_id": "uuid"
    },
    "sidebar_1": {
      "article_id": "uuid"
    },
    "sidebar_2": {
      "article_id": "uuid"
    }
  }
}
```

### Configuration Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `masthead_text` | string | No | Newspaper title (defaults to "{displayName} Times") |
| `tagline` | string | No | Subtitle/motto for the publication |
| `color_scheme` | string | No | Color theme: `classic`, `modern`, or `minimal` |
| `slots` | object | No | Article slot assignments (5 slots) |

### Slot Structure

Each template has **5 article slots**:

| Slot Name | Location | Prominence | Supports Image |
|-----------|----------|------------|----------------|
| `main_headline` | Top left | Primary | Yes (large) |
| `secondary_1` | Middle left | Secondary | Yes (small) |
| `secondary_2` | Middle left | Secondary | Yes (small) |
| `sidebar_1` | Right sidebar | Tertiary | No |
| `sidebar_2` | Right sidebar | Tertiary | No |

### Slot Fields

Each slot object can contain:
- `article_id` (string, UUID) - Reference to curated article
- `custom_headline` (string, optional) - Override article title
- `custom_blurb` (string, optional) - Override article description
- `custom_image_url` (string, optional) - Override article image

### Responsive Behavior

- **Desktop (≥768px)**: 2-column layout (main + sidebar)
- **Tablet/Mobile (<768px)**: Single column, stacked in order (main → secondary → sidebar)

### Customization UI

Users can edit the Your Times template through:
1. **Masthead Editor** - Set title and tagline
2. **Color Scheme Picker** - Choose theme (classic, modern, minimal)
3. **Slot Assignment Interface** - Assign curated articles to slots
4. **Article Customization** - Override headline/blurb per slot

### Empty Slot Handling

If a slot is not assigned an article, the template displays:
> "Add a story or hide slot"

This prompts users to either fill the slot or configure it as hidden.

---

## Your Report Template

### Description

A three-column link aggregator layout inspired by Drudge Report. Focuses on clean, text-heavy presentation with minimal images.

### Configuration Schema

```json
{
  "main_header": "Weekly Report",
  "uppercase_style": true,
  "headline_photo_url": "https://example.com/image.jpg",
  "columns": [
    {
      "section_header": "Technology",
      "items": [
        {
          "article_id": "uuid",
          "custom_headline": null
        }
      ]
    },
    {
      "section_header": "Business",
      "items": [
        {
          "article_id": "uuid"
        }
      ]
    },
    {
      "section_header": "Science",
      "items": [
        {
          "article_id": "uuid"
        }
      ]
    }
  ]
}
```

### Configuration Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `main_header` | string | No | Page title (defaults to "{displayName}'s Report") |
| `uppercase_style` | boolean | No | Apply uppercase styling to all text (default: false) |
| `headline_photo_url` | string | No | Header image URL (optional) |
| `columns` | array | No | Array of column configurations |

### Column Structure

Each column object contains:
- `section_header` (string) - Column title (e.g., "Technology", "Politics")
- `items` (array) - List of article items in this column

### Item Structure

Each item object contains:
- `article_id` (string, UUID) - Reference to curated article
- `custom_headline` (string, optional) - Override article title

### Responsive Behavior

- **Desktop (≥992px)**: 3 columns side by side
- **Tablet (768-991px)**: 2 columns
- **Mobile (<768px)**: Single column, stacked

### Customization UI

Users can edit the Your Report template through:
1. **Main Header Input** - Set page title
2. **Headline Photo URL** - Provide header image URL
3. **Uppercase Style Toggle** - Enable/disable uppercase text
4. **Column Manager** - Add/remove columns and assign section headers
5. **Item Manager** - Add articles to columns with custom headlines

---

## SEO & Metadata

All templates include complete SEO metadata automatically:

### Standard Meta Tags
- `<title>` - Page title with username
- `<meta name="description">` - Profile bio or template-specific description

### Open Graph Protocol
- `og:type` - Set to "profile"
- `og:title` - Display name or template title
- `og:description` - Bio or template description
- `og:image` - Avatar, headline photo, or default image
- `og:url` - Canonical profile URL

### Twitter Card
- `twitter:card` - Summary or summary_large_image
- `twitter:title` - Same as og:title
- `twitter:description` - Same as og:description
- `twitter:image` - Same as og:image

### Canonical URL
- `<link rel="canonical">` - Points to `https://homepage.villagecompute.com/u/{username}`

### SEO Best Practices

1. **Unique Titles**: Each template generates a unique page title based on configuration
2. **Rich Descriptions**: Use masthead tagline or bio for descriptions
3. **Images**: Provide high-quality images (1200x630px recommended for OG images)
4. **Mobile-Friendly**: All templates are fully responsive

---

## Switching Templates

Users can switch between templates at any time. Template changes:

1. **Preserve compatible fields** - Common fields like `header_text` are preserved where applicable
2. **Reset template-specific config** - Slot assignments and layout configurations are template-specific and won't transfer
3. **Require republish** - After switching templates, users should preview and republish

### Migration Example

If a user switches from `your_times` to `your_report`:
- ✅ Curated articles remain assigned to profile
- ❌ Your Times slot assignments are not transferred
- ⚠️ User must reassign articles to Your Report columns

---

## Template Validation

All template configurations are validated server-side before saving:

### Public Homepage Validation
- `accent_color` must be valid hex color code (#RGB or #RRGGBB)
- Widget positions must be within 12-column grid bounds

### Your Times Validation
- `color_scheme` must be one of: `classic`, `modern`, `minimal`
- Slot names must match: `main_headline`, `secondary_1`, `secondary_2`, `sidebar_1`, `sidebar_2`

### Your Report Validation
- `columns` must be an array if provided
- Each column must have a `section_header` string
- Items must reference valid article IDs

---

## Preview Mode

All templates support **preview mode** at `/u/{username}/preview`:
- **Owner-only access** - Only profile owners can preview unpublished profiles
- **No view count increment** - Preview visits don't affect analytics
- **Real-time updates** - See changes immediately before publishing

---

## Responsive Design Summary

All templates follow mobile-first responsive design:

| Breakpoint | Size | Layout Behavior |
|------------|------|-----------------|
| xs | <576px | Single column, stacked |
| sm | ≥576px | Mobile landscape, small tablets |
| md | ≥768px | Tablets (Your Times switches to 1 column) |
| lg | ≥992px | Desktops (Your Report switches to 3 columns) |
| xl | ≥1200px | Large desktops |
| xxl | ≥1600px | Extra large screens |

---

## Common Troubleshooting

### Problem: "This slot is empty" warning

**Cause**: Article slot has no assigned article.

**Solution**:
1. Navigate to profile editor
2. Open slot assignment interface
3. Select an article from your curated list
4. Save configuration

### Problem: Template configuration not saving

**Cause**: Invalid configuration format (e.g., invalid color code, unknown slot name).

**Solution**:
1. Check browser console for validation errors
2. Ensure all required fields are valid
3. Verify article IDs exist in your curated articles
4. Contact support if issue persists

### Problem: Template looks different on mobile

**Cause**: Expected responsive behavior.

**Solution**: All templates use responsive layouts. Use the device preview tabs in the editor to see how your profile will appear on different screen sizes.

---

## API Endpoints

For programmatic access:

### Update Template Configuration
```http
PUT /api/profiles/{id}/template
Content-Type: application/json

{
  "template": "your_times",
  "template_config": {
    "masthead_text": "The Daily Times",
    "tagline": "All the news that's fit to curate"
  }
}
```

### Get Slot Information
```http
GET /api/profiles/{id}/slots?template=your_times

Response:
{
  "template": "your_times",
  "slots": {
    "main_headline": { "capacity": 1, "required": true },
    "secondary_1": { "capacity": 1, "required": false },
    ...
  }
}
```

---

## Related Documentation

- [Profile Curation Guide](./profile-curation.md) - How to curate articles for your profile
- [UI/UX Architecture](../architecture/06_UI_UX_Architecture.md) - Component design patterns
- [Feature Specification F11](../architecture/03_Feature_Specification.md) - Public profiles feature spec

---

**Last Updated**: 2026-01-21
**Version**: 1.0
**Contact**: support@villagecompute.com
