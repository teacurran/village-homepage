package villagecompute.homepage.api.types;

/**
 * DTO for sitemap URL entries.
 * <p>
 * Represents a single URL entry in a sitemap XML file conforming to sitemaps.org protocol.
 *
 * @param location
 *            Absolute URL (must start with http:// or https://)
 * @param lastModified
 *            Last modification date in ISO 8601 format (YYYY-MM-DD)
 * @param changeFreq
 *            Expected change frequency (always, hourly, daily, weekly, monthly, yearly, never)
 * @param priority
 *            Page importance (0.0 to 1.0, default 0.5)
 */
public record SitemapUrlType(String location, String lastModified, String changeFreq, double priority) {
}
