package villagecompute.homepage.jobs;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.data.models.DirectoryAiSuggestion;
import villagecompute.homepage.data.models.User;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for BulkImportJobHandler.
 *
 * <p>
 * Tests CSV parsing, duplicate detection, AI categorization integration, and budget enforcement.
 */
@QuarkusTest
public class BulkImportJobHandlerTest {

    @Inject
    BulkImportJobHandler handler;

    private UUID testUserId;

    @BeforeEach
    @Transactional
    public void setup() {
        // Create test user
        User testUser = new User();
        testUser.email = "test@example.com";
        testUser.adminRole = User.ROLE_SUPER_ADMIN;
        testUser.persist();
        testUserId = testUser.id;

        // Clean up any existing test suggestions
        DirectoryAiSuggestion.delete("uploadedByUserId = ?1", testUserId);
    }

    @Test
    public void testHandlesType() {
        assertEquals(JobType.DIRECTORY_BULK_IMPORT, handler.handlesType());
    }

    @Test
    @Transactional
    public void testExecute_validCsv() throws Exception {
        // Create test CSV file
        Path csvPath = createTestCsv(List.of("url,title,description", "https://example.com,Example Site,A test website",
                "https://github.com,GitHub,Code hosting platform",
                "https://stackoverflow.com,Stack Overflow,Programming Q&A"));

        try {
            // Execute job
            Map<String, Object> payload = new HashMap<>();
            payload.put("csv_path", csvPath.toString());
            payload.put("uploaded_by_user_id", testUserId.toString());

            handler.execute(1L, payload);

            // Verify suggestions were created
            List<DirectoryAiSuggestion> suggestions = DirectoryAiSuggestion.list("uploadedByUserId", testUserId);
            assertEquals(3, suggestions.size(), "Should create 3 suggestions");

            // Verify suggestion details
            DirectoryAiSuggestion githubSuggestion = suggestions.stream().filter(s -> s.url.contains("github.com"))
                    .findFirst().orElse(null);

            assertNotNull(githubSuggestion);
            assertEquals("GitHub", githubSuggestion.title);
            assertEquals("Code hosting platform", githubSuggestion.description);
            assertEquals("pending", githubSuggestion.status);
            assertNotNull(githubSuggestion.suggestedCategoryIds, "Should have AI-suggested categories");
            assertTrue(githubSuggestion.suggestedCategoryIds.length > 0, "Should suggest at least one category");

        } finally {
            // Clean up temp file
            Files.deleteIfExists(csvPath);
        }
    }

    @Test
    @Transactional
    public void testExecute_skipsDuplicates() throws Exception {
        // Create initial suggestion
        DirectoryAiSuggestion existing = new DirectoryAiSuggestion();
        existing.url = "https://github.com/";
        existing.domain = "github.com";
        existing.title = "GitHub";
        existing.status = "pending";
        existing.uploadedByUserId = testUserId;
        existing.persist();

        // Create CSV with duplicate URL
        Path csvPath = createTestCsv(
                List.of("url,title,description", "https://github.com,GitHub Duplicate,Should be skipped",
                        "https://example.com,Example Site,Should be created"));

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("csv_path", csvPath.toString());
            payload.put("uploaded_by_user_id", testUserId.toString());

            handler.execute(1L, payload);

            // Verify only one new suggestion created
            List<DirectoryAiSuggestion> suggestions = DirectoryAiSuggestion.list("uploadedByUserId", testUserId);
            assertEquals(2, suggestions.size(), "Should skip duplicate, create 1 new");

            // Verify example.com was created
            assertTrue(suggestions.stream().anyMatch(s -> s.url.contains("example.com")));

        } finally {
            Files.deleteIfExists(csvPath);
        }
    }

    @Test
    @Transactional
    public void testExecute_emptyFile() throws Exception {
        // Create empty CSV
        Path csvPath = createTestCsv(List.of("url,title,description"));

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("csv_path", csvPath.toString());
            payload.put("uploaded_by_user_id", testUserId.toString());

            // Should not throw exception
            handler.execute(1L, payload);

            // Verify no suggestions created
            List<DirectoryAiSuggestion> suggestions = DirectoryAiSuggestion.list("uploadedByUserId", testUserId);
            assertEquals(0, suggestions.size());

        } finally {
            Files.deleteIfExists(csvPath);
        }
    }

    @Test
    @Transactional
    public void testExecute_missingUrlColumn() throws Exception {
        // Create CSV with missing URL in row (should skip the row)
        Path csvPath = createTestCsv(List.of("url,title,description", ",Example Site,No URL provided",
                "https://example.com,Valid Site,Valid URL"));

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("csv_path", csvPath.toString());
            payload.put("uploaded_by_user_id", testUserId.toString());

            // Should skip invalid row, process valid row
            handler.execute(1L, payload);

            // Verify only 1 suggestion created (skipped the blank URL)
            List<DirectoryAiSuggestion> suggestions = DirectoryAiSuggestion.list("uploadedByUserId", testUserId);
            assertEquals(1, suggestions.size(), "Should skip row with missing URL");

        } finally {
            Files.deleteIfExists(csvPath);
        }
    }

    @Test
    @Transactional
    public void testExecute_optionalColumns() throws Exception {
        // Create CSV with only required url column
        Path csvPath = createTestCsv(List.of("url", "https://example.com", "https://github.com"));

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("csv_path", csvPath.toString());
            payload.put("uploaded_by_user_id", testUserId.toString());

            handler.execute(1L, payload);

            // Verify suggestions created with default values
            List<DirectoryAiSuggestion> suggestions = DirectoryAiSuggestion.list("uploadedByUserId", testUserId);
            assertEquals(2, suggestions.size());

            // Check that title defaults to domain
            DirectoryAiSuggestion exampleSuggestion = suggestions.stream().filter(s -> s.url.contains("example.com"))
                    .findFirst().orElse(null);

            assertNotNull(exampleSuggestion);
            assertEquals("example.com", exampleSuggestion.title);
            assertNull(exampleSuggestion.description);

        } finally {
            Files.deleteIfExists(csvPath);
        }
    }

    /**
     * Helper to create temporary CSV file for testing.
     */
    private Path createTestCsv(List<String> lines) throws IOException {
        Path tempFile = Files.createTempFile("test-bulk-import-", ".csv");
        Files.write(tempFile, lines);
        return tempFile;
    }
}
