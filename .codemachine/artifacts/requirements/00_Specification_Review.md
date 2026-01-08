# Specification Review & Recommendations: Village Storefront SaaS Platform

**Date:** 2026-01-08
**Status:** Awaiting Specification Enhancement

### **1.0 Executive Summary**

This document is an automated analysis of the provided project specifications. It has identified critical decision points that require explicit definition before architectural design can proceed.

**Required Action:** The user is required to review the assertions below and **update the original specification document** to resolve the ambiguities. This updated document will serve as the canonical source for subsequent development phases.

### **2.0 Synthesized Project Vision**

*Based on the provided data, the core project objective is to engineer a system that:*

Delivers a multi-tenant SaaS ecommerce platform enabling merchants to operate independent online stores with consignment inventory management, integrated payment processing via Stripe Connect, and a dual-frontend architecture (Qute-rendered storefront + Vue.js admin dashboard) built on Java 21 + Quarkus with PostgreSQL persistence and native compilation for Kubernetes deployment.

### **3.0 Critical Assertions & Required Clarifications**

---

#### **Assertion 1: Row-Level Security Implementation Strategy**

*   **Observation:** The specification mandates PostgreSQL Row-Level Security (RLS) for tenant isolation with "defense-in-depth via Panache query filters," but the precise coordination mechanism between RLS policies and application-layer filters is undefined.
*   **Architectural Impact:** This is a foundational security decision impacting data isolation guarantees, query performance, and development complexity.
    *   **Path A (RLS Primary):** PostgreSQL RLS enforces all tenant isolation. Application sets `SET LOCAL app.tenant_id = ?` at transaction start. Panache queries require no tenant filters (RLS handles everything). Maximum security guarantee, but requires careful connection pooling strategy and session variable management.
    *   **Path B (Application Primary):** Panache base repository applies tenant filters to all queries. RLS policies serve as safety net to catch filter bypass bugs. Simpler connection pooling, but depends on application-layer correctness for primary isolation.
    *   **Path C (Hybrid with Explicit Gates):** RLS enforced on high-sensitivity tables (orders, payments, customer data). Application filters on catalog tables (products, categories) for performance. Requires explicit table classification and dual maintenance.
*   **Default Assumption & Required Action:** To balance security guarantees with development simplicity, the system will be architected assuming **Path B (Application Primary)** with RLS as a safety mechanism. **The specification must be updated** to explicitly define the tenant isolation enforcement strategy, including connection pooling implications and transaction lifecycle management.

---

#### **Assertion 2: Media Processing Execution Model & Resource Allocation**

*   **Observation:** The specification states "FFmpeg invoked via ProcessBuilder within application pods" for video transcoding with 10-minute timeouts, but resource limits, concurrency controls, and failure isolation strategies are undefined.
*   **Architectural Impact:** This decision dictates pod sizing, horizontal scaling strategy, and system stability under media processing load.
    *   **Tier 1 (In-Process, No Isolation):** DelayedJob executes FFmpeg directly in web application pods via ProcessBuilder. Simple deployment, but video transcoding can exhaust pod CPU/memory, impacting request serving. Risk of pod OOM kills during large video processing.
    *   **Tier 2 (Dedicated Worker Pods):** Separate Kubernetes deployment for media workers (`DELAYED_JOB_QUEUES=CRITICAL` only). Web pods disable media job processing. Clean resource isolation, but requires maintaining separate deployment manifests and autoscaling configurations.
    *   **Tier 3 (External Processing Service):** Offload to external transcode API (e.g., AWS MediaConvert, Cloudflare Stream). Maximum isolation and scalability, but introduces external dependency, cost-per-transcode, and API integration complexity.
*   **Default Assumption & Required Action:** The architecture will assume **Tier 2 (Dedicated Worker Pods)** to ensure resource isolation while maintaining operational simplicity. **The specification must be updated** to define pod resource requests/limits (CPU, memory), concurrency limits per worker pod, and autoscaling triggers (queue depth thresholds).

---

#### **Assertion 3: Custom Domain SSL Certificate Provisioning Flow**

*   **Observation:** The specification states "cert-manager handles issuance, renewal, and secret management" for custom domains, but the application's responsibilities for DNS validation, domain ownership verification, and error handling are undefined.
*   **Architectural Impact:** This determines the merchant onboarding UX, security surface for domain takeover attacks, and operational complexity of domain management.
    *   **Path A (Automated ACME with DNS Delegation):** Merchant adds domain in admin UI. Application provisions DNS TXT records via DNS provider API (e.g., Cloudflare). cert-manager performs ACME DNS-01 challenge automatically. Requires DNS provider API integration and credential management.
    *   **Path B (Merchant-Managed DNS with HTTP-01):** Merchant adds domain and manually creates CNAME pointing to platform. Application validates CNAME target, then creates cert-manager Certificate resource. cert-manager performs HTTP-01 challenge via Ingress. Simpler application logic, but requires merchant DNS access and increases support burden.
    *   **Path C (Manual Verification with Delayed Activation):** Merchant adds domain, receives verification token, creates DNS TXT record, clicks "Verify." Application polls DNS, creates Certificate resource upon success. Highest friction, but simplest implementation and strongest domain ownership proof.
*   **Default Assumption & Required Action:** The architecture will assume **Path B (Merchant-Managed DNS with HTTP-01)** to minimize external API dependencies while maintaining reasonable UX. **The specification must be updated** to define the exact domain verification workflow, error states (failed challenges, expired certificates), and merchant communication flow (email notifications for expiring certificates).

---

#### **Assertion 4: Consignment Vendor Payout Timing & Balance Settlement**

*   **Observation:** The specification states "vendor balance credited after refund window expires (30 days post-fulfillment)" but does not define the accounting treatment during the 30-day hold period or the mechanics of chargeback reconciliation.
*   **Architectural Impact:** This determines database schema for vendor balances, financial reporting accuracy, and merchant liability in dispute scenarios.
    *   **Path A (Two-Phase Balance):** Maintain separate `pending_balance` and `available_balance` columns. Sales credit `pending_balance` immediately. Scheduled job sweeps `pending_balance → available_balance` for items >30 days past fulfillment. Chargebacks deduct from `available_balance`, flagging negative balances for merchant resolution. Clear separation, but requires job orchestration and handling negative balance states.
    *   **Path B (Event Sourcing with Projections):** Store all vendor balance events (sale, refund, chargeback, payout) in append-only ledger. Compute `available_balance` via query filtering events by eligibility date. Authoritative audit trail, but requires careful query optimization and introduces complexity in balance calculation logic.
    *   **Path C (Immediate Availability with Reserve):** Credit vendor balance immediately on sale. Maintain platform-level reserve fund for chargeback risk. Chargebacks deducted from reserve, not vendor balance. Simplest vendor UX, but exposes platform to chargeback losses if vendors are insolvent.
*   **Default Assumption & Required Action:** The architecture will assume **Path A (Two-Phase Balance)** to balance transparency and risk management. **The specification must be updated** to define the exact balance state transitions, chargeback reconciliation flow, and merchant notification requirements when vendor balances go negative.

---

#### **Assertion 5: Session Activity Partitioning & Archive Query Strategy**

*   **Observation:** The specification mandates "90 days hot storage in PostgreSQL with monthly partitions" and "JSONL + gzip archive to R2 for older data," but the query interface for historical data beyond 90 days is undefined.
*   **Architectural Impact:** This determines the feasibility of regulatory compliance reporting, support tooling for historical session lookups, and development complexity of dual-storage queries.
    *   **Tier 1 (Archive-Only Access):** Data beyond 90 days is write-only archive. No query interface in application. Admins must download JSONL archives and query locally via scripts. Simplest implementation, but severely limits historical reporting.
    *   **Tier 2 (Admin Export Tool):** Application provides "Export to CSV" feature for date ranges. For >90 days, backend streams from R2, decompresses JSONL, converts to CSV. No interactive queries, but enables compliance reporting via exports. Moderate complexity.
    *   **Tier 3 (Unified Query Layer):** Application transparently queries PostgreSQL for recent data and R2 archives for historical data, merging results. Requires implementing archive indexing (e.g., partitioned by month in R2 prefixes), streaming decompression, and result pagination. High complexity, but provides seamless historical query UX.
*   **Default Assumption & Required Action:** The architecture will assume **Tier 2 (Admin Export Tool)** to satisfy compliance requirements without over-engineering query infrastructure. **The specification must be updated** to define the exact export formats supported, maximum date range per export operation, and expected query latency SLAs for archived data retrieval.

---

#### **Assertion 6: Loyalty Program Point Redemption Atomicity**

*   **Observation:** The specification describes "Points-to-Currency" redemption as "applied as payment method" at checkout, but the transaction model for point deduction + order creation is undefined.
*   **Architectural Impact:** This determines data consistency guarantees, handling of partial payment failures, and customer support scenarios for point disputes.
    *   **Path A (Optimistic Deduction):** Deduct points at checkout submission, before payment processing. If payment fails, refund points in separate transaction. Simple implementation, but creates window where points are deducted without completed order (requires compensation logic).
    *   **Path B (Two-Phase Commit):** Reserve points (via `points_reserved` column), process payment, commit point deduction + order creation in single transaction. If payment fails, release reservation. Stronger consistency, but requires reservation expiration logic and handling reservation leaks.
    *   **Path C (Post-Payment Reconciliation):** Process payment first, then deduct points in separate transaction upon payment success. If point deduction fails, issue store credit equal to point value. Avoids blocking checkout on point system, but introduces compensating transaction complexity.
*   **Default Assumption & Required Action:** The architecture will assume **Path B (Two-Phase Commit)** to maintain transactional integrity for point redemptions. **The specification must be updated** to define point reservation timeout (how long before automatic release), handling of concurrent redemption attempts, and refund point reinstatement timing (immediate vs. asynchronous).

---

#### **Assertion 7: Platform Admin Cross-Tenant Query Scope**

*   **Observation:** The specification states platform admins can "view all stores" and access "platform-wide analytics," but the permitted aggregation granularity and individual record access boundaries are undefined.
*   **Architectural Impact:** This determines privacy posture, regulatory compliance risk (GDPR, CCPA), and technical implementation of admin dashboards.
    *   **Scope A (Aggregate-Only):** Platform admins can query aggregate metrics (total stores, total revenue, signup trends) but cannot access individual store data, customer PII, or order details without impersonation. Maximum privacy protection, but limits troubleshooting capabilities.
    *   **Scope B (Store-Level Metadata):** Platform admins can view store-level summaries (store name, plan, revenue, active products count) but cannot access customer or order data without impersonation. Balances operations needs with privacy.
    *   **Scope C (Full Read Access):** Platform admins can query all data across all tenants for support and analytics. Simplest implementation, but creates significant privacy and compliance risk. Requires audit logging of all cross-tenant queries.
*   **Default Assumption & Required Action:** The architecture will assume **Scope B (Store-Level Metadata)** to enable platform operations while enforcing impersonation for sensitive data access. **The specification must be updated** to define the exact data elements accessible without impersonation, audit log retention for platform admin queries, and compliance controls for cross-tenant data access (e.g., purpose limitation, access reviews).

---

### **4.0 Next Steps**

Upon the user's update of the original specification document, the development process will be unblocked and can proceed to the architectural design phase.
