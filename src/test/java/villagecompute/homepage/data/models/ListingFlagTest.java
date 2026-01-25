package villagecompute.homepage.data.models;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ListingFlag entity covering flag submission, moderation workflow, and fraud detection.
 *
 * <p>
 * Test coverage per I4.T8 acceptance criteria:
 * <ul>
 * <li>Flag creation and status transitions (pending → approved/dismissed)</li>
 * <li>Flag counting (pending flags for auto-hide threshold)</li>
 * <li>Rate limiting (count recent flags by user)</li>
 * <li>Fraud score storage and retrieval</li>
 * <li>Admin review tracking</li>
 * </ul>
 */
@QuarkusTest
class ListingFlagTest {

    private UUID testListingId;
    private UUID testUserId;
    private UUID testAdminUserId;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clear all flags before each test
        ListingFlag.deleteAll();

        testListingId = UUID.randomUUID();
        testUserId = UUID.randomUUID();
        testAdminUserId = UUID.randomUUID();
    }

    /**
     * Test: Create flag with pending status.
     */
    @Test
    @Transactional
    void testCreateFlag_Pending() {
        ListingFlag flag = new ListingFlag();
        flag.listingId = testListingId;
        flag.userId = testUserId;
        flag.reason = "fraud";
        flag.details = "Price too good to be true";
        flag.status = "pending";
        flag.createdAt = Instant.now();
        flag.updatedAt = Instant.now();
        flag.persist();

        assertNotNull(flag.id);
        assertEquals("pending", flag.status);
        assertEquals("fraud", flag.reason);
        assertEquals(testListingId, flag.listingId);
        assertEquals(testUserId, flag.userId);
    }

    /**
     * Test: Count pending flags for a listing.
     */
    @Test
    @Transactional
    void testCountPendingForListing() {
        // Create 3 pending flags
        for (int i = 0; i < 3; i++) {
            ListingFlag flag = new ListingFlag();
            flag.listingId = testListingId;
            flag.userId = UUID.randomUUID();
            flag.reason = "spam";
            flag.status = "pending";
            flag.createdAt = Instant.now();
            flag.updatedAt = Instant.now();
            flag.persist();
        }

        long count = ListingFlag.countPendingForListing(testListingId);
        assertEquals(3, count);
    }

    /**
     * Test: Count pending flags excludes approved and dismissed.
     */
    @Test
    @Transactional
    void testCountPendingForListing_ExcludesReviewed() {
        // Create 2 pending, 1 approved, 1 dismissed
        ListingFlag pending1 = createFlag("pending");
        ListingFlag pending2 = createFlag("pending");
        ListingFlag approved = createFlag("approved");
        ListingFlag dismissed = createFlag("dismissed");

        long count = ListingFlag.countPendingForListing(testListingId);
        assertEquals(2, count, "Should only count pending flags");
    }

    /**
     * Test: Count recent flags by user for rate limiting.
     */
    @Test
    @Transactional
    void testCountRecentByUser() {
        // Create 5 flags for test user
        for (int i = 0; i < 5; i++) {
            ListingFlag flag = new ListingFlag();
            flag.listingId = UUID.randomUUID();
            flag.userId = testUserId;
            flag.reason = "spam";
            flag.status = "pending";
            flag.createdAt = Instant.now();
            flag.updatedAt = Instant.now();
            flag.persist();
        }

        long count = ListingFlag.countRecentByUser(testUserId);
        assertEquals(5, count);
    }

    /**
     * Test: Approve flag updates status and timestamps.
     */
    @Test
    @Transactional
    void testApproveFlag() {
        ListingFlag flag = createFlag("pending");

        flag.approve(testAdminUserId, "Confirmed fraud");

        assertEquals("approved", flag.status);
        assertEquals(testAdminUserId, flag.reviewedByUserId);
        assertNotNull(flag.reviewedAt);
        assertEquals("Confirmed fraud", flag.reviewNotes);
    }

    /**
     * Test: Dismiss flag updates status and timestamps.
     */
    @Test
    @Transactional
    void testDismissFlag() {
        ListingFlag flag = createFlag("pending");

        flag.dismiss(testAdminUserId, "False positive");

        assertEquals("dismissed", flag.status);
        assertEquals(testAdminUserId, flag.reviewedByUserId);
        assertNotNull(flag.reviewedAt);
        assertEquals("False positive", flag.reviewNotes);
    }

    /**
     * Test: Update fraud analysis stores score and reasons.
     */
    @Test
    @Transactional
    void testUpdateFraudAnalysis() {
        ListingFlag flag = createFlag("pending");

        BigDecimal fraudScore = new BigDecimal("0.85");
        String fraudReasons = "{\"reasons\": [\"Suspicious payment method\"], \"prompt_version\": \"v1.0\"}";

        flag.updateFraudAnalysis(fraudScore, fraudReasons);

        assertEquals(fraudScore, flag.fraudScore);
        assertEquals(fraudReasons, flag.fraudReasons);
    }

    /**
     * Test: Find pending flags returns only pending status.
     */
    @Test
    @Transactional
    void testFindPending() {
        createFlag("pending");
        createFlag("pending");
        createFlag("approved");
        createFlag("dismissed");

        List<ListingFlag> pendingFlags = ListingFlag.findPending();

        assertEquals(2, pendingFlags.size());
        assertTrue(pendingFlags.stream().allMatch(f -> "pending".equals(f.status)));
    }

    /**
     * Test: Find flags by status with pagination.
     */
    @Test
    @Transactional
    void testFindByStatus_Pagination() {
        // Create 15 pending flags
        for (int i = 0; i < 15; i++) {
            createFlag("pending");
        }

        // Fetch first page (10 items)
        List<ListingFlag> page1 = ListingFlag.findByStatus("pending", 0, 10);
        assertEquals(10, page1.size());

        // Fetch second page (5 items)
        List<ListingFlag> page2 = ListingFlag.findByStatus("pending", 10, 10);
        assertEquals(5, page2.size());
    }

    /**
     * Test: Find flags by listing ID.
     */
    @Test
    @Transactional
    void testFindByListingId() {
        createFlag("pending");
        createFlag("approved");
        createFlag("dismissed");

        List<ListingFlag> flags = ListingFlag.findByListingId(testListingId);

        assertEquals(3, flags.size());
        assertTrue(flags.stream().allMatch(f -> testListingId.equals(f.listingId)));
    }

    /**
     * Helper: Create a flag with the given status.
     */
    private ListingFlag createFlag(String status) {
        ListingFlag flag = new ListingFlag();
        flag.listingId = testListingId;
        flag.userId = testUserId;
        flag.reason = "spam";
        flag.status = status;
        flag.createdAt = Instant.now();
        flag.updatedAt = Instant.now();

        if ("approved".equals(status) || "dismissed".equals(status)) {
            flag.reviewedByUserId = testAdminUserId;
            flag.reviewedAt = Instant.now();
        }

        flag.persist();
        return flag;
    }
}
