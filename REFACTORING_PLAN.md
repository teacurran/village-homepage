# Village Homepage Refactoring Plan

**Date:** 2026-01-22
**Status:** In Progress
**Goal:** Fix all known and unknown errors, implement all TODOs, achieve 95% test coverage

## Current State Analysis

### Compilation Status
✅ **Project compiles successfully** with 235 source files
- 1 deprecation warning (MultipartForm in ListingImageResource)
- No blocking compilation errors

### Known Issues Summary

#### 1. Missing @NamedQuery Annotations (Critical - Startup Validation)
**Impact:** Named queries are referenced but not defined, bypassing startup validation

**Affected Entities:**
- ReservedUsername (2 queries: findByUsername, findByReason)
- MarketplaceCategory (5 queries: findRootCategories, findByParentId, findBySlug, findActive, findAllOrdered)
- DirectoryCategory (5 queries: same as MarketplaceCategory)
- MarketplaceListing (3 queries: findByUserId, findByCategoryId, findByStatus)
- ListingPromotion (2 queries: findByListingId, findByPaymentIntent)
- UserFeedSubscription (1 query: findByUserAndSource)
- SocialPost (1 query: findByPlatformPostId)
- PaymentRefund (3 queries: findByUserId, findByListingId, findByPaymentIntent)
- ProfileCuratedArticle (2 queries: findByProfile, findByFeedItem)
- MarketplaceMessage (3 queries: findByListingId, findByThreadId, findByMessageId)
- RssSource (2 queries: findByUrl, findByCategory)
- ImpersonationAudit (2 queries: findByImpersonator, findByTarget)
- AccountMergeAudit (1 query: findByAuthenticatedUser)
- GdprRequest (2 queries: findByUser, findByStatus)
- ListingFlag (2 queries: findByListing, findByStatus)
- FeedItem (3 queries: findByGuid, findBySource, findByContentHash)
- UserProfile (2 queries: findByUsername, findByUserId)

**Fix Strategy:**
1. Add @NamedQuery annotations to each entity class
2. Define JPQL queries properly with aliases
3. Keep constants for JPQL fragments where dynamic sorting is needed
4. Validate all queries compile at startup

#### 2. Test Failures (204 total)

**Category A: MarketplaceListing.flagCount not-null errors (167 failures)**
- Location: MarketplaceSearchServiceTest, MessageRelayServiceTest
- Root Cause: Test fixtures not setting flagCount field (non-nullable column)
- Fix: Initialize flagCount = 0 in all test listing creation methods

**Category B: Detached Entity Errors (30 failures)**
- Location: ModerationServiceTest, SocialIntegrationServiceTest
- Root Cause: Entities persisted in @BeforeEach then used in @Test without proper transaction boundaries
- Fix: Use `@TestTransaction` or persist within test transaction context

**Category C: Runtime Errors (7 failures)**
- SocialIntegrationServiceTest: testGetSocialFeed_Disconnected fails with "Failed to get social feed"
- Root Cause: Missing MetaGraphClient implementation (stub returns hardcoded data)
- Fix: Implement actual logic or mock properly in tests

#### 3. TODO Comments (63 total)

**Category: Authentication & Authorization (15 TODOs)**
- Extract user ID from JWT/SecurityIdentity (8 occurrences)
- Get user tier from karma (4 occurrences)
- Extract IP address for rate limiting (2 occurrences)
- Extract user agent from headers (1 occurrence)

**Category: AI Services (6 TODOs)**
- AiTaggingService - LangChain4j integration stub
- AiCategorizationService - LangChain4j integration stub
- FraudDetectionService - LangChain4j integration stub
- ObservabilityMetrics - AI budget tracking integration

**Category: Email Notifications (5 TODOs)**
- GDPR export ready notification
- GDPR deletion confirmation
- User ban notification with appeal process
- Listing reminder emails
- Link health check moderator notifications

**Category: OAuth & Social Integration (3 TODOs)**
- MetaGraphClient.refreshAccessToken() implementation
- MetaGraphClient.getUserProfile() implementation
- SocialFeedRefreshScheduler token refresh logic

**Category: Image Processing (5 TODOs)**
- WebP conversion in StorageGateway (2 occurrences)
- Image resize variants in ListingImageProcessingJobHandler (3 sizes: thumbnail 150x150, list 300x225, full 1200x900)

**Category: Feature Implementation (10 TODOs)**
- Geo-city validation in MarketplaceListingResource (2 occurrences)
- Image count implementation (2 occurrences)
- Cookie consent banner state reading
- Rejection reason audit logging
- News widget source/category matching
- Directory service HTTP fetch & OpenGraph extraction
- Feature flag integration in LoggingEnricher
- Rate limit bucket tracking

**Category: Security (2 TODOs)**
- Encrypt social tokens at rest using Quarkus Vault (Policy P5)

**Category: Miscellaneous (17 TODOs)**
- Mark active listings as removed when user banned
- ObservabilityMetrics delayed job depth query
- Various documentation TODOs in comments

## Refactoring Strategy

### Phase 1: Critical Fixes (Blocks Testing)

**1.1 Add All Missing @NamedQuery Annotations**
- Priority: CRITICAL
- Time Estimate: 1-2 hours
- Deliverable: All entities have proper @NamedQuery annotations
- Validation: `./mvnw compile` succeeds with query validation

**1.2 Fix Test Data Creation**
- Priority: CRITICAL
- Time Estimate: 30 minutes
- Deliverable: All test fixtures properly initialize required fields
- Validation: Test failures drop from 204 to <50

**1.3 Fix Detached Entity Issues**
- Priority: HIGH
- Time Estimate: 1 hour
- Deliverable: Proper transaction boundaries in tests
- Validation: ModerationServiceTest and SocialIntegrationServiceTest pass

### Phase 2: Service Implementation (Core Features)

**2.1 Implement AI Services with LangChain4j**
- Services: AiTaggingService, AiCategorizationService, FraudDetectionService
- Time Estimate: 3-4 hours
- Requirements:
  - Configure LangChain4j with Anthropic Claude API
  - Implement proper prompt templates
  - Add retry logic and error handling
  - Track token usage in AiUsageTracking entity
  - Respect monthly budget limits

**2.2 Implement Email Notification System**
- Time Estimate: 2-3 hours
- Components:
  - EmailService with Qute template integration
  - GDPR notification templates
  - Ban notification templates
  - Listing reminder templates
  - Moderator alert templates
- Integration Points:
  - GdprExportJobHandler
  - GdprDeletionJobHandler
  - User.ban() method
  - ListingReminderJobHandler
  - LinkHealthCheckJobHandler

**2.3 Implement OAuth & Social Integration**
- Time Estimate: 2-3 hours
- Components:
  - MetaGraphClient.refreshAccessToken() - implement token refresh with Meta Graph API
  - MetaGraphClient.getUserProfile() - fetch user profile data
  - SocialFeedRefreshScheduler token refresh logic
- Requirements:
  - Handle expired tokens gracefully
  - Store refresh tokens securely
  - Update SocialToken expiry tracking

### Phase 3: Image Processing

**3.1 Implement WebP Conversion**
- Time Estimate: 1-2 hours
- Library: Thumbnailator or ImageMagick Java wrapper
- Location: StorageGateway.convertToWebP()
- Quality: 85% compression
- Validation: Original format preserved, WebP variant generated

**3.2 Implement Image Resizing**
- Time Estimate: 1-2 hours
- Location: ListingImageProcessingJobHandler
- Variants:
  - Thumbnail: 150x150 (square crop)
  - List: 300x225 (4:3 aspect ratio)
  - Full: 1200x900 (max dimensions, preserve aspect)
- Output: All variants uploaded to R2 with proper keys

### Phase 4: Authentication & Authorization

**4.1 Implement User Context Helpers**
- Time Estimate: 2 hours
- Components:
  - UserContextService (application-scoped)
  - getCurrentUser() - extract from SecurityIdentity
  - getCurrentUserId() - extract UUID from JWT
  - getUserTier() - calculate from karma
  - isAuthenticated() - check session state
- Integration: Inject into all resources needing user context

**4.2 Implement IP Extraction for Rate Limiting**
- Time Estimate: 1 hour
- Location: RateLimitFilter
- Logic: Check X-Forwarded-For, X-Real-IP, then fallback to remote address
- Handle proxies and CDN headers

### Phase 5: Feature Completions

**5.1 Geo-City Validation**
- Time Estimate: 30 minutes
- Add GeoCity.exists(Long id) static method
- Call from MarketplaceListingResource validation

**5.2 Image Count Implementation**
- Time Estimate: 30 minutes
- Add static query to count images per listing
- Populate in ListingSearchResultType

**5.3 Cookie Consent Integration**
- Time Estimate: 1 hour
- Read consent cookie in HomepageResource
- Pass to FeatureFlagService for analytics toggle

**5.4 Other Small TODOs**
- Rejection reason audit (15 min)
- News widget category matching (30 min)
- User ban listing removal (30 min)

### Phase 6: Test Coverage (95% Target)

**6.1 Apply DRY Principles to Tests**
- Extract test constants (TestConstants class)
- Create parameterized tests for edge cases
- Build test base classes (BaseIntegrationTest, BaseResourceTest)
- Create entity factory methods (TestDataFactory)
- Custom assertion helpers

**6.2 Add Missing Test Coverage**
- Target: 95% line and branch coverage
- Focus Areas:
  - Service layer (currently ~70%)
  - Job handlers (currently ~60%)
  - Edge cases and error paths
  - Validation logic

**6.3 Improve Test Quality**
- Use real database interactions (minimize mocking)
- WireMock for external API boundaries only
- Test realistic scenarios
- Add integration tests for critical flows

### Phase 7: Final Validation

**7.1 Code Quality**
- Run `./mvnw spotless:apply`
- Fix any remaining warnings
- Ensure no TODOs remain

**7.2 Test Suite**
- Run `./mvnw test jacoco:report`
- Verify 95% coverage achieved
- All tests passing (0 failures, 0 errors)

**7.3 Documentation**
- Update ARCHITECTURE.md with new services
- Document any design decisions made during refactoring

## Implementation Order

### Sprint 1: Critical Path (Day 1)
1. Add all @NamedQuery annotations
2. Fix test data creation (flagCount)
3. Fix detached entity issues
4. Validate: Tests run with <10 failures

### Sprint 2: Core Services (Days 2-3)
1. Implement UserContextService
2. Implement AI services with LangChain4j
3. Implement email notification system
4. Validate: Core features functional

### Sprint 3: Integrations (Day 4)
1. Implement OAuth token refresh
2. Implement image processing (WebP + resize)
3. Implement remaining feature TODOs
4. Validate: All features complete

### Sprint 4: Testing & Quality (Day 5)
1. Apply DRY principles to tests
2. Add missing test coverage
3. Achieve 95% coverage target
4. Final validation and cleanup

## Success Criteria

✅ **Zero TODO comments** in src/main/java
✅ **Zero compilation errors or warnings**
✅ **Zero test failures** (all 756+ tests passing)
✅ **95% line coverage** (JaCoCo report)
✅ **95% branch coverage** (JaCoCo report)
✅ **All @NamedQueries defined** with proper JPQL
✅ **Code formatted** with Spotless
✅ **SonarQube quality gate** passing

## Risk Factors

1. **LangChain4j Configuration** - May require API keys and credentials not available in test environment
   - Mitigation: Use mocks in tests, document configuration requirements

2. **Meta Graph API** - Requires Facebook/Instagram app credentials
   - Mitigation: Use WireMock in tests, document OAuth setup

3. **Image Processing Library** - Performance impact of WebP conversion
   - Mitigation: Process asynchronously via delayed jobs, benchmark performance

4. **Test Coverage** - Some areas may be difficult to test (external APIs, background jobs)
   - Mitigation: Focus on integration tests, use test containers for real interactions

## Notes

- This refactoring plan assumes access to all required external services (Anthropic API, Meta Graph API, etc.)
- Some TODOs may be intentionally deferred to future iterations if they represent optional enhancements
- Test coverage target of 95% is aggressive but achievable with comprehensive integration tests
- DRY principles in tests are mandatory to maintain test suite quality as codebase grows
