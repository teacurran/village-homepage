/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import dev.langchain4j.model.chat.ChatModel;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.BaseIntegrationTest;
import villagecompute.homepage.TestFixtures;
import villagecompute.homepage.api.types.FraudAnalysisResultType;
import villagecompute.homepage.data.models.ListingFlag;
import villagecompute.homepage.data.models.MarketplaceListing;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.testing.PostgreSQLTestProfile;

/**
 * Tests for AI-powered fraud detection service.
 *
 * <p>
 * Verifies LangChain4j integration with Claude Sonnet for fraud analysis, JSON parsing robustness, risk score
 * calculation, auto-flagging logic, and error handling.
 *
 * <p>
 * <b>Critical Test Coverage:</b>
 * <ul>
 * <li>Low risk listings (&lt;0.30 confidence) are auto-approved</li>
 * <li>Medium risk listings (0.30-0.70) queued for manual review</li>
 * <li>High risk listings (&gt;0.70) auto-flagged with ListingFlag creation</li>
 * <li>User behavior analysis based on historical flags</li>
 * <li>Graceful degradation on AI API failures</li>
 * </ul>
 */
@QuarkusTest
@TestProfile(PostgreSQLTestProfile.class)
class FraudDetectionServiceTest extends BaseIntegrationTest {

    @Inject
    FraudDetectionService service;

    @InjectMock
    @Named("sonnet")
    ChatModel mockChatModel;

    /**
     * Tests low-risk listing analysis (confidence &lt; 0.30).
     *
     * <p>
     * Verifies that clean listings are marked as non-suspicious and auto-approved.
     */
    @Test
    @Transactional
    void testLowRiskListing_AutoApproved() {
        // Given: mock clean response (low confidence)
        String mockResponse = """
                {
                  "is_suspicious": false,
                  "confidence": 0.05,
                  "reasons": []
                }
                """;

        when(mockChatModel.chat(anyString())).thenReturn(mockResponse);

        // Create test listing
        User owner = TestFixtures.createTestUser();
        MarketplaceListing listing = TestFixtures.createTestListing(owner, "Used Mountain Bike",
                "Excellent condition, well maintained. $200.", new BigDecimal("200.00"));
        listing.status = "pending"; // Start as pending
        listing.persist();

        // When: analyze listing
        FraudAnalysisResultType result = service.analyzeListing(listing.id);

        // Then: verify low risk
        assertNotNull(result);
        assertFalse(result.isSuspicious());
        assertTrue(result.confidence().compareTo(BigDecimal.valueOf(0.30)) < 0, "Confidence should be < 0.30");
        assertEquals(0, result.reasons().size());

        // When: auto-flag decision
        service.autoFlagHighRisk(listing.id, result);

        // Then: verify listing auto-approved (status changed to active)
        MarketplaceListing updatedListing = MarketplaceListing.findById(listing.id);
        assertEquals("active", updatedListing.status, "Low-risk listing should be auto-approved");

        // Verify no flags created
        List<ListingFlag> flags = ListingFlag.findByListingId(listing.id);
        assertEquals(0, flags.size(), "Low-risk listing should not create flags");
    }

    /**
     * Tests medium-risk listing analysis (confidence 0.30-0.70).
     *
     * <p>
     * Verifies that moderately suspicious listings remain pending for manual review without auto-flagging.
     */
    @Test
    @Transactional
    void testMediumRiskListing_ManualReview() {
        // Given: mock medium-risk response
        String mockResponse = """
                {
                  "is_suspicious": true,
                  "confidence": 0.55,
                  "reasons": ["Vague description", "Generic template language"]
                }
                """;

        when(mockChatModel.chat(anyString())).thenReturn(mockResponse);

        // Create test listing with vague description
        User owner = TestFixtures.createTestUser();
        MarketplaceListing listing = TestFixtures.createTestListing(owner, "Great Deal!",
                "Amazing item! Contact me for details.", new BigDecimal("50.00"));
        listing.status = "pending";
        listing.persist();

        // When: analyze listing
        FraudAnalysisResultType result = service.analyzeListing(listing.id);

        // Then: verify medium risk
        assertNotNull(result);
        assertTrue(result.isSuspicious());
        assertTrue(result.confidence().compareTo(BigDecimal.valueOf(0.30)) >= 0, "Confidence should be >= 0.30");
        assertTrue(result.confidence().compareTo(BigDecimal.valueOf(0.70)) <= 0, "Confidence should be <= 0.70");
        assertEquals(2, result.reasons().size());
        assertTrue(result.reasons().contains("Vague description"));

        // When: auto-flag decision
        service.autoFlagHighRisk(listing.id, result);

        // Then: verify listing status unchanged (still pending for manual review)
        MarketplaceListing updatedListing = MarketplaceListing.findById(listing.id);
        assertEquals("pending", updatedListing.status, "Medium-risk listing should remain pending for manual review");

        // Verify no flags created (medium risk doesn't auto-flag)
        List<ListingFlag> flags = ListingFlag.findByListingId(listing.id);
        assertEquals(0, flags.size(), "Medium-risk listing should not auto-flag");
    }

    /**
     * Tests high-risk listing analysis (confidence &gt; 0.70).
     *
     * <p>
     * Verifies that highly suspicious listings trigger auto-flagging with ListingFlag creation and remain pending for
     * admin review.
     */
    @Test
    @Transactional
    void testHighRiskListing_AutoFlagged() {
        // Given: mock high-risk response (scam indicators)
        String mockResponse = """
                {
                  "is_suspicious": true,
                  "confidence": 0.92,
                  "reasons": [
                    "Too-good-to-be-true pricing",
                    "Urgency tactics detected",
                    "Suspicious payment method mentioned"
                  ]
                }
                """;

        when(mockChatModel.chat(anyString())).thenReturn(mockResponse);

        // Create test listing with scam indicators
        User owner = TestFixtures.createTestUser();
        MarketplaceListing listing = TestFixtures.createTestListing(owner, "iPhone 15 Pro Max - $50 - ACT NOW!!!",
                "URGENT!! Must sell today! Wire transfer only. No questions asked.", new BigDecimal("50.00"));
        listing.status = "pending";
        listing.persist();

        // When: analyze listing
        FraudAnalysisResultType result = service.analyzeListing(listing.id);

        // Then: verify high risk
        assertNotNull(result);
        assertTrue(result.isSuspicious());
        assertTrue(result.confidence().compareTo(BigDecimal.valueOf(0.70)) > 0, "Confidence should be > 0.70");
        assertEquals(3, result.reasons().size());
        assertTrue(result.reasons().contains("Too-good-to-be-true pricing"));
        assertTrue(result.reasons().contains("Urgency tactics detected"));

        // When: auto-flag high-risk listing
        service.autoFlagHighRisk(listing.id, result);

        // Then: verify listing status unchanged (still pending, not auto-approved)
        MarketplaceListing updatedListing = MarketplaceListing.findById(listing.id);
        assertEquals("pending", updatedListing.status, "High-risk listing should remain pending (not auto-approved)");

        // Verify ListingFlag created
        List<ListingFlag> flags = ListingFlag.findByListingId(listing.id);
        assertEquals(1, flags.size(), "High-risk listing should create 1 flag");

        ListingFlag flag = flags.get(0);
        assertEquals(listing.id, flag.listingId);
        assertEquals(listing.userId, flag.userId);
        assertEquals("pending", flag.status);
        assertEquals("AI fraud detection: high risk", flag.reason);
        assertNotNull(flag.fraudScore);
        assertTrue(flag.fraudScore.compareTo(BigDecimal.valueOf(0.70)) > 0, "Flag fraud score should be > 0.70");
        assertNotNull(flag.fraudReasons);
        assertTrue(flag.fraudReasons.contains("Too-good-to-be-true pricing"));
    }

    /**
     * Tests user behavior analysis with no historical flags (clean user).
     */
    @Test
    @Transactional
    void testUserBehaviorAnalysis_CleanUser() {
        // Given: user with no flags
        User user = TestFixtures.createTestUser();

        // When: analyze user behavior
        FraudAnalysisResultType result = service.analyzeUserBehavior(user);

        // Then: verify clean result
        assertNotNull(result);
        assertFalse(result.isSuspicious());
        assertEquals(BigDecimal.ZERO, result.confidence());
        assertEquals(0, result.reasons().size());
    }

    /**
     * Tests user behavior analysis with 2-4 historical flags (medium risk user).
     */
    @Test
    @Transactional
    void testUserBehaviorAnalysis_MediumRiskUser() {
        // Given: user with 3 flagged listings
        User user = TestFixtures.createTestUser();

        // Create 3 listings with flags
        for (int i = 0; i < 3; i++) {
            MarketplaceListing listing = TestFixtures.createTestListing(user);
            ListingFlag flag = new ListingFlag();
            flag.listingId = listing.id;
            flag.userId = user.id;
            flag.reason = "Test flag " + i;
            flag.status = "pending";
            flag.fraudReasons = null; // JSONB field - must be null or valid JSON
            flag.persist();
        }

        // When: analyze user behavior
        FraudAnalysisResultType result = service.analyzeUserBehavior(user);

        // Then: verify medium risk
        assertNotNull(result);
        assertTrue(result.isSuspicious());
        assertEquals(BigDecimal.valueOf(0.50), result.confidence());
        assertEquals(1, result.reasons().size());
        assertTrue(result.reasons().get(0).contains("3 flagged listings"));
    }

    /**
     * Tests user behavior analysis with 5+ historical flags (high risk user).
     */
    @Test
    @Transactional
    void testUserBehaviorAnalysis_HighRiskUser() {
        // Given: user with 7 flagged listings
        User user = TestFixtures.createTestUser();

        // Create 7 listings with flags
        for (int i = 0; i < 7; i++) {
            MarketplaceListing listing = TestFixtures.createTestListing(user);
            ListingFlag flag = new ListingFlag();
            flag.listingId = listing.id;
            flag.userId = user.id;
            flag.reason = "Test flag " + i;
            flag.status = "pending";
            flag.fraudReasons = null; // JSONB field - must be null or valid JSON
            flag.persist();
        }

        // When: analyze user behavior
        FraudAnalysisResultType result = service.analyzeUserBehavior(user);

        // Then: verify high risk
        assertNotNull(result);
        assertTrue(result.isSuspicious());
        assertEquals(BigDecimal.valueOf(0.80), result.confidence());
        assertEquals(1, result.reasons().size());
        assertTrue(result.reasons().get(0).contains("7 flagged listings"));
    }

    /**
     * Tests handling of AI API failures.
     *
     * <p>
     * Verifies that service falls back to rule-based analysis when Claude API is unavailable.
     */
    @Test
    @Transactional
    void testApiFailure_FallbackToRuleBased() {
        // Given: mock API failure
        when(mockChatModel.chat(anyString())).thenThrow(new RuntimeException("AI API unavailable"));

        // Create test listing with suspicious keywords
        User owner = TestFixtures.createTestUser();
        MarketplaceListing listing = TestFixtures.createTestListing(owner, "iPhone for sale - wire transfer only",
                "URGENT! Must sell today! Western Union preferred.", new BigDecimal("100.00"));

        // When: analyze listing (should fallback to rule-based)
        FraudAnalysisResultType result = service.analyzeListing(listing.id);

        // Then: verify fallback result (rule-based analysis)
        assertNotNull(result, "Should return result even on API failure");
        assertTrue(result.isSuspicious(), "Rule-based analysis should detect suspicious keywords");
        assertTrue(result.reasons().size() > 0, "Should have detected fraud indicators via rules");
        assertTrue(result.reasons().contains("Suspicious payment method mentioned")
                || result.reasons().contains("Urgency tactics detected"));
        assertEquals("rule-based-v1.0", result.promptVersion(), "Should indicate rule-based analysis");
    }

    /**
     * Tests parsing robustness with markdown-wrapped JSON response.
     */
    @Test
    @Transactional
    void testAnalyzeListing_WithMarkdownCodeBlocks() {
        // Given: mock response wrapped in markdown
        String mockResponse = """
                ```json
                {
                  "is_suspicious": true,
                  "confidence": 0.75,
                  "reasons": ["Counterfeit goods mentioned"]
                }
                ```
                """;

        when(mockChatModel.chat(anyString())).thenReturn(mockResponse);

        User owner = TestFixtures.createTestUser();
        MarketplaceListing listing = TestFixtures.createTestListing(owner, "Replica Watch",
                "High-quality replica Rolex", new BigDecimal("200.00"));

        // When: analyze listing
        FraudAnalysisResultType result = service.analyzeListing(listing.id);

        // Then: verify parsing succeeded despite markdown
        assertNotNull(result);
        assertTrue(result.isSuspicious());
        assertEquals(BigDecimal.valueOf(0.75), result.confidence());
        assertEquals(1, result.reasons().size());
        assertEquals("Counterfeit goods mentioned", result.reasons().get(0));
    }

    /**
     * Tests handling of malformed JSON response.
     *
     * <p>
     * Verifies that service returns low-confidence suspicious result on parse failure.
     */
    @Test
    @Transactional
    void testAnalyzeListing_MalformedJSON() {
        // Given: mock response with invalid JSON
        String mockResponse = "This is not valid JSON at all!";

        when(mockChatModel.chat(anyString())).thenReturn(mockResponse);

        User owner = TestFixtures.createTestUser();
        MarketplaceListing listing = TestFixtures.createTestListing(owner);

        // When: analyze listing
        FraudAnalysisResultType result = service.analyzeListing(listing.id);

        // Then: verify fallback result (low confidence suspicious)
        assertNotNull(result);
        assertTrue(result.isSuspicious(), "Parse failure should return suspicious result");
        assertEquals(BigDecimal.valueOf(0.3), result.confidence(), "Parse failure should use low confidence");
        assertEquals(1, result.reasons().size());
        assertEquals("AI response parse failure", result.reasons().get(0));
    }

    /**
     * Tests analysis of directory site submission.
     */
    @Test
    @Transactional
    void testAnalyzeSiteSubmission_Suspicious() {
        // Given: mock suspicious response
        String mockResponse = """
                {
                  "is_suspicious": true,
                  "confidence": 0.85,
                  "reasons": ["Potential spam site", "Excessive capitalization"]
                }
                """;

        when(mockChatModel.chat(anyString())).thenReturn(mockResponse);

        User submitter = TestFixtures.createTestUser();
        villagecompute.homepage.data.models.DirectorySite site = TestFixtures.createTestDirectorySite(submitter,
                "https://spamsite.example.com", "FREE MONEY!!! CLICK HERE!!!");

        // When: analyze site
        FraudAnalysisResultType result = service.analyzeSiteSubmission(site);

        // Then: verify suspicious result
        assertNotNull(result);
        assertTrue(result.isSuspicious());
        assertEquals(BigDecimal.valueOf(0.85), result.confidence());
        assertEquals(2, result.reasons().size());
    }

    /**
     * Tests handling of null listing ID.
     */
    @Test
    @Transactional
    void testAnalyzeListing_NullListingId() {
        // When: analyze null listing
        FraudAnalysisResultType result = service.analyzeListing((UUID) null);

        // Then: verify clean result (defensive handling)
        assertNotNull(result);
        assertFalse(result.isSuspicious());
    }

    /**
     * Tests handling of null user.
     */
    @Test
    @Transactional
    void testUserBehaviorAnalysis_NullUser() {
        // When: analyze null user
        FraudAnalysisResultType result = service.analyzeUserBehavior(null);

        // Then: verify clean result (defensive handling)
        assertNotNull(result);
        assertFalse(result.isSuspicious());
    }

    /**
     * Tests rule-based analysis detecting prohibited pharmaceuticals.
     */
    @Test
    @Transactional
    void testRuleBasedAnalysis_ProhibitedItems() {
        // Given: API failure forcing rule-based analysis
        when(mockChatModel.chat(anyString())).thenThrow(new RuntimeException("API unavailable"));

        User owner = TestFixtures.createTestUser();
        MarketplaceListing listing = TestFixtures.createTestListing(owner, "Prescription Medications",
                "Selling Oxycontin, Adderall, and Xanax. Contact for pricing.", new BigDecimal("0.00"));

        // When: analyze listing (fallback to rules)
        FraudAnalysisResultType result = service.analyzeListing(listing.id);

        // Then: verify prohibited items detected
        assertNotNull(result);
        assertTrue(result.isSuspicious());
        assertTrue(result.reasons().contains("Prohibited pharmaceutical items"));
    }

    /**
     * Tests rule-based analysis detecting MLM/pyramid scheme patterns.
     */
    @Test
    @Transactional
    void testRuleBasedAnalysis_MLMPatterns() {
        // Given: API failure forcing rule-based analysis
        when(mockChatModel.chat(anyString())).thenThrow(new RuntimeException("API unavailable"));

        User owner = TestFixtures.createTestUser();
        MarketplaceListing listing = TestFixtures.createTestListing(owner, "Financial Freedom Opportunity",
                "Be your own boss! Unlimited earning potential! Passive income system.", new BigDecimal("0.00"));

        // When: analyze listing (fallback to rules)
        FraudAnalysisResultType result = service.analyzeListing(listing.id);

        // Then: verify MLM indicators detected
        assertNotNull(result);
        assertTrue(result.isSuspicious());
        assertTrue(result.reasons().contains("MLM or pyramid scheme indicators"));
    }

    /**
     * Tests rule-based analysis detecting excessive capitalization (spam indicator).
     */
    @Test
    @Transactional
    void testRuleBasedAnalysis_ExcessiveCaps() {
        // Given: API failure forcing rule-based analysis
        when(mockChatModel.chat(anyString())).thenThrow(new RuntimeException("API unavailable"));

        User owner = TestFixtures.createTestUser();
        MarketplaceListing listing = TestFixtures.createTestListing(owner,
                "AMAZING DEAL!!! BEST PRICE EVER!!! LIMITED TIME!!!",
                "Description text", new BigDecimal("100.00"));

        // When: analyze listing (fallback to rules)
        FraudAnalysisResultType result = service.analyzeListing(listing.id);

        // Then: verify excessive caps detected
        assertNotNull(result);
        assertTrue(result.isSuspicious());
        assertTrue(result.reasons().contains("Excessive capitalization (spam indicator)"));
    }
}
