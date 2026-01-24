package villagecompute.homepage.jobs;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import villagecompute.homepage.data.models.DirectoryCategory;
import villagecompute.homepage.data.models.DirectorySite;
import villagecompute.homepage.data.models.MarketplaceListing;
import villagecompute.homepage.services.SitemapGenerationService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration tests for {@link SitemapGenerationJobHandler}.
 * <p>
 * Tests full job execution flow including database queries, XML generation, and R2 upload.
 */
@QuarkusTest
class SitemapGenerationJobHandlerTest {

    @Inject
    SitemapGenerationJobHandler handler;

    @Inject
    SitemapGenerationService service;

    @InjectMock
    S3Client s3Client;

    @Inject
    MeterRegistry meterRegistry;

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
    }

    @Test
    void testHandlesType() {
        // When: Check handler type
        JobType type = handler.handlesType();

        // Then: Should handle SITEMAP_GENERATION
        assertEquals(JobType.SITEMAP_GENERATION, type);
    }

    @Test
    @Transactional
    void testExecute_generatesAndUploadsSitemap() throws Exception {
        // Given: Test data in database (from setUp)
        Map<String, Object> payload = Map.of();

        // When: Execute job
        handler.execute(1L, payload);

        // Then: Should call S3 client to upload sitemap
        verify(s3Client, atLeastOnce()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @Transactional
    void testExecute_recordsMetrics() throws Exception {
        // Given: Test data in database
        Map<String, Object> payload = Map.of();

        // When: Execute job
        handler.execute(1L, payload);

        // Then: Metrics should be recorded
        // Note: Metrics are recorded in SitemapGenerationService
        assertNotNull(meterRegistry.find("sitemap.generation.duration").timer(),
                "Should record sitemap.generation.duration metric");
    }

    @Test
    @Transactional
    void testExecute_handlesEmptyDatabase() throws Exception {
        // Given: Empty database
        MarketplaceListing.deleteAll();
        DirectorySite.deleteAll();
        DirectoryCategory.deleteAll();

        Map<String, Object> payload = Map.of();

        // When: Execute job
        handler.execute(1L, payload);

        // Then: Should complete successfully (even with only homepage)
        // Verify S3 upload was called
        verify(s3Client, atLeastOnce()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @Transactional
    void testExecute_uploadsSingleSitemapForSmallDataset() throws Exception {
        // Given: Small dataset (<50K URLs)
        Map<String, Object> payload = Map.of();

        // When: Execute job
        handler.execute(1L, payload);

        // Then: Should upload single sitemap.xml
        verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }
}
