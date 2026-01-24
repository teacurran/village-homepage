package villagecompute.homepage.services;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import villagecompute.homepage.api.types.SitemapUrlType;
import villagecompute.homepage.data.models.DirectoryCategory;
import villagecompute.homepage.data.models.DirectorySite;
import villagecompute.homepage.data.models.MarketplaceListing;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Service for generating and uploading SEO sitemap XML files.
 * <p>
 * Generates sitemaps.org-compliant XML sitemaps for all public content (directory categories, approved sites, active
 * marketplace listings). Automatically splits into multiple files if URL count exceeds 50,000 per protocol limits.
 * Uploads to Cloudflare R2 for CDN delivery.
 * <p>
 * <b>URL Priority Strategy:</b>
 * <ul>
 * <li>Homepage: 1.0 (highest)</li>
 * <li>Directory categories: 0.8 (high traffic)</li>
 * <li>Directory sites: 0.7 (moderate traffic)</li>
 * <li>Marketplace listings: 0.6 (moderate traffic)</li>
 * </ul>
 * <p>
 * <b>Change Frequency:</b>
 * <ul>
 * <li>Homepage: daily (content updates)</li>
 * <li>Directory categories: weekly (rank updates hourly but content less frequent)</li>
 * <li>Directory sites: monthly (static content)</li>
 * <li>Marketplace listings: daily (expiration after 30 days)</li>
 * </ul>
 * <p>
 * <b>Exclusions:</b>
 * <ul>
 * <li>Directory sites with status != 'approved'</li>
 * <li>Marketplace listings with status != 'active'</li>
 * <li>Expired marketplace listings (expiresAt < NOW())</li>
 * <li>User profiles (private)</li>
 * <li>Admin pages</li>
 * </ul>
 *
 * @see SitemapUrlType
 */
@ApplicationScoped
public class SitemapGenerationService {

    private static final Logger LOG = Logger.getLogger(SitemapGenerationService.class);

    private static final int MAX_URLS_PER_SITEMAP = 50000;
    private static final DateTimeFormatter ISO_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.of("UTC"));

    @Inject
    S3Client s3Client;

    @ConfigProperty(
            name = "villagecompute.homepage.base-url")
    String baseUrl;

    @ConfigProperty(
            name = "villagecompute.cdn.base-url")
    String cdnUrl;

    @ConfigProperty(
            name = "villagecompute.storage.buckets.listings")
    String listingsBucketName;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    Tracer tracer;

    @Inject
    @Location("sitemap/sitemap.xml")
    Template sitemapTemplate;

    @Inject
    @Location("sitemap/sitemap-index.xml")
    Template sitemapIndexTemplate;

    /**
     * Queries all public URLs from the database.
     * <p>
     * Includes:
     * <ul>
     * <li>Homepage</li>
     * <li>All active directory categories</li>
     * <li>All approved directory sites</li>
     * <li>All active, non-expired marketplace listings</li>
     * </ul>
     *
     * @return List of sitemap URLs sorted by priority DESC
     */
    public List<SitemapUrlType> getSitemapUrls() {
        Span span = tracer.spanBuilder("sitemap.get_urls").startSpan();

        try (var scope = span.makeCurrent()) {
            List<SitemapUrlType> urls = new ArrayList<>();

            // 1. Homepage (highest priority)
            urls.add(new SitemapUrlType(baseUrl, formatDate(Instant.now()), "daily", 1.0));

            // 2. Directory categories (high traffic)
            List<DirectoryCategory> categories = DirectoryCategory.findActive();
            LOG.infof("Found %d active directory categories", categories.size());
            for (DirectoryCategory category : categories) {
                String url = baseUrl + "/directory/" + category.slug;
                String lastMod = formatDate(category.updatedAt);
                urls.add(new SitemapUrlType(url, lastMod, "weekly", 0.8));
            }

            // 3. Directory sites (approved only)
            List<DirectorySite> sites = DirectorySite.findByStatus("approved");
            LOG.infof("Found %d approved directory sites", sites.size());
            for (DirectorySite site : sites) {
                String url = baseUrl + "/directory/sites/" + site.id.toString();
                String lastMod = formatDate(site.updatedAt);
                urls.add(new SitemapUrlType(url, lastMod, "monthly", 0.7));
            }

            // 4. Marketplace listings (active only, non-expired)
            List<MarketplaceListing> listings = MarketplaceListing
                    .find("status = 'active' AND expiresAt > CURRENT_TIMESTAMP ORDER BY updatedAt DESC").list();
            LOG.infof("Found %d active marketplace listings", listings.size());
            for (MarketplaceListing listing : listings) {
                String url = baseUrl + "/marketplace/listings/" + listing.id.toString();
                String lastMod = formatDate(listing.updatedAt);
                urls.add(new SitemapUrlType(url, lastMod, "daily", 0.6));
            }

            // Sort by priority DESC (highest priority first)
            urls.sort(Comparator.comparingDouble(SitemapUrlType::priority).reversed());

            span.setAttribute("total_urls", urls.size());
            span.setAttribute("categories", categories.size());
            span.setAttribute("sites", sites.size());
            span.setAttribute("listings", listings.size());

            LOG.infof("Generated %d total sitemap URLs", urls.size());

            meterRegistry.counter("sitemap.urls.total").increment(urls.size());

            return urls;

        } catch (Exception e) {
            span.recordException(e);
            LOG.errorf(e, "Failed to query sitemap URLs: %s", e.getMessage());
            throw new RuntimeException("Failed to query sitemap URLs", e);

        } finally {
            span.end();
        }
    }

    /**
     * Generates a single sitemap XML file from a list of URLs.
     *
     * @param urls
     *            List of sitemap URLs (max 50,000)
     * @return Sitemap XML string
     */
    public String generateSitemap(List<SitemapUrlType> urls) {
        if (urls.size() > MAX_URLS_PER_SITEMAP) {
            throw new IllegalArgumentException(
                    "URL count exceeds sitemap limit: " + urls.size() + " > " + MAX_URLS_PER_SITEMAP);
        }

        Span span = tracer.spanBuilder("sitemap.generate").setAttribute("url_count", urls.size()).startSpan();

        try (var scope = span.makeCurrent()) {
            String xml = sitemapTemplate.data("urls", urls).render();

            // Basic XML validation
            if (!xml.startsWith("<?xml") || !xml.contains("<urlset")) {
                throw new RuntimeException("Generated XML is invalid");
            }

            LOG.infof("Generated sitemap XML with %d URLs (%d bytes)", urls.size(), xml.length());

            return xml;

        } catch (Exception e) {
            span.recordException(e);
            LOG.errorf(e, "Failed to generate sitemap XML: %s", e.getMessage());
            throw new RuntimeException("Failed to generate sitemap XML", e);

        } finally {
            span.end();
        }
    }

    /**
     * Generates a sitemap index XML file referencing multiple sitemap files.
     *
     * @param sitemapUrls
     *            List of CDN URLs for individual sitemap files
     * @return Sitemap index XML string
     */
    public String generateSitemapIndex(List<String> sitemapUrls) {
        Span span = tracer.spanBuilder("sitemap.generate_index").setAttribute("sitemap_count", sitemapUrls.size())
                .startSpan();

        try (var scope = span.makeCurrent()) {
            String now = formatDate(Instant.now());
            String xml = sitemapIndexTemplate.data("sitemapUrls", sitemapUrls).data("now", now).render();

            // Basic XML validation
            if (!xml.startsWith("<?xml") || !xml.contains("<sitemapindex")) {
                throw new RuntimeException("Generated sitemap index XML is invalid");
            }

            LOG.infof("Generated sitemap index XML with %d sitemap files (%d bytes)", sitemapUrls.size(), xml.length());

            return xml;

        } catch (Exception e) {
            span.recordException(e);
            LOG.errorf(e, "Failed to generate sitemap index XML: %s", e.getMessage());
            throw new RuntimeException("Failed to generate sitemap index XML", e);

        } finally {
            span.end();
        }
    }

    /**
     * Uploads a sitemap XML file to R2 storage.
     *
     * @param xml
     *            Sitemap XML content
     * @param filename
     *            Object key (e.g., "sitemap.xml", "sitemap-1.xml")
     */
    public void uploadSitemap(String xml, String filename) {
        Span span = tracer.spanBuilder("sitemap.upload").setAttribute("filename", filename)
                .setAttribute("size_bytes", xml.length()).startSpan();

        Timer.Sample timer = Timer.start(meterRegistry);

        try (var scope = span.makeCurrent()) {
            byte[] xmlBytes = xml.getBytes(StandardCharsets.UTF_8);
            String objectKey = "sitemaps/" + filename;

            // Build metadata
            Map<String, String> metadata = Map.of("content-type", "application/xml", "generated-at",
                    Instant.now().toString());

            PutObjectRequest putRequest = PutObjectRequest.builder().bucket(listingsBucketName).key(objectKey)
                    .contentType("application/xml").metadata(metadata).build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(xmlBytes));

            String cdnUrlFull = cdnUrl + "/" + objectKey;
            LOG.infof("Uploaded sitemap to %s (%d bytes)", cdnUrlFull, xmlBytes.length);

            span.setAttribute("upload_success", true);
            span.setAttribute("cdn_url", cdnUrlFull);

            meterRegistry.counter("sitemap.upload.success").increment();
            meterRegistry.counter("sitemap.upload.size_bytes").increment(xmlBytes.length);

        } catch (Exception e) {
            span.recordException(e);
            span.setAttribute("upload_success", false);
            LOG.errorf(e, "Failed to upload sitemap %s: %s", filename, e.getMessage());
            meterRegistry.counter("sitemap.upload.failure").increment();
            throw new RuntimeException("Failed to upload sitemap: " + e.getMessage(), e);

        } finally {
            timer.stop(meterRegistry.timer("sitemap.upload.duration"));
            span.end();
        }
    }

    /**
     * Generates and uploads sitemaps for all public content.
     * <p>
     * If total URLs < 50,000: Generates single sitemap.xml and uploads to R2 If total URLs >= 50,000: Splits into
     * multiple sitemap files (sitemap-1.xml, sitemap-2.xml, etc.), generates sitemap index, uploads all files
     *
     * @return List of CDN URLs for uploaded sitemap files
     */
    public List<String> generateAndUploadSitemaps() {
        Span span = tracer.spanBuilder("sitemap.generate_and_upload_all").startSpan();

        Timer.Sample timer = Timer.start(meterRegistry);

        try (var scope = span.makeCurrent()) {
            // 1. Query all public URLs
            List<SitemapUrlType> allUrls = getSitemapUrls();
            int totalUrls = allUrls.size();

            span.setAttribute("total_urls", totalUrls);

            if (totalUrls == 0) {
                LOG.warn("No public URLs found for sitemap generation");
                return List.of();
            }

            List<String> uploadedUrls = new ArrayList<>();

            // 2. If URLs fit in single sitemap, generate and upload
            if (totalUrls <= MAX_URLS_PER_SITEMAP) {
                String xml = generateSitemap(allUrls);
                uploadSitemap(xml, "sitemap.xml");
                String cdnUrl = this.cdnUrl + "/sitemaps/sitemap.xml";
                uploadedUrls.add(cdnUrl);

                span.setAttribute("sitemap_count", 1);
                span.setAttribute("split", false);

                LOG.infof("Uploaded single sitemap with %d URLs", totalUrls);

                meterRegistry.counter("sitemap.files.generated").increment();

            } else {
                // 3. Split into multiple sitemaps
                int chunks = (int) Math.ceil((double) totalUrls / MAX_URLS_PER_SITEMAP);
                span.setAttribute("sitemap_count", chunks);
                span.setAttribute("split", true);

                LOG.infof("Splitting %d URLs into %d sitemap files", totalUrls, chunks);

                List<String> sitemapCdnUrls = new ArrayList<>();

                for (int i = 0; i < chunks; i++) {
                    int fromIndex = i * MAX_URLS_PER_SITEMAP;
                    int toIndex = Math.min(fromIndex + MAX_URLS_PER_SITEMAP, totalUrls);
                    List<SitemapUrlType> chunk = allUrls.subList(fromIndex, toIndex);

                    String xml = generateSitemap(chunk);
                    String filename = "sitemap-" + (i + 1) + ".xml";
                    uploadSitemap(xml, filename);

                    String cdnUrl = this.cdnUrl + "/sitemaps/" + filename;
                    sitemapCdnUrls.add(cdnUrl);

                    LOG.infof("Uploaded sitemap %s with %d URLs", filename, chunk.size());

                    meterRegistry.counter("sitemap.files.generated").increment();
                }

                // 4. Generate and upload sitemap index
                String indexXml = generateSitemapIndex(sitemapCdnUrls);
                uploadSitemap(indexXml, "sitemap.xml");
                uploadedUrls.add(this.cdnUrl + "/sitemaps/sitemap.xml");

                LOG.infof("Uploaded sitemap index referencing %d sitemap files", sitemapCdnUrls.size());
            }

            return uploadedUrls;

        } catch (Exception e) {
            span.recordException(e);
            LOG.errorf(e, "Failed to generate and upload sitemaps: %s", e.getMessage());
            throw new RuntimeException("Failed to generate and upload sitemaps", e);

        } finally {
            timer.stop(meterRegistry.timer("sitemap.generation.duration"));
            span.end();
        }
    }

    /**
     * Formats an Instant as ISO 8601 date (YYYY-MM-DD) for sitemap lastmod field.
     *
     * @param instant
     *            The instant to format
     * @return ISO 8601 date string
     */
    private String formatDate(Instant instant) {
        return ISO_DATE_FORMATTER.format(instant);
    }
}
