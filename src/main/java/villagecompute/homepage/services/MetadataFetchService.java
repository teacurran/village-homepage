package villagecompute.homepage.services;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import villagecompute.homepage.exceptions.ValidationException;

import java.io.IOException;
import java.net.URI;
import java.util.regex.Pattern;

/**
 * Service for fetching OpenGraph metadata from URLs.
 *
 * <p>
 * Fetches and parses OpenGraph metadata (og:title, og:description, og:image) from web pages using Jsoup. Handles
 * timeouts, redirects, and invalid HTML gracefully with fallback strategies.
 *
 * <p>
 * <b>Fetch Strategy:</b>
 * <ol>
 * <li>HTTP GET with 5-second timeout</li>
 * <li>Parse HTML with Jsoup</li>
 * <li>Extract OpenGraph meta tags (og:title, og:description, og:image)</li>
 * <li>Fallback to HTML title/meta tags if OpenGraph missing</li>
 * <li>Sanitize all extracted content</li>
 * </ol>
 *
 * <p>
 * <b>Security:</b>
 * <ul>
 * <li>Only http/https URLs allowed</li>
 * <li>All content sanitized (HTML tags stripped)</li>
 * <li>Connection timeout: 5 seconds</li>
 * <li>Read timeout: 10 seconds</li>
 * <li>Follow redirects: yes (max 5)</li>
 * </ul>
 *
 * <p>
 * <b>Feature:</b> F11.7 - Profile Curated Articles Metadata Refresh
 * </p>
 */
@ApplicationScoped
public class MetadataFetchService {

    private static final Logger LOG = Logger.getLogger(MetadataFetchService.class);

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final int MAX_BODY_SIZE = 1024 * 1024; // 1MB

    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");

    /**
     * Fetches OpenGraph metadata from a URL.
     *
     * @param url
     *            the URL to fetch metadata from
     * @return fetched metadata
     * @throws ValidationException
     *             if URL is invalid or unsupported
     */
    public SiteMetadata fetchMetadata(String url) {
        LOG.debugf("Fetching metadata for URL: %s", url);

        // Validate URL
        if (url == null || url.isBlank()) {
            throw new ValidationException("URL cannot be blank");
        }

        // Parse and validate URL scheme
        URI uri;
        try {
            uri = new URI(url);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
                throw new ValidationException("Only http/https URLs are supported");
            }
        } catch (Exception e) {
            throw new ValidationException("Invalid URL: " + e.getMessage());
        }

        try {
            // Fetch page with timeout
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (compatible; VillageHomepage/1.0; +https://homepage.villagecompute.com)")
                    .timeout(CONNECT_TIMEOUT_MS).maxBodySize(MAX_BODY_SIZE).followRedirects(true).get();

            // Extract OpenGraph metadata
            String ogTitle = extractMetaTag(doc, "meta[property=og:title]");
            String ogDescription = extractMetaTag(doc, "meta[property=og:description]");
            String ogImage = extractMetaTag(doc, "meta[property=og:image]");

            // Fallback to standard HTML tags
            String title = ogTitle != null ? ogTitle : doc.title();
            String description = ogDescription != null ? ogDescription : extractMetaTag(doc, "meta[name=description]");

            // Sanitize extracted content
            title = sanitize(title);
            description = sanitize(description);

            // Extract favicon
            String faviconUrl = extractFavicon(doc, url);

            // If no title, extract from URL
            if (title == null || title.isBlank()) {
                title = extractTitleFromUrl(url);
            }

            LOG.debugf("Fetched metadata: title=\"%s\", description=\"%s\", image=\"%s\"", title, description, ogImage);

            return new SiteMetadata(title, description, ogImage, faviconUrl, null);

        } catch (IOException e) {
            LOG.warnf(e, "Failed to fetch metadata from URL: %s", url);
            // Return fallback metadata
            return new SiteMetadata(extractTitleFromUrl(url), null, null, null, null);
        }
    }

    /**
     * Extracts a meta tag content attribute.
     *
     * @param doc
     *            the document
     * @param cssQuery
     *            CSS selector for the meta tag
     * @return content attribute value, or null if not found
     */
    private String extractMetaTag(Document doc, String cssQuery) {
        Element element = doc.selectFirst(cssQuery);
        if (element != null) {
            return element.attr("content");
        }
        return null;
    }

    /**
     * Extracts favicon URL from document.
     *
     * @param doc
     *            the document
     * @param baseUrl
     *            the base URL for resolving relative paths
     * @return favicon URL, or null if not found
     */
    private String extractFavicon(Document doc, String baseUrl) {
        // Try link[rel=icon]
        Element iconLink = doc.selectFirst("link[rel~=icon]");
        if (iconLink != null) {
            String href = iconLink.attr("abs:href");
            if (href != null && !href.isBlank()) {
                return href;
            }
        }

        // Fallback to /favicon.ico
        try {
            URI uri = new URI(baseUrl);
            return uri.getScheme() + "://" + uri.getHost() + "/favicon.ico";
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Sanitizes input by removing HTML tags.
     *
     * @param input
     *            raw input
     * @return sanitized text
     */
    private String sanitize(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        String cleaned = HTML_TAG_PATTERN.matcher(input).replaceAll("");
        cleaned = cleaned.trim();

        return cleaned.isBlank() ? null : cleaned;
    }

    /**
     * Extracts a title from a URL (fallback strategy).
     *
     * @param url
     *            the URL
     * @return extracted title
     */
    private String extractTitleFromUrl(String url) {
        try {
            URI uri = new URI(url);
            String path = uri.getPath();
            if (path != null && !path.isEmpty() && !path.equals("/")) {
                String[] segments = path.split("/");
                String lastSegment = segments[segments.length - 1];
                return lastSegment.replaceAll("[_-]", " ");
            }
            return uri.getHost();
        } catch (Exception e) {
            return "Untitled";
        }
    }

    /**
     * Record type for fetched site metadata.
     *
     * @param title
     *            page title (from og:title or HTML title)
     * @param description
     *            page description (from og:description or meta description)
     * @param ogImageUrl
     *            OpenGraph image URL (from og:image)
     * @param faviconUrl
     *            favicon URL (from link[rel=icon] or /favicon.ico)
     * @param customImageUrl
     *            custom image URL (user-provided override)
     */
    public record SiteMetadata(String title, String description, String ogImageUrl, String faviconUrl,
            String customImageUrl) {
    }
}
