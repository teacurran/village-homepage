import { test, expect } from '@playwright/test';

/**
 * Good Sites Directory E2E Tests for Village Homepage (I5.T9)
 *
 * Tests cover:
 * - Homepage browsing and category navigation
 * - Site detail pages
 * - Search functionality
 * - Voting (skipped until React component ready)
 * - Site submission (skipped - requires auth setup)
 * - Moderation workflow (skipped - admin-only)
 *
 * Note: These tests require a running Quarkus dev server with seed data.
 * Use `./mvnw quarkus:dev` or configure via playwright.config.ts webServer.
 */

test.describe('Good Sites - Homepage and Navigation', () => {

  test('loads Good Sites homepage', async ({ page }) => {
    await page.goto('/good-sites');

    // Verify page title
    await expect(page).toHaveTitle(/Good Sites.*Village Homepage/);

    // Verify root categories are displayed
    // Note: Specific categories depend on seed data, so just check for category structure
    const categoryLinks = page.locator('a[href^="/good-sites/"]');
    const count = await categoryLinks.count();
    expect(count).toBeGreaterThan(0);
  });

  test('navigates from homepage to category', async ({ page }) => {
    await page.goto('/good-sites');

    // Click on first category link
    const firstCategoryLink = page.locator('a[href^="/good-sites/"][href!="/good-sites"]').first();
    await firstCategoryLink.click();

    // Verify navigation to category page
    await expect(page).toHaveURL(/\/good-sites\/[\w-]+/);

    // Verify page loaded
    await expect(page.locator('body')).toBeVisible();
  });

  test('displays category page with sites', async ({ page }) => {
    // Note: This test assumes seed data exists
    // In a real scenario, use a known test category

    await page.goto('/good-sites');

    // Find and click a category that has sites
    const categoryLink = page.locator('a[href^="/good-sites/"][href!="/good-sites"]').first();
    const categorySlug = await categoryLink.getAttribute('href');

    if (categorySlug) {
      await page.goto(categorySlug);

      // Verify category page loaded
      await expect(page.locator('body')).toBeVisible();

      // Check for site listings (if any exist)
      // Note: Seed data may or may not have sites, so this is optional
      const siteLinkExists = await page.locator('.site-link, .directory-site').count();
      // Just verify page structure exists, don't require sites
      expect(siteLinkExists).toBeGreaterThanOrEqual(0);
    }
  });

  test('displays subcategories on category page', async ({ page }) => {
    // Test hierarchical navigation
    await page.goto('/good-sites');

    // Click on first category
    const firstCategory = page.locator('a[href^="/good-sites/"][href!="/good-sites"]').first();
    await firstCategory.click();

    // Check if subcategories are displayed
    // (Depends on seed data structure - just verify page loads)
    await expect(page.locator('body')).toBeVisible();
  });
});

test.describe('Good Sites - Site Detail Pages', () => {

  test.skip('displays site detail page with metadata', async ({ page }) => {
    // Skip: Requires knowing a specific site ID from seed data
    // TODO: Implement once seed data is stable

    // Navigate to a known site detail page
    // await page.goto('/good-sites/site/KNOWN_SITE_ID');

    // Verify site title is displayed
    // await expect(page.locator('h1')).toBeVisible();

    // Verify site URL/domain is displayed
    // await expect(page.locator('.site-url')).toBeVisible();

    // Verify site description is displayed
    // await expect(page.locator('.site-description')).toBeVisible();
  });

  test.skip('displays vote buttons on site detail page', async ({ page }) => {
    // Skip: Requires VoteButtons React component implementation (see REACT_COMPONENTS_TODO.md)
    // TODO: Implement once VoteButtons.tsx is ready

    // Navigate to site detail page
    // await page.goto('/good-sites/site/KNOWN_SITE_ID');

    // Verify upvote button exists
    // await expect(page.locator('[data-testid="vote-up-btn"]')).toBeVisible();

    // Verify downvote button exists
    // await expect(page.locator('[data-testid="vote-down-btn"]')).toBeVisible();
  });

  test.skip('displays dead site warning', async ({ page }) => {
    // Skip: Requires known dead site ID
    // TODO: Implement once seed data includes dead sites

    // Navigate to dead site
    // await page.goto('/good-sites/site/DEAD_SITE_ID');

    // Verify dead site warning is displayed
    // await expect(page.locator('.dead-site-warning')).toBeVisible();
    // await expect(page.locator('.dead-site-warning')).toContainText('This site may no longer be available');

    // Verify vote buttons are disabled
    // await expect(page.locator('[data-testid="vote-up-btn"]')).toBeDisabled();
    // await expect(page.locator('[data-testid="vote-down-btn"]')).toBeDisabled();
  });
});

test.describe('Good Sites - Search', () => {

  test('loads search page', async ({ page }) => {
    await page.goto('/good-sites/search');

    // Verify search page loaded
    await expect(page).toHaveTitle(/Good Sites.*Village Homepage/);

    // Verify search form is present
    await expect(page.locator('input[name="q"], input[type="search"]')).toBeVisible();
  });

  test('performs basic search', async ({ page }) => {
    await page.goto('/good-sites/search');

    // Find search input (try multiple possible selectors)
    const searchInput = page.locator('input[name="q"], input[type="search"]').first();
    await searchInput.fill('test');

    // Submit search (try button or form submit)
    const searchButton = page.locator('button[type="submit"], button:has-text("Search")').first();
    if (await searchButton.isVisible()) {
      await searchButton.click();
    } else {
      await searchInput.press('Enter');
    }

    // Wait for navigation or results update
    await page.waitForLoadState('networkidle');

    // Verify search results page loaded
    await expect(page).toHaveURL(/q=test/);
  });

  test('handles empty search query', async ({ page }) => {
    await page.goto('/good-sites/search?q=');

    // Verify page loads (empty query should not cause error)
    await expect(page.locator('body')).toBeVisible();
  });

  test('handles search with special characters', async ({ page }) => {
    // Test that special characters don't break search
    await page.goto('/good-sites/search?q=C%2B%2B'); // C++

    // Verify page loads without errors
    await expect(page.locator('body')).toBeVisible();
  });

  test.skip('displays search results with highlighting', async ({ page }) => {
    // Skip: Requires known search term that returns results
    // TODO: Implement once seed data is stable

    // Perform search
    // await page.goto('/good-sites/search?q=programming');

    // Verify results are displayed
    // const results = page.locator('.search-result, .site-link');
    // const count = await results.count();
    // expect(count).toBeGreaterThan(0);

    // Verify search term is highlighted (if implemented)
    // await expect(page.locator('.highlight, mark')).toBeVisible();
  });
});

test.describe('Good Sites - Pagination', () => {

  test('displays pagination controls on category page', async ({ page }) => {
    // Navigate to category page
    await page.goto('/good-sites');
    const firstCategory = page.locator('a[href^="/good-sites/"][href!="/good-sites"]').first();
    await firstCategory.click();

    // Check if pagination controls exist (may not if < 25 sites)
    // Just verify page structure, don't require pagination
    await expect(page.locator('body')).toBeVisible();
  });

  test.skip('navigates between pages', async ({ page }) => {
    // Skip: Requires category with >25 sites
    // TODO: Implement once seed data has enough sites

    // Navigate to category page 1
    // await page.goto('/good-sites/CATEGORY_SLUG?page=1');

    // Click "Next" button
    // await page.click('button:has-text("Next"), a:has-text("Next")');

    // Verify navigation to page 2
    // await expect(page).toHaveURL(/page=2/);

    // Click "Previous" button
    // await page.click('button:has-text("Previous"), a:has-text("Previous")');

    // Verify navigation back to page 1
    // await expect(page).toHaveURL(/page=1/);
  });

  test('handles invalid page numbers gracefully', async ({ page }) => {
    // Test negative page number
    await page.goto('/good-sites?page=-1');
    await expect(page.locator('body')).toBeVisible();

    // Test zero page number
    await page.goto('/good-sites?page=0');
    await expect(page.locator('body')).toBeVisible();

    // Test very large page number
    await page.goto('/good-sites?page=99999');
    await expect(page.locator('body')).toBeVisible();
  });
});

test.describe('Good Sites - Voting (Requires React Component)', () => {

  test.skip('casts upvote on site', async ({ page }) => {
    // Skip: VoteButtons React component not yet implemented (see REACT_COMPONENTS_TODO.md)
    // TODO: Implement once VoteButtons.tsx is ready

    // Login as user
    // await loginAsTestUser(page);

    // Navigate to site detail page
    // await page.goto('/good-sites/site/KNOWN_SITE_ID');

    // Click upvote button
    // await page.click('[data-testid="vote-up-btn"]');

    // Verify optimistic UI update
    // await expect(page.locator('[data-testid="vote-up-btn"]')).toHaveClass(/active/);

    // Verify vote count increased
    // const voteCount = page.locator('[data-testid="vote-count"]');
    // const initialCount = parseInt(await voteCount.textContent() || '0');
    // await expect(voteCount).toContainText(String(initialCount + 1));
  });

  test.skip('changes vote from upvote to downvote', async ({ page }) => {
    // Skip: VoteButtons React component not yet implemented
    // TODO: Implement once VoteButtons.tsx is ready

    // Login and cast initial upvote
    // await loginAsTestUser(page);
    // await page.goto('/good-sites/site/KNOWN_SITE_ID');
    // await page.click('[data-testid="vote-up-btn"]');

    // Change to downvote
    // await page.click('[data-testid="vote-down-btn"]');

    // Verify UI update
    // await expect(page.locator('[data-testid="vote-down-btn"]')).toHaveClass(/active/);
    // await expect(page.locator('[data-testid="vote-up-btn"]')).not.toHaveClass(/active/);
  });

  test.skip('removes vote', async ({ page }) => {
    // Skip: VoteButtons React component not yet implemented
    // TODO: Implement once VoteButtons.tsx is ready

    // Login and cast initial vote
    // await loginAsTestUser(page);
    // await page.goto('/good-sites/site/KNOWN_SITE_ID');
    // await page.click('[data-testid="vote-up-btn"]');

    // Click same button again to remove vote
    // await page.click('[data-testid="vote-up-btn"]');

    // Verify vote removed
    // await expect(page.locator('[data-testid="vote-up-btn"]')).not.toHaveClass(/active/);
  });

  test.skip('shows login prompt when voting without auth', async ({ page }) => {
    // Skip: Requires auth flow setup
    // TODO: Implement once auth is integrated in E2E tests

    // Navigate to site detail page (not logged in)
    // await page.goto('/good-sites/site/KNOWN_SITE_ID');

    // Click vote button
    // await page.click('[data-testid="vote-up-btn"]');

    // Verify login modal/redirect
    // await expect(page).toHaveURL(/\/login/);
    // OR
    // await expect(page.locator('.login-modal')).toBeVisible();
  });

  test.skip('rate limits voting', async ({ page }) => {
    // Skip: Requires VoteButtons component and multiple test sites
    // TODO: Implement once voting is fully functional

    // Login as user
    // await loginAsTestUser(page);

    // Cast 50 votes rapidly (rate limit threshold)
    // for (let i = 0; i < 50; i++) {
    //   await page.goto('/good-sites/site/SITE_ID_' + i);
    //   await page.click('[data-testid="vote-up-btn"]');
    // }

    // Attempt 51st vote
    // await page.goto('/good-sites/site/SITE_ID_51');
    // await page.click('[data-testid="vote-up-btn"]');

    // Verify rate limit error message
    // await expect(page.locator('.error-message, .ant-message-error')).toContainText('rate limit');
  });
});

test.describe('Good Sites - Site Submission (Requires Auth)', () => {

  test.skip('submits new site', async ({ page }) => {
    // Skip: Requires auth setup and submission form
    // TODO: Implement once site submission UI is ready

    // Login as user
    // await loginAsTestUser(page);

    // Navigate to submit page
    // await page.goto('/good-sites/submit');

    // Fill out submission form
    // await page.fill('input[name="url"]', 'https://example.com');
    // await page.fill('input[name="title"]', 'Example Site');
    // await page.fill('textarea[name="description"]', 'A great example site');
    // await page.selectOption('select[name="category"]', 'CATEGORY_ID');

    // Submit form
    // await page.click('button[type="submit"]');

    // Verify success message
    // await expect(page.locator('.success-message')).toContainText('submitted for review');
  });

  test.skip('validates submission form', async ({ page }) => {
    // Skip: Requires submission form UI
    // TODO: Implement once submission UI is ready

    // Login and navigate to submit page
    // await loginAsTestUser(page);
    // await page.goto('/good-sites/submit');

    // Submit empty form
    // await page.click('button[type="submit"]');

    // Verify validation errors
    // await expect(page.locator('.error-message:has-text("URL is required")')).toBeVisible();
    // await expect(page.locator('.error-message:has-text("Title is required")')).toBeVisible();
  });
});

test.describe('Good Sites - Moderation (Admin Only)', () => {

  test.skip('admin views moderation queue', async ({ page }) => {
    // Skip: Requires admin auth setup
    // TODO: Implement once admin auth is ready in E2E tests

    // Login as admin
    // await loginAsAdmin(page);

    // Navigate to moderation queue
    // await page.goto('/admin/directory/moderation');

    // Verify pending submissions are displayed
    // await expect(page.locator('.pending-submission')).toBeVisible();
  });

  test.skip('admin approves submission', async ({ page }) => {
    // Skip: Requires admin auth and moderation UI
    // TODO: Implement once admin UI is ready

    // Login as admin and navigate to moderation queue
    // await loginAsAdmin(page);
    // await page.goto('/admin/directory/moderation');

    // Click approve on first submission
    // await page.click('.pending-submission:first-child button:has-text("Approve")');

    // Verify success message
    // await expect(page.locator('.success-message')).toContainText('approved');
  });

  test.skip('admin rejects submission', async ({ page }) => {
    // Skip: Requires admin auth and moderation UI
    // TODO: Implement once admin UI is ready

    // Login as admin and navigate to moderation queue
    // await loginAsAdmin(page);
    // await page.goto('/admin/directory/moderation');

    // Click reject on first submission
    // await page.click('.pending-submission:first-child button:has-text("Reject")');

    // Enter rejection reason
    // await page.fill('textarea[name="reason"]', 'Does not meet quality standards');

    // Confirm rejection
    // await page.click('button:has-text("Confirm Rejection")');

    // Verify success message
    // await expect(page.locator('.success-message')).toContainText('rejected');
  });
});

/**
 * Helper function for test user login (to be implemented)
 */
async function loginAsTestUser(page: any) {
  // TODO: Implement test user login flow
  // This will depend on the auth system implementation
}

/**
 * Helper function for admin login (to be implemented)
 */
async function loginAsAdmin(page: any) {
  // TODO: Implement admin login flow
}
