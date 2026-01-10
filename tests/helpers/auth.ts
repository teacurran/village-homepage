import { Page } from '@playwright/test';

/**
 * Authentication helper for E2E tests.
 *
 * Provides utilities for:
 * - Test user login
 * - Admin user login
 * - Anonymous session management
 * - Cookie/session handling
 */

/**
 * Logs in a test user via OAuth provider test mode.
 *
 * @param page Playwright page object
 * @param email User email
 * @param provider OAuth provider (google, facebook, apple)
 */
export async function loginAsUser(page: Page, email: string = 'test@example.com', provider: string = 'google') {
  // Navigate to login page
  await page.goto(`/auth/login?provider=${provider}&test_mode=true&test_email=${encodeURIComponent(email)}`);

  // Wait for redirect to homepage after successful login
  await page.waitForURL('/');

  // Verify user is logged in by checking for user menu or profile link
  await page.waitForSelector('[data-testid="user-menu"]', { state: 'visible' });
}

/**
 * Logs in as admin user for moderation tests.
 *
 * @param page Playwright page object
 */
export async function loginAsAdmin(page: Page) {
  await loginAsUser(page, 'admin@villagecompute.com', 'google');

  // Verify admin menu is visible
  await page.waitForSelector('[data-testid="admin-menu"]', { state: 'visible' });
}

/**
 * Logs out the current user.
 *
 * @param page Playwright page object
 */
export async function logout(page: Page) {
  await page.click('[data-testid="user-menu"]');
  await page.click('button:has-text("Logout")');

  // Wait for redirect to homepage
  await page.waitForURL('/');
}

/**
 * Gets the current anonymous session cookie.
 *
 * @param page Playwright page object
 * @returns Anonymous session ID or null if not set
 */
export async function getAnonymousSessionId(page: Page): Promise<string | null> {
  const cookies = await page.context().cookies();
  const sessionCookie = cookies.find(c => c.name === 'vu_anon_id');
  return sessionCookie?.value || null;
}

/**
 * Clears all session cookies to reset user state.
 *
 * @param page Playwright page object
 */
export async function clearSession(page: Page) {
  await page.context().clearCookies();
}
