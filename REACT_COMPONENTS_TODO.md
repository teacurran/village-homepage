# React Components TODO for Good Sites

## VoteButtons Component

**File:** `frontend/src/components/GoodSites/VoteButtons.tsx` (to be created)

**Purpose:** Interactive voting controls for Good Sites directory with optimistic UI updates.

**Props:**
```typescript
interface VoteButtonsProps {
  siteCategoryId: string;
  score: number;
  upvotes: number;
  downvotes: number;
  userVote: number | null; // +1, -1, or null
  isAuthenticated: boolean;
}
```

**Features:**
- Display current score and vote counts
- Upvote/downvote buttons with color-coded states (green/red)
- Optimistic UI: update score immediately on click
- AJAX POST to `/api/good-sites/vote` with siteCategoryId and vote value
- Rollback on failure with toast notification
- Disable buttons if not authenticated (show tooltip "Login to vote")
- Rate limit handling (50 votes/hour) with error message
- Accessibility: ARIA labels, keyboard navigation, semantic icons

**Implementation Notes:**
- Use Fetch API for voting requests
- Handle 429 rate limit errors gracefully
- Display loading state during vote submission
- Icons: ▲ (upvote) and ▼ (downvote) for color-blind accessibility
- Add animation on successful vote

**Mount Point:**
Templates include `data-mount="VoteButtons"` attributes with JSON props.

**Example Usage:**
```html
<div class="site-vote"
     data-mount="VoteButtons"
     data-props='{"siteCategoryId":"123","score":42,"upvotes":45,"downvotes":3,"userVote":1,"isAuthenticated":true}'>
</div>
```

## Integration with Existing React Setup

Good Sites templates reference `/assets/js/good-sites.js` which should be added to the React islands build:

1. Add `GoodSites/VoteButtons.tsx` to `frontend/src/components/`
2. Export from `frontend/src/mounts.ts`:
   ```typescript
   export { VoteButtons } from './components/GoodSites/VoteButtons';
   ```
3. Update build config to generate `good-sites.js` bundle
4. Hydrate on `data-mount="VoteButtons"` attributes

## CSS Integration

Vote button styles already defined in `/assets/css/good-sites.css`:
- `.vote-btn` - Base button styles
- `.vote-btn.vote-up:hover` - Green hover for upvote
- `.vote-btn.vote-down:hover` - Red hover for downvote
- `.vote-btn.voted-up` - Active upvote state
- `.vote-btn.voted-down` - Active downvote state
- `.vote-score` - Score display

## API Endpoints

**Cast Vote:**
```
POST /api/good-sites/vote
Content-Type: application/json

{
  "site_category_id": "uuid",
  "vote": 1  // or -1
}

Response 200:
{
  "site_category_id": "uuid",
  "score": 43,
  "upvotes": 46,
  "downvotes": 3,
  "user_vote": 1
}

Response 400 (Rate Limited):
{
  "message": "Vote rate limit exceeded. Please try again later."
}
```

**Remove Vote:**
```
DELETE /api/good-sites/vote/{siteCategoryId}

Response 204 No Content
```

## Testing

Create integration test:
```java
@QuarkusTest
public class VoteButtonsTest {
    @Test
    public void testOptimisticVoting() {
        // Test vote submission with optimistic update
        // Verify rollback on failure
        // Test rate limiting
    }
}
```
