# Specification Review & Recommendations: Village Homepage SaaS Portal

**Date:** 2026-01-08
**Status:** Awaiting Specification Enhancement

### **1.0 Executive Summary**

This document is an automated analysis of the provided project specifications. It has identified critical decision points that require explicit definition before architectural design can proceed.

**Required Action:** The user is required to review the assertions below and **update the original specification document** to resolve the ambiguities. This updated document will serve as the canonical source for subsequent development phases.

### **2.0 Synthesized Project Vision**

*Based on the provided data, the core project objective is to engineer a system that:*

Delivers a customizable homepage portal SaaS combining personalized content aggregation (news, weather, stocks, social media) with community features (marketplace classifieds and curated web directory), leveraging AI-powered content tagging and user-centric customization via drag-and-drop widgets.

### **3.0 Critical Assertions & Required Clarifications**

---

#### **Assertion 1: AI Tagging Infrastructure & Fallback Architecture**

*   **Observation:** The specification mandates LangChain4j integration with Claude API for all feed item tagging, with a $500/month cost ceiling and batch processing controls. However, the architectural response when budget exhaustion occurs mid-month remains strategically undefined.
*   **Architectural Impact:** This decision determines whether the platform maintains feature parity during budget constraints or accepts degraded functionality. It affects user experience consistency, caching infrastructure complexity, and operational predictability.
    *   **Path A (Hard Degradation):** Queue all items when budget exhausted, display untagged content with visual indicators. Minimal infrastructure, but creates inconsistent UX.
    *   **Path B (Hybrid Fallback):** Implement rule-based tagging fallback using keyword extraction and category mapping. Adds complexity but maintains feature continuity.
    *   **Path C (Dynamic Budget Reallocation):** Auto-increase budget ceiling with admin alerts, treating $500 as soft limit. Reduces technical debt but increases operational cost unpredictability.
*   **Default Assumption & Required Action:** To balance UX consistency with cost control, the system will implement **Path A (Hard Degradation)** with graceful UI messaging. **The specification must be updated** to explicitly define: (1) acceptable UX degradation tolerance during budget exhaustion, (2) whether backup tagging methods are required, (3) prioritization rules for limited budget allocation across user feeds vs system feeds.

---

#### **Assertion 2: Geographic Search Performance & Index Strategy**

*   **Observation:** The specification requires PostGIS spatial indexes for marketplace radius searches with p95 < 100ms latency targets, supporting 40,000 US/Canada cities with radii up to 250 miles. However, the query optimization strategy for high-concurrency scenarios and the relationship between Elasticsearch full-text search and PostGIS spatial queries is architecturally ambiguous.
*   **Architectural Impact:** This determines whether spatial and text search are unified or split, affecting index duplication, query complexity, and infrastructure cost.
    *   **Tier 1 (PostGIS-Only):** All queries route through PostgreSQL with GiST indexes. Simplest architecture but may struggle under concurrent load (100+ qps).
    *   **Tier 2 (Hybrid: ES Text + PostGIS Spatial):** Elasticsearch for text search, PostGIS for spatial filtering. Dual-index maintenance overhead but better horizontal scaling.
    *   **Tier 3 (Elasticsearch Geo-Spatial Unified):** Migrate all spatial queries to Elasticsearch geo_point fields. Single index, best performance at scale, but diverges from "PostGIS" specification language.
*   **Default Assumption & Required Action:** The architecture will assume **Tier 1 (PostGIS-Only)** for v1 simplicity, with Elasticsearch reserved for text search only. **The specification must be updated** to define: (1) expected peak concurrent search load (queries/second), (2) whether Elasticsearch geo-spatial capabilities should be leveraged for unified search, (3) performance targets for combined text+spatial queries vs spatial-only queries.

---

#### **Assertion 3: Screenshot Capture Concurrency & Resource Allocation**

*   **Observation:** The specification mandates self-hosted jvppeteer with configurable browser pool size (default 3 per pod) and defines screenshot capture as a BULK queue job. However, the provisioning strategy for pods processing screenshot queues vs general application pods is undefined, as is the behavior under bulk import scenarios (e.g., 10,000 directory sites).
*   **Architectural Impact:** This decision affects infrastructure cost, job completion SLA, and pod resource allocation patterns.
    *   **Path A (Unified Pods):** All application pods process all job queues including screenshots. Simple deployment but risks memory pressure on general-purpose pods (Chrome requires ~300MB per instance).
    *   **Path B (Dedicated Screenshot Workers):** Deploy specialized pods configured to process only SCREENSHOT queue with higher memory allocation (6GB+). Clean separation but increases deployment complexity and minimum infrastructure footprint.
    *   **Path C (Serverless Screenshot Service):** Extract screenshot capture to external service (e.g., AWS Lambda with Puppeteer layer). Eliminates in-pod Chrome overhead but introduces latency and external dependency.
*   **Default Assumption & Required Action:** The system will implement **Path A (Unified Pods)** with runtime queue filtering via environment variable, accepting 4GB minimum pod memory. **The specification must be updated** to define: (1) acceptable bulk import completion time for screenshot batches (e.g., 1,000 sites), (2) whether dedicated screenshot worker pods are required or acceptable, (3) maximum concurrent screenshot jobs per deployment environment (dev vs prod).

---

#### **Assertion 4: Social Media Token Refresh & Failure UX Persistence**

*   **Observation:** Policy P5 mandates proactive token refresh 7 days before expiry with email notification on failure, displaying cached posts with a reconnect banner indefinitely. However, the post refresh strategy during degraded token states (expired but not yet reconnected) and the cache invalidation policy when users explicitly disconnect are undefined.
*   **Architectural Impact:** This determines data retention obligations, cache storage growth patterns, and GDPR compliance surface area.
    *   **Path A (Indefinite Retention):** Cached posts persist forever unless user deletes widget or account. Maximum user convenience but unbounded storage growth and potential GDPR concerns.
    *   **Path B (Staleness Expiry):** Auto-purge cached posts after 90 days of token failure. Balances storage with usability, aligns with GDPR minimization principles.
    *   **Path C (User-Controlled Archive):** On token failure, prompt user to explicitly archive or discard cached posts. Maximum transparency but adds UX friction.
*   **Default Assumption & Required Action:** The architecture will implement **Path B (Staleness Expiry)** with 90-day auto-purge and user notification before deletion. **The specification must be updated** to define: (1) explicit cache retention policy for disconnected social accounts, (2) whether GDPR "right to erasure" applies to cached social posts, (3) user notification requirements before cached data purge.

---

#### **Assertion 5: Feature Flag Analytics & User Cohort Stability**

*   **Observation:** Policy P7 mandates stable cohorts via user ID hash (MD5 mod 100) for rollout percentages, supporting both A/B testing and deployment control. However, the specification does not define how cohort assignment interacts with user account transitions (anonymous → authenticated) or how flag evaluation logs are used for A/B test statistical analysis.
*   **Architectural Impact:** This affects cohort consistency during user lifecycle transitions and determines whether internal analytics suffice or external tools (e.g., Amplitude, Mixpanel) are required.
    *   **Path A (Session-Stable Only):** Anonymous users get session-based cohorts that reset on login. Simplest implementation but breaks A/B test continuity across authentication boundary.
    *   **Path B (Cookie-Stable with Migration):** Use persistent cookie hash for anonymous users, migrate cohort assignment on authentication. Maintains experiment integrity but requires cookie→user mapping table.
    *   **Path C (External Analytics Integration):** Export flag evaluations to external A/B testing platform for analysis. Highest fidelity but adds vendor dependency and cost.
*   **Default Assumption & Required Action:** The system will implement **Path B (Cookie-Stable with Migration)** using the same `vu_anon_id` cookie for cohort hashing. **The specification must be updated** to define: (1) whether A/B test cohorts must persist across anonymous→authenticated transition, (2) required statistical analysis capabilities (chi-square tests, confidence intervals, etc.), (3) whether external analytics tools will be integrated or all analysis is internal.

---

#### **Assertion 6: Marketplace Payment Processing & Chargeback Dispute Strategy**

*   **Observation:** Policy P3 defines a 24-hour conditional refund window with chargeback handling and 2-strike ban policy. However, the operational process for contesting chargebacks (evidence gathering, Stripe dispute API automation) and the user communication strategy during disputes are undefined.
*   **Architectural Impact:** This determines whether chargeback handling is fully automated, semi-automated with admin intervention, or manual, affecting admin workload and financial risk exposure.
    *   **Path A (Manual Process):** Admins manually review chargebacks, gather evidence, and submit disputes via Stripe dashboard. No custom automation but highest operational overhead.
    *   **Path B (Semi-Automated):** System auto-collects dispute evidence (listing details, timestamps, email logs) and presents in admin UI for one-click submission. Balances automation with human judgment.
    *   **Path C (Fully Automated):** Auto-contest all chargebacks with stored transaction metadata, auto-ban users exceeding threshold. Minimal overhead but risks inappropriate bans.
*   **Default Assumption & Required Action:** The architecture will implement **Path B (Semi-Automated)** with an admin moderation queue for chargeback review. **The specification must be updated** to define: (1) acceptable admin response time for chargeback disputes (Stripe deadline is 7 days), (2) whether auto-ban on chargebacks requires manual review or is automatic, (3) user communication templates for chargeback-related account actions.

---

#### **Assertion 7: Good Sites Screenshot Version History & Storage Growth Model**

*   **Observation:** Policy P4 mandates unlimited screenshot retention with version history, storing both thumbnail (320x200) and full (1280x800) WebP images. With 30-day auto-refresh for all active directory sites, this creates unbounded storage growth without defined archival or compression strategy.
*   **Architectural Impact:** This determines long-term storage cost predictability and whether old screenshots are truly accessible or archived to cold storage.
    *   **Path A (Hot Storage Unlimited):** All screenshot versions remain in primary S3/R2 bucket indefinitely. Simplest architecture but storage cost grows linearly with directory size and age.
    *   **Path B (Cold Archive After 1 Year):** Migrate screenshot versions older than 1 year to Glacier/cold storage tier. Balances accessibility with cost optimization.
    *   **Path C (LRU Eviction):** Retain only last 5 versions per site in hot storage, archive older versions to cold tier. Caps hot storage growth at predictable multiplier.
*   **Default Assumption & Required Action:** The architecture will implement **Path C (LRU Eviction)** with 5-version hot retention and auto-archive to cold storage. **The specification must be updated** to define: (1) expected directory site count at 1 year, 3 years, 5 years for cost modeling, (2) acceptable latency for accessing archived screenshot versions (cold storage retrieval is ~minutes), (3) whether screenshot version history UI should indicate cold vs hot storage status.

---

### **4.0 Next Steps**

Upon the user's update of the original specification document, the development process will be unblocked and can proceed to the architectural design phase.
