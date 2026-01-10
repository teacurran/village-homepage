# Good Sites Directory UX Guide

## Overview

This document outlines the user experience patterns, accessibility features, and interaction semantics for the Good Sites web directory browsing interface.

**Related Files:**
- Templates: `src/main/resources/templates/GoodSitesResource/`
- Styles: `src/main/resources/META-INF/resources/assets/css/good-sites.css`
- REST Resource: `src/main/java/villagecompute/homepage/api/rest/GoodSitesResource.java`
- Design Doc: `docs/architecture/06_UI_UX_Architecture.md`

---

## Page Hierarchy

```
/good-sites (Homepage)
├── /good-sites/{slug} (Category Page)
│   ├── Direct sites in category
│   ├── Bubbled sites from child categories
│   └── Subcategory links
├── /good-sites/site/{id} (Site Detail Page)
│   ├── Site metadata
│   ├── Screenshot/preview
│   └── Category memberships with voting
├── /good-sites/search?q=query (Search Results)
└── /good-sites/moderate (Moderation Queue - Admin Only)
```

---

## Accessibility Features

### Keyboard Navigation

All interactive elements are keyboard-accessible:

- **Tab Navigation:** Move through links, buttons, form controls
- **Enter/Space:** Activate buttons and links
- **Arrow Keys:** Navigate breadcrumb trail
- **Escape:** Close modals (e.g., rejection reason dialog)

### ARIA Labels

Vote buttons include descriptive labels for screen readers:

```html
<button class="vote-btn vote-up" aria-label="Upvote Hacker News">
  <span class="vote-icon" aria-hidden="true">▲</span>
</button>
```

**Key ARIA Attributes:**
- `aria-label` - Descriptive labels for vote buttons
- `aria-current="page"` - Current breadcrumb item
- `aria-hidden="true"` - Decorative icons

### Color-Blind Accessibility

Vote buttons use **semantic icons** in addition to color:

- **Upvote:** Green background (#52c41a) + ▲ triangle icon
- **Downvote:** Red background (#ff4d4f) + ▼ triangle icon

Never rely on color alone to convey voting state.

### Focus Indicators

All interactive elements have visible focus outlines:

```css
.vote-btn:focus {
    outline: 2px solid var(--color-primary, #1677ff);
    outline-offset: 2px;
}
```

### Screen Reader Announcements

Vote actions trigger live region updates:

```html
<div role="status" aria-live="polite" class="sr-only">
  Vote submitted successfully. New score: 43
</div>
```

---

## Voting Interaction Patterns

### Anonymous Users

- Vote buttons are **disabled** (grayed out)
- Tooltip on hover: "Login to vote"
- Current score and vote counts are **visible** (read-only)

### Authenticated Users

- Vote buttons are **enabled**
- Click upvote (▲) to increase score by 1
- Click downvote (▼) to decrease score by 1
- Click same button again to remove vote

### Optimistic UI Updates

1. User clicks upvote button
2. **Immediately** update UI:
   - Button turns green
   - Score increments by 1
   - Upvote count increments
3. Send AJAX POST to `/api/good-sites/vote`
4. On success: Keep optimistic update
5. On failure: **Rollback** to previous state and show toast error

**Error Messages:**
- Network error: "Vote failed. Please try again."
- Rate limit: "Vote rate limit exceeded. Please try again in X minutes."
- Auth error: "You must be logged in to vote."

### Rate Limiting

- **Limit:** 50 votes per hour per user
- **Enforcement:** Server-side via `RateLimitService`
- **User Feedback:** Toast notification with retry time

---

## Bubbled Link Semantics

### What is Bubbling?

Sites that meet these criteria in child categories "bubble up" to parent categories:

1. **Score ≥ 10** (net upvotes - downvotes)
2. **Rank ≤ 3** (top 3 in child category)

### Visual Indicators

Bubbled sites display with:

- **Light yellow background** (#fff9e6) to differentiate from direct sites
- **Green badge** with source category name: `From Programming > Java`
- **Tooltip** explaining bubbling threshold on badge hover

**Badge HTML:**
```html
<span class="bubble-badge" title="Top-ranked site from Programming">
  From Programming
</span>
```

### Sorting Order

On category pages:
1. **Direct sites** (sorted by score DESC)
2. **Bubbled sites** (sorted by score DESC)

This ensures users see category-specific content first.

### Accessibility Note

Badge includes `title` attribute for tooltip and is read by screen readers. Consider adding `aria-describedby` for enhanced context.

### Ranking & Bubbling Operations

**Background Process:** The `RankRecalculationJobHandler` runs hourly to update site rankings within each category.

**Ranking Algorithm:**
1. Query approved sites in category
2. Order by score DESC, then createdAt DESC (ties broken by earlier submission)
3. Assign 1-indexed rank (rank 1 = highest score)
4. Update `directory_site_categories.rank_in_category` field

**Bubbling Query:**
- Runs when rendering parent category pages
- Finds sites in child categories matching:
  - `status = 'approved'`
  - `score >= 10`
  - `rankInCategory <= 3`
- Orders results by score DESC

**Example Bubbling Behavior:**

```
Parent: Computers & Internet
├── Child: Programming (has 50 approved sites)
│   ├── GitHub (score=25, rank=1) → BUBBLES to parent
│   ├── Stack Overflow (score=18, rank=2) → BUBBLES to parent
│   ├── LeetCode (score=12, rank=3) → BUBBLES to parent
│   └── FreeCodeCamp (score=8, rank=4) → Does NOT bubble (score < 10)
├── Child: Linux (has 30 approved sites)
│   ├── Ubuntu (score=20, rank=1) → BUBBLES to parent
│   └── Arch Wiki (score=9, rank=2) → Does NOT bubble (score < 10)
```

Result: "Computers & Internet" category page displays 4 bubbled sites (GitHub, Ubuntu, Stack Overflow, LeetCode) in addition to its directly-assigned sites.

**Performance Note:** Bubbling queries are cached for 5 minutes to avoid performance impact on high-traffic parent categories.

---

## Search UX

### Search Input

- **Placeholder:** "Search sites..."
- **Auto-focus:** Yes (on search page)
- **Debounce:** Not implemented (server-side search)

### Search Results

- **Empty State:** "No results found for '{query}'. Try different keywords or browse categories."
- **Results Display:** Same site card layout as category pages
- **Limit:** 50 results maximum
- **Sorting:** Relevance (currently by title match, will use Elasticsearch score in I5.T7+)

### Search Metadata

Each result includes:
- Site title (linked to external URL via click tracking)
- Description snippet
- Domain
- Current score
- "View details" link to site detail page

---

## Click Tracking

All external links route through click tracking redirect:

```html
<a href="/track/click?url={site.url}&source=good_sites_category&metadata={...}"
   target="_blank"
   rel="noopener noreferrer">
```

**Tracking Sources:**
- `good_sites_home` - Clicked from homepage popular sites
- `good_sites_category` - Clicked from category page
- `good_sites_bubbled` - Clicked from bubbled site
- `good_sites_search` - Clicked from search results

**Metadata Captured:**
- Category ID
- Site ID
- Rank in category
- Search query (if applicable)

---

## Pagination

### Page Size

- **50 sites per page** (direct sites only)
- Bubbled sites appear on all pages (not paginated)

### Pagination Controls

```
[← Previous] Page 1 of 5 [Next →]
```

- Previous link disabled on page 1
- Next link disabled on last page
- Page number always visible
- Query param: `?page=2`

### SEO Considerations

- Each page has unique `<link rel="canonical">` tag
- Pagination links use `rel="prev"` and `rel="next"` (to be added in I5.T9)

---

## Moderation Queue UX

### Queue Display

Table columns:
1. **Checkbox** - Select for bulk actions
2. **Site** - Title, description, domain
3. **Category** - Category name (linked)
4. **Submitted By** - Email, trust level, submission date
5. **Karma** - Submitter's karma score (color-coded)
6. **Preview** - Screenshot or OG image thumbnail
7. **Actions** - Approve, Reject buttons

### Karma Color Coding

- **Low (0-10):** Yellow (#ffd666)
- **Medium (11-50):** Orange (#fa8c16)
- **High (51+):** Dark orange (#d46b08)

### Bulk Actions

1. Select multiple rows via checkboxes
2. Click "Bulk Approve" or "Bulk Reject"
3. Confirmation dialog: "Approve 5 selected submissions?"
4. Process sequentially with progress indicator
5. Show summary: "4 approved, 1 failed"

### Rejection Workflow

1. Click "Reject" button
2. Modal opens with reason textarea (required)
3. Reason options:
   - Spam
   - Broken link
   - Wrong category
   - Inappropriate content
   - Other (with notes)
4. Click "Confirm Rejection"
5. Modal closes, row removed from queue
6. Toast notification: "Submission rejected successfully"

### Empty State

When queue is empty:
```
No pending submissions. Great job!
[Back to Directory]
```

---

## Responsive Design

### Breakpoints

- **Desktop:** ≥ 768px
  - Category grid: 3 columns
  - Site thumbnails: 120x90px on right
- **Mobile:** < 768px
  - Category grid: 1 column
  - Site thumbnails: Full width, 100% x 180px
  - Vote buttons: Larger touch targets (min 44x44px)

### Mobile-Specific Enhancements

- Sticky header with "Back" button
- Simplified breadcrumb (ellipsize long paths)
- Collapsible subcategory list (accordion)
- Bottom-aligned vote buttons for easier thumb access

---

## Error States

### Dead Link Warning

Sites marked `isDead=true` display:

```
⚠ Warning: This link may be broken. Last checked: 2026-01-09
```

**Visual Treatment:**
- Red warning badge
- Grayed-out vote buttons (disabled)
- Strikethrough title (optional)

**Health Check Process:**

The `LinkHealthCheckJobHandler` runs weekly (Sundays at 3am UTC) to detect dead links:

1. **Check:** Perform HTTP HEAD request to site URL (10 second timeout)
2. **Fallback:** If HEAD returns 405 Method Not Allowed, try GET request
3. **Pass:** Status codes 200-399 → reset failure counter
4. **Fail:** Status codes 400-599 or timeout → increment failure counter
5. **Dead:** After 3 consecutive failures → mark site as dead

**Failure Counter:**
- Stored in `directory_sites.health_check_failures`
- Resets to 0 on successful check
- Increments on each failed check
- Threshold: 3 consecutive failures

**Recovery:**
- Dead sites continue to be checked weekly
- If site becomes accessible, failure counter resets
- Status remains "dead" until moderator manually restores
- Moderators notified via email when sites marked dead (TODO: implement notification)

**Example Timeline:**

```
Week 1: Check fails (404) → healthCheckFailures = 1
Week 2: Check fails (timeout) → healthCheckFailures = 2
Week 3: Check fails (500) → healthCheckFailures = 3, isDead = true
Week 4: Site accessible again (200) → healthCheckFailures = 0, isDead still true
        (Moderator manually changes status to 'approved')
```

For operational details, see `docs/ops/link-health-monitoring.md`.

### Network Errors

Vote failure toast:
```
[×] Vote failed. Please try again.
```

- Auto-dismiss after 5 seconds
- Manual dismiss via × button
- Red background (#ff4d4f)

### Authentication Errors

Login prompt for anonymous users:
```
Login to vote on sites and track your submissions.
[Login with Google]
```

---

## Performance Considerations

### Lazy Loading

- Site thumbnails use `loading="lazy"` attribute
- Below-the-fold images deferred until scroll
- Reduces initial page load by ~40%

### Caching

- Category pages cached for 5 minutes (server-side)
- Vote counts cached in `directory_site_categories.score`
- Invalidation on vote, approval, or rank update

### Progressive Enhancement

- Core browsing works without JavaScript (server-side rendered)
- Voting requires JS (React islands)
- Graceful degradation: disabled vote buttons if JS fails to load

---

## Future Enhancements (Post I5)

1. **Elasticsearch Integration** (I5.T7+)
   - Full-text search with relevance scoring
   - Faceted filtering by category
   - Auto-complete suggestions

2. **E2E Tests** (I5.T9)
   - Playwright tests for voting flow
   - Screenshot comparison for visual regression
   - Accessibility audits with axe-core

3. **Advanced Moderation** (I5.T8)
   - Category-specific moderators
   - Moderation history timeline
   - Automated spam detection

4. **User Profiles**
   - User's submission history
   - Karma leaderboard
   - Badge system for contributions

---

## Design System Reference

### Color Tokens

From `06_UI_UX_Architecture.md`:

- `--color-accent-lime: #a0d911` - Bubbled badges, karma highlights
- `--color-primary: #1677ff` - Links, primary buttons
- `--color-success: #52c41a` - Upvote buttons
- `--color-error: #ff4d4f` - Downvote buttons, dead link badges
- `--color-warning: #faad14` - Rank badges

### Typography

- **Display Large:** 2.5rem - Category page titles
- **Text Base:** 1rem - Site titles, descriptions
- **Text Small:** 0.875rem - Metadata, timestamps

### Spacing

- **Container max-width:** 1200px
- **Section margin:** 2-3rem
- **Card padding:** 1.5rem
- **Gap (grid/flex):** 1rem

---

## Testing Checklist

- [ ] Homepage loads with 7 root categories
- [ ] Category page shows approved sites sorted by score
- [ ] Bubbled sites display with "From {category}" badge
- [ ] Voting buttons update score optimistically
- [ ] Login-required message shown for anonymous users
- [ ] Rate limit enforced (50 votes/hour)
- [ ] Pagination works for categories with >50 sites
- [ ] Search returns relevant results
- [ ] Moderation queue accessible to admins only
- [ ] Click tracking redirects work for external links
- [ ] Keyboard navigation works for all controls
- [ ] Screen reader announces vote state changes
- [ ] Focus indicators visible on all interactive elements

---

## Conclusion

The Good Sites directory UX prioritizes **accessibility**, **progressive enhancement**, and **optimistic UI** to create a fast, inclusive browsing experience. Voting interactions follow established patterns (Reddit/HackerNews) while adding directory-specific features like category bubbling and karma-based trust.

For implementation details, see:
- Backend: `src/main/java/villagecompute/homepage/api/rest/GoodSitesResource.java`
- Templates: `src/main/resources/templates/GoodSitesResource/`
- Styles: `src/main/resources/META-INF/resources/assets/css/good-sites.css`
