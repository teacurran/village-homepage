package villagecompute.homepage.data.models;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.api.types.ContactInfoType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for MarketplaceMessage entity (I4.T7).
 *
 * <p>
 * Test coverage:
 * <ul>
 * <li>Entity CRUD operations</li>
 * <li>Static finder methods</li>
 * <li>Message threading</li>
 * <li>Spam flagging</li>
 * <li>Direction tracking</li>
 * </ul>
 */
@QuarkusTest
public class MarketplaceMessageTest {

    private UUID testListingId;
    private UUID testCategoryId;
    private UUID testUserId;

    @BeforeEach
    @Transactional
    public void setupTestData() {
        // Clean up
        MarketplaceMessage.deleteAll();
        MarketplaceListing.deleteAll();
        MarketplaceCategory.deleteAll();

        // Create test category
        MarketplaceCategory category = new MarketplaceCategory();
        category.name = "For Sale";
        category.slug = "for-sale";
        category.sortOrder = 1;
        category.isActive = true;
        category.feeSchedule = new villagecompute.homepage.api.types.FeeScheduleType(BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO);
        category.createdAt = Instant.now();
        category.updatedAt = Instant.now();
        category.persist();
        testCategoryId = category.id;

        // Create test user
        testUserId = UUID.randomUUID();

        // Create test listing
        MarketplaceListing listing = new MarketplaceListing();
        listing.userId = testUserId;
        listing.categoryId = testCategoryId;
        listing.title = "Test Listing for Messages";
        listing.description = "Test description for message relay tests";
        listing.price = BigDecimal.valueOf(100);
        listing.contactInfo = ContactInfoType.forListing("seller@example.com", "+1-555-1234");
        listing.status = "active";
        listing.createdAt = Instant.now();
        listing.updatedAt = Instant.now();
        listing.expiresAt = Instant.now().plus(30, java.time.temporal.ChronoUnit.DAYS);
        listing.persist();
        testListingId = listing.id;
    }

    @Test
    @Transactional
    public void testCreateMessage() {
        MarketplaceMessage message = new MarketplaceMessage();
        message.listingId = testListingId;
        message.messageId = "msg-test-123@villagecompute.com";
        message.fromEmail = "buyer@example.com";
        message.fromName = "John Doe";
        message.toEmail = "seller@example.com";
        message.subject = "Test inquiry";
        message.body = "Is this item still available?";
        message.direction = "buyer_to_seller";
        message.createdAt = Instant.now();

        MarketplaceMessage.create(message);

        assertNotNull(message.id);
        assertNotNull(message.createdAt);
        assertEquals("buyer_to_seller", message.direction);
    }

    @Test
    @Transactional
    public void testFindByListingId() {
        // Create multiple messages for listing
        createTestMessage(testListingId, "msg-1@villagecompute.com", "buyer_to_seller");
        createTestMessage(testListingId, "msg-2@villagecompute.com", "seller_to_buyer");
        createTestMessage(testListingId, "msg-3@villagecompute.com", "buyer_to_seller");

        List<MarketplaceMessage> messages = MarketplaceMessage.findByListingId(testListingId);

        assertEquals(3, messages.size());
        // Verify chronological order (oldest first)
        assertEquals("msg-1@villagecompute.com", messages.get(0).messageId);
    }

    @Test
    @Transactional
    public void testFindByMessageId() {
        String messageId = "msg-unique-123@villagecompute.com";
        createTestMessage(testListingId, messageId, "buyer_to_seller");

        Optional<MarketplaceMessage> found = MarketplaceMessage.findByMessageId(messageId);

        assertTrue(found.isPresent());
        assertEquals(messageId, found.get().messageId);
    }

    @Test
    @Transactional
    public void testFindByThreadId() {
        UUID threadId = UUID.randomUUID();

        // Create threaded conversation
        MarketplaceMessage msg1 = createTestMessage(testListingId, "msg-1@villagecompute.com", "buyer_to_seller");
        msg1.threadId = threadId;
        msg1.persist();

        MarketplaceMessage msg2 = createTestMessage(testListingId, "msg-2@villagecompute.com", "seller_to_buyer");
        msg2.threadId = threadId;
        msg2.inReplyTo = msg1.messageId;
        msg2.persist();

        List<MarketplaceMessage> thread = MarketplaceMessage.findByThreadId(threadId);

        assertEquals(2, thread.size());
        assertEquals("msg-1@villagecompute.com", thread.get(0).messageId);
        assertEquals("msg-2@villagecompute.com", thread.get(1).messageId);
        assertEquals(msg1.messageId, thread.get(1).inReplyTo);
    }

    @Test
    @Transactional
    public void testMarkSent() {
        MarketplaceMessage message = createTestMessage(testListingId, "msg-sent-test@villagecompute.com",
                "buyer_to_seller");
        assertNull(message.sentAt);

        Instant sentTime = Instant.now();
        MarketplaceMessage.markSent(message.id, sentTime);

        MarketplaceMessage updated = MarketplaceMessage.findById(message.id);
        assertNotNull(updated.sentAt);
        assertEquals(sentTime, updated.sentAt);
    }

    @Test
    @Transactional
    public void testFlagForReview() {
        MarketplaceMessage message = createTestMessage(testListingId, "msg-flag-test@villagecompute.com",
                "buyer_to_seller");
        assertFalse(message.flaggedForReview);

        MarketplaceMessage.flagForReview(message.id);

        MarketplaceMessage updated = MarketplaceMessage.findById(message.id);
        assertTrue(updated.flaggedForReview);
    }

    @Test
    @Transactional
    public void testMarkSpam() {
        MarketplaceMessage message = createTestMessage(testListingId, "msg-spam-test@villagecompute.com",
                "buyer_to_seller");
        assertFalse(message.isSpam);

        BigDecimal spamScore = new BigDecimal("0.95");
        MarketplaceMessage.markSpam(message.id, spamScore);

        MarketplaceMessage updated = MarketplaceMessage.findById(message.id);
        assertTrue(updated.isSpam);
        assertEquals(spamScore, updated.spamScore);
        assertTrue(updated.flaggedForReview); // Auto-flagged for review
    }

    @Test
    @Transactional
    public void testCountByListingId() {
        createTestMessage(testListingId, "msg-count-1@villagecompute.com", "buyer_to_seller");
        createTestMessage(testListingId, "msg-count-2@villagecompute.com", "seller_to_buyer");
        createTestMessage(testListingId, "msg-count-3@villagecompute.com", "buyer_to_seller");

        long count = MarketplaceMessage.countByListingId(testListingId);

        assertEquals(3, count);
    }

    @Test
    @Transactional
    public void testCascadeDeleteWhenListingDeleted() {
        createTestMessage(testListingId, "msg-cascade-1@villagecompute.com", "buyer_to_seller");
        createTestMessage(testListingId, "msg-cascade-2@villagecompute.com", "seller_to_buyer");

        assertEquals(2, MarketplaceMessage.countByListingId(testListingId));

        // Delete listing (should CASCADE delete messages per GDPR P1)
        MarketplaceListing listing = MarketplaceListing.findById(testListingId);
        listing.delete();

        assertEquals(0, MarketplaceMessage.countByListingId(testListingId));
    }

    @Test
    @Transactional
    public void testFindFlaggedForReview() {
        MarketplaceMessage msg1 = createTestMessage(testListingId, "msg-flagged-1@villagecompute.com",
                "buyer_to_seller");
        MarketplaceMessage.flagForReview(msg1.id);

        MarketplaceMessage msg2 = createTestMessage(testListingId, "msg-flagged-2@villagecompute.com",
                "buyer_to_seller");
        MarketplaceMessage.flagForReview(msg2.id);

        // Create unflagged message
        createTestMessage(testListingId, "msg-not-flagged@villagecompute.com", "buyer_to_seller");

        List<MarketplaceMessage> flagged = MarketplaceMessage.findFlaggedForReview();

        assertEquals(2, flagged.size());
    }

    @Test
    @Transactional
    public void testFindSpam() {
        MarketplaceMessage msg1 = createTestMessage(testListingId, "msg-spam-1@villagecompute.com",
                "buyer_to_seller");
        MarketplaceMessage.markSpam(msg1.id, new BigDecimal("0.99"));

        MarketplaceMessage msg2 = createTestMessage(testListingId, "msg-spam-2@villagecompute.com",
                "buyer_to_seller");
        MarketplaceMessage.markSpam(msg2.id, new BigDecimal("0.75"));

        // Create non-spam message
        createTestMessage(testListingId, "msg-not-spam@villagecompute.com", "buyer_to_seller");

        List<MarketplaceMessage> spam = MarketplaceMessage.findSpam();

        assertEquals(2, spam.size());
        // Verify ordered by spam score descending
        assertEquals(new BigDecimal("0.99"), spam.get(0).spamScore);
        assertEquals(new BigDecimal("0.75"), spam.get(1).spamScore);
    }

    // Helper method to create test messages
    private MarketplaceMessage createTestMessage(UUID listingId, String messageId, String direction) {
        MarketplaceMessage message = new MarketplaceMessage();
        message.listingId = listingId;
        message.messageId = messageId;
        message.fromEmail = direction.equals("buyer_to_seller") ? "buyer@example.com" : "seller@example.com";
        message.fromName = direction.equals("buyer_to_seller") ? "Buyer" : "Seller";
        message.toEmail = direction.equals("buyer_to_seller") ? "seller@example.com" : "buyer@example.com";
        message.subject = "Test message";
        message.body = "Test body content";
        message.direction = direction;
        message.createdAt = Instant.now();
        message.persist();
        return message;
    }
}
