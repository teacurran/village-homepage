<!-- anchor: iteration-4-plan -->
### Iteration 4: Marketplace Data, Payments, and Search Experience

*   **Iteration ID:** `I4`
*   **Goal:** Deliver classifieds marketplace foundation covering geo data, categories, listings, payments/promotions, moderation, masked communication, and PostGIS/Elasticsearch search so users can post and discover listings within policy bounds.
*   **Prerequisites:** `I1`–`I3` (storage, auth, content widgets) plus StorageGateway + ClickTracking per previous iterations.
*   **Iteration KPIs:** Geo import completeness, listing publish time, payment success rate, search latency (p95 <200ms), moderation response time, message relay reliability.
*   **Iteration Testing Focus:** Database migrations, PostGIS queries, Elasticsearch indexing, Stripe webhook flows, job handlers (expiration/reminder/image processing), masked email routing, refunds.
*   **Iteration Exit Criteria:** Listings can be created with fees/promotions, search returns filtered results, masked messaging works, moderation dashboard operational, and runbooks cover payments/refunds/fraud detection.
*   **Iteration Collaboration Notes:** Work closely with finance/security for Stripe keys + refund policies, with infra for Elasticsearch tuning, and with UX for Craigslist-style layout + text-first approach.
*   **Iteration Documentation Outputs:** Update docs covering geo import, payments, storage, search, moderation, and runbooks for queue jobs + messaging; include anchors for manifest linking.
*   **Iteration Risks:** Stripe disputes, PostGIS performance issues, or spam abuse can delay release; mitigate via staged rollout flags (`marketplace`, `promoted_listings`) and load testing.
*   **Iteration Communications:** Provide twice-weekly status to leadership summarizing payment stats, moderation backlog, and queue health; escalate SLA breaches immediately.
*   **Iteration Dependencies:** Marketplace outputs power click tracking, analytics, and revenue instrumentation for I6; Good Sites/Profiles rely on StorageGateway built earlier.

<!-- anchor: task-i4-t1 -->
*   **Task 4.1:**
    *   **Task ID:** `I4.T1`
    *   **Description:** Import dr5hn US/Canada geo dataset into `geo_countries`, `geo_states`, `geo_cities`; add PostGIS `location` column, indexes, helper queries, and CLI/script for loading data.
        Document download + transform steps, integrate with docker-compose for dev.
    *   **Agent Type Hint:** `DatabaseAgent`
    *   **Inputs:** Policy P6, Section F12.1, PostGIS directives.
    *   **Input Files:** `["migrations/","scripts/","docs/ops/"]`
    *   **Target Files:** `["migrations/V12__geo_data.sql","scripts/import-geo-data.sh","docs/ops/geo-import.md"]`
    *   **Deliverables:** Migration/loader, script, doc.
    *   **Acceptance Criteria:** Tables populated, indexes exist, CLI documented, PostGIS queries validated via tests.
    *   **Dependencies:** `I1.T6`, `I1.T3`.
    *   **Parallelizable:** No.

<!-- anchor: task-i4-t2 -->
*   **Task 4.2:**
    *   **Task ID:** `I4.T2`
    *   **Description:** Create `marketplace_categories` schema with hierarchy, seed data per requirements (for sale, housing, jobs, etc.), provide admin CRUD, and caching for frequently accessed category tree.
        Include moderation metadata (fees, enabled flags).
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Feature F12.3, Section 2 components.
    *   **Input Files:** `["migrations/","src/main/java/villagecompute/homepage/data/models/","src/main/java/villagecompute/homepage/api/rest/admin/"]`
    *   **Target Files:** `["migrations/V13__marketplace_categories.sql","src/main/java/villagecompute/homepage/data/models/MarketplaceCategory.java","src/main/java/villagecompute/homepage/api/rest/admin/MarketplaceCategoryResource.java"]`
    *   **Deliverables:** Schema, entity, admin endpoints, seeds.
    *   **Acceptance Criteria:** Category tree accessible, admin can reorder/enable, caching layer hits, tests cover hierarchical queries.
    *   **Dependencies:** `I4.T1`.
    *   **Parallelizable:** Partial.

<!-- anchor: task-i4-t3 -->
*   **Task 4.3:**
    *   **Task ID:** `I4.T3`
    *   **Description:** Implement `marketplace_listings` entity + Panache logic, validation (title, price, description, category, location), default statuses, expiration schedule (30 days) with job `ListingExpirationJobHandler` and reminder job.
        Provide user endpoints for CRUD and saved drafts.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Feature F12.4–F12.7, Section 2 components, job blueprint.
    *   **Input Files:** `["migrations/","src/main/java/villagecompute/homepage/data/models/","src/main/java/villagecompute/homepage/api/rest/marketplace/"]`
    *   **Target Files:** `["migrations/V14__marketplace_listings.sql","src/main/java/villagecompute/homepage/data/models/MarketplaceListing.java","src/main/java/villagecompute/homepage/services/MarketplaceService.java","src/main/java/villagecompute/homepage/api/rest/marketplace/ListingResource.java","src/main/java/villagecompute/homepage/jobs/ListingExpirationJobHandler.java"]`
    *   **Deliverables:** Schema, service, REST endpoints, jobs, tests.
    *   **Acceptance Criteria:** Listings persist with statuses, expiration/reminder jobs run, validations enforce limits, API conforms to OpenAPI spec.
    *   **Dependencies:** `I4.T2`, `I2.T5`.
    *   **Parallelizable:** Partial.

<!-- anchor: task-i4-t4 -->
*   **Task 4.4:**
    *   **Task ID:** `I4.T4`
    *   **Description:** Integrate Stripe Payment Intents for fee categories + promotions; implement `listing_promotions` table, checkout session creation, webhook handlers (success/failure/refund), admin UI for refunds, and policy compliance (P3).
        Include `payment_refunds` schema + workflow for automatic vs manual refunds.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Policy P3, Feature F12.8, Section 2 components.
    *   **Input Files:** `["migrations/","src/main/java/villagecompute/homepage/integration/payments/","src/main/java/villagecompute/homepage/api/rest/marketplace/"]`
    *   **Target Files:** `["migrations/V15__listing_promotions.sql","migrations/V16__payment_refunds.sql","src/main/java/villagecompute/homepage/integration/payments/StripeService.java","src/main/java/villagecompute/homepage/api/rest/marketplace/PaymentResource.java","src/main/java/villagecompute/homepage/jobs/PromotionExpirationJobHandler.java","docs/ops/payments.md"]`
    *   **Deliverables:** Migrations, Stripe client/wrappers, REST endpoints, webhook handler, doc.
    *   **Acceptance Criteria:** Posting fee enforcement for categories, promotions schedule/resets, refunds logged with statuses, webhook tests pass, doc outlines chargeback process.
    *   **Dependencies:** `I4.T3`, `I1.T8`.
    *   **Parallelizable:** No.

<!-- anchor: task-i4-t5 -->
*   **Task 4.5:**
    *   **Task ID:** `I4.T5`
    *   **Description:** Build marketplace search stack: Elasticsearch indexing for listings, PostGIS radius queries (5–250 miles), filters (category, price, has images, date), sorting options, saved searches, and `/api/marketplace/search` endpoint.
        Implement pipeline to keep ES index synced with listing CRUD.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Feature F12.2, F12.10, Policy P11, Section 2 components.
    *   **Input Files:** `["src/main/java/villagecompute/homepage/services/SearchService.java","src/main/java/villagecompute/homepage/api/rest/marketplace/SearchResource.java","src/main/java/villagecompute/homepage/jobs/ListingIndexJobHandler.java"]`
    *   **Target Files:** `["src/main/java/villagecompute/homepage/services/SearchService.java","src/main/java/villagecompute/homepage/api/rest/marketplace/SearchResource.java","src/main/java/villagecompute/homepage/jobs/ListingIndexJobHandler.java","docs/ops/search.md"]`
    *   **Deliverables:** Search service, REST resource, job, doc.
    *   **Acceptance Criteria:** PostGIS + ES queries combined, caching/resilience for ES downtime, filters behave per spec, tests cover radius/performance, doc lists tuning knobs.
    *   **Dependencies:** `I4.T1`, `I4.T3`.
    *   **Parallelizable:** Partial.

<!-- anchor: task-i4-t6 -->
*   **Task 4.6:**
    *   **Task ID:** `I4.T6`
    *   **Description:** Implement image upload + processing: `marketplace_listing_images` schema, StorageGateway integration, size validation (12 limit), queue-based processing job (resize thumbnail 150x150, list 300x225, full 1200x900), CDN signed URLs, cleanup when listing removed.
        Update listing composer UI + API for uploads, add tests.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Feature F12.5, StorageGateway from I3, Section 2 components.
    *   **Input Files:** `["migrations/","src/main/java/villagecompute/homepage/services/","src/main/java/villagecompute/homepage/jobs/","docs/ops/storage.md"]`
    *   **Target Files:** `["migrations/V17__marketplace_images.sql","src/main/java/villagecompute/homepage/services/ListingImageService.java","src/main/java/villagecompute/homepage/jobs/ListingImageProcessingJobHandler.java","docs/ui-guides/marketplace-images.md"]`
    *   **Deliverables:** Schema, service, job, doc, tests/mocks.
    *   **Acceptance Criteria:** Upload endpoint returns signed URL, job generates derivatives, metadata stored, cleanup job removes stale images, UI handles progress.
    *   **Dependencies:** `I3.T7`, `I4.T3`.
    *   **Parallelizable:** No.

<!-- anchor: task-i4-t7 -->
*   **Task 4.7:**
    *   **Task ID:** `I4.T7`
    *   **Description:** Build contact system: masked email addresses, message relay endpoints, inbound email processor for replies, spam throttling, logging for compliance, optional phone display.
        Provide Qute email templates (listing published, inquiry, reply) per F14.3.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Feature F12.6, F14.3, Section 2 components.
    *   **Input Files:** `["migrations/","src/main/java/villagecompute/homepage/services/","src/main/java/villagecompute/homepage/jobs/","src/main/resources/templates/email-templates/"]`
    *   **Target Files:** `["migrations/V18__marketplace_messages.sql","src/main/java/villagecompute/homepage/data/models/MarketplaceMessage.java","src/main/java/villagecompute/homepage/services/MessageRelayService.java","src/main/java/villagecompute/homepage/jobs/MessageRelayJobHandler.java","src/main/java/villagecompute/homepage/jobs/InboundEmailProcessor.java","src/main/resources/templates/email-templates/listing-message.html"]`
    *   **Deliverables:** Schema, services, jobs, email templates, docs.
    *   **Acceptance Criteria:** Masked addresses generated, inbound processor routes replies, spam/rate limit enforced, tests simulate IMAP + SMTP flows, docs cover troubleshooting.
    *   **Dependencies:** `I4.T3`, `I1.T8`.
    *   **Parallelizable:** Partial.

<!-- anchor: task-i4-t8 -->
*   **Task 4.8:**
    *   **Task ID:** `I4.T8`
    *   **Description:** Implement moderation + flagging + fraud detection: `listing_flags` schema, admin queue UI, AI heuristics integrating AiTaggingBudgetService for textual analysis, refund automation per P3, ban logic for repeated chargebacks.
        Provide analytics for flag counts + statuses.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Policy P3, F12.9, Section 2 components.
    *   **Input Files:** `["migrations/","src/main/java/villagecompute/homepage/api/rest/admin/","src/main/java/villagecompute/homepage/services/"]`
    *   **Target Files:** `["migrations/V19__listing_flags.sql","src/main/java/villagecompute/homepage/api/rest/admin/MarketplaceModerationResource.java","src/main/java/villagecompute/homepage/services/FraudDetectionService.java","docs/ops/moderation.md"]`
    *   **Deliverables:** Schema, admin resource, service hooking AI heuristics, doc.
    *   **Acceptance Criteria:** Flags recorded + thresholded (auto-hide at 3), moderation actions logged, AI heuristics produce review queue suggestions, refund automation documented.
    *   **Dependencies:** `I3.T3`, `I4.T3`, `I4.T4`.
    *   **Parallelizable:** No.

<!-- anchor: task-i4-t9 -->
*   **Task 4.9:**
    *   **Task ID:** `I4.T9`
    *   **Description:** Deliver end-to-end verification + analytics: Playwright/Cypress tests for listing creation/search/contact, load tests for search radius queries, click tracking instrumentation hooking `/track/click` to listings, dashboards for revenue/promotions/refunds.
        Update CI + docs accordingly.
    *   **Agent Type Hint:** `QAAgent`
    *   **Inputs:** All I4 tasks, Section 4 quality gate, click tracking spec.
    *   **Input Files:** `["tests/e2e/","src/test/java/","docs/ops/testing.md","docs/ops/analytics.md"]`
    *   **Target Files:** `["tests/e2e/marketplace.spec.ts","src/test/java/villagecompute/homepage/marketplace/ListingResourceTest.java","docs/ops/testing.md","docs/ops/analytics.md"]`
    *   **Deliverables:** E2E tests, integration tests, docs for analytics dashboards.
    *   **Acceptance Criteria:** Tests pass covering fees/promotions/search/messaging, coverage metrics updated, analytics doc describes dashboards + KPIs, CI job includes new suites.
    *   **Dependencies:** All previous I4 tasks.
    *   **Parallelizable:** No.
