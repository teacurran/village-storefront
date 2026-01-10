# Specification Review & Recommendations: Village Storefront - Multi-Tenant SaaS Ecommerce Platform

**Date:** 2026-01-09
**Status:** Awaiting Specification Enhancement

### **1.0 Executive Summary**

This document is an automated analysis of the provided project specifications. It has identified critical decision points that require explicit definition before architectural design can proceed.

**Required Action:** The user is required to review the assertions below and **update the original specification document** to resolve the ambiguities. This updated document will serve as the canonical source for subsequent development phases.

### **2.0 Synthesized Project Vision**

*Based on the provided data, the core project objective is to engineer a system that:*

Delivers a multi-tenant SaaS ecommerce platform enabling small-to-medium merchants (including consignment-based businesses) to operate independent online stores with subdomain/custom domain access, full inventory management, integrated payment processing via Stripe Connect, and comprehensive consignment vendor management with automated payouts. The system architecture mandates Java 21 + Quarkus with GraalVM native compilation, PostgreSQL database, Qute templates for customer-facing storefronts, and Vue.js 3 admin dashboards.

### **3.0 Critical Assertions & Required Clarifications**

---

#### **Assertion 1: Tenant Isolation RLS Implementation Strategy**

*   **Observation:** The specification declares "Application-Primary with RLS Safety Net" but provides conflicting technical details. It mandates Panache entity filtering as primary enforcement while stating RLS policies check `current_setting('app.tenant_id')` - yet also claims "no session variable management" for simpler connection pooling. These constraints are mutually exclusive.
*   **Architectural Impact:** This is a foundational security decision affecting database connection architecture, query performance, and data breach risk surface.
    *   **Path A (Pure Application-Layer):** Panache base class applies tenant filters exclusively; RLS policies disabled or nonexistent. Simplest connection pooling, but single filter bypass bug exposes cross-tenant data.
    *   **Path B (True Hybrid with Session Variables):** Connection filter sets PostgreSQL session variable on checkout from pool; RLS policies enforce tenant isolation as defense-in-depth. Requires connection pool lifecycle hooks but provides genuine safety net.
    *   **Path C (RLS-Primary):** PostgreSQL RLS policies as sole enforcement mechanism; application trusts database to filter all queries. Maximum security guarantee, moderate connection management complexity.
*   **Default Assumption & Required Action:** The architecture will assume **Path A (Pure Application-Layer)** with comprehensive integration test coverage of tenant isolation to mitigate bypass risk. **The specification must be updated** to explicitly define the RLS implementation strategy, including whether PostgreSQL session variables will be used and the specific lifecycle hooks for setting `app.tenant_id` if Path B is chosen.

---

#### **Assertion 2: Multi-Location Inventory Data Model & Concurrency Control**

*   **Observation:** The specification requires "Multi-location inventory: Track stock across warehouses, stores, suppliers" and "Stock transfers: Move inventory between locations" but provides no data model, no concurrency strategy for simultaneous sales/transfers, and no definition of location hierarchy or transfer workflows.
*   **Architectural Impact:** This variable dictates database schema complexity, transaction isolation requirements, and the feasibility of real-time inventory accuracy across locations.
    *   **Tier 1 (Simple Location Ledger):** Single `inventory_ledger` table with `(product_variant_id, location_id, quantity)` composite key. Transfers are two-row updates (decrement source, increment destination) in single transaction. No historical tracking. Suitable for <5 locations with low transfer volume.
    *   **Tier 2 (Event-Sourced Inventory):** All inventory changes (sales, transfers, adjustments) modeled as immutable events in `inventory_events` table; current stock computed via aggregation. Full audit trail, supports complex workflows (multi-stage transfers, approvals), but requires materialized views for query performance.
    *   **Tier 3 (Reserved Quantity Model):** Separate columns for `physical_quantity`, `reserved_quantity`, `available_quantity`. Orders reserve stock before payment; fulfillment decrements physical. Prevents overselling but adds complexity to transfer logic.
*   **Default Assumption & Required Action:** The system will implement **Tier 2 (Event-Sourced Inventory)** to satisfy audit requirements and support consignment accounting complexity. **The specification must be updated** to define: (1) maximum expected locations per tenant, (2) transfer approval workflow requirements, (3) whether "available to promise" logic (reservations) is required for multi-location scenarios.

---

#### **Assertion 3: Consignment Vendor Balance Settlement & Chargeback Risk Model**

*   **Observation:** The specification defines a two-phase balance model (`pending_balance` → `available_balance` after 30 days) and states chargebacks deduct from `available_balance`, flagging negative balances for "merchant resolution." The mechanism for this resolution and liability allocation between platform, store, and vendor are undefined.
*   **Architectural Impact:** This decision determines financial risk distribution, cash flow timing, and potential platform loss exposure in fraud scenarios.
    *   **Path A (Vendor Liability):** Negative vendor balances are vendor debt; future sales credits apply until balance positive. Store is made whole by platform. Requires platform reserve fund for chargebacks.
    *   **Path B (Store Liability):** Negative vendor balances are store debt; store must either clawback from vendor externally or absorb loss. Platform remains neutral intermediary. Requires store-level reserve or escrow mechanism.
    *   **Path C (Shared Liability Pool):** Platform, store, and vendor each absorb percentage of chargeback loss per configurable policy. Complex accounting but distributes risk.
*   **Default Assumption & Required Action:** The architecture will assume **Path B (Store Liability)** to minimize platform capital requirements and align with Stripe Connect's liability model where platform fees are store responsibility. **The specification must be updated** to explicitly define: (1) chargeback liability party, (2) negative balance resolution workflow and timeline, (3) whether platform will hold reserve funds or escrow for chargeback risk.

---

#### **Assertion 4: Custom Domain SSL Certificate Lifecycle & Failure States**

*   **Observation:** The specification mandates "Merchant-Managed DNS with HTTP-01" and outlines the happy-path workflow (CNAME validation → cert-manager issuance), but does not define certificate renewal failure handling, domain removal workflows, or the behavior when a merchant's CNAME is deleted after initial setup.
*   **Architectural Impact:** Unhandled certificate expiration or DNS misconfiguration results in customer-facing HTTPS errors, directly impacting merchant revenue and platform reputation.
    *   **Scenario A (Passive Monitoring):** System monitors certificate expiration dates; emails merchant at 14 days and 3 days before expiry. If renewal fails, custom domain automatically disabled and traffic falls back to subdomain. Merchant must re-verify.
    *   **Scenario B (Active Remediation):** System detects CNAME removal or validation failure; automatically retries HTTP-01 challenge every 6 hours for 72 hours. If unsuccessful, domain marked "degraded" but remains active with warning banner in admin. Merchant has 7 days to fix before forced fallback.
    *   **Scenario C (Fail-Secure with Grace Period):** Certificate expiration triggers immediate fallback to subdomain with merchant notification. Grace period (48 hours) allows re-verification without customer disruption. After grace period, custom domain entry deleted and requires full re-setup.
*   **Default Assumption & Required Action:** The system will implement **Scenario A (Passive Monitoring)** with automatic fallback to minimize platform operational burden. **The specification must be updated** to define: (1) renewal failure SLA and fallback behavior, (2) whether active retry attempts are required, (3) merchant notification cadence and escalation policy for certificate issues.

---

#### **Assertion 5: Platform Admin Impersonation Session Boundaries & Action Scope**

*   **Observation:** The specification requires impersonation of store admins and customers with audit logging, but does not define whether impersonated sessions inherit full admin privileges, whether certain sensitive actions (refunds, user deletion, Stripe disconnect) are restricted during impersonation, or whether impersonation sessions can span multiple stores in a single session.
*   **Architectural Impact:** This variable determines authorization complexity, audit log schema design, and the potential for platform admin abuse or accidental destructive actions.
    *   **Path A (Full Privilege Inheritance):** Impersonated session has identical permissions to target user. Platform admin can perform any action the user could perform, including financial transactions. All actions logged but not restricted. Simplest implementation, highest risk.
    *   **Path B (Read-Only Impersonation):** Impersonated session has view-only access; no write operations allowed. Platform admin must exit impersonation to take corrective action in their own capacity. Safest but least operationally useful for customer service.
    *   **Path C (Restricted Write Impersonation):** Impersonated session allows most actions but blocks high-risk operations (refunds >$X, user account deletion, payment config changes). Blocked actions require explicit platform admin override with additional audit justification field.
*   **Default Assumption & Required Action:** The system will implement **Path C (Restricted Write Impersonation)** with thresholds configurable per platform admin role. **The specification must be updated** to define: (1) explicit list of actions restricted during impersonation, (2) whether multi-store impersonation (switching tenants within a single session) is permitted, (3) maximum impersonation session duration and idle timeout.

---

#### **Assertion 6: Media Processing Queue Isolation & Resource Starvation Prevention**

*   **Observation:** The specification describes a DelayedJob-based queue system with priority tiers (CRITICAL, HIGH, DEFAULT, LOW, BULK) and suggests video processing runs on separate worker pods, but does not define the queue assignment logic for media jobs, whether image processing shares queues with transactional jobs (emails, webhooks), or the autoscaling trigger thresholds.
*   **Architectural Impact:** Improper queue isolation results in video transcoding jobs starving time-sensitive operations like order confirmation emails or low-stock alerts, directly degrading customer experience.
    *   **Tier 1 (Shared Queue with Priority):** All jobs (media, email, webhooks) use same queue with priority ordering. Video jobs are LOW priority. Risk: large video uploads can delay email delivery if queue depth exceeds worker capacity.
    *   **Tier 2 (Dedicated Media Queue):** CRITICAL queue exclusively for video transcoding; HIGH/DEFAULT for transactional jobs; BULK for analytics. Separate pod pools subscribe to different queues. Clean isolation but requires more infrastructure planning.
    *   **Tier 3 (External Media Processing):** Video transcoding offloaded to external service (AWS MediaConvert, Cloudflare Stream). Application only uploads source files and polls for completion. Highest reliability but adds vendor dependency and cost.
*   **Default Assumption & Required Action:** The system will implement **Tier 2 (Dedicated Media Queue)** with video processing isolated to CRITICAL queue and dedicated worker pods. **The specification must be updated** to define: (1) queue assignment matrix (which job types use which priority tier), (2) HPA thresholds for each worker pod type (queue depth, CPU, memory), (3) whether image resizing uses asynchronous queues or synchronous in-process execution.

---

#### **Assertion 7: Session & Audit Log Archival Format & Historical Query Performance**

*   **Observation:** The specification mandates 90-day hot storage in PostgreSQL with JSONL+gzip archival to R2, and describes a CSV export tool for archived data. However, the query patterns for historical data (date range filters, user ID lookups, action type filtering) and whether pre-aggregated summaries are required for compliance reporting are undefined.
*   **Architectural Impact:** Without indexed summary tables or queryable archive format, generating compliance reports (e.g., "all impersonation sessions in Q4 2025") requires full archive decompression and linear scan, potentially taking hours for large tenants.
    *   **Path A (Raw Archive with Export Tool):** JSONL archives are opaque; all historical queries require full export to CSV for manual analysis. Simple to implement but painful for compliance audits. Suitable if queries are rare (<1/month).
    *   **Path B (Indexed Summary Tables):** Scheduled job pre-aggregates audit logs into summary tables (daily session counts, action type breakdowns, impersonation index) retained indefinitely. Archives remain JSONL but summaries provide fast reporting. Moderate complexity, excellent query performance.
    *   **Path C (Queryable Archive Layer):** Archives stored in Parquet format (columnar, compressed) on R2; external query engine (DuckDB, Athena) allows SQL queries against archives without import. High setup cost but unlimited historical analysis capability.
*   **Default Assumption & Required Action:** The system will implement **Path B (Indexed Summary Tables)** to balance compliance requirements with implementation complexity. **The specification must be updated** to define: (1) specific audit report types required (session duration histograms, action frequency by user role, etc.), (2) query SLA for archived data (seconds, minutes, hours), (3) whether GDPR "right to erasure" requires selective deletion from archives or archive regeneration.

---

### **4.0 Next Steps**

Upon the user's update of the original specification document, the development process will be unblocked and can proceed to the architectural design phase. Each assertion above requires an explicit resolution statement in the updated specification, referencing the chosen path or tier and providing the requested clarification details.
