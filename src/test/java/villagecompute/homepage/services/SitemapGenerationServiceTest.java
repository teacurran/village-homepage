package villagecompute.homepage.services;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import villagecompute.homepage.api.types.SitemapUrlType;
import villagecompute.homepage.data.models.DirectoryCategory;
import villagecompute.homepage.data.models.DirectorySite;
import villagecompute.homepage.data.models.MarketplaceListing;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SitemapGenerationService}.
 * <p>
 * Tests sitemap XML generation, URL collection, splitting logic, and R2 upload integration.
 */
@QuarkusTest
class SitemapGenerationServiceTest {

    @Inject
    SitemapGenerationService service;

    @InjectMock
    S3Client s3Client;

    @Inject
    EntityManager entityManager;

    private UUID categoryId;
    private UUID siteId;
    private UUID listingId;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up test data
        MarketplaceListing.deleteAll();
        DirectorySite.deleteAll();
        DirectoryCategory.deleteAll();

        // Create test directory category
        DirectoryCategory category = new DirectoryCategory();
        category.slug = "test-category";
        category.name = "Test Category";
        category.description = "Test category for sitemap";
        category.parentId = null;
        category.iconUrl = null;
        category.sortOrder = 1;
        category.linkCount = 0;
        category.isActive = true;
        category.createdAt = Instant.now();
        category.updatedAt = Instant.now();
        category.persist();
        categoryId = category.id;

        // Create approved directory site
        DirectorySite site = new DirectorySite();
        site.url = "https://example.com";
        site.domain = "example.com";
        site.title = "Example Site";
        site.description = "Test site";
        site.submittedByUserId = UUID.randomUUID();
        site.status = "approved";
        site.isDead = false;
        site.healthCheckFailures = 0;
        site.createdAt = Instant.now();
        site.updatedAt = Instant.now();
        site.persist();
        siteId = site.id;

        // Create active marketplace listing
        MarketplaceListing listing = new MarketplaceListing();
        listing.title = "Test Listing";
        listing.description = "Test listing description";
        listing.userId = UUID.randomUUID();
        listing.categoryId = UUID.randomUUID();
        listing.status = "active";
        listing.expiresAt = Instant.now().plus(30, ChronoUnit.DAYS);
        listing.createdAt = Instant.now();
        listing.updatedAt = Instant.now();
        listing.persist();
        listingId = listing.id;
    }

    @Test
    @Transactional
    void testGetSitemapUrls_includesAllPublicContent() {
        // When: Query all sitemap URLs
        List<SitemapUrlType> urls = service.getSitemapUrls();

        // Then: Should include homepage, category, site, and listing
        assertTrue(urls.size() >= 4, "Should include at least homepage + category + site + listing");

        // Verify homepage (priority 1.0)
        SitemapUrlType homepage = urls.stream().filter(u -> u.priority() == 1.0).findFirst().orElse(null);
        assertNotNull(homepage, "Homepage should be included");
        assertTrue(homepage.location().endsWith("/"), "Homepage should end with /");
        assertEquals("daily", homepage.changeFreq());

        // Verify category (priority 0.8)
        SitemapUrlType category = urls.stream().filter(u -> u.location().contains("/directory/test-category"))
                .findFirst().orElse(null);
        assertNotNull(category, "Category should be included");
        assertEquals(0.8, category.priority());
        assertEquals("weekly", category.changeFreq());

        // Verify site (priority 0.7)
        SitemapUrlType site = urls.stream().filter(u -> u.location().contains("/directory/sites/" + siteId)).findFirst()
                .orElse(null);
        assertNotNull(site, "Directory site should be included");
        assertEquals(0.7, site.priority());
        assertEquals("monthly", site.changeFreq());

        // Verify listing (priority 0.6)
        SitemapUrlType listing = urls.stream().filter(u -> u.location().contains("/marketplace/listings/" + listingId))
                .findFirst().orElse(null);
        assertNotNull(listing, "Marketplace listing should be included");
        assertEquals(0.6, listing.priority());
        assertEquals("daily", listing.changeFreq());
    }

    @Test
    @Transactional
    void testGetSitemapUrls_excludesPendingDirectorySites() {
        // Given: Create pending directory site
        DirectorySite pendingSite = new DirectorySite();
        pendingSite.url = "https://pending-site.com";
        pendingSite.domain = "pending-site.com";
        pendingSite.title = "Pending Site";
        pendingSite.description = "Pending site for testing";
        pendingSite.submittedByUserId = UUID.randomUUID();
        pendingSite.status = "pending";
        pendingSite.isDead = false;
        pendingSite.healthCheckFailures = 0;
        pendingSite.createdAt = Instant.now();
        pendingSite.updatedAt = Instant.now();
        pendingSite.persist();

        // When: Query sitemap URLs
        List<SitemapUrlType> urls = service.getSitemapUrls();

        // Then: Pending site should NOT be included
        boolean hasPendingSite = urls.stream().anyMatch(u -> u.location().contains(pendingSite.id.toString()));
        assertFalse(hasPendingSite, "Pending directory sites should be excluded");
    }

    @Test
    @Transactional
    void testGetSitemapUrls_excludesExpiredListings() {
        // Given: Create expired marketplace listing
        MarketplaceListing expiredListing = new MarketplaceListing();
        expiredListing.title = "Expired Listing";
        expiredListing.description = "Expired listing for testing";
        expiredListing.userId = UUID.randomUUID();
        expiredListing.categoryId = UUID.randomUUID();
        expiredListing.status = "active";
        expiredListing.expiresAt = Instant.now().minus(1, ChronoUnit.DAYS); // Expired
        expiredListing.createdAt = Instant.now();
        expiredListing.updatedAt = Instant.now();
        expiredListing.persist();

        // When: Query sitemap URLs
        List<SitemapUrlType> urls = service.getSitemapUrls();

        // Then: Expired listing should NOT be included
        boolean hasExpiredListing = urls.stream().anyMatch(u -> u.location().contains(expiredListing.id.toString()));
        assertFalse(hasExpiredListing, "Expired marketplace listings should be excluded");
    }

    @Test
    @Transactional
    void testGetSitemapUrls_sortedByPriorityDesc() {
        // When: Query sitemap URLs
        List<SitemapUrlType> urls = service.getSitemapUrls();

        // Then: URLs should be sorted by priority DESC (homepage first)
        assertTrue(urls.size() > 0, "Should have at least one URL");
        assertEquals(1.0, urls.get(0).priority(), "First URL should be homepage with priority 1.0");

        // Verify descending priority order
        for (int i = 1; i < urls.size(); i++) {
            assertTrue(urls.get(i - 1).priority() >= urls.get(i).priority(), "URLs should be sorted by priority DESC");
        }
    }

    @Test
    void testGenerateSitemap_producesValidXml() {
        // Given: Sample URLs
        List<SitemapUrlType> urls = List.of(
                new SitemapUrlType("https://homepage.villagecompute.com/", "2024-01-15", "daily", 1.0),
                new SitemapUrlType("https://homepage.villagecompute.com/directory/test", "2024-01-14", "weekly", 0.8));

        // When: Generate sitemap XML
        String xml = service.generateSitemap(urls);

        // Then: Should produce valid XML
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"), "XML should start with declaration");
        assertTrue(xml.contains("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">"),
                "XML should have urlset root with namespace");
        assertTrue(xml.contains("<loc>https://homepage.villagecompute.com/</loc>"), "XML should contain URLs");
        assertTrue(xml.contains("<priority>1.0</priority>"), "XML should contain priority");
        assertTrue(xml.contains("<changefreq>daily</changefreq>"), "XML should contain change frequency");
        assertTrue(xml.contains("<lastmod>2024-01-15</lastmod>"), "XML should contain last modified date");
    }

    @Test
    void testGenerateSitemap_throwsExceptionIfTooManyUrls() {
        // Given: More than 50,000 URLs
        List<SitemapUrlType> tooManyUrls = new java.util.ArrayList<>();
        for (int i = 0; i < 50001; i++) {
            tooManyUrls.add(new SitemapUrlType("https://example.com/" + i, "2024-01-15", "daily", 0.5));
        }

        // When/Then: Should throw exception
        assertThrows(IllegalArgumentException.class, () -> service.generateSitemap(tooManyUrls),
                "Should reject URL count exceeding 50,000");
    }

    @Test
    void testGenerateSitemapIndex_producesValidXml() {
        // Given: Sample sitemap URLs
        List<String> sitemapUrls = List.of("https://cdn.villagecompute.com/sitemaps/sitemap-1.xml",
                "https://cdn.villagecompute.com/sitemaps/sitemap-2.xml");

        // When: Generate sitemap index XML
        String xml = service.generateSitemapIndex(sitemapUrls);

        // Then: Should produce valid XML
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"), "XML should start with declaration");
        assertTrue(xml.contains("<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">"),
                "XML should have sitemapindex root with namespace");
        assertTrue(xml.contains("<loc>https://cdn.villagecompute.com/sitemaps/sitemap-1.xml</loc>"),
                "XML should contain sitemap URLs");
        assertTrue(xml.contains("<loc>https://cdn.villagecompute.com/sitemaps/sitemap-2.xml</loc>"),
                "XML should contain all sitemap URLs");
        assertTrue(xml.contains("<lastmod>"), "XML should contain lastmod element");
    }

    @Test
    void testUploadSitemap_callsS3Client() {
        // Given: Sample XML
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><urlset></urlset>";

        // When: Upload sitemap
        service.uploadSitemap(xml, "sitemap.xml");

        // Then: Should call S3 client with correct parameters
        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);

        verify(s3Client, times(1)).putObject(requestCaptor.capture(), bodyCaptor.capture());

        PutObjectRequest request = requestCaptor.getValue();
        assertEquals("sitemaps/sitemap.xml", request.key(), "Object key should include sitemaps prefix");
        assertEquals("application/xml", request.contentType(), "Content type should be application/xml");
        assertTrue(request.metadata().containsKey("content-type"), "Metadata should include content-type");
    }

    @Test
    @Transactional
    void testGenerateAndUploadSitemaps_singleFile() {
        // Given: Less than 50K URLs (setUp creates only a few)
        // When: Generate and upload sitemaps
        List<String> uploadedUrls = service.generateAndUploadSitemaps();

        // Then: Should upload single sitemap
        assertEquals(1, uploadedUrls.size(), "Should upload single sitemap for <50K URLs");
        assertTrue(uploadedUrls.get(0).endsWith("/sitemaps/sitemap.xml"), "URL should be CDN sitemap URL");

        // Verify S3 upload was called once
        verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void testGenerateAndUploadSitemaps_emptyDatabase() {
        // Given: Empty database (delete all test data)
        entityManager.getTransaction().begin();
        MarketplaceListing.deleteAll();
        DirectorySite.deleteAll();
        DirectoryCategory.deleteAll();
        entityManager.getTransaction().commit();

        // When: Generate and upload sitemaps
        List<String> uploadedUrls = service.generateAndUploadSitemaps();

        // Then: Should still generate sitemap with at least homepage
        assertTrue(uploadedUrls.size() >= 1, "Should upload sitemap even if database is empty (homepage exists)");
    }

    @Test
    @Transactional
    void testGetSitemapUrls_formatsLastModifiedCorrectly() {
        // When: Query sitemap URLs
        List<SitemapUrlType> urls = service.getSitemapUrls();

        // Then: lastModified should be in ISO 8601 date format (YYYY-MM-DD)
        for (SitemapUrlType url : urls) {
            assertNotNull(url.lastModified(), "lastModified should not be null");
            assertTrue(url.lastModified().matches("\\d{4}-\\d{2}-\\d{2}"),
                    "lastModified should be ISO 8601 date format (YYYY-MM-DD)");
        }
    }

    @Test
    @Transactional
    void testGetSitemapUrls_absoluteUrlsOnly() {
        // When: Query sitemap URLs
        List<SitemapUrlType> urls = service.getSitemapUrls();

        // Then: All URLs should be absolute (start with http:// or https://)
        for (SitemapUrlType url : urls) {
            assertTrue(url.location().startsWith("http://") || url.location().startsWith("https://"),
                    "All sitemap URLs must be absolute: " + url.location());
        }
    }
}
