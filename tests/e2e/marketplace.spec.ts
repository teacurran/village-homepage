import { test, expect } from '@playwright/test';

/**
 * Marketplace E2E Tests for Village Homepage (I4.T9)
 *
 * Tests cover:
 * - Listing creation with payment
 * - Listing search and filtering
 * - Masked email contact
 * - Moderation (flagging)
 *
 * Note: These tests require a running Quarkus dev server with test data seeded.
 * Use `./mvnw quarkus:dev` or configure via playwright.config.ts webServer.
 */

test.describe('Marketplace - Listing Creation', () => {

  test.skip('creates listing with posting fee payment', async ({ page }) => {
    // Skip for now - requires Stripe test mode integration
    // TODO: Implement when Stripe mock is available

    await page.goto('/marketplace');
    await expect(page).toHaveTitle(/Village Homepage/);

    // Click "Post Listing" button
    await page.click('[data-testid="post-listing-btn"]');

    // Fill out listing form
    await page.fill('input[name="title"]', 'Vintage Road Bicycle');
    await page.fill('textarea[name="description"]', 'Well-maintained vintage road bike. Great condition, barely used. Original parts.');
    await page.selectOption('select[name="category"]', 'for-sale');
    await page.selectOption('select[name="subcategory"]', 'bicycles');
    await page.fill('input[name="price"]', '250');
    await page.fill('input[name="location"]', 'San Francisco, CA');

    // Submit form (triggers Stripe payment modal in real scenario)
    await page.click('button[type="submit"]:has-text("Continue to Payment")');

    // In test environment with Stripe testmode:
    // 1. Fill Stripe Elements iframe
    // 2. Submit payment
    // 3. Verify redirect to listing detail page
    // 4. Verify listing status is "active"

    // For now, just verify form validation works
    await expect(page.locator('form')).toBeVisible();
  });

  test.skip('validates listing form fields', async ({ page }) => {
    await page.goto('/marketplace/new');

    // Submit empty form
    await page.click('button[type="submit"]');

    // Verify validation errors
    await expect(page.locator('.ant-form-item-explain-error:has-text("Title is required")')).toBeVisible();
    await expect(page.locator('.ant-form-item-explain-error:has-text("Description is required")')).toBeVisible();
    await expect(page.locator('.ant-form-item-explain-error:has-text("Category is required")')).toBeVisible();
  });

  test.skip('enforces 12 image upload limit', async ({ page }) => {
    await page.goto('/marketplace/new');

    // Attempt to upload 13 images
    // Verify error message: "Maximum 12 images allowed"
    // Verify only first 12 images are accepted
  });
});

test.describe('Marketplace - Search and Filtering', () => {

  test('loads marketplace homepage', async ({ page }) => {
    await page.goto('/marketplace');

    // Verify page title
    await expect(page).toHaveTitle(/Marketplace.*Village Homepage/);

    // Verify search form is present
    await expect(page.locator('input[name="q"]')).toBeVisible();

    // Verify category filter is present
    await expect(page.locator('select[name="category"]')).toBeVisible();
  });

  test.skip('filters listings by category', async ({ page }) => {
    await page.goto('/marketplace/search');

    // Select "For Sale" category
    await page.selectOption('select[name="category"]', 'for-sale');
    await page.click('button:has-text("Search")');

    // Wait for results to load
    await page.waitForSelector('.listing-card', { state: 'visible' });

    // Verify all listings have "For Sale" category
    const categoryBadges = page.locator('.listing-card .category-badge');
    const count = await categoryBadges.count();

    for (let i = 0; i < count; i++) {
      const text = await categoryBadges.nth(i).textContent();
      expect(text).toContain('For Sale');
    }
  });

  test.skip('filters listings by price range', async ({ page }) => {
    await page.goto('/marketplace/search');

    // Set price range: $100 - $500
    await page.fill('input[name="min_price"]', '100');
    await page.fill('input[name="max_price"]', '500');
    await page.click('button:has-text("Search")');

    // Wait for results
    await page.waitForSelector('.listing-card', { state: 'visible' });

    // Verify all listings are within price range
    const prices = page.locator('.listing-card .listing-price');
    const count = await prices.count();

    for (let i = 0; i < count; i++) {
      const priceText = await prices.nth(i).textContent();
      const price = parseFloat(priceText!.replace(/[$,]/g, ''));
      expect(price).toBeGreaterThanOrEqual(100);
      expect(price).toBeLessThanOrEqual(500);
    }
  });

  test.skip('filters listings by radius', async ({ page }) => {
    await page.goto('/marketplace/search');

    // Enter location
    await page.fill('input[name="location"]', '94102'); // San Francisco zip code

    // Select 25 mile radius
    await page.selectOption('select[name="radius"]', '25');
    await page.click('button:has-text("Search")');

    // Wait for results
    await page.waitForSelector('.listing-card', { state: 'visible' });

    // Verify search param is set
    await expect(page).toHaveURL(/radius=25/);
    await expect(page).toHaveURL(/location=94102/);
  });

  test.skip('paginates search results', async ({ page }) => {
    await page.goto('/marketplace/search');

    // First page should have up to 25 results
    const page1Listings = page.locator('.listing-card');
    const page1Count = await page1Listings.count();
    expect(page1Count).toBeGreaterThan(0);
    expect(page1Count).toBeLessThanOrEqual(25);

    // Click "Next" button
    await page.click('button:has-text("Next")');

    // Verify URL has offset parameter
    await expect(page).toHaveURL(/offset=25/);

    // Second page should load
    await page.waitForSelector('.listing-card', { state: 'visible' });
    const page2Count = await page.locator('.listing-card').count();
    expect(page2Count).toBeGreaterThan(0);
  });

  test.skip('sorts search results', async ({ page }) => {
    await page.goto('/marketplace/search');

    // Sort by price (low to high)
    await page.selectOption('select[name="sort"]', 'price_asc');
    await page.click('button:has-text("Search")');

    // Wait for results
    await page.waitForSelector('.listing-card', { state: 'visible' });

    // Verify results are sorted correctly
    const prices = page.locator('.listing-card .listing-price');
    const count = await prices.count();
    const priceValues: number[] = [];

    for (let i = 0; i < count; i++) {
      const priceText = await prices.nth(i).textContent();
      const price = parseFloat(priceText!.replace(/[$,]/g, ''));
      priceValues.push(price);
    }

    // Verify ascending order
    for (let i = 1; i < priceValues.length; i++) {
      expect(priceValues[i]).toBeGreaterThanOrEqual(priceValues[i - 1]);
    }
  });

  test.skip('searches by keyword', async ({ page }) => {
    await page.goto('/marketplace/search');

    // Search for "bicycle"
    await page.fill('input[name="q"]', 'bicycle');
    await page.click('button:has-text("Search")');

    // Wait for results
    await page.waitForSelector('.listing-card', { state: 'visible' });

    // Verify all results contain "bicycle" in title or description
    const listings = page.locator('.listing-card');
    const count = await listings.count();

    for (let i = 0; i < count; i++) {
      const text = await listings.nth(i).textContent();
      const lowerText = text!.toLowerCase();
      expect(lowerText).toContain('bicycle');
    }
  });
});

test.describe('Marketplace - Listing Contact', () => {

  test.skip('sends message via masked email relay', async ({ page }) => {
    // Navigate to a listing detail page
    await page.goto('/marketplace/listings/test-listing-id');

    // Click "Contact Seller" button
    await page.click('button[data-testid="contact-seller-btn"]');

    // Fill message form
    await page.fill('input[name="sender_name"]', 'John Doe');
    await page.fill('input[name="sender_email"]', 'john@example.com');
    await page.fill('textarea[name="message"]', 'Is this item still available? Can you provide more details?');

    // Submit message
    await page.click('button[type="submit"]:has-text("Send Message")');

    // Verify success message
    await expect(page.locator('.ant-message-success')).toContainText('Message sent successfully');

    // Verify masked email notice
    await expect(page.locator('.masked-email-notice')).toContainText('Your email address will be protected');
  });

  test.skip('rate limits contact messages', async ({ page }) => {
    // Navigate to listing
    await page.goto('/marketplace/listings/test-listing-id');

    // Send multiple messages rapidly (exceeds rate limit)
    for (let i = 0; i < 6; i++) {
      await page.click('button[data-testid="contact-seller-btn"]');
      await page.fill('input[name="sender_name"]', 'Spammer');
      await page.fill('input[name="sender_email"]', 'spam@example.com');
      await page.fill('textarea[name="message"]', `Spam message ${i}`);
      await page.click('button[type="submit"]:has-text("Send Message")');

      if (i < 5) {
        // First 5 should succeed
        await expect(page.locator('.ant-message-success')).toBeVisible();
      } else {
        // 6th should be rate limited
        await expect(page.locator('.ant-message-error')).toContainText('Too many requests');
      }
    }
  });
});

test.describe('Marketplace - Moderation', () => {

  test.skip('flags inappropriate listing', async ({ page }) => {
    // Navigate to listing
    await page.goto('/marketplace/listings/test-listing-id');

    // Click "Flag" button
    await page.click('button[data-testid="flag-listing-btn"]');

    // Select flag reason
    await page.selectOption('select[name="reason"]', 'spam');
    await page.fill('textarea[name="details"]', 'This listing contains spam content.');

    // Submit flag
    await page.click('button[type="submit"]:has-text("Submit Flag")');

    // Verify success
    await expect(page.locator('.ant-message-success')).toContainText('Thank you for reporting');

    // Verify flag button is disabled (already flagged)
    await expect(page.locator('button[data-testid="flag-listing-btn"]')).toBeDisabled();
  });

  test.skip('admin reviews flagged listing', async ({ page }) => {
    // Login as admin
    // Navigate to moderation queue

    await page.goto('/admin/moderation/queue');

    // Verify flagged listings are displayed
    await expect(page.locator('.flagged-listing')).toBeVisible();

    // Click on first flagged listing
    await page.click('.flagged-listing:first-child');

    // Review flag details
    await expect(page.locator('.flag-reason')).toContainText('spam');

    // Take action: Remove listing
    await page.click('button:has-text("Remove Listing")');

    // Confirm action
    await page.click('button:has-text("Confirm")');

    // Verify listing is removed from queue
    await expect(page.locator('.ant-message-success')).toContainText('Listing removed');
  });
});

test.describe('Marketplace - Analytics Tracking', () => {

  test.skip('tracks listing view clicks', async ({ page }) => {
    // Intercept click tracking requests
    const clickTrackingRequests: any[] = [];
    page.on('request', request => {
      if (request.url().includes('/track/click')) {
        clickTrackingRequests.push({
          url: request.url(),
          postData: request.postData(),
        });
      }
    });

    // Navigate to search results
    await page.goto('/marketplace/search?q=bicycle');
    await page.waitForSelector('.listing-card', { state: 'visible' });

    // Click on first listing
    await page.click('.listing-card:first-child a');

    // Verify click tracking request was sent
    expect(clickTrackingRequests.length).toBeGreaterThan(0);

    const trackingData = JSON.parse(clickTrackingRequests[0].postData);
    expect(trackingData.context).toBe('marketplace_listing');
    expect(trackingData.metadata.source).toBe('search_results');
    expect(trackingData.metadata.search_query).toBe('bicycle');
  });
});
