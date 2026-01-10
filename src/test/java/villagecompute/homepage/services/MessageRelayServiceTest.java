package villagecompute.homepage.services;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.api.types.ContactInfoType;
import villagecompute.homepage.data.models.MarketplaceCategory;
import villagecompute.homepage.data.models.MarketplaceListing;
import villagecompute.homepage.data.models.MarketplaceMessage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MessageRelayService (I4.T7).
 *
 * <p>
 * Test coverage (focusing on logic, not SMTP):
 * <ul>
 * <li>Spam keyword detection</li>
 * <li>Message ID extraction</li>
 * <li>Validation logic</li>
 * </ul>
 *
 * Note: Full email sending tests require SMTP server (tested via manual/integration tests).
 */
@QuarkusTest
public class MessageRelayServiceTest {

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
        listing.title = "Test Listing for Message Relay";
        listing.description = "Test description for message relay service tests";
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
    public void testContainsSpamKeywords() {
        // Test spam keywords
        assertTrue(MessageRelayService.containsSpamKeywords("Click here to unsubscribe from this list"));
        assertTrue(MessageRelayService.containsSpamKeywords("You have won the lottery!"));
        assertTrue(MessageRelayService.containsSpamKeywords("Free money guaranteed"));
        assertTrue(MessageRelayService.containsSpamKeywords("This is about viagra pills"));
        assertTrue(MessageRelayService.containsSpamKeywords("Work from home guaranteed income"));
        assertTrue(MessageRelayService.containsSpamKeywords("nigerian prince needs help"));

        // Test legitimate messages
        assertFalse(MessageRelayService.containsSpamKeywords("Is this item still available?"));
        assertFalse(MessageRelayService.containsSpamKeywords("Can we schedule a viewing?"));
        assertFalse(MessageRelayService.containsSpamKeywords("What is the condition of the item?"));
        assertFalse(MessageRelayService.containsSpamKeywords("I'm interested in purchasing this"));

        // Test null/blank
        assertFalse(MessageRelayService.containsSpamKeywords(null));
        assertFalse(MessageRelayService.containsSpamKeywords(""));
        assertFalse(MessageRelayService.containsSpamKeywords("   "));
    }

    @Test
    public void testExtractMessageIdFromReplyAddress() {
        String uuid = UUID.randomUUID().toString();
        String replyAddress = "reply-" + uuid + "@villagecompute.com";
        String expectedMessageId = "msg-" + uuid + "@villagecompute.com";

        String extracted = MessageRelayService.extractMessageIdFromReplyAddress(replyAddress);

        assertEquals(expectedMessageId, extracted);
    }

    @Test
    public void testExtractMessageIdFromInvalidReplyAddress() {
        assertNull(MessageRelayService.extractMessageIdFromReplyAddress("invalid@example.com"));
        assertNull(MessageRelayService.extractMessageIdFromReplyAddress("msg-abc@villagecompute.com")); // Should start
                                                                                                          // with
                                                                                                          // "reply-"
        assertNull(MessageRelayService.extractMessageIdFromReplyAddress(null));
        assertNull(MessageRelayService.extractMessageIdFromReplyAddress(""));
    }

    @Test
    public void testExtractMessageIdCaseInsensitive() {
        String uuid = UUID.randomUUID().toString();
        String replyAddressUpper = "REPLY-" + uuid + "@villagecompute.com";
        String expectedMessageId = "msg-" + uuid + "@villagecompute.com";

        String extracted = MessageRelayService.extractMessageIdFromReplyAddress(replyAddressUpper);

        assertEquals(expectedMessageId, extracted);
    }
}
