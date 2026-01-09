package villagecompute.homepage.data.models;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.testing.H2TestResource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PaymentRefund entity covering refund workflow and chargeback tracking.
 *
 * <p>
 * Test coverage per I4.T4 acceptance criteria:
 * <ul>
 * <li>Refund status transitions (pending → approved/rejected → processed)</li>
 * <li>Chargeback counting for user ban logic (2+ chargebacks)</li>
 * <li>Admin review tracking (reviewed_by, reviewed_at)</li>
 * <li>Automatic vs manual refund workflows</li>
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(H2TestResource.class)
class PaymentRefundTest {

    private UUID testUserId;
    private UUID testListingId;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clear all refunds before each test
        PaymentRefund.deleteAll();

        testUserId = UUID.randomUUID();
        testListingId = UUID.randomUUID();
    }

    /**
     * Test: Create refund with automatic processing.
     */
    @Test
    @Transactional
    void testCreateRefund_AutomaticProcessing() {
        PaymentRefund refund = new PaymentRefund();
        refund.stripePaymentIntentId = "pi_test_123";
        refund.stripeRefundId = "re_test_123";
        refund.listingId = testListingId;
        refund.userId = testUserId;
        refund.amountCents = 500;
        refund.reason = "technical_failure";
        refund.status = "processed";

        PaymentRefund created = PaymentRefund.create(refund);

        assertNotNull(created.id);
        assertEquals("processed", created.status);
        assertEquals("technical_failure", created.reason);
        assertNull(created.reviewedByUserId, "Automatic refunds don't have reviewer");
        assertNotNull(created.createdAt);
    }

    /**
     * Test: Create refund pending admin review.
     */
    @Test
    @Transactional
    void testCreateRefund_PendingReview() {
        PaymentRefund refund = new PaymentRefund();
        refund.stripePaymentIntentId = "pi_test_456";
        refund.listingId = testListingId;
        refund.userId = testUserId;
        refund.amountCents = 500;
        refund.reason = "user_request";
        refund.status = "pending";
        refund.notes = "User requested refund after 24h";

        PaymentRefund created = PaymentRefund.create(refund);

        assertEquals("pending", created.status);
        assertNull(created.stripeRefundId, "Pending refunds don't have Stripe refund ID yet");
    }

    /**
     * Test: Find pending refunds.
     */
    @Test
    @Transactional
    void testFindPending_ReturnsPendingOnly() {
        // Create pending refund
        PaymentRefund pending = new PaymentRefund();
        pending.stripePaymentIntentId = "pi_pending";
        pending.listingId = testListingId;
        pending.userId = testUserId;
        pending.amountCents = 500;
        pending.reason = "user_request";
        pending.status = "pending";
        PaymentRefund.create(pending);

        // Create processed refund
        PaymentRefund processed = new PaymentRefund();
        processed.stripePaymentIntentId = "pi_processed";
        processed.listingId = testListingId;
        processed.userId = testUserId;
        processed.amountCents = 500;
        processed.reason = "technical_failure";
        processed.status = "processed";
        PaymentRefund.create(processed);

        List<PaymentRefund> pendingRefunds = PaymentRefund.findPending();

        assertEquals(1, pendingRefunds.size());
        assertEquals("pending", pendingRefunds.get(0).status);
    }

    /**
     * Test: Find refunds by user ID.
     */
    @Test
    @Transactional
    void testFindByUserId_ReturnsUserRefunds() {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();

        // Create refund for user1
        PaymentRefund refund1 = new PaymentRefund();
        refund1.stripePaymentIntentId = "pi_user1";
        refund1.listingId = testListingId;
        refund1.userId = user1;
        refund1.amountCents = 500;
        refund1.reason = "user_request";
        refund1.status = "processed";
        PaymentRefund.create(refund1);

        // Create refund for user2
        PaymentRefund refund2 = new PaymentRefund();
        refund2.stripePaymentIntentId = "pi_user2";
        refund2.listingId = testListingId;
        refund2.userId = user2;
        refund2.amountCents = 300;
        refund2.reason = "technical_failure";
        refund2.status = "processed";
        PaymentRefund.create(refund2);

        List<PaymentRefund> user1Refunds = PaymentRefund.findByUserId(user1);

        assertEquals(1, user1Refunds.size());
        assertEquals(user1, user1Refunds.get(0).userId);
    }

    /**
     * Test: Find refund by Payment Intent ID.
     */
    @Test
    @Transactional
    void testFindByPaymentIntent_ReturnsRefund() {
        String paymentIntentId = "pi_test_unique";

        PaymentRefund refund = new PaymentRefund();
        refund.stripePaymentIntentId = paymentIntentId;
        refund.listingId = testListingId;
        refund.userId = testUserId;
        refund.amountCents = 500;
        refund.reason = "user_request";
        refund.status = "pending";
        PaymentRefund.create(refund);

        Optional<PaymentRefund> found = PaymentRefund.findByPaymentIntent(paymentIntentId);

        assertTrue(found.isPresent());
        assertEquals(paymentIntentId, found.get().stripePaymentIntentId);
    }

    /**
     * Test: Count chargebacks for user.
     */
    @Test
    @Transactional
    void testCountChargebacks_ReturnsChargebackCount() {
        UUID userId = UUID.randomUUID();

        // Create 2 chargebacks for user
        for (int i = 0; i < 2; i++) {
            PaymentRefund chargeback = new PaymentRefund();
            chargeback.stripePaymentIntentId = "pi_chargeback_" + i;
            chargeback.listingId = testListingId;
            chargeback.userId = userId;
            chargeback.amountCents = 500;
            chargeback.reason = "chargeback";
            chargeback.status = "processed";
            PaymentRefund.create(chargeback);
        }

        // Create 1 normal refund for user
        PaymentRefund normalRefund = new PaymentRefund();
        normalRefund.stripePaymentIntentId = "pi_normal";
        normalRefund.listingId = testListingId;
        normalRefund.userId = userId;
        normalRefund.amountCents = 300;
        normalRefund.reason = "user_request";
        normalRefund.status = "processed";
        PaymentRefund.create(normalRefund);

        long chargebackCount = PaymentRefund.countChargebacks(userId);

        assertEquals(2, chargebackCount, "Should count only chargebacks, not other refunds");
    }

    /**
     * Test: Approve pending refund.
     */
    @Test
    @Transactional
    void testApprove_TransitionsToApproved() {
        UUID adminUserId = UUID.randomUUID();

        PaymentRefund refund = new PaymentRefund();
        refund.stripePaymentIntentId = "pi_to_approve";
        refund.listingId = testListingId;
        refund.userId = testUserId;
        refund.amountCents = 500;
        refund.reason = "user_request";
        refund.status = "pending";
        refund = PaymentRefund.create(refund);

        PaymentRefund.approve(refund.id, adminUserId, "Approved due to exceptional circumstances");

        PaymentRefund approved = PaymentRefund.findById(refund.id);
        assertEquals("approved", approved.status);
        assertEquals(adminUserId, approved.reviewedByUserId);
        assertNotNull(approved.reviewedAt);
        assertEquals("Approved due to exceptional circumstances", approved.notes);
    }

    /**
     * Test: Reject pending refund.
     */
    @Test
    @Transactional
    void testReject_TransitionsToRejected() {
        UUID adminUserId = UUID.randomUUID();

        PaymentRefund refund = new PaymentRefund();
        refund.stripePaymentIntentId = "pi_to_reject";
        refund.listingId = testListingId;
        refund.userId = testUserId;
        refund.amountCents = 500;
        refund.reason = "user_request";
        refund.status = "pending";
        refund = PaymentRefund.create(refund);

        PaymentRefund.reject(refund.id, adminUserId, "Outside refund policy window");

        PaymentRefund rejected = PaymentRefund.findById(refund.id);
        assertEquals("rejected", rejected.status);
        assertEquals(adminUserId, rejected.reviewedByUserId);
        assertNotNull(rejected.reviewedAt);
        assertEquals("Outside refund policy window", rejected.notes);
    }

    /**
     * Test: Mark refund as processed.
     */
    @Test
    @Transactional
    void testMarkProcessed_SetsRefundId() {
        PaymentRefund refund = new PaymentRefund();
        refund.stripePaymentIntentId = "pi_to_process";
        refund.listingId = testListingId;
        refund.userId = testUserId;
        refund.amountCents = 500;
        refund.reason = "user_request";
        refund.status = "approved";
        refund = PaymentRefund.create(refund);

        String stripeRefundId = "re_test_123";
        PaymentRefund.markProcessed(refund.id, stripeRefundId);

        PaymentRefund processed = PaymentRefund.findById(refund.id);
        assertEquals("processed", processed.status);
        assertEquals(stripeRefundId, processed.stripeRefundId);
    }

    /**
     * Test: Cannot approve non-pending refund.
     */
    @Test
    @Transactional
    void testApprove_NonPending_ThrowsException() {
        UUID adminUserId = UUID.randomUUID();

        PaymentRefund refund = new PaymentRefund();
        refund.stripePaymentIntentId = "pi_already_processed";
        refund.listingId = testListingId;
        refund.userId = testUserId;
        refund.amountCents = 500;
        refund.reason = "technical_failure";
        refund.status = "processed";
        refund = PaymentRefund.create(refund);

        UUID refundId = refund.id;
        assertThrows(IllegalStateException.class, () -> {
            PaymentRefund.approve(refundId, adminUserId, "Should fail");
        }, "Cannot approve refund that is not pending");
    }
}
