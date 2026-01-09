# Specification Review & Recommendations: Village Homepage

**Date:** 2026-01-08
**Status:** Awaiting Specification Enhancement

### **1.0 Executive Summary**

This document is an automated analysis of the provided project specifications. It has identified critical decision points that require explicit definition before architectural design can proceed.

**Required Action:** The user is required to review the assertions below and **update the original specification document** to resolve the ambiguities. This updated document will serve as the canonical source for subsequent development phases.

### **2.0 Synthesized Project Vision**

*Based on the provided data, the core project objective is to engineer a system that:*

Delivers a customizable homepage portal SaaS (Yahoo/Bing-style) built on Java Quarkus, enabling users to aggregate news, weather, stocks, and social media content through drag-and-drop widgets. The system extends beyond basic portals to include a Craigslist-style marketplace with location-based filtering and a hand-curated web directory (Yahoo Directory/DMOZ-inspired) with Reddit-style voting and automated screenshot capture.

### **3.0 Critical Assertions & Required Clarifications**

---

#### **Assertion 1: TypeScript Build Integration Strategy**

*   **Observation:** The specification mandates TypeScript for all frontend code with Ant Design components mounted as React islands, but does not define the build pipeline integration point with Maven/Quarkus lifecycle.
*   **Architectural Impact:** This decision determines developer experience, CI/CD complexity, and whether frontend and backend can be developed independently.
    *   **Path A (Maven Frontend Plugin):** Integrate npm/esbuild via `frontend-maven-plugin`, ensuring frontend builds during `mvn compile`. Single-command builds but slower iteration.
    *   **Path B (Parallel Watch Mode):** Separate `npm run watch` process alongside `./mvnw quarkus:dev`. Faster frontend iteration but requires two terminal sessions.
    *   **Path C (Quarkus Web Bundler):** Use experimental Quarkus web-bundler extension for integrated TypeScript compilation. Unified dev experience but less mature tooling.
*   **Default Assumption & Required Action:** To balance development velocity and production reliability, the system will assume **Path A (Maven Frontend Plugin)** with optional parallel watch mode during development. **The specification must be updated** to explicitly define the TypeScript build integration strategy and document the developer workflow (single vs dual-process).

---

#### **Assertion 2: Anonymous User Widget Persistence Architecture**

*   **Observation:** The specification states anonymous users can customize their homepage with widgets stored server-side via cookie-based user ID, but does not address the data model for storing layouts before user authentication.
*   **Architectural Impact:** This decision affects database schema design, cookie security requirements, and the complexity of the authentication merge logic.
    *   **Option A (Shared Users Table):** Store anonymous and authenticated users in the same `users` table with `is_anonymous` flag. Simple schema but requires careful query filtering.
    *   **Option B (Separate Anonymous Table):** Dedicated `anonymous_users` table with scheduled cleanup. Clean separation but adds migration complexity during OAuth merge.
    *   **Option C (Session Storage Only):** Store anonymous layouts in Redis/session store, not PostgreSQL. Ephemeral by design but limits 30-day persistence requirement.
*   **Default Assumption & Required Action:** To minimize schema complexity and support the specified 30-day retention policy, the system will use **Option A (Shared Users Table)** with indexed `is_anonymous` and `last_active_at` columns for efficient cleanup jobs. **The specification must be updated** to confirm this approach and define the cookie security requirements (HttpOnly, Secure, SameSite attributes).

---

#### **Assertion 3: AI Tagging Cost Control Enforcement Mechanism**

*   **Observation:** Policy P2 establishes a $500/month AI tagging budget ceiling with batch processing and deduplication, but does not specify the enforcement point (pre-queue rejection vs post-billing cap).
*   **Architectural Impact:** This determines whether the system proactively blocks tagging jobs when approaching the limit or allows overage with alerting.
    *   **Strategy A (Hard Pre-Queue Limit):** Check budget before enqueueing AI jobs; reject if budget exhausted. Prevents overage but may cause user-visible delays in feed updates.
    *   **Strategy B (Soft Post-Billing Cap):** Allow jobs to run but track costs asynchronously; pause future jobs when 100% threshold hit. Allows burst capacity but risks small overage.
    *   **Strategy C (Tiered Throttling):** At 75% budget, reduce batch sizes; at 90%, queue for next cycle; at 100%, hard stop. Gradual degradation with predictable behavior.
*   **Default Assumption & Required Action:** To balance cost control with user experience, the system will implement **Strategy C (Tiered Throttling)** with admin email alerts at each threshold. **The specification must be updated** to explicitly define the budget enforcement strategy and clarify whether "queue for next billing cycle" means immediate pause or reduced priority.

---

#### **Assertion 4: Marketplace Location Search Performance Guarantee**

*   **Observation:** Policy P6 specifies PostGIS with p95 < 100ms and p99 < 200ms latency targets for radius searches, but does not define the geographic scope per query (city-level vs multi-state).
*   **Architectural Impact:** This affects index design, query complexity, and whether caching layers are required to meet performance targets.
    *   **Scenario A (Single City Search):** Users search within their immediate metro area (10-50 mile radius). Spatial index alone likely sufficient for sub-100ms queries.
    *   **Scenario B (Multi-State Search):** Users can search "Any" radius across entire US/Canada dataset (40k cities). May require materialized views or Elasticsearch geo-spatial support to hit p95 target.
    *   **Scenario C (Hybrid Approach):** Optimize for 95% of queries (Scenario A) with graceful degradation for "Any" radius searches. Adds complexity to UI messaging.
*   **Default Assumption & Required Action:** To meet the specified performance targets for the majority use case, the system will optimize for **Scenario A (Single City Search)** with spatial indexes on `geo_cities.location` and expect most queries to use 5-100 mile radius filters. **The specification must be updated** to define the expected distribution of search radius values and whether "Any" radius is a required feature for v1 or acceptable to defer.

---

#### **Assertion 5: Screenshot Capture Concurrency & Resource Limits**

*   **Observation:** The specification requires jvppeteer for screenshot capture with unlimited retention and version history (Policy P4), but does not define concurrency limits for the Puppeteer browser pool.
*   **Architectural Impact:** This determines container resource allocation (CPU/memory), job queue throughput, and whether screenshot capture can become a bottleneck.
    *   **Model A (Single Browser, Sequential):** One Puppeteer browser instance processing screenshots sequentially. Minimal memory (~500MB) but slow for bulk imports (10+ minutes for 100 sites).
    *   **Model B (Browser Pool, Parallel):** Pool of 3-5 browser instances with concurrent page rendering. Faster bulk processing (~2-3 minutes for 100 sites) but higher memory footprint (~2-3GB).
    *   **Model C (On-Demand Instances):** Launch browser per screenshot, terminate immediately. Lowest idle memory but highest startup overhead (2-5 seconds per screenshot).
*   **Default Assumption & Required Action:** To balance throughput for the bulk import feature (F13.14) with container cost, the system will use **Model B (Browser Pool, Parallel)** with a default pool size of 3 browsers and configurable max concurrency. **The specification must be updated** to define acceptable screenshot capture SLAs (e.g., "100 screenshots processed within 5 minutes") and confirm container memory allocation (recommend 4GB minimum for Quarkus + 3 Chrome instances).

---

#### **Assertion 6: Social Media Token Refresh Failure User Experience**

*   **Observation:** Policy P5 specifies proactive token refresh 7 days before expiry with email notification on failure, but does not define the UI state when tokens cannot be refreshed due to Meta API permission revocation.
*   **Architectural Impact:** This affects the user notification strategy, widget display logic, and whether the system supports partial degradation vs full disconnect.
    *   **UX Path A (Graceful Archive Mode):** Show cached posts with prominent "Reconnect" banner; treat as indefinite archive. User can browse history but knows it's stale.
    *   **UX Path B (Hard Disconnect):** Hide social widget entirely after 7 days of failed refresh; require full re-authentication to restore. Clean state but disruptive.
    *   **UX Path C (Hybrid Notification):** Show banner at 3 days stale, hide widget at 7 days, preserve cached data. Balances user awareness with data retention policy.
*   **Default Assumption & Required Action:** To align with Policy P5's "indefinite post retention" clause while respecting user attention, the system will implement **UX Path C (Hybrid Notification)** with configurable staleness thresholds. **The specification must be updated** to explicitly define the UI behavior at 3-day, 7-day, and 30-day staleness milestones, and clarify whether "archived" posts should be visually distinguished from live posts (e.g., grayscale treatment, timestamp warning).

---

#### **Assertion 7: Feature Flag Analytics Data Retention & Privacy**

*   **Observation:** Policy P7 mandates logging all feature flag evaluations for A/B testing analysis with 90-day retention, but does not specify whether this includes personally identifiable user IDs or anonymized session hashes.
*   **Architectural Impact:** This determines GDPR compliance requirements, data export complexity, and whether analytics can correlate flag exposure with user behavior across sessions.
    *   **Privacy Model A (User ID Logging):** Store `user_id` in `feature_flag_evaluations` for full cross-session analysis. Enables cohort tracking but requires GDPR data export/deletion support.
    *   **Privacy Model B (Session Hash Only):** Store anonymized session hash instead of user ID. Privacy-preserving but cannot track same user across login sessions.
    *   **Privacy Model C (Dual Logging):** Log user ID for authenticated users (with consent), session hash for anonymous. Hybrid approach with conditional analytics.
*   **Default Assumption & Required Action:** To maximize analytics value while respecting the existing GDPR implementation (Policy P1), the system will use **Privacy Model A (User ID Logging)** with flag evaluation data included in the user data export endpoint. **The specification must be updated** to confirm this approach and define whether flag evaluation logs should be purged immediately upon user account deletion (right to erasure) or retained in aggregate form for 90 days with user IDs anonymized.

---

### **4.0 Next Steps**

Upon the user's update of the original specification document, the development process will be unblocked and can proceed to the architectural design phase.
